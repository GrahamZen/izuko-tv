/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.datasources.api.MediaCacheMetadata

/**
 * 这一集在本机有没有缓存记录. 读的是缓存的元数据 ([MediaCacheSave]), 不等缓存恢复, 也不等 BT 服务连上.
 *
 * 判据从宽, 宁可让线索多等一次本地缓存源, 也不让它盖过缓存: 条目 ID 相同 (记录没有条目 ID 时本地缓存源按名字认, 也算),
 * 剧集 ID 或序号对得上.
 */
fun interface EpisodeCacheRecords {
    suspend fun hasRecord(subjectId: Int, episode: EpisodeInfo): Boolean

    /** 这一集每一份缓存记录的资源 ([MediaCacheSave.origin] 的 mediaId), 判据同 [hasRecord]; 只认得有没有的实现给空. */
    suspend fun mediaIdsOf(subjectId: Int, episode: EpisodeInfo): List<String> = emptyList()

    companion object {
        fun of(store: DataStore<List<MediaCacheSave>>): EpisodeCacheRecords = object : EpisodeCacheRecords {
            override suspend fun hasRecord(subjectId: Int, episode: EpisodeInfo): Boolean =
                store.data.first().any { it.isOf(subjectId, episode) }

            override suspend fun mediaIdsOf(subjectId: Int, episode: EpisodeInfo): List<String> =
                store.data.first().filter { it.isOf(subjectId, episode) }.map { it.origin.mediaId }.distinct()
        }

        private fun MediaCacheSave.isOf(subjectId: Int, episode: EpisodeInfo): Boolean = metadata.isOfEpisode(subjectId, episode)
    }
}

/**
 * 这条缓存记的是不是 [subjectId] 的 [episode] (判据见 [EpisodeCacheRecords]): 条目 ID 相同或记录没写条目, 剧集 ID 或序号对得上.
 * 有没有记录与哪几条下完了 ([finishedCacheMediaIds]) 用同一个判据, 认进候选的缓存才分得出下没下完.
 */
internal fun MediaCacheMetadata.isOfEpisode(subjectId: Int, episode: EpisodeInfo): Boolean =
    (this.subjectId.isEmpty() || this.subjectId == subjectId.toString()) &&
            (episodeId == episode.episodeId.toString() || episodeSort == episode.sort)
