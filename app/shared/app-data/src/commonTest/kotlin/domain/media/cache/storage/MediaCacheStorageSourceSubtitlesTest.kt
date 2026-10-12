/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.TestMediaCache
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.datasources.api.Subtitle
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MediaCacheStorageSourceSubtitlesTest {
    @Test
    fun `cached media keeps the subtitles saved with the cache`() = runTest {
        val metadata = MediaCacheMetadata(
            subjectId = "1",
            episodeId = "11",
            subjectNameCN = "Test Subject",
            subjectNames = listOf("Test Subject"),
            episodeSort = EpisodeSort(1),
            episodeEp = EpisodeSort(1),
            episodeName = "Episode 1",
        )
        // 缓存引擎给出的 CachedMedia 挂着建缓存时存下的本地字幕, 原始资源没有
        val local = MediaExtraFiles(listOf(Subtitle("file:///cache/izuko-subtitles/x/0.ass", label = "简体中文")))
        val cached = CachedMedia(
            origin = testMedia(),
            cacheMediaSourceId = "local-file-system",
            download = ResourceLocation.LocalFile("/cache/x.mkv"),
            extraFiles = local,
        )
        val source = MediaCacheStorageSource(ListOnlyStorage(listOf(TestMediaCache(cached, metadata))), "Local cache")

        val request = MediaFetchRequest(
            subjectId = "1",
            episodeId = "11",
            subjectNames = listOf("Test Subject"),
            episodeSort = EpisodeSort(1),
            episodeName = "Episode 1",
        )
        val media = source.fetch(request).results.toList().single().media

        assertIs<CachedMedia>(media)
        assertEquals("11", media.cacheEpisodeId)
        assertEquals(local.subtitles, media.extraFiles.subtitles)
    }
}

/** 只给出缓存列表的存储 */
private class ListOnlyStorage(caches: List<MediaCache>) : MediaCacheStorage {
    override val mediaSourceId: String get() = "local-file-system"
    override val cacheMediaSource: MediaSource get() = error("unused")
    override val engine: MediaCacheEngine get() = error("unused")
    override val stats: Flow<MediaStats> = emptyFlow()
    override val listFlow: Flow<List<MediaCache>> = flowOf(caches)
    override suspend fun restorePersistedCaches() = Unit
    override suspend fun cache(
        media: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        resume: Boolean,
    ): MediaCache = error("unused")

    override suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean = false
    override fun close() = Unit
}

private fun testMedia(): Media = createTestDefaultMedia(
    mediaId = "media-1",
    mediaSourceId = "source-1",
    originalUrl = "https://example.com/media-1",
    download = ResourceLocation.HttpStreamingFile("https://example.com/media-1.mkv"),
    originalTitle = "Episode 1",
    publishedTime = 1L,
    properties = createTestMediaProperties(
        subjectName = "Test Subject",
        episodeName = "Episode 1",
    ),
    episodeRange = EpisodeRange.single(EpisodeSort(1)),
    location = MediaSourceLocation.Online,
    kind = MediaSourceKind.WEB,
)
