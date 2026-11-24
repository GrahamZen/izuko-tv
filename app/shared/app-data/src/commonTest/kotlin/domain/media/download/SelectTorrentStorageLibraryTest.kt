/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.SOURCE_DMHY
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.DummyMediaCache
import me.him188.ani.app.domain.media.cache.engine.DummyMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.cache.library.LibraryEngineKey
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize.Companion.gigaBytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 合集里下完的几集移进片库后, 片库也有这个种子的记录; 片库不下载, 不能因此认成这个种子的主人 ([selectTorrentStorage]).
 */
class SelectTorrentStorageLibraryTest {
    /** 整季合集: 每一集都是同一条资源 */
    private val pack = createTestDefaultMedia(
        mediaId = "$SOURCE_DMHY.pack",
        mediaSourceId = SOURCE_DMHY,
        originalTitle = "[字幕组] 孤独摇滚 [01-12][1080P]",
        download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:1"),
        originalUrl = "https://example.com/pack",
        publishedTime = 1,
        episodeRange = EpisodeRange.range(EpisodeSort(1), EpisodeSort(12)),
        properties = createTestMediaProperties(
            subtitleLanguageIds = listOf("CHS"),
            resolution = "1080P",
            alliance = "字幕组",
            size = 3.gigaBytes,
            subtitleKind = null,
        ),
        kind = MediaSourceKind.BitTorrent,
        location = MediaSourceLocation.Online,
    )

    private fun metadata(episodeId: String) = MediaCacheMetadata(
        subjectId = "1",
        episodeId = episodeId,
        subjectNameCN = "孤独摇滚",
        subjectNames = listOf("孤独摇滚"),
        episodeSort = EpisodeSort(episodeId),
        episodeEp = EpisodeSort(episodeId),
        episodeName = "test",
    )

    private class FakeStorage(engineKey: MediaCacheEngineKey, private val supports: Boolean) : MediaCacheStorage {
        override val mediaSourceId: String get() = MediaDownloadManager.LOCAL_FS_MEDIA_SOURCE_ID
        override val cacheMediaSource: MediaSource get() = throw UnsupportedOperationException()
        override val engine: MediaCacheEngine = object : MediaCacheEngine by DummyMediaCacheEngine(mediaSourceId, engineKey = engineKey) {
            override fun supports(media: Media): Boolean = supports
        }
        override val listFlow = MutableStateFlow<List<MediaCache>>(emptyList())
        override val stats: Flow<MediaStats> = flowOf(MediaStats.Unspecified)

        override suspend fun restorePersistedCaches() = Unit

        override suspend fun cache(
            media: Media,
            metadata: MediaCacheMetadata,
            episodeMetadata: EpisodeMetadata,
            resume: Boolean,
        ): MediaCache = throw UnsupportedOperationException()

        override suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean = false

        override fun close() = Unit
    }

    private fun FakeStorage.addRecord(episodeId: String) {
        listFlow.value += DummyMediaCache(pack, metadata(episodeId), mediaSourceId)
    }

    private val anitorrent = FakeStorage(MediaCacheEngineKey.Anitorrent, supports = true)

    /** 片库不支持任何资源 */
    private val library = FakeStorage(LibraryEngineKey, supports = false)

    @Test
    fun `episodes of a pack published to the library do not stop another episode from downloading`() = runTest {
        library.addRecord(episodeId = "1")
        library.addRecord(episodeId = "2")

        assertEquals(anitorrent, selectTorrentStorage(listOf(library, anitorrent), pack))
    }

    @Test
    fun `the torrent engine still downloading the pack keeps it`() = runTest {
        val pikPak = FakeStorage(MediaCacheEngineKey.PikPak, supports = true)
        library.addRecord(episodeId = "1")
        anitorrent.addRecord(episodeId = "3")

        assertEquals(anitorrent, selectTorrentStorage(listOf(library, pikPak, anitorrent), pack, playedWith = MediaCacheEngineKey.PikPak))
    }

    @Test
    fun `a disabled engine owning the pack still blocks the others`() = runTest {
        val disabled = FakeStorage(MediaCacheEngineKey.PikPak, supports = false)
        library.addRecord(episodeId = "1")
        disabled.addRecord(episodeId = "2")

        assertNull(selectTorrentStorage(listOf(library, disabled, anitorrent), pack))
    }
}
