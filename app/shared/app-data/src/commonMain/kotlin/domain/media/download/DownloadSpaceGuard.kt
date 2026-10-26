/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.usableSpace
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 缓存所在的盘快满时暂停那个盘上正在进行的下载, 并经 [lowSpacePauses] 交给界面提示. 各盘分开算 (缓存可以放在外接盘上,
 * 见 [MediaCache.locationId]): 一个盘满了不影响别的盘上的下载.
 *
 * 下载不会因为空间不够自己停下, 会一直写到分区只剩几百 KB. 那时系统和别的应用写不进数据库, 会被判定损坏而删掉
 * (电视的节目数据库就这样没过), 删缓存的记录也写不进去, 重启后被删的缓存又回来. 所以剩余空间低于 [lowSpaceBytes]
 * 就先全部停下, 由用户腾出空间后再继续; 空间仍不够时继续, 会再停下并提示.
 * 正在播放的 BT 视频本身也在写盘, 它不是下载记录, 这里不管.
 *
 * torrent 服务断开重连时, 有的下载读状态会一直等到服务回来 (或报错); 每个下载的读取与暂停都限时, 一次检查出错只记日志,
 * 不能让一个读不出状态的下载把整个保护停住 —— 重连后下载照常恢复, 这时正要靠它停下.
 */
class DownloadSpaceGuard(
    private val downloadManager: MediaDownloadManager,
    /** 位置 ([MediaCache.locationId]) 的缓存根目录; 那个盘没接上时为 `null`, 不管 */
    private val rootOf: (location: String?) -> SystemPath?,
    private val scope: CoroutineScope,
    private val freeBytes: (SystemPath) -> Long? = { it.usableSpace() },
    private val lowSpaceBytes: Long = LOW_SPACE_BYTES,
    private val checkInterval: Duration = CHECK_INTERVAL,
) {
    fun start() {
        scope.launch {
            while (isActive) {
                delay(checkInterval)
                try {
                    check()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to check the free space for caches" }
                }
            }
        }
    }

    /** 空间不足的盘上有下载在进行时暂停它们, 返回一共暂停了几个. */
    internal suspend fun check(): Int {
        val downloads = downloadManager.downloads.value
        // 先只看剩余空间 (便宜), 空间都够时不去读各下载的状态
        val lowFree = downloads.mapTo(LinkedHashSet()) { it.cache.locationId }
            .mapNotNull { location ->
                val free = rootOf(location)?.let(freeBytes) ?: return@mapNotNull null
                if (free < lowSpaceBytes) location to free else null
            }
            .toMap()
        if (lowFree.isEmpty()) return 0
        var paused = 0
        for ((location, free) in lowFree) {
            paused += pauseRunning(downloads.filter { it.cache.locationId == location }, location, free)
        }
        return paused
    }

    private suspend fun pauseRunning(downloads: List<MediaDownload>, location: String?, free: Long): Int {
        val running = downloads.filter { stateOf(it) == MediaCacheState.IN_PROGRESS }
        if (running.isEmpty()) return 0
        logger.warn { "Only ${free.bytes} left on location ${location ?: "internal"}, pausing ${running.size} downloads" }
        for (download in running) {
            try {
                if (withTimeoutOrNull(STATE_TIMEOUT) { download.pause() } == null) {
                    logger.warn { "Pausing download ${download.id} for low storage timed out" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to pause download ${download.id} for low storage" }
            }
        }
        _lowSpacePauses.tryEmit(LowSpacePause(freeBytes = free, paused = running.size, location = location))
        return running.size
    }

    /** 读不出来 (超时 / 出错) 时为 null, 这次不管它, 下次检查再看. */
    private suspend fun stateOf(download: MediaDownload): MediaCacheState? = try {
        withTimeoutOrNull(STATE_TIMEOUT) { download.cache.state.first() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn { "Failed to read the state of download ${download.id}: $e" }
        null
    }

    /** 因空间不足暂停了 [location] 这个位置上的 [paused] 个下载, 那个盘当时还剩 [freeBytes] 字节. */
    data class LowSpacePause(val freeBytes: Long, val paused: Int, val location: String? = null)

    companion object {
        /** 剩余空间低于它就暂停. 下得最快时 (十几 MB/s) 两次检查之间写进去的远小于它. */
        const val LOW_SPACE_BYTES: Long = 1L shl 30
        val CHECK_INTERVAL: Duration = 5.seconds
        private val STATE_TIMEOUT: Duration = 3.seconds

        private val logger = logger<DownloadSpaceGuard>()
        private val _lowSpacePauses =
            MutableSharedFlow<LowSpacePause>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** 界面订阅它弹提示 (下载可能在任何一页、或经 Web 控制台开始). */
        val lowSpacePauses: SharedFlow<LowSpacePause> = _lowSpacePauses.asSharedFlow()
    }
}
