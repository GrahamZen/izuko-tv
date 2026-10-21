/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.anitorrent.session

import kotlinx.io.files.Path
import me.him188.ani.app.torrent.anitorrent.HandleId
import me.him188.ani.app.torrent.api.TorrentDownloaderConfig
import me.him188.ani.app.torrent.api.TorrentHandleState
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.peer.PeerInfo

/**
 * libtorrent 的 session_t, 用来管理多个 torrent 任务
 */
interface TorrentManagerSession<Handle : TorrentHandle, AddInfo : TorrentAddInfo> {
    fun createTorrentHandle(): Handle
    fun createTorrentAddInfo(): AddInfo

    fun startDownload(handle: Handle, addInfo: AddInfo, saveDir: Path): Boolean
    fun releaseHandle(handle: Handle)

    fun resume()

    fun applyConfig(config: TorrentDownloaderConfig)
}

/**
 * Native handle
 */
interface TorrentHandle {
    val id: HandleId

    val isValid: Boolean

    fun postStatusUpdates()
    fun postSaveResume()

    fun resume()
    fun setFilePriority(index: Int, priority: FilePriority)

    /**
     * @return 当前状态, `null` if session is closed
     */
    fun getState(): TorrentHandleState?
    fun reloadFile(): TorrentDescriptor

    fun getPeers(): List<PeerInfo>

    fun setPieceDeadline(index: Int, deadline: Int)
    fun clearPieceDeadlines()

    fun addTracker(tracker: String, tier: Short = 0, failLimit: Short = 0)

    fun getMagnetUri(): String?

    /**
     * 丢掉文件 [index] 已下载的数据、腾出空间 (整季包里删掉一集), 见 anitorrent 的 `torrent_handle_t::discard_file`:
     * 完全落在这个文件里的分块此后算没下载, 续传数据 [fastResumePath] 同时删掉 (调用方随后写一份新的).
     * 返回这些分块是不是已经忘掉; 原生库不支持或空间还不回去时返回 false, 什么也不变.
     */
    fun discardFile(index: Int, fastResumePath: String): Boolean = false

    /**
     * [discardFile] 对文件 [index] 还有没有东西可丢: libtorrent 还有完全落在这个文件里的分块, 或者那一段还占着磁盘.
     */
    fun hasDiscardableData(index: Int): Boolean = false
}


interface TorrentAddInfo {
    fun setMagnetUri(uri: String)
    fun setTorrentFilePath(absolutePath: String)

    fun setResumeDataPath(absolutePath: String)
}

