/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.download.DownloadTestCache
import me.him188.ani.app.domain.media.download.DownloadTestStorage
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.download.testDownload
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 下完的缓存按 [EpisodeCacheRecords] 同样的判据认这一集: 条目 ID 相同或记录没写条目, 剧集 ID 或序号对得上.
 * 认得比候选窄的话, 选源里这样认进来的下完的缓存会被当成没下完, 放着本地文件去播网络源.
 */
class FinishedCacheMediaIdsTest {
    /** 第 [id] 条缓存, 下完了; 记的是条目 [subjectId] 的剧集 [episodeId]、第 [sort] 集 */
    private fun finished(id: Int, subjectId: String, episodeId: Int, sort: Int): DownloadTestCache {
        val cache = testDownload(id, episodeId = episodeId)
        return DownloadTestCache(cache.media, cache.metadata.copy(subjectId = subjectId, episodeSort = EpisodeSort(sort)))
            .apply { state.value = MediaCacheState.COMPLETED }
    }

    @Test
    fun `finished caches are matched like the cache records`() = runTest {
        // 这一集现在是条目 1 的剧集 5、第 1 集
        val renumbered = finished(1, subjectId = "1", episodeId = 99, sort = 1) // 缓存时记的剧集 ID 不一样 (条目重排过分集)
        val unnamed = finished(2, subjectId = "", episodeId = 5, sort = 1) // 记录没写条目 (本地缓存源按名字认)
        val otherSubject = finished(3, subjectId = "2", episodeId = 5, sort = 1)
        val otherEpisode = finished(4, subjectId = "1", episodeId = 6, sort = 2)
        val storage = DownloadTestStorage().apply { listFlow.value = listOf(renumbered, unnamed, otherSubject, otherEpisode) }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)

        val ids = manager.finishedCacheMediaIds(subjectId = 1, EpisodeInfo(episodeId = 5, type = EpisodeType.MainStory, sort = EpisodeSort(1)))
        assertEquals(
            setOf(renumbered, unnamed).map { "${storage.mediaSourceId}:${it.origin.mediaId}:${it.metadata.episodeId}" }.toSet(),
            ids,
        )
    }
}
