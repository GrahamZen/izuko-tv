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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.library.LibraryMediaCacheEngine
import me.him188.ani.app.domain.media.cache.storage.AbstractDataStoreMediaCacheStorage
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.app.domain.media.download.DownloadSpaceGuard
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.usableSpace
import me.him188.ani.utils.io.writeText
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
 * 在播的番 (同一部的任何一集, 包括 TV 上退出播放页后在后台保留着的会话) 跳过: 播放会话占着同一个种子与文件.
 * 跳过的记下来 ([waiting], 存盘), 那部番不再播之后 (或重开应用后) 由 [resumeSkipped] 接着搬, 进度从已经搬好的接上.
 * 目标盘剩余空间不够时停下.
 */
/** 搬哪些: 一份 ([MediaCacheMoveUnit]) 与它包含的缓存 */
typealias MoveFilter = suspend (unit: MediaCacheMoveUnit, caches: List<MediaCache>) -> Boolean

class MediaCacheMover(
    private val locations: MediaCacheLocations,
    private val storages: () -> List<MediaCacheStorage>,
    /** 这部番正在播 (条目 id) */
    private val isPlayingSubject: (subjectId: String) -> Boolean,
    private val scope: CoroutineScope,
    /** 缓存换成占位后等多久再复制: 让 BT 服务把种子关掉 */
    private val settleDelay: Duration = 2.seconds,
    private val freeBytes: (SystemPath) -> Long? = { it.usableSpace() },
    /** [waiting] 存在哪 (应用私有目录); `null` = 只在内存里 */
    private val waitingFile: SystemPath? = null,
) {
    /**
     * 一批正在搬的进度. [done] 是搬好的, [failed] 是没搬成的, [skipped] 是因为在播先不搬的 (不算处理完, [bytesDone] 里也不算);
     * 接着搬上一批跳过的时, [done] 与 [bytesDone] 含之前已经处理完的 (见 [Waiting.doneUnits]).
     * [total] 为 0: 刚开始, 还在列要搬哪些 (等各存储恢复完、盘扫完, 盘慢或正忙时要几秒), 这时还没有数.
     */
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
    ) {
        /** 一份要搬的都没有 (如引导里换了设置, 已有的都已经在那): 没有结果可说 */
        val nothingToMove: Boolean get() = stopReason == null && moved == 0 && failed == 0 && skipped == 0
    }

    enum class StopReason { TARGET_UNAVAILABLE, NOT_ENOUGH_SPACE, CANCELLED }

    /** 不在某个位置上的缓存有多少 */
    data class Pending(val units: Int, val bytes: Long)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _finished = MutableSharedFlow<Result>(extraBufferCapacity = 4)
    val finished: SharedFlow<Result> = _finished.asSharedFlow()

    /**
     * 搬完的一批里因为在播跳过、等那部番不再播的: 搬到哪 ([target])、哪几份 ([keys], 一共 [bytes]).
     * [doneUnits] 与 [doneBytes] 是这次搬家已经处理完的 (搬好与没搬成的), 接着搬时进度从这里接上.
     */
    @Serializable
    data class Waiting(
        val target: MediaCacheMoveTarget,
        val keys: Set<String>,
        val bytes: Long,
        val doneUnits: Int = 0,
        val doneBytes: Long = 0,
    ) {
        val units: Int get() = keys.size
    }

    private val _waiting = MutableStateFlow(loadWaiting())

    /** 等停播再搬的 (见 [Waiting]); 正在搬的那一批不在里面 */
    val waiting: StateFlow<Waiting?> = _waiting.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** 不在 [target] 位置上、可以搬过去的缓存 */
    suspend fun pending(target: String?): Pending = pending(listOf(target)).getValue(target)

    /** 对 [targets] 里每个位置, 不在它上面、可以搬过去的缓存 (只扫一遍) */
    suspend fun pending(targets: List<String?>): Map<String?, Pending> {
        val units = entries().map { it.unit }
        return targets.associateWith { target ->
            val others = units.filter { !it.isAt(MediaCacheMoveTarget(target)) }
            Pending(others.size, others.sumOf { it.bytes })
        }
    }

    /** 全部可以搬的缓存 (各在哪个位置、多大) */
    suspend fun units(): List<MediaCacheMoveUnit> = entries().map { it.unit }

    /** 不在 [target] 上、[include] 选中的缓存 (见 [start]) */
    suspend fun pending(target: MediaCacheMoveTarget, include: MoveFilter = { _, _ -> true }): List<MediaCacheMoveUnit> =
        selected(target, include).map { it.unit }

    /** 开始把不在 [target] 的缓存都搬过去; 已经在搬时不再开一批, 返回 false */
    fun start(target: String?): Boolean = start(MediaCacheMoveTarget(target))

    /**
     * 开始把不在 [target] 的缓存搬过去, 只搬 [include] 选中的 (参数是一份与它包含的缓存, 如打开片库时下完的交给发布, 这里不搬);
     * 已经在搬时不再开一批, 返回 false. 上一批跳过的不再等 (这一批按新的选择算).
     */
    fun start(target: MediaCacheMoveTarget, include: MoveFilter = { _, _ -> true }): Boolean {
        if (isRunning) return false
        setWaiting(null)
        job = scope.launch { run(target, include = include) }
        return true
    }

    /**
     * 等停播的 ([waiting]) 里不再播的那些接着搬到跳过时的位置; 还在播的继续等, 已经不在了的 (删掉了、搬过去了) 不再等.
     * [target] 是现在要搬到哪: 与跳过时不同 (设置改了) 就不搬了. 由播放状态的变化与应用启动触发 (见 MediaStorageSetup). 开了一批时返回 true.
     */
    suspend fun resumeSkipped(target: MediaCacheMoveTarget): Boolean {
        val waiting = _waiting.value ?: return false
        if (waiting.target != target) {
            setWaiting(null)
            return false
        }
        if (isRunning) return false
        val (ready, playing) = selected(target) { unit, _ -> unit.key in waiting.keys }
            .partition { entry -> entry.cachesOf().none { isPlayingSubject(it.metadata.subjectId) } }
        val stillWaiting = waiting.copy(keys = playing.mapTo(HashSet()) { it.unit.key }, bytes = playing.sumOf { it.unit.bytes })
            .takeIf { it.keys.isNotEmpty() }
        if (ready.isEmpty()) {
            setWaiting(stillWaiting)
            return false
        }
        val readyKeys = ready.mapTo(HashSet()) { it.unit.key }
        logger.info { "Resuming ${ready.size} caches skipped while playing" }
        job = scope.launch { run(target, resuming = waiting, stillWaiting = stillWaiting) { unit, _ -> unit.key in readyKeys } }
        return true
    }

    /** 不再等停播的 ([waiting]): 它们留在原处 (如改成只放新的、用户取消) */
    fun forgetSkipped() {
        setWaiting(null)
    }

    /** 停下正在搬的一批; 等停播的也不再等 */
    fun cancel() {
        job?.cancel()
        setWaiting(null)
    }

    private class Entry(val storage: MediaCacheStorage, val mover: MediaCacheEngineMover, val unit: MediaCacheMoveUnit) {
        suspend fun cachesOf(): List<MediaCache> = storage.listFlow.first().filter(unit.matches)
    }

    private suspend fun entries(): List<Entry> = storages().flatMap { storage ->
        val mover = moverOf(storage.engine) ?: return@flatMap emptyList()
        mover.units().map { Entry(storage, mover, it) }
    }

    private suspend fun selected(target: MediaCacheMoveTarget, include: MoveFilter): List<Entry> =
        entries().filter { !it.unit.isAt(target) && include(it.unit, it.cachesOf()) }

    /**
     * 搬一批. [resuming]: 这一批是接着搬等停播的, 进度从它已经处理完的接上; [stillWaiting] 是其中还要接着等的,
     * 这一批开始搬时换上 (面板上从「等停播」直接变成进度, 中间不空一下).
     */
    private suspend fun run(
        moveTarget: MediaCacheMoveTarget,
        resuming: Waiting? = null,
        stillWaiting: Waiting? = null,
        include: MoveFilter,
    ) {
        val target = moveTarget.location
        val doneBefore = resuming?.doneUnits ?: 0
        val doneBytesBefore = resuming?.doneBytes ?: 0
        var moved = 0
        var failed = 0
        var doneBytes = 0L
        var stopReason: StopReason? = null
        val skipped = mutableListOf<MediaCacheMoveUnit>()
        // 一开始就有进度 (还没有数): 列要搬哪些可能要几秒, 这期间动作面板上已经有这一条, 等停播的那条也不会先没了
        _progress.value = Progress(target, 0, 0, 0, 0, 0, 0)
        try {
            val entries = selected(moveTarget, include)
            val targetRoot = locations.rootOf(target)
            if (targetRoot == null || locations.locations.value.firstOrNull { it.id == target }?.unusable != null) {
                stopReason = StopReason.TARGET_UNAVAILABLE
                return
            }
            logger.info { "Moving ${entries.size} caches (${entries.sumOf { it.unit.bytes }} bytes) to ${target ?: "internal"}" }
            var progress = Progress(
                target, doneBefore + entries.size, doneBefore, 0, 0,
                doneBytesBefore + entries.sumOf { it.unit.bytes }, doneBytesBefore,
            )
            _progress.value = progress
            if (resuming != null) setWaiting(stillWaiting)
            for (entry in entries) {
                if (entry.cachesOf().any { isPlayingSubject(it.metadata.subjectId) }) {
                    logger.info { "Skipping ${entry.unit.key}: it is playing" }
                    skipped += entry.unit
                } else {
                    val free = freeBytes(targetRoot)
                    if (free != null && free - entry.unit.bytes < DownloadSpaceGuard.LOW_SPACE_BYTES) {
                        logger.warn { "Only $free bytes left on ${target ?: "internal"}, stopping before ${entry.unit.key} (${entry.unit.bytes} bytes)" }
                        stopReason = StopReason.NOT_ENOUGH_SPACE
                        break
                    }
                    val base = progress.bytesDone
                    if (moveOne(entry, moveTarget) { copied -> _progress.update { it?.copy(bytesDone = base + copied) } }) moved++ else failed++
                    doneBytes += entry.unit.bytes
                }
                progress = progress.copy(
                    done = doneBefore + moved, failed = failed, skipped = skipped.size, bytesDone = doneBytesBefore + doneBytes,
                )
                _progress.value = progress
            }
        } catch (e: CancellationException) {
            stopReason = StopReason.CANCELLED
            throw e
        } finally {
            // 整批走完时才等跳过的, 停下、取消的一批连同还在等的都不再自己接着搬. 先换上等停播的再撤进度, 面板上不空一下
            setWaiting(
                if (stopReason == null) {
                    val before = _waiting.value?.takeIf { it.target == moveTarget }
                    Waiting(
                        target = moveTarget,
                        keys = before?.keys.orEmpty() + skipped.map { it.key },
                        bytes = (before?.bytes ?: 0) + skipped.sumOf { it.bytes },
                        doneUnits = doneBefore + moved + failed,
                        doneBytes = doneBytesBefore + doneBytes,
                    ).takeIf { it.keys.isNotEmpty() }
                } else {
                    null
                },
            )
            _progress.value = null
            val result = Result(target, moved, failed, skipped.size, stopReason)
            logger.info { "Moving to ${target ?: "internal"} finished: $result" }
            _finished.tryEmit(result)
        }
    }

    private fun setWaiting(value: Waiting?) {
        _waiting.value = value
        val file = waitingFile ?: return
        try {
            if (value == null) {
                file.delete()
            } else {
                val tmp = file.resolveSibling(file.name + ".tmp")
                tmp.writeText(DataStoreJson.encodeToString(Waiting.serializer(), value))
                tmp.moveTo(file)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to save the caches waiting for playback to stop" }
        }
    }

    private fun loadWaiting(): Waiting? {
        val file = waitingFile ?: return null
        if (!file.exists()) return null
        return try {
            DataStoreJson.decodeFromString(Waiting.serializer(), file.readText())
        } catch (e: Exception) {
            // 读不出来就不接着搬了: 那些缓存留在原处, 设置里照常列出
            logger.warn(e) { "Caches waiting for playback to stop are unreadable, forgetting them" }
            null
        }
    }

    /** 搬一份, 返回是否搬成了 */
    private suspend fun moveOne(entry: Entry, moveTarget: MediaCacheMoveTarget, onProgress: (copied: Long) -> Unit): Boolean {
        val target = moveTarget.location
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
            // 复制到改完记录之间不让启动清扫插进来 (见 MediaCacheLocations.moveLock); 不包住上面的 refreshCache: 启动清扫持着存储锁等这把锁
            sources = locations.moveLock.withLock {
                entry.mover.move(unit, moveTarget) { bytes ->
                    copied += bytes
                    onProgress(copied)
                }
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
                    try {
                        entry.mover.afterSourcesDeleted(paths)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to clean up after moving ${unit.key}" }
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
            is LibraryMediaCacheEngine -> engine.mover
            else -> null
        }
    }
}
