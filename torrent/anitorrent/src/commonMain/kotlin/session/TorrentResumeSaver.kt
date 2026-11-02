/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 把续传数据 (哪些分块下好了) 定期写进 fastresume. 重启时 libtorrent 按它恢复、不再校验, 比实际旧的那部分当成没下、重新下;
 * 所以被系统杀掉、覆盖安装时丢的进度最多是一个 [interval].
 *
 * - 每 [interval] 看一次下载量与上传量 ([progress]), 都没变就不写, 闲着的外接盘不被叫醒 (libtorrent 也只在有变化时生成);
 * - 有文件停下 (暂停、关掉) 时马上写一次 ([saveSoon]);
 * - 写之前先把这个种子写着的文件落盘 ([syncFiles]), 续传数据记着的分块断电后也还在盘上.
 *   落盘与 libtorrent 生成续传数据之间完成的分块仍可能没落盘, 只有生成前的那一下.
 *
 * @param postSave 请 libtorrent 生成续传数据, 生成好了由会话写文件 (见 [AnitorrentDownloadSession.onSaveResumeData])
 */
internal class TorrentResumeSaver(
    private val progress: suspend () -> Pair<Long, Long>?,
    private val syncFiles: suspend () -> Unit,
    private val postSave: () -> Unit,
    private val interval: Duration = INTERVAL,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** 有文件停下了, 不等下一个间隔就写一次. */
    fun saveSoon() {
        requests.trySend(Unit)
    }

    /** 一直写到 [isValid] 为 false (种子移除了). */
    suspend fun run(isValid: () -> Boolean) {
        var saved: Pair<Long, Long>? = null
        while (isValid()) {
            val requested = withTimeoutOrNull(interval) { requests.receive() } != null
            if (!isValid()) return
            val now = progress()
            if (!requested && (now == null || now == saved)) continue
            // 落盘失败 (目录没了、外接盘读写出错) 也照样写续传数据: 不写的话之后每次都一样失败, 进度一直只在内存里
            try {
                syncFiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to sync the files before saving resume data" }
            }
            try {
                postSave()
                saved = now
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to save resume data" }
            }
        }
    }

    private companion object {
        val INTERVAL = 2.minutes
        val logger = logger<TorrentResumeSaver>()
    }
}

/**
 * 这次写续传数据之前要落盘的文件: 下载量比上次落盘时变了的 ([synced] 记着上次落盘时各文件的下载量).
 * 早已下完的、做种时只有上传量在变的都不再落盘 (外接盘上每开关一次文件都不便宜).
 */
internal fun filesToSync(downloaded: Map<String, Long>, synced: Map<String, Long>): List<String> =
    downloaded.filter { (path, bytes) -> bytes > 0 && synced[path] != bytes }.keys.toList()
