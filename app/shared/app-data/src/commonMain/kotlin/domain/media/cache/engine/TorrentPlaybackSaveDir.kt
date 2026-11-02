/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.torrent.api.TorrentDownloader
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.utils.io.SystemPath

/**
 * BT 播放 (还没建缓存时) 的下载放在哪个目录.
 *
 * 开播后这一集会自动建缓存 (`CacheOnBtPlayExtension`), 下载器按种子只开一个会话、不看目录, 建缓存时直接用正在播的那个会话.
 * 所以播放的目录必须与缓存记录算出的一致, 规则同建缓存 ([TorrentMediaCacheEngine.playbackSaveDirOf]):
 * 否则缓存记在外接盘上、数据下在内部存储, 重启后按记录去外接盘从 0 开始, 内部那份没有记录用着, 被启动清扫删掉.
 */
fun interface TorrentPlaybackSaveDir {
    /** `null` = 由下载引擎决定 (内部存储) */
    suspend fun of(downloader: TorrentDownloader, data: EncodedTorrentInfo): SystemPath?
}

/** 开始播放用的下载: 放在 [saveDir] 给的目录, 没给时由下载引擎决定 */
suspend fun TorrentDownloader.startPlaybackDownload(data: EncodedTorrentInfo, saveDir: TorrentPlaybackSaveDir?): TorrentSession {
    val dir = saveDir?.of(this, data) ?: return startDownload(data)
    return startDownloadAt(data, dir)
}

/**
 * 各下载引擎播放时的 [TorrentPlaybackSaveDir]: 跟着同一个引擎的 BT 缓存放.
 * [downloadManager] 在开播时才取: 网页缓存引擎建的时候就要播放解析器, 而下载管理器又要网页缓存引擎.
 */
fun torrentPlaybackSaveDirs(downloadManager: () -> MediaDownloadManager): (TorrentEngine) -> TorrentPlaybackSaveDir = { engine ->
    TorrentPlaybackSaveDir { downloader, data ->
        downloadManager().storages
            .firstNotNullOfOrNull { (it.engine as? TorrentMediaCacheEngine)?.takeIf { cacheEngine -> cacheEngine.torrentEngine === engine } }
            ?.playbackSaveDirOf(downloader, data)
    }
}
