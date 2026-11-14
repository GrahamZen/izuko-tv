/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.resolveFreshLink
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.utils.coroutines.update
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadSource
import me.him188.ani.utils.httpdownloader.DownloadSourceRefresher
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.httpdownloader.RefreshableHttpDownloader

/**
 * 在线源缓存的下载地址被拒 (多半是签名过期: 网盘直链约 6 小时失效, 停一阵再接着下时落库的地址早过期了) 时, 重新解析原资源换一个新地址
 * (见 [resolveFreshLink], 与播放共用; 要开网页现抓的源这里也重新抓). 下载器怎么换、什么时候放弃见 [DownloadSourceRefresher].
 */
internal class HttpCacheSourceRefresher(
    private val mediaResolver: MediaResolver,
) : DownloadSourceRefresher {
    private class Origin(val media: Media, val metadata: MediaCacheMetadata)

    /** 各任务对应的原资源, 新建与恢复缓存时记下 */
    private val origins = MutableStateFlow(persistentMapOf<DownloadId, Origin>())

    /** 让 [downloader] 地址被拒时来这里换 */
    fun attachTo(downloader: HttpDownloader) {
        (downloader as? RefreshableHttpDownloader)?.sourceRefresher = this
    }

    fun register(downloadId: DownloadId, origin: Media, metadata: MediaCacheMetadata) {
        origins.update { put(downloadId, Origin(origin, metadata)) }
    }

    override suspend fun refresh(state: DownloadState): DownloadSource? {
        val origin = origins.value[state.downloadId] ?: return null
        val metadata = origin.metadata
        val episode = EpisodeMetadata(metadata.episodeName, metadata.episodeEp, metadata.episodeSort)
        return mediaResolver.resolveFreshLink(origin.media, episode, allowWebPage = true)?.let { DownloadSource(it.uri, it.headers) }
    }
}
