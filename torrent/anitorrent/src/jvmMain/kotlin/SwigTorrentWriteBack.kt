/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent

import kotlinx.coroutines.CoroutineScope
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.anitorrent.binding.session_t
import org.openani.anitorrent.binding.write_back_listener_t

/**
 * 把 [TorrentWriteBack] 接到 anitorrent 原生层的写盘回调上. 原生库不带这个回调时 (桌面端仍是上游的 0.2.0)
 * 只记一条日志, 下载照常.
 */
internal class SwigTorrentWriteBack(private val session: session_t, scope: CoroutineScope) : AutoCloseable {
    private val writeBack = TorrentWriteBack(
        scope,
        writeBackDone = { session.write_back_done(it) },
        forcedReleases = { session.write_back_forced_releases() },
    )

    // 原生层只存指针, 这里必须一直引用着
    private val listener: write_back_listener_t? = try {
        object : write_back_listener_t() {
            override fun on_written(path: String?, bytes: Long): Boolean =
                path != null && writeBack.onWritten(path, bytes)
        }.also { session.set_write_back_listener(it) }
    } catch (e: UnsatisfiedLinkError) {
        logger.warn { "anitorrent native library has no write-back hook, downloads are not synced as they are written: $e" }
        null
    }

    override fun close() {
        if (listener != null) session.set_write_back_listener(null)
    }

    private companion object {
        val logger = logger<SwigTorrentWriteBack>()
    }
}
