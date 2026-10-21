/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.WriteBackCounter
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.syncFileContents
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * BT 下载写盘的节奏, 与在线源下载同一套 (见 [WriteBackCounter]): 每个文件写够 [syncEveryBytes] 就落盘一次,
 * 落完之前 libtorrent 不再从连接里收数据, 没落盘的数据积不起来.
 *
 * anitorrent 原生层每往一个文件写一段就调用 [onWritten] (在 libtorrent 的网络线程上). 返回 true 时原生层压住接收,
 * 这里在 IO 线程上 [syncFileContents] 落盘, 落完调用 [writeBackDone] 放开. 同一个文件上一次还没落完时不再叠一次,
 * 那段时间接收本来就停着. 没选中的集的数据写在种子的 .parts 文件里, 原生层报的是它的路径, 一样处理.
 */
internal class TorrentWriteBack(
    private val scope: CoroutineScope,
    private val writeBackDone: (path: String) -> Unit,
    /** 原生层等落盘过久、自己放开的累计次数, 只用来记日志 */
    private val forcedReleases: () -> Long = { 0 },
    private val syncEveryBytes: Long = WriteBackCounter.SYNC_EVERY_BYTES_DOWNLOAD,
    private val syncFile: (SystemPath) -> Boolean = ::syncFileContents,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    private val lock = SynchronizedObject()
    private val counters = HashMap<String, WriteBackCounter>()
    private val inFlight = HashSet<String>()

    // 以下由 lock 保护, 每 SUMMARY_INTERVAL 写一条日志
    private var windowStart = TimeSource.Monotonic.markNow()
    private var syncs = 0
    private var failures = 0
    private var totalWait = Duration.ZERO
    private var maxWait = Duration.ZERO
    private var lastForcedReleases = 0L

    /** 文件 [path] 又写了 [bytes] 字节. 返回 true 表示要落盘, 落完之前原生层压住接收. */
    fun onWritten(path: String, bytes: Long): Boolean {
        synchronized(lock) {
            val counter = counters.getOrPut(path) { WriteBackCounter(syncEveryBytes) }
            if (!counter.wrote(bytes) || !inFlight.add(path)) return false
        }
        // ATOMIC: 下载器正在关闭 (scope 已取消) 时也要跑完, 原生层才会放开接收
        scope.launch(ioDispatcher, start = CoroutineStart.ATOMIC) {
            val started = TimeSource.Monotonic.markNow()
            var synced = false
            try {
                synced = syncFile(Path(path).inSystem)
            } catch (e: Exception) {
                logger.info { "Failed to sync downloaded torrent data to storage: $e" }
            } finally {
                synchronized(lock) {
                    inFlight -= path
                    record(synced, started.elapsedNow())
                }
                writeBackDone(path)
            }
        }
        return true
    }

    private fun record(synced: Boolean, wait: Duration) {
        syncs++
        if (!synced) failures++
        totalWait += wait
        if (wait > maxWait) maxWait = wait
        val window = windowStart.elapsedNow()
        if (window < SUMMARY_INTERVAL) return
        val forced = forcedReleases()
        logger.info {
            "Write-back in the last ${window.inWholeSeconds}s: $syncs syncs ($failures failed), " +
                    "wait avg ${(totalWait / syncs).inWholeMilliseconds} ms / max ${maxWait.inWholeMilliseconds} ms, " +
                    "forced releases ${forced - lastForcedReleases}"
        }
        windowStart = TimeSource.Monotonic.markNow()
        syncs = 0
        failures = 0
        totalWait = Duration.ZERO
        maxWait = Duration.ZERO
        lastForcedReleases = forced
    }

    private companion object {
        val SUMMARY_INTERVAL = 30.seconds
        val logger = logger<TorrentWriteBack>()
    }
}
