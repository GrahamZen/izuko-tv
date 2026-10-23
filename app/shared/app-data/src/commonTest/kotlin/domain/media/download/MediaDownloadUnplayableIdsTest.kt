/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.download

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/** 这一集不能播的缓存 id 要与选源候选里那条缓存的 id 一样, 筛选才认得出「缓存未完成」. */
class MediaDownloadUnplayableIdsTest {
    @Test
    fun `unplayable ids match the cached media listed for the episode`() = runTest {
        val unfinished = testDownload(1).apply { canPlay.value = false }
        val finished = testDownload(2, episodeId = 1)
        val storage = DownloadTestStorage().apply { listFlow.value = listOf(unfinished, finished) }
        val manager = MediaDownloadManager(listOf(storage), backgroundScope)

        // 存储列缓存候选时带上这一集的 id (MediaCacheStorage 的 forRecord)
        val listed = CachedMedia(
            unfinished.origin, storage.mediaSourceId, ResourceLocation.LocalFile("/download-1"),
            cacheEpisodeId = unfinished.metadata.episodeId,
        )
        assertEquals(setOf(listed.mediaId), manager.unplayableCacheMediaIds(subjectId = 1, episodeId = 1).first())
    }
}
