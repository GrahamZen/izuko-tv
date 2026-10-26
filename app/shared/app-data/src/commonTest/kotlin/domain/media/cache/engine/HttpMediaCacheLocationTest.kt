/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.media.cache.location.TestMediaCacheLocations
import me.him188.ani.app.domain.media.cache.storage.HttpMediaCacheStorage
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.location.UnavailableMediaCache
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadOptions
import me.him188.ani.utils.httpdownloader.DownloadProgress
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.httpdownloader.MediaType
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.inSystem
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlin.time.Duration

/**
 * 网页缓存放在外接盘上 (见 [me.him188.ani.app.domain.media.cache.location.MediaCacheLocations]): 新任务放到选的位置,
 * 盘不在时记录照留、不去恢复下载 (不然会被记成失败).
 */
class HttpMediaCacheLocationTest {
    private val locations = TestMediaCacheLocations(Path("/internal").inSystem)
    private val media = TestMediaList.first().copy(
        mediaId = "source/1",
        kind = MediaSourceKind.WEB,
        download = ResourceLocation.HttpStreamingFile("https://example.com/1.mp4"),
    )
    private val metadata = MediaCacheMetadata(
        subjectId = "1",
        episodeId = "1",
        subjectNames = listOf("Subject"),
        episodeSort = EpisodeSort(1),
        episodeEp = EpisodeSort(1),
        episodeName = "Episode 1",
    )

    @Test
    fun `a new download goes to the location chosen for new caches`() = runTest {
        locations.connect("usb", Path("/usb").inSystem)
        locations.forNewCache = "usb"
        val downloader = LocationFakeDownloader()

        val cache = engine(downloader).createCache(media, metadata, episodeMetadata(), backgroundScope.coroutineContext)

        assertEquals("usb", cache.locationId)
        assertEquals("usb", downloader.states.values.single().location)
    }

    @Test
    fun `a download on a drive that is not connected restores as a placeholder without resuming`() = runTest {
        locations.connect("usb", Path("/usb").inSystem)
        locations.forNewCache = "usb"
        val downloader = LocationFakeDownloader()
        val engine = engine(downloader)
        engine.createCache(media, metadata, episodeMetadata(), backgroundScope.coroutineContext)
        val downloadId = downloader.states.keys.single()
        downloader.resumed.clear()

        locations.disconnect("usb")
        val restored = engine.restore(media, metadata, backgroundScope.coroutineContext)

        assertIs<UnavailableMediaCache>(restored)
        assertEquals("usb", restored.locationId)
        assertEquals(MediaCacheState.FAILED, restored.state.first())
        assertTrue(downloader.resumed.isEmpty(), "a download on a missing drive was resumed")
        // 还在跑的任务停下, 记成暂停 (插回来恢复时接着下)
        assertEquals(listOf(downloadId), downloader.paused)
        assertTrue(downloadId in downloader.states, "the task of a missing drive was dropped")
    }

    @Test
    fun `deleting a placeholder removes the task`() = runTest {
        locations.connect("usb", Path("/usb").inSystem)
        locations.forNewCache = "usb"
        val downloader = LocationFakeDownloader()
        val engine = engine(downloader)
        engine.createCache(media, metadata, episodeMetadata(), backgroundScope.coroutineContext)

        locations.disconnect("usb")
        val restored = assertIs<UnavailableMediaCache>(engine.restore(media, metadata, backgroundScope.coroutineContext))
        restored.closeAndDeleteFiles()

        assertTrue(downloader.states.isEmpty())
    }

    @Test
    fun `a download on a connected drive restores and resumes as usual`() = runTest {
        locations.connect("usb", Path("/usb").inSystem)
        locations.forNewCache = "usb"
        val downloader = LocationFakeDownloader()
        val engine = engine(downloader)
        engine.createCache(media, metadata, episodeMetadata(), backgroundScope.coroutineContext)
        val downloadId = downloader.states.keys.single()
        downloader.resumed.clear()

        val restored = engine.restore(media, metadata, backgroundScope.coroutineContext)

        assertFalse(restored is UnavailableMediaCache)
        assertEquals("usb", restored?.locationId)
        assertEquals(listOf(downloadId), downloader.resumed)
    }

    @Test
    fun `the startup sweep keeps files of records on a drive that were restored as placeholders`() = runTest {
        val root = Path(SystemTemporaryDirectory, "http-location-sweep-${Random.nextLong().toULong()}")
        try {
            val internalDir = Path(root, "internal", "web-m3u").also { SystemFileSystem.createDirectories(it) }
            val usbDir = Path(root, "usb", "web-m3u").also { SystemFileSystem.createDirectories(it) }
            val owned = Path(usbDir, "owned.mp4").also { SystemFileSystem.sink(it).close() }
            val orphan = Path(usbDir, "orphan.mp4").also { SystemFileSystem.sink(it).close() }
            locations.connect("usb", Path(root, "usb").inSystem)
            val downloader = LocationFakeDownloader()
            downloader.states[DownloadId("owned")] = DownloadState(
                DownloadId("owned"), "https://example.com/owned.mp4", "owned.mp4", emptyList(), 0, 0, 0,
                DownloadStatus.COMPLETED, relativeSegmentCacheDir = "segments_owned",
                requestHeaders = emptyMap(), mediaType = MediaType.MP4, location = "usb",
            )

            // 这次恢复时盘还没接上, 恢复结果里没有它 (是占位); 清扫时盘已经接上
            engine(downloader, saveDir = internalDir).deleteUnusedCaches(emptyList())

            assertTrue(SystemFileSystem.exists(owned), "a file of a record on the drive was deleted")
            assertFalse(SystemFileSystem.exists(orphan), "an unused file on the drive was kept")
        } finally {
            SystemFileSystem.deleteRecursively(root)
        }
    }

    @Test
    fun `a finished download moves to another drive and its record follows`() = runTest {
        val root = Path(SystemTemporaryDirectory, "http-location-move-${Random.nextLong().toULong()}")
        try {
            val internalDir = Path(root, "internal", "web-m3u").also { SystemFileSystem.createDirectories(it) }
            val downloader = LocationFakeDownloader()
            val engine = engine(downloader, saveDir = internalDir)
            engine.createCache(media, metadata, episodeMetadata(), backgroundScope.coroutineContext)
            val id = downloader.states.keys.single()
            val state = downloader.states.getValue(id).copy(status = DownloadStatus.COMPLETED)
            downloader.states[id] = state
            val source = Path(internalDir, state.relativeOutputPath)
            SystemFileSystem.sink(source).buffered().use { it.write(ByteArray(4096) { b -> b.toByte() }) }
            val storage = HttpMediaCacheStorage(
                mediaSourceId = "test-storage",
                store = MemoryDataStore(listOf(MediaCacheSave(media, metadata, MediaCacheEngineKey.WebM3u))),
                dao = daoOf(downloader),
                httpEngine = engine,
                displayName = "Test",
                parentCoroutineContext = backgroundScope.coroutineContext,
            )
            storage.restorePersistedCaches()
            locations.connect("usb", Path(root, "usb").inSystem)
            val mover = MediaCacheMover(
                locations, { listOf(storage) }, isPlayingSubject = { false }, scope = backgroundScope, settleDelay = Duration.ZERO,
            )

            val result = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { mover.finished.first() }
            assertTrue(mover.start("usb"))

            assertEquals(MediaCacheMover.Result("usb", moved = 1, failed = 0, skipped = 0, stopReason = null), result.await())
            assertEquals("usb", downloader.states.getValue(id).location)
            val moved = Path(root, "usb", HttpMediaCacheEngine.MEDIA_CACHE_DIR, state.relativeOutputPath)
            assertEquals(4096L, SystemFileSystem.metadataOrNull(moved)?.size)
            assertFalse(SystemFileSystem.exists(source), "the old copy was kept")
            assertEquals("usb", storage.listFlow.value.single().locationId)
            storage.close()
        } finally {
            SystemFileSystem.deleteRecursively(root)
        }
    }

    private fun episodeMetadata() = EpisodeMetadata("Episode 1", EpisodeSort(1), EpisodeSort(1))

    private fun engine(downloader: LocationFakeDownloader, saveDir: Path = Path("/internal/web-m3u")) = HttpMediaCacheEngine(
        downloader,
        saveDir,
        object : MediaResolver by TestUniversalMediaResolver {
            override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> =
                object : MediaDataProvider<UriMediaData> {
                    override val extraFiles = MediaExtraFiles.EMPTY
                    override suspend fun open(scopeForCleanup: CoroutineScope) = UriMediaData("https://example.com/1.mp4")
                }
        },
        "test-storage",
        daoOf(downloader),
        locations = locations,
    )

    /** 记录就是 [downloader] 里的任务 (同真实的: 下载器改了状态即落库) */
    private fun daoOf(downloader: LocationFakeDownloader) = object : HttpCacheDownloadStateDao {
        override fun getAll() = flowOf(downloader.states.values.toList())
        override suspend fun upsert(state: DownloadState) {
            downloader.states[state.downloadId] = state
        }

        override suspend fun updateStatus(id: DownloadId, status: DownloadStatus) = Unit
        override suspend fun deleteAll() {
            downloader.states.clear()
        }

        override suspend fun deleteById(id: DownloadId) {
            downloader.states.remove(id)
        }

        override suspend fun getById(id: DownloadId) = downloader.states[id]
    }
}

/** 内存里的 downloader, 记下任务的位置与被要求恢复 / 暂停的任务. */
private class LocationFakeDownloader : HttpDownloader {
    val states = mutableMapOf<DownloadId, DownloadState>()
    val resumed = mutableListOf<DownloadId>()
    val paused = mutableListOf<DownloadId>()

    override val progressFlow: Flow<DownloadProgress> = flowOf()
    override val downloadStatesFlow: Flow<List<DownloadState>> = flowOf(emptyList())
    override fun getProgressFlow(downloadId: DownloadId): Flow<DownloadProgress> = flowOf()
    override suspend fun init() = Unit
    override suspend fun download(url: String, options: DownloadOptions): DownloadId = error("unused")
    override suspend fun downloadWithId(downloadId: DownloadId, url: String, options: DownloadOptions): DownloadState =
        states.getOrPut(downloadId) {
            DownloadState(
                downloadId, url, "${downloadId.value}.mp4", emptyList(), 0, 0, 0,
                DownloadStatus.DOWNLOADING, relativeSegmentCacheDir = "segments_${downloadId.value}",
                requestHeaders = options.headers, mediaType = MediaType.MP4, location = options.location,
            )
        }

    override suspend fun resume(downloadId: DownloadId): Boolean {
        resumed += downloadId
        return true
    }

    override suspend fun getActiveDownloadIds() =
        states.values.filter { it.status == DownloadStatus.DOWNLOADING }.map { it.downloadId }

    override suspend fun pause(downloadId: DownloadId): Boolean {
        paused += downloadId
        states[downloadId]?.let { states[downloadId] = it.copy(status = DownloadStatus.PAUSED) }
        return true
    }

    override suspend fun pauseAll() = states.keys.toList()
    override suspend fun cancel(downloadId: DownloadId) = true
    override suspend fun cancelAll() = Unit
    override suspend fun remove(downloadId: DownloadId) = states.remove(downloadId) != null
    override suspend fun getState(downloadId: DownloadId) = states[downloadId]
    override suspend fun getAllStates() = states.values.toList()
    override suspend fun relocate(downloadId: DownloadId, location: String?): Boolean {
        val state = states[downloadId] ?: return false
        states[downloadId] = state.copy(location = location)
        return true
    }

    override fun close() = Unit
}
