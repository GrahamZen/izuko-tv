/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeBytes
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 存在一个 JSON 小文件里的用户数据 (播放线索、配置): 丢了找不回来, 和 [JsonFileCache] 只差读不出来时怎么办.
 *
 * - 内容读不懂 (文件坏了、装回旧版本读不懂新写的内容) 不删, 改名成 `<名字>.unreadable` 留一份, 再当没有;
 * - 读的时候出了别的错 (IO 错, 不是内容的事) 隔一会儿再读, 几次都不成就当没有, 但文件原样留着, 这个进程里也不再写它
 *   (写了就是拿空的盖掉原来的), 下次启动再读;
 * - 写先写临时文件再原子挪过去, 写到一半进程被杀也不会留下半截. 写失败记一行日志、返回 false, 调用方内存里的照用, 下次整份写时补上.
 */
internal class JsonUserDataFile<T>(
    private val file: SystemPath,
    private val serializer: KSerializer<T>,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    /** 读的时候出了 IO 错, 这个进程里不写 (见类的说明) */
    @Volatile
    private var writable = true

    suspend fun read(): T? {
        var failure: Exception? = null
        repeat(READ_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(READ_RETRY_DELAY)
            try {
                return withContext(ioDispatcher) { readOrSetAside() }.also { writable = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        logger.warn(failure) { "Failed to read ${file.name}, using nothing and not writing it until it reads again" }
        writable = false
        return null
    }

    /** 没有文件为 null; 内容读不懂时改名留一份, 也为 null; 读本身出错 (IO) 时抛出 */
    private fun readOrSetAside(): T? {
        if (!file.exists()) return null
        val text = file.readBytes().decodeToString()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) {
            // SerializationException 也是它
            val kept = file.resolveSibling(file.name + UNREADABLE_SUFFIX)
            logger.warn(e) { "Failed to read ${file.name}, keeping it as ${kept.name}" }
            runCatching { file.moveTo(kept) }
            null
        }
    }

    /** 写成了没有; 写失败、读出过 IO 错 (这个进程里不写) 时为 false */
    suspend fun write(value: T): Boolean {
        if (!writable) {
            logger.warn { "Not writing ${file.name}: it failed to read in this process" }
            return false
        }
        return withContext(ioDispatcher) {
            try {
                val temp = file.resolveSibling(file.name + ".tmp")
                temp.writeBytes(json.encodeToString(serializer, value).encodeToByteArray())
                temp.moveTo(file)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to write ${file.name}" }
                false
            }
        }
    }

    companion object {
        const val UNREADABLE_SUFFIX = ".unreadable"

        /** 读出 IO 错时最多读几次, 中间隔多久 */
        private const val READ_ATTEMPTS = 3
        private val READ_RETRY_DELAY = 200.milliseconds
        private val logger = logger("JsonUserDataFile")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
