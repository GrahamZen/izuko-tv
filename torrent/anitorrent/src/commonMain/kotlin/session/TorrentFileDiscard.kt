/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import kotlin.time.Duration.Companion.seconds

/**
 * 丢掉种子里一个文件已下载的数据 (整季包里删掉的一集), 空间当场还给系统; 种子的其余部分照常下载和上传.
 * 丢数据本身由原生层做 ([TorrentHandle.discardFile]): 让 libtorrent 忘掉完全落在这个文件里的分块, 再把那一段打成空洞.
 *
 * 续传数据 ([fastResumeFile]) 必须和磁盘一致, 否则下次打开种子会把空洞当成下好的数据 (重新缓存这一集秒判完成,
 * 交出坏文件). 原生层忘掉分块的同时删掉它, 与续传数据的写入互斥, 忘掉之前生成的旧数据也写不下去;
 * 这里丢完马上写一份新的, 来不及写就没了, 下次打开全量校验.
 */
internal class TorrentFileDiscard(
    private val handle: TorrentHandle,
    private val fastResumeFile: SystemPath,
    private val saveResumeData: suspend () -> Unit,
) {
    private val lock = Mutex()

    /** 丢掉第 [index] 个文件的数据. 没有东西可丢、或丢不了时返回 false, 数据与续传数据都不变. */
    suspend fun discard(index: Int): Boolean = lock.withLock {
        val discarded = withContext(Dispatchers.IO_) {
            handle.hasDiscardableData(index) && handle.discardFile(index, fastResumeFile.absolutePath)
        }
        if (discarded) withTimeoutOrNull(RESUME_SAVE_TIMEOUT) { saveResumeData() }
        discarded
    }

    private companion object {
        val RESUME_SAVE_TIMEOUT = 10.seconds
    }
}
