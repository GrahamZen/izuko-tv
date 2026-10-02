/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** 分享里列出来的一个文件夹: [path] 是从分享根起的名字 (第一个是分享的标题). */
@Serializable
data class DriveShareIndexedFolder(val fid: String, val path: List<String>)

/** 一个分享往下 [depth] 层的文件夹 ([folders]), 在 [indexedAtMillis] (墙上时间) 列出. 见 [DriveShareSearchEngine]. */
@Serializable
data class DriveShareIndex(
    val depth: Int,
    val indexedAtMillis: Long,
    val folders: List<DriveShareIndexedFolder>,
)

/**
 * 分享搜索 ([DriveShareSearchEngine]) 列出的分享文件夹存在哪. 合集有二三十个分享, 全部重列要二三十秒;
 * 存下来的话应用重启后第一次搜不用重列. 只管存取, 多久算过期由搜索那边判断.
 */
interface DriveShareIndexStore {
    /** 分享 [shareId] 存着的文件夹; 没有时为 null. */
    suspend fun get(shareId: String): DriveShareIndex?

    suspend fun put(shareId: String, index: DriveShareIndex)

    /** 不存: 只留在搜索自己的内存里. */
    object None : DriveShareIndexStore {
        override suspend fun get(shareId: String): DriveShareIndex? = null
        override suspend fun put(shareId: String, index: DriveShareIndex) {}
    }
}

/**
 * 存在一个 JSON 文件里: 第一次用到时整份读进内存, 改了之后等一会儿 ([saveDelay]) 整份写回 —— 一次搜索会连着列好几十个分享, 攒成一次写.
 * 写回时丢掉超过 [maxAge] 的 (反正要重列). 文件坏了当作没有.
 */
class DriveShareIndexFileStore(
    private val file: SystemPath,
    parentCoroutineContext: CoroutineContext,
    private val maxAge: Duration = 6.hours,
    private val saveDelay: Duration = 2.seconds,
    private val now: () -> Long = { currentTimeMillis() },
    /** 读写文件在哪里跑. */
    private val ioContext: CoroutineContext = Dispatchers.IO_,
) : DriveShareIndexStore {
    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))
    private val lock = Mutex()
    private var entries: MutableMap<String, DriveShareIndex>? = null
    private var saveScheduled = false

    override suspend fun get(shareId: String): DriveShareIndex? = lock.withLock { load()[shareId] }

    override suspend fun put(shareId: String, index: DriveShareIndex) {
        lock.withLock {
            load()[shareId] = index
            if (saveScheduled) return
            saveScheduled = true
        }
        scope.launch {
            delay(saveDelay)
            save()
        }
    }

    /** 调之前要拿着 [lock]. */
    private suspend fun load(): MutableMap<String, DriveShareIndex> =
        entries ?: withContext(ioContext) { read() }.also { entries = it }

    private fun read(): MutableMap<String, DriveShareIndex> {
        if (!file.exists()) return HashMap()
        return try {
            json.decodeFromString(serializer, file.readText()).toMutableMap().also {
                logger.info { "Loaded ${it.size} share indexes from ${file.name}" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read share indexes from ${file.name}, starting over" }
            HashMap()
        }
    }

    private suspend fun save() {
        val snapshot = lock.withLock {
            saveScheduled = false
            val current = load()
            val oldest = now() - maxAge.inWholeMilliseconds
            current.values.removeAll { it.indexedAtMillis < oldest }
            HashMap(current)
        }
        try {
            withContext(ioContext) {
                // 先写到旁边的临时文件再换过去, 写一半被杀不会留下坏文件
                val temp = file.resolveSibling(file.name + ".tmp")
                temp.writeText(json.encodeToString(serializer, snapshot))
                temp.moveTo(file)
            }
            logger.info { "Saved ${snapshot.size} share indexes to ${file.name}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to save share indexes to ${file.name}" }
        }
    }

    private companion object {
        private val logger = logger<DriveShareIndexFileStore>()
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), DriveShareIndex.serializer())
    }
}
