/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.service.proxy

import android.os.Binder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.torrent.service.TorrentClientHandles
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * 服务进程里由应用进程建的文件句柄 (见 [TorrentClientHandles]): 建句柄时记下是哪个文件、这次 binder 调用的哪个进程建的,
 * 顺带把这个文件上已经不在了的应用进程留下的句柄关掉. 进程在不在看 `/proc/<pid>` (同一个 uid 的进程看得到).
 */
internal object TorrentServiceClientHandles {
    private val logger = logger<TorrentServiceClientHandles>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO_)
    private val handles = TorrentClientHandles<TorrentFileHandleProxy, TorrentFileEntry> { pid -> File("/proc/$pid").exists() }

    /** 应用进程给 [file] 建了 [handle] (在 binder 调用里调) */
    fun opened(handle: TorrentFileHandleProxy, file: TorrentFileEntry) {
        val pid = Binder.getCallingPid()
        val stale = handles.register(handle, file, pid)
        if (stale.isEmpty()) return
        logger.info { "Closing ${stale.size} handles of ${file.pathInTorrent} left by app processes that are gone, current app process $pid" }
        scope.launch {
            for (leftover in stale) {
                try {
                    leftover.closeLeftover()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to close a file handle left by a gone app process" }
                }
            }
        }
    }

    /** [handle] 关掉了 */
    fun closed(handle: TorrentFileHandleProxy) {
        handles.unregister(handle)
    }
}
