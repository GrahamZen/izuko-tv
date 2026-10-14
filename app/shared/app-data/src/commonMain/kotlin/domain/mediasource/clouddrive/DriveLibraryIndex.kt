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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 媒体文件夹的索引 (见 [DriveLibraryConfig]), 代替服务端的按名搜索 (WebDAV 没有搜索).
 *
 * 把媒体文件夹 ([roots]) 往下列 [DriveLibraryConfig.depth] 层, 记下列到的文件与文件夹; 按名找时挑出归一化后的名字含关键词的
 * (同 [DriveSubjectMatcher] 核对搜索结果的规则), 文件夹在前. 之后与服务端搜索的结果一样往下展开、认季认集.
 *
 * 索引在内存里, 建好后 [DriveLibraryConfig.refreshMinutes] 内直接用; 过期后先用旧的回答、后台重建 —— 新放进去的番最多晚这么久才找得到,
 * 已经播过的番记着文件夹 (见 [CloudDriveService.matchRememberedFolder]), 新的一集不受影响. 媒体文件夹或层数改了马上重建.
 * 第一次建的时候根文件夹都列不出来 (没登录、地址不对) 就报给调用方; 更深的文件夹列不出来只跳过它.
 *
 * @param listFolder 列一个文件夹的直接子项
 * @param roots 媒体文件夹 (没指定时是网盘的根)
 */
internal class DriveLibraryIndex(
    private val listFolder: suspend (folderId: String) -> List<DriveFile>,
    private val roots: () -> List<String>,
    private val config: () -> DriveLibraryConfig,
) {
    private data class Key(val roots: List<String>, val depth: Int)

    private class Snapshot(val key: Key, val entries: List<DriveFile>, val builtAt: TimeMark)

    private val buildLock = Mutex()

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var refreshing: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 名字含 [keyword] 的文件与文件夹 (文件夹在前), 最多 [MAX_RESULTS] 个. */
    suspend fun search(keyword: String): List<DriveFile> {
        val wanted = DriveNameParser.normalize(keyword)
        if (wanted.isEmpty()) return emptyList()
        return entries()
            .filter { DriveNameParser.normalize(it.fileName).contains(wanted) }
            .sortedByDescending { it.dir }
            .take(MAX_RESULTS)
    }

    /** 媒体文件夹改了或要立即重建 (如重新登录后). */
    fun invalidate() {
        snapshot = null
    }

    private fun currentKey(): Key = Key(roots(), config().depth.coerceIn(1, MAX_DEPTH))

    private suspend fun entries(): List<DriveFile> {
        val key = currentKey()
        snapshot?.takeIf { it.key == key }?.let { current ->
            if (current.builtAt.elapsedNow() >= config().refreshMinutes.coerceAtLeast(1).minutes) refreshInBackground(key)
            return current.entries
        }
        return buildLock.withLock {
            snapshot?.takeIf { it.key == key }?.entries ?: build(key).also { snapshot = Snapshot(key, it, TimeSource.Monotonic.markNow()) }
        }
    }

    private fun refreshInBackground(key: Key) {
        if (refreshing?.isActive == true) return
        refreshing = scope.launch {
            try {
                val entries = buildLock.withLock { build(key) }
                if (currentKey() == key) snapshot = Snapshot(key, entries, TimeSource.Monotonic.markNow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 旧的照用, 下次过期了再试
                logger.warn { "Failed to refresh drive library index, keeping the old one: $e" }
            }
        }
    }

    /** 从 [key] 的根往下逐层列, 每层并发 [LIST_CONCURRENCY] 个, 一共最多列 [MAX_FOLDERS] 个文件夹. */
    private suspend fun build(key: Key): List<DriveFile> {
        val start = TimeSource.Monotonic.markNow()
        val entries = LinkedHashMap<String, DriveFile>()
        var level = key.roots.distinct()
        var listed = 0
        var failed = 0
        for (depth in 1..key.depth) {
            if (level.isEmpty() || listed >= MAX_FOLDERS) break
            val batch = level.take(MAX_FOLDERS - listed)
            listed += batch.size
            val semaphore = Semaphore(LIST_CONCURRENCY)
            val results = coroutineScope {
                batch.map { folder ->
                    async {
                        semaphore.withPermit {
                            try {
                                Result.success(listFolder(folder))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Result.failure(e)
                            }
                        }
                    }
                }.awaitAll()
            }
            // 根一个都列不出来: 没登录、地址不对、服务器不在线, 报给调用方 (不能当成「什么都没有」)
            if (depth == 1 && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
            failed += results.count { it.isFailure }
            val children = results.flatMap { it.getOrNull().orEmpty() }
            for (child in children) entries.putIfAbsent(child.fid, child)
            level = children.filter { it.dir }.map { it.fid }
        }
        logger.info {
            "Drive library index: ${entries.size} entries from $listed folders ($failed failed) under ${key.roots.size} roots, " +
                    "depth ${key.depth}, took ${start.elapsedNow().inWholeMilliseconds} ms"
        }
        return entries.values.toList()
    }

    private companion object {
        private val logger = logger<DriveLibraryIndex>()

        /** 最多往下列几层. */
        private const val MAX_DEPTH = 4

        /** 建一次索引最多列多少个文件夹. */
        private const val MAX_FOLDERS = 400

        /** 同时列几个文件夹. */
        private const val LIST_CONCURRENCY = 6

        /** 一次最多给多少个结果 (同服务端搜索的一页). */
        private const val MAX_RESULTS = 100
    }
}
