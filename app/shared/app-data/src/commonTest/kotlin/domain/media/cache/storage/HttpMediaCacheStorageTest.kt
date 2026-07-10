/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.TestMediaCache
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpMediaCacheStorageTest {
    @Test
    fun `cache shows placeholder before slow engine creation completes`() = runTest {
        val createGate = CompletableDeferred<Unit>()
        val metadataStore = MemoryDataStore<List<MediaCacheSave>>(emptyList())
        val engine = DelayedHttpCacheEngine(createGate)
        val storage = HttpMediaCacheStorage(
            mediaSourceId = "local-file-system",
            store = metadataStore,
            dao = FakeHttpCacheDownloadStateDao,
            httpEngine = engine,
            displayName = "Test HTTP Storage",
            parentCoroutineContext = backgroundScope.coroutineContext,
        )
        val media = testMedia()
        val metadata = testMetadata()

        val job = launch {
            storage.cache(
                media = media,
                metadata = metadata,
                episodeMetadata = EpisodeMetadata("Episode 1", EpisodeSort(1), EpisodeSort(1)),
                resume = false,
            )
        }

        runCurrent()

        val pending = storage.listFlow.first().single()
        assertSame(media, pending.origin)
        assertEquals(metadata, pending.metadata)
        assertEquals(MediaCacheState.IN_PROGRESS, pending.state.first())
        assertEquals(MediaCache.FileStats.Unspecified, pending.fileStats.first())
        assertEquals(MediaCache.SessionStats.Unspecified, pending.sessionStats.first())
        assertFalse(pending.canPlay.first())
        assertEquals(emptyList(), metadataStore.data.first())

        createGate.complete(Unit)
        runCurrent()
        job.join()

        val saved = metadataStore.data.first().single()
        assertEquals(media.mediaId, saved.origin.mediaId)
        assertEquals(metadata, saved.metadata)
    }

    @Test
    fun `startup cleanup must keep lock until snapshot cleanup finishes`() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val engine = StartupCleanupOrderingHttpCacheEngine(cleanupStarted, allowCleanup)
        val storage = HttpMediaCacheStorage(
            mediaSourceId = "local-file-system",
            store = MemoryDataStore(emptyList()),
            dao = FakeHttpCacheDownloadStateDao,
            httpEngine = engine,
            displayName = "Test HTTP Storage",
            parentCoroutineContext = backgroundScope.coroutineContext,
        )
        val media = testMedia()
        val metadata = testMetadata()
        val episodeMetadata = EpisodeMetadata("Episode 1", EpisodeSort(1), EpisodeSort(1))

        val restore = launch { storage.restorePersistedCaches() }
        cleanupStarted.await()

        val cache = launch {
            storage.cache(media, metadata, episodeMetadata, resume = false)
        }
        runCurrent()

        // deleteUnusedCaches 使用的是恢复完成时的冻结快照. 在它结束前不能允许新缓存创建,
        // 否则新下载不在白名单中, 会被本轮启动清扫误删.
        assertEquals(0, engine.createCount)
        assertFalse(cache.isCompleted)

        allowCleanup.complete(Unit)
        restore.join()
        cache.join()

        assertEquals(1, engine.createCount)
        assertEquals(listOf("cleanup-start", "cleanup-end", "create-1"), engine.events)
    }

    @Test
    fun `caller cancellation must not release lock while old HTTP cache is still deleting`() = runTest {
        val deleteStarted = CompletableDeferred<Unit>()
        val allowDelete = CompletableDeferred<Unit>()
        val metadataStore = MemoryDataStore<List<MediaCacheSave>>(emptyList())
        val engine = DeletionOrderingHttpCacheEngine(deleteStarted, allowDelete)
        val storage = HttpMediaCacheStorage(
            mediaSourceId = "local-file-system",
            store = metadataStore,
            dao = FakeHttpCacheDownloadStateDao,
            httpEngine = engine,
            displayName = "Test HTTP Storage",
            parentCoroutineContext = backgroundScope.coroutineContext,
        )
        val media = testMedia()
        val metadata = testMetadata()
        val episodeMetadata = EpisodeMetadata("Episode 1", EpisodeSort(1), EpisodeSort(1))

        val oldCache = storage.cache(media, metadata, episodeMetadata, resume = false)
        val deleteCaller = launch { storage.delete(oldCache) }
        deleteStarted.await()

        // 模拟发起删除的 ViewModel/界面被销毁. 真正的删除由 storage scope 继续执行.
        deleteCaller.cancelAndJoin()

        val recache = launch {
            storage.cache(media, metadata, episodeMetadata, resume = false)
        }
        runCurrent()

        // 旧删除尚未完成时必须继续持有 HTTP storage lock, 否则相同 downloadId 的新任务
        // 会先创建, 随后被旧 closeAndDeleteFiles 误删.
        assertEquals(1, engine.createCount)
        assertFalse(recache.isCompleted)

        allowDelete.complete(Unit)
        recache.join()

        assertEquals(2, engine.createCount)
        assertEquals(listOf("create-1", "delete-1", "create-2"), engine.events)
        assertTrue(metadataStore.data.first().isNotEmpty())
    }
}

private abstract class TestHttpCacheEngine : MediaCacheEngine {
    override val engineKey: MediaCacheEngineKey = MediaCacheEngineKey.WebM3u
    override val stats: Flow<MediaStats> = flowOf(MediaStats.Zero)

    override fun supports(media: Media): Boolean = true

    override suspend fun restore(
        origin: Media,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext,
    ): MediaCache? = null

    protected fun testCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        suffix: String = "",
    ): TestMediaCache = TestMediaCache(
        media = CachedMedia(
            origin = origin,
            cacheMediaSourceId = "local-file-system",
            download = ResourceLocation.LocalFile("/tmp/${origin.mediaId}$suffix.mp4"),
        ),
        metadata = metadata,
    )

    override suspend fun deleteUnusedCaches(all: List<MediaCache>) = Unit
}

private class DelayedHttpCacheEngine(
    private val createGate: CompletableDeferred<Unit>,
) : TestHttpCacheEngine() {

    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext,
    ): MediaCache {
        createGate.await()
        return testCache(origin, metadata)
    }
}

private class StartupCleanupOrderingHttpCacheEngine(
    private val cleanupStarted: CompletableDeferred<Unit>,
    private val allowCleanup: CompletableDeferred<Unit>,
) : TestHttpCacheEngine() {
    var createCount: Int = 0
        private set
    val events = mutableListOf<String>()

    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext,
    ): MediaCache {
        val generation = ++createCount
        events += "create-$generation"
        return testCache(origin, metadata, "-$generation")
    }

    override suspend fun deleteUnusedCaches(all: List<MediaCache>) {
        events += "cleanup-start"
        cleanupStarted.complete(Unit)
        allowCleanup.await()
        events += "cleanup-end"
    }
}

private class DeletionOrderingHttpCacheEngine(
    private val deleteStarted: CompletableDeferred<Unit>,
    private val allowDelete: CompletableDeferred<Unit>,
) : TestHttpCacheEngine() {
    var createCount: Int = 0
        private set
    val events = mutableListOf<String>()

    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext,
    ): MediaCache {
        val generation = ++createCount
        events += "create-$generation"
        val delegate = testCache(origin, metadata, "-$generation")
        return object : MediaCache by delegate {
            override suspend fun closeAndDeleteFiles() {
                deleteStarted.complete(Unit)
                allowDelete.await()
                events += "delete-$generation"
                delegate.closeAndDeleteFiles()
            }
        }
    }
}

private object FakeHttpCacheDownloadStateDao : HttpCacheDownloadStateDao {
    override fun getAll(): Flow<List<DownloadState>> = flowOf(emptyList())

    override suspend fun upsert(state: DownloadState) = Unit

    override suspend fun updateStatus(id: DownloadId, status: DownloadStatus) = Unit

    override suspend fun deleteAll() = Unit

    override suspend fun deleteById(id: DownloadId) = Unit

    override suspend fun getById(id: DownloadId): DownloadState? = null
}

private fun testMedia(): Media {
    return createTestDefaultMedia(
        mediaId = "media-1",
        mediaSourceId = "source-1",
        originalUrl = "https://example.com/media-1",
        download = ResourceLocation.HttpStreamingFile("https://example.com/media-1.m3u8"),
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
}

private fun testMetadata(): MediaCacheMetadata {
    return MediaCacheMetadata(
        subjectId = "1",
        episodeId = "1",
        subjectNameCN = "Test Subject",
        subjectNames = listOf("Test Subject"),
        episodeSort = EpisodeSort(1),
        episodeEp = EpisodeSort(1),
        episodeName = "Episode 1",
    )
}
