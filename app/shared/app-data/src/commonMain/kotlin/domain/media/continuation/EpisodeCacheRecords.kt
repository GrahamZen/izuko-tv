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

/**
 * 这一集在本机有没有缓存记录. 读的是缓存的元数据 ([MediaCacheSave]), 不等缓存恢复, 也不等 BT 服务连上.
 *
 * 判据从宽, 宁可让线索多等一次本地缓存源, 也不让它盖过缓存: 条目 ID 相同 (记录没有条目 ID 时本地缓存源按名字认, 也算),
 * 剧集 ID 或序号对得上.
 */
fun interface EpisodeCacheRecords {
    suspend fun hasRecord(subjectId: Int, episode: EpisodeInfo): Boolean

    companion object {
        fun of(store: DataStore<List<MediaCacheSave>>): EpisodeCacheRecords = EpisodeCacheRecords { subjectId, episode ->
            val subject = subjectId.toString()
            val episodeId = episode.episodeId.toString()
            store.data.first().any { save ->
                val metadata = save.metadata
                (metadata.subjectId.isEmpty() || metadata.subjectId == subject) &&
                        (metadata.episodeId == episodeId || metadata.episodeSort == episode.sort)
            }
        }
    }
}
