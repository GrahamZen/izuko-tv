/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.storage.AbstractDataStoreMediaCacheStorage
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.app.domain.media.download.DownloadSpaceGuard
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.usableSpace
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 把已有的缓存搬到另一个位置 (如「缓存位置」换成外接盘后, 把内部存储上的搬过去). 一次只搬一批, 一份一份来:
 *
 * 1. 在 [MediaCacheLocations.moving] 登记, 存储重新恢复它: 引擎认出正在搬, 给「正在移动」占位, 下载会话 / 任务随之停下;
 * 2. 引擎的搬法 ([MediaCacheEngineMover]) 复制到目标位置、核对、改记录;
 * 3. 取消登记, 存储再恢复一遍, 按新位置接着用; 最后删原处的文件.
 *
 * 任何一步出错或被取消, 这一份留在原处照常用 (目标处复制了的由搬法清掉), 接着搬下一份 (取消则停下).
 * 在播的番 (同一部的任何一集) 跳过: 播放会话占着同一个种子与文件. 目标盘剩余空间不够时停下.
 */
class MediaCacheMover(
    private val locations: MediaCacheLocations,
    private val storages: () -> List<MediaCacheStorage>,
    /** 这部番正在播 (条目 id) */
    private val isPlayingSubject: (subjectId: String) -> Boolean,
    private val scope: CoroutineScope,
    /** 缓存换成占位后等多久再复制: 让 BT 服务把种子关掉 */
    private val settleDelay: Duration = 2.seconds,
    private val freeBytes: (SystemPath) -> Long? = { it.usableSpace() },
) {
    /** 一批正在搬的进度 */
    data class Progress(
        val target: String?,
        val total: Int,
        val done: Int,
        val failed: Int,
        val skipped: Int,
        val bytesTotal: Long,
        val bytesDone: Long,
    )

    /** 一批搬完 (或停下) 的结果 */
    data class Result(
        val target: String?,
        val moved: Int,
        val failed: Int,
        /** 因为在播跳过的 */
        val skipped: Int,
        /** 整批停下的原因; null = 搬完了 */
        val stopReason: StopReason?,
    )

    enum class StopReason { TARGET_UNAVAILABLE, NOT_ENOUGH_SPACE, CANCELLED }

    /** 不在某个位置上的缓存有多少 */
    data class Pending(val units: Int, val bytes: Long)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _finished = MutableSharedFlow<Result>(extraBufferCapacity = 4)
    val finished: SharedFlow<Result> = _finished.asSharedFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** 不在 [target] 位置上、可以搬过去的缓存 */
    suspend fun pending(target: String?): Pending = pending(listOf(target)).getValue(target)

    /** 对 [targets] 里每个位置, 不在它上面、可以搬过去的缓存 (只扫一遍) */
    suspend fun pending(targets: List<String?>): Map<String?, Pending> {
        val units = entries().map { it.unit }
        return targets.associateWith { target ->
            val others = units.filter { it.location != target }
            Pending(others.size, others.sumOf { it.bytes })
        }
    }

    /** 开始把不在 [target] 的缓存都搬过去; 已经在搬时不再开一批, 返回 false */
    fun start(target: String?): Boolean {
        if (isRunning) return false
        job = scope.launch { run(target) }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    private class Entry(val storage: MediaCacheStorage, val mover: MediaCacheEngineMover, val unit: MediaCacheMoveUnit)

    private suspend fun entries(): List<Entry> = storages().flatMap { storage ->
        val mover = moverOf(storage.engine) ?: return@flatMap emptyList()
        mover.units().map { Entry(storage, mover, it) }
    }

    private suspend fun run(target: String?) {
        var moved = 0
        var failed = 0
        var skipped = 0
        var stopReason: StopReason? = null
        try {
            val entries = entries().filter { it.unit.location != target }
            val targetRoot = locations.rootOf(target)
            if (targetRoot == null || locations.locations.value.firstOrNull { it.id == target }?.unusable != null) {
                stopReason = StopReason.TARGET_UNAVAILABLE
                return
            }
            logger.info { "Moving ${entries.size} caches (${entries.sumOf { it.unit.bytes }} bytes) to ${target ?: "internal"}" }
            var progress = Progress(target, entries.size, 0, 0, 0, entries.sumOf { it.unit.bytes }, 0)
            _progress.value = progress
            for (entry in entries) {
                val caches = entry.storage.listFlow.first().filter(entry.unit.matches)
                if (caches.any { isPlayingSubject(it.metadata.subjectId) }) {
                    logger.info { "Skipping ${entry.unit.key}: it is playing" }
                    skipped++
                } else {
                    val free = freeBytes(targetRoot)
                    if (free != null && free - entry.unit.bytes < DownloadSpaceGuard.LOW_SPACE_BYTES) {
                        logger.warn { "Only $free bytes left on ${target ?: "internal"}, stopping before ${entry.unit.key} (${entry.unit.bytes} bytes)" }
                        stopReason = StopReason.NOT_ENOUGH_SPACE
                        break
                    }
                    val base = progress.bytesDone
                    if (moveOne(entry, target) { copied -> _progress.update { it?.copy(bytesDone = base + copied) } }) moved++ else failed++
                }
                progress = progress.copy(done = moved, failed = failed, skipped = skipped, bytesDone = progress.bytesDone + entry.unit.bytes)
                _progress.value = progress
            }
        } catch (e: CancellationException) {
            stopReason = StopReason.CANCELLED
            throw e
        } finally {
            _progress.value = null
            val result = Result(target, moved, failed, skipped, stopReason)
            logger.info { "Moving to ${target ?: "internal"} finished: $result" }
            _finished.tryEmit(result)
        }
    }

    /** 搬一份, 返回是否搬成了 */
    private suspend fun moveOne(entry: Entry, target: String?, onProgress: (copied: Long) -> Unit): Boolean {
        val storage = entry.storage as? AbstractDataStoreMediaCacheStorage ?: return false
        val unit = entry.unit
        var sources: List<Path>? = null
        locations.moving.update { it + unit.key }
        try {
            // 换成「正在移动」占位: 下完的缓存恢复一次就不再管, 先让存储忘掉它们
            storage.forgetRestoredLocalFiles(unit.matches)
            storage.refreshCache()
            delay(settleDelay)
            var copied = 0L
            sources = entry.mover.move(unit, target) { bytes ->
                copied += bytes
                onProgress(copied)
            }
            logger.info { "Moved ${unit.key} from ${unit.location ?: "internal"} to ${target ?: "internal"}" }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to move ${unit.key} to ${target ?: "internal"}, it stays where it was" }
            return false
        } finally {
            withContext(NonCancellable) {
                locations.moving.update { it - unit.key }
                try {
                    storage.refreshCache()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to restore caches after moving ${unit.key}" }
                }
                sources?.let { paths ->
                    delay(settleDelay)
                    for (path in paths) {
                        try {
                            SystemFileSystem.deleteRecursively(path)
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to delete the old copy $path, the startup sweep will" }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        private val logger = logger<MediaCacheMover>()

        fun moverOf(engine: MediaCacheEngine): MediaCacheEngineMover? = when (engine) {
            is TorrentMediaCacheEngine -> engine.mover
            is HttpMediaCacheEngine -> engine.mover
            else -> null
        }
    }
}
