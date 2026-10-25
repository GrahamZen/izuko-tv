/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheEpisodeEntity
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.TorrentMediaCacheStorage
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.torrent.anitorrent.test.TestAnitorrentTorrentDownloader
import me.him188.ani.app.torrent.anitorrent.test.TestTorrentDescriptor
import me.him188.ani.app.torrent.anitorrent.test.TestTorrentFileInfo
import me.him188.ani.app.torrent.api.TorrentDownloaderConfig
import me.him188.ani.app.torrent.api.TorrentHandleState
import me.him188.ani.app.torrent.api.files.AbstractTorrentFileEntry
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.unwrapCached
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * @see TorrentMediaCacheStorage
 */
class TorrentMediaCacheStorageTest : AbstractTorrentMediaCacheEngineTest() {

    private val storages = mutableListOf<TorrentMediaCacheStorage>()

    private val metadataFlow = metadataStore.data
        .map { list ->
            list
                .filter { it.engine == CacheEngineKey }
                .sortedBy { it.origin.mediaId } // consistent stable order
        }


    private fun cleanup() {
        storages.forEach { it.close() }
        storages.clear()
    }

    private fun runTest(
        context: CoroutineContext = EmptyCoroutineContext,
        timeout: Duration = 5.seconds,
        testBody: suspend TestScope.() -> Unit
    ) = kotlinx.coroutines.test.runTest(context, timeout) {
        try {
            testBody()
        } finally {
            cleanup()
        }
    }

    @Test
    fun `persisted ownership is available before cache restoration`() = runTest {
        metadataStore.updateData {
            listOf(MediaCacheSave(testMedia, mediaCacheMetadata(), CacheEngineKey))
        }
        val disabled = UnsupportedTorrentEngine(createTestAnitorrentEngine(coroutineContext))
        val storage = createStorage(createEngine(engine = disabled))

        assertTrue(storage.listFlow.value.isEmpty())
        assertTrue(storage.hasRecordForMedia(testMedia.mediaId))
        assertFalse(storage.hasRecordForMedia("unrelated-media"))
    }

    @Test
    fun `resumeByUser turns an auto cache into an explicit one`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(autoCached = true), resume = true)

        cache.resumeByUser()
        advanceUntilIdle()
        assertEquals(false, cache.metadata.autoCached)
        assertEquals(FilePriority.NORMAL, cache.requestingPriority())

        assertEquals(
            false,
            metadataFlow.first().single { it.origin.mediaId == cache.origin.mediaId }.metadata.autoCached,
        )
    }

    @Test
    fun `downloading a season promotes the record left by playback instead of adding one`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )

        // 播放第 1 集时跟随播放建立的记录.
        val played = storage.cache(testMedia, mediaCacheMetadata(autoCached = true, episodeId = "1"), resume = true)

        // 批量下载整季: 每集一次请求, 共用同一个合集 media, autoCached 为 false.
        val ep1 = storage.cache(testMedia, mediaCacheMetadata(autoCached = false, episodeId = "1"), resume = true)
        val ep2 = storage.cache(testMedia, mediaCacheMetadata(autoCached = false, episodeId = "2"), resume = true)
        advanceUntilIdle()

        assertSame(played, ep1)
        assertEquals(listOf(ep1, ep2), storage.listFlow.first())

        val saves = metadataFlow.first()
        assertEquals(listOf("1", "2"), saves.map { it.metadata.episodeId }.sorted())
        assertEquals(false, saves.single { it.metadata.episodeId == "1" }.metadata.autoCached)
        assertEquals(false, ep1.metadata.autoCached)
        assertEquals(FilePriority.NORMAL, ep1.requestingPriority())
    }

    @Test
    fun `deleting one record of a pack keeps the row for the others`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )

        val ep1 = storage.cache(testMedia, mediaCacheMetadata(episodeId = "1"), resume = false)
        val ep2 = storage.cache(testMedia, mediaCacheMetadata(episodeId = "2"), resume = false)
        assertEquals(2, storage.listFlow.first().size)

        assertEquals(true, storage.delete(ep1))
        assertEquals(listOf(ep2), storage.listFlow.first())
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))

        assertEquals(true, storage.delete(ep2))
        assertNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    @Test
    fun `deleting one episode of a pack discards its data while another episode still downloads`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val downloader = torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader
        downloader.native.descriptor = {
            TestTorrentDescriptor("pack", numPieces = 1000, lastPieceSize = 1024, pieceLength = 1024).apply {
                files.add(TestTorrentFileInfo("[Test] Pack - 01 [1080P].mkv", "[Test] Pack - 01 [1080P].mkv", 512_000))
                files.add(TestTorrentFileInfo("[Test] Pack - 02 [1080P].mkv", "[Test] Pack - 02 [1080P].mkv", 512_000))
            }
        }
        fun metadata(episode: Int) = mediaCacheMetadata(episodeId = episode.toString()).copy(
            episodeSort = EpisodeSort(episode),
            episodeEp = EpisodeSort(episode),
        )

        val ep1 = storage.cache(testMedia, metadata(1), resume = true)
        val ep2 = storage.cache(testMedia, metadata(2), resume = true)
        advanceUntilIdle()
        recordPath("2", "[Test] Pack - 02 [1080P].mkv")
        val handle = downloader.native.handles.single()

        assertEquals(true, storage.delete(ep1))
        awaitReal { handle.discardedFiles.isNotEmpty() }
        assertEquals(listOf(0), handle.discardedFiles, "only the deleted episode is discarded")
        assertEquals(FilePriority.NORMAL, (ep2 as TorrentMediaCacheEngine.TorrentMediaCache).requestingPriority())

        // the last one goes with the whole torrent
        assertEquals(true, storage.delete(ep2))
        awaitReal(300.milliseconds) { false }
        assertEquals(listOf(0), handle.discardedFiles)
    }

    @Test
    fun `deleting an episode keeps the file that another record still uses`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val downloader = torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader
        // 单文件的种子: 两集选中的是同一个文件, 像合集里特别篇选中了同号正片那样
        val ep1 = storage.cache(testMedia, mediaCacheMetadata(episodeId = "1"), resume = true)
        val ep2 = storage.cache(testMedia, mediaCacheMetadata(episodeId = "2"), resume = true)
        advanceUntilIdle()
        recordPath("2", "test.mkv")
        val handle = downloader.native.handles.single()

        assertEquals(true, storage.delete(ep1))
        awaitReal(300.milliseconds) { false }
        assertEquals(emptyList(), handle.discardedFiles)
        assertEquals(FilePriority.NORMAL, (ep2 as TorrentMediaCacheEngine.TorrentMediaCache).requestingPriority())
    }

    @Test
    fun `startup discards the data of pack files that lost their record`() = runTest {
        val packDescriptor = {
            TestTorrentDescriptor("pack", numPieces = 1000, lastPieceSize = 1024, pieceLength = 1024).apply {
                files.add(TestTorrentFileInfo("[Test] Pack - 01 [1080P].mkv", "[Test] Pack - 01 [1080P].mkv", 512_000))
                files.add(TestTorrentFileInfo("[Test] Pack - 02 [1080P].mkv", "[Test] Pack - 02 [1080P].mkv", 512_000))
            }
        }
        fun metadata(episode: Int) = mediaCacheMetadata(episodeId = episode.toString()).copy(
            episodeSort = EpisodeSort(episode),
            episodeEp = EpisodeSort(episode),
        )
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        (torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader).native.descriptor = packDescriptor
        storage.cache(testMedia, metadata(1), resume = true)
        storage.cache(testMedia, metadata(2), resume = true)
        advanceUntilIdle()
        recordPath("1", "[Test] Pack - 01 [1080P].mkv")
        // 旧版本删掉第二集: 记录没了, 数据还在种子里
        metadataStore.updateData { saves -> saves.filterNot { it.metadata.episodeId == "2" } }
        torrentInfoDatabase.deleteEpisode(testMedia.mediaId, "2")
        storage.close()

        val restarted = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val downloader = torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader
        downloader.native.descriptor = packDescriptor
        restarted.restorePersistedCaches()
        restarted.listFlow.first { it.size == 1 }
        val handle = downloader.native.handles.single()
        awaitReal { handle.discardedFiles.isNotEmpty() }
        assertEquals(listOf(1), handle.discardedFiles)
    }

    @Test
    fun `a paused download does not move its download window when pieces finish`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val downloader = torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader
        // 分块够大, 文件头尾之外还有普通分块, 才有下载窗口
        downloader.native.descriptor = {
            TestTorrentDescriptor("big", numPieces = 100, lastPieceSize = 1L shl 20, pieceLength = 1L shl 20).apply {
                files.add(TestTorrentFileInfo("big.mkv", "big.mkv", 100L shl 20))
            }
        }
        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = true)
        advanceUntilIdle()
        val handle = downloader.native.handles.single()
        // 窗口里的普通分块: 截止时间排在文件头尾的分块之后
        val window = handle.pieceDeadlines.indices.filter { (handle.pieceDeadlines[it] ?: Int.MIN_VALUE) > -5000 }
        assertTrue(window.size >= 2, "no download window: ${handle.pieceDeadlines}")

        cache.pause()
        advanceUntilIdle()
        handle.clearPieceDeadlines()
        downloader.dispatchToFirstSession { it.onPieceFinished(window[0]) }
        advanceUntilIdle()
        assertTrue(handle.pieceDeadlines.all { it == null }, "the paused download asked for more pieces")

        cache.resume()
        advanceUntilIdle()
        handle.clearPieceDeadlines()
        downloader.dispatchToFirstSession { it.onPieceFinished(window[1]) }
        advanceUntilIdle()
        assertTrue(handle.pieceDeadlines.any { it != null }, "a running download moves its window")
    }

    @Test
    fun `an episode cached again after its data was discarded moves its download window`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val downloader = torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader
        downloader.native.descriptor = {
            TestTorrentDescriptor("pack", numPieces = 60, lastPieceSize = 1L shl 20, pieceLength = 1L shl 20).apply {
                files.add(TestTorrentFileInfo("[Test] Pack - 01 [1080P].mkv", "[Test] Pack - 01 [1080P].mkv", 30L shl 20))
                files.add(TestTorrentFileInfo("[Test] Pack - 02 [1080P].mkv", "[Test] Pack - 02 [1080P].mkv", 30L shl 20))
            }
        }
        fun metadata(episode: Int) = mediaCacheMetadata(episodeId = episode.toString()).copy(
            episodeSort = EpisodeSort(episode),
            episodeEp = EpisodeSort(episode),
        )
        val ep1 = storage.cache(testMedia, metadata(1), resume = true)
        storage.cache(testMedia, metadata(2), resume = true)
        advanceUntilIdle()
        recordPath("2", "[Test] Pack - 02 [1080P].mkv")
        val handle = downloader.native.handles.single()
        // 第一集在这次会话里下完: 它的下载窗口走到头
        for (piece in 0 until 30) downloader.dispatchToFirstSession { it.onPieceFinished(piece) }
        advanceUntilIdle()

        assertEquals(true, storage.delete(ep1))
        awaitReal { handle.discardedFiles.isNotEmpty() }
        assertEquals(listOf(0), handle.discardedFiles)

        storage.cache(testMedia, metadata(1), resume = true)
        advanceUntilIdle()
        val window = (0 until 30).filter { (handle.pieceDeadlines[it] ?: Int.MIN_VALUE) > -5000 }
        assertTrue(window.size >= 2, "no download window: ${handle.pieceDeadlines}")
        handle.clearPieceDeadlines()
        downloader.dispatchToFirstSession { it.onPieceFinished(window[0]) }
        advanceUntilIdle()
        assertTrue((0 until 30).any { handle.pieceDeadlines[it] != null }, "the window stays where the old download ended")
    }

    @Test
    fun `episodes of a pack cached together download one at a time in episode order`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val downloader = torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader
        // 种子里的文件顺序与集数不同: 分块 0-29 是第二集, 30-59 第一集, 60-89 第三集
        downloader.native.descriptor = {
            TestTorrentDescriptor("pack", numPieces = 90, lastPieceSize = 1L shl 20, pieceLength = 1L shl 20).apply {
                for (episode in listOf("02", "01", "03")) {
                    files.add(TestTorrentFileInfo("[Test] Pack - $episode [1080P].mkv", "[Test] Pack - $episode [1080P].mkv", 30L shl 20))
                }
            }
        }
        fun metadata(episode: Int) = mediaCacheMetadata(episodeId = episode.toString()).copy(
            episodeSort = EpisodeSort(episode),
            episodeEp = EpisodeSort(episode),
        )
        val ep2 = storage.cache(testMedia, metadata(2), resume = true)
        storage.cache(testMedia, metadata(3), resume = true)
        storage.cache(testMedia, metadata(1), resume = true)
        advanceUntilIdle()
        val handle = downloader.native.handles.single()
        val ep2Pieces = 0 until 30
        val ep1Pieces = 30 until 60
        val ep3Pieces = 60 until 90

        assertEquals(mapOf(0 to FilePriority.LOW, 1 to FilePriority.NORMAL, 2 to FilePriority.LOW), handle.filePriorities)
        assertTrue(ep3Pieces.all { handle.pieceDeadlines[it] == null }, "a waiting episode asked for pieces")
        handle.clearPieceDeadlines()
        downloader.dispatchToFirstSession { it.onPieceFinished(5) }
        advanceUntilIdle()
        assertTrue(ep2Pieces.all { handle.pieceDeadlines[it] == null }, "a waiting episode moved its window")

        // 第一集下完, 第二集接上并从头排下载窗口
        for (piece in ep1Pieces) downloader.dispatchToFirstSession { it.onPieceFinished(piece) }
        advanceUntilIdle()
        assertEquals(FilePriority.NORMAL, handle.filePriorities[0])
        assertEquals(FilePriority.LOW, handle.filePriorities[2])
        assertTrue(ep2Pieces.any { handle.pieceDeadlines[it] != null }, "the next episode did not start its window")

        // 暂停的不占位置
        ep2.pause()
        advanceUntilIdle()
        assertEquals(FilePriority.IGNORE, handle.filePriorities[0])
        assertEquals(FilePriority.NORMAL, handle.filePriorities[2])
        assertTrue(ep3Pieces.any { handle.pieceDeadlines[it] != null }, "the last episode did not start its window")
    }

    @Test
    fun `trackers added to the config also reach downloads in progress`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        storage.cache(testMedia, mediaCacheMetadata(), resume = true)
        advanceUntilIdle()
        val downloader = torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader
        val handle = downloader.native.handles.single()
        val added = handle.trackers.toList()
        val subscribed = "udp://subscribed.example:6969/announce"

        downloader.applyConfig(TorrentDownloaderConfig(extraTrackers = listOf(subscribed)))
        assertEquals(added + subscribed, handle.trackers)

        // 没变的配置 (比如只改了限速) 不再重复加
        downloader.applyConfig(TorrentDownloaderConfig(extraTrackers = listOf(subscribed)))
        assertEquals(added + subscribed, handle.trackers)
    }

    @Test
    fun `a paused download stays paused after a restart`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = true)
        advanceUntilIdle()
        cache.pause()
        advanceUntilIdle()
        assertEquals(true, metadataStore.data.first().single().metadata.paused)
        storage.close()

        val restarted = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        // 先收下服务的连接状态: 启动恢复之后才收到的话算作重连, 会再刷新一遍、换掉这里拿到的缓存对象
        advanceUntilIdle()
        restarted.restorePersistedCaches()
        val restored = restarted.listFlow.first { it.size == 1 }.single() as TorrentMediaCacheEngine.TorrentMediaCache
        advanceUntilIdle()
        assertEquals(MediaCacheState.PAUSED, restored.state.first())
        assertEquals(FilePriority.IGNORE, restored.requestingPriority())

        restored.resume()
        advanceUntilIdle()
        assertEquals(false, metadataStore.data.first().single().metadata.paused)
        assertEquals(FilePriority.NORMAL, restored.requestingPriority())
    }

    @Test
    fun `a completed record does not make its pack siblings completed`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )
        val ep1 = storage.cache(testMedia, mediaCacheMetadata(episodeId = "1"), resume = false)
        storage.cache(testMedia, mediaCacheMetadata(episodeId = "2"), resume = false)

        val path = assertNotNull(ep1.fileHandle.entry.first()).pathInTorrent
        val row = assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))

        File(dir, row.relativeDir).resolve(path).apply {
            parentFile.mkdirs()
            writeText("x")
        }

        torrentInfoDatabase.upsertEpisode(
            TorrentCacheEpisodeEntity(testMedia.mediaId, episodeId = "1", completed = true, pathInTorrent = path),
        )
        storage.close()

        val restored = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        restored.restorePersistedCaches()

        val byEpisode = restored.listFlow.first { it.size == 2 }.associateBy { it.metadata.episodeId }
        assertIs<LocalFileMediaCache>(byEpisode.getValue("1"))
        assertIs<TorrentMediaCacheEngine.TorrentMediaCache>(byEpisode.getValue("2"))
    }

    @Test
    fun `a record from before the upgrade takes completion from the torrent row`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )
        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        val path = assertNotNull(cache.fileHandle.entry.first()).pathInTorrent
        val row = assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))

        // Before this version only the torrent row knew, and there was no episode record at all.
        torrentInfoDatabase.upsert(row.copy(completed = true, pathInTorrent = path))
        torrentInfoDatabase.deleteEpisode(testMedia.mediaId, "1")
        File(dir, row.relativeDir).resolve(path).apply {
            parentFile.mkdirs()
            writeText("x")
        }
        storage.close()

        val restored = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        restored.restorePersistedCaches()

        assertIs<LocalFileMediaCache>(restored.listFlow.first { it.isNotEmpty() }.single())

        val record = assertNotNull(torrentInfoDatabase.getEpisode(testMedia.mediaId, "1"))
        assertEquals(true, record.completed)
        assertEquals(path, record.pathInTorrent)
    }

    @Test
    fun `a completed record restores while the engine is disabled`() = runTest {
        val storage = createStorage(
            createEngine(onDownloadStarted = { it.onTorrentChecked() }),
        )
        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        val path = assertNotNull(cache.fileHandle.entry.first()).pathInTorrent
        val row = assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
        File(dir, row.relativeDir).resolve(path).apply {
            parentFile.mkdirs()
            writeText("x")
        }
        torrentInfoDatabase.upsertEpisode(
            TorrentCacheEpisodeEntity(testMedia.mediaId, episodeId = "1", completed = true, pathInTorrent = path),
        )
        storage.close()

        val disabled = UnsupportedTorrentEngine(createTestAnitorrentEngine(coroutineContext))
        val restored = createStorage(createEngine(engine = disabled))
        restored.restorePersistedCaches()

        assertIs<LocalFileMediaCache>(restored.listFlow.first { it.isNotEmpty() }.single())
    }

    private class UnsupportedTorrentEngine(
        private val delegate: TorrentEngine,
    ) : TorrentEngine by delegate {
        override val isSupported: Boolean get() = false
    }

    @Test
    fun `enabling an engine restores missing downloads and retains live handles`() = runTest {
        val original = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        original.cache(testMedia, mediaCacheMetadata(), resume = false)
        original.close()

        val available = MutableStateFlow(false)
        val delegate = createTestAnitorrentEngine(coroutineContext)
        val switchable = object : TorrentEngine by delegate {
            override val isSupported: Boolean get() = available.value
        }
        val restored = createStorage(
            createEngine(engine = switchable, onDownloadStarted = { it.onTorrentChecked() }),
            engineAvailability = available,
        )
        restored.restorePersistedCaches()
        advanceUntilIdle()
        assertTrue(restored.listFlow.value.isEmpty())
        assertTrue(restored.hasRecordForMedia(testMedia.mediaId))

        available.value = true
        val cache = restored.listFlow.first { it.isNotEmpty() }.single()
        assertEquals(testMedia.mediaId, cache.origin.mediaId)
        available.value = false
        runCurrent()
        available.value = true
        runCurrent()
        assertSame(cache, restored.listFlow.value.single())
    }

    @Test
    fun `a refresh closes the downloads it replaces so pausing the listed one stops the file`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val before = storage.cache(testMedia, mediaCacheMetadata(), resume = true)
        advanceUntilIdle()
        assertEquals(FilePriority.NORMAL, before.requestingPriority())

        storage.refreshCache()
        advanceUntilIdle()
        val after = storage.listFlow.value.single() as TorrentMediaCacheEngine.TorrentMediaCache
        assertNotSame(before, after)

        after.pause()
        advanceUntilIdle()
        assertEquals(FilePriority.IGNORE, after.requestingPriority(), "the replaced record still requests the file")
    }

    @Test
    fun `a refresh keeps a paused download paused`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val before = storage.cache(testMedia, mediaCacheMetadata(), resume = true)
        advanceUntilIdle()
        before.pause()
        advanceUntilIdle()

        storage.refreshCache()
        advanceUntilIdle()
        val after = storage.listFlow.value.single() as TorrentMediaCacheEngine.TorrentMediaCache
        assertNotSame(before, after)
        assertEquals(MediaCacheState.PAUSED, after.state.first())
        assertEquals(FilePriority.IGNORE, after.requestingPriority(), "the restored record downloads again")
    }

    /**
     * 等 [condition] 成立, 最多 [timeout] 真实时间: 丢数据在真实的 IO 线程上做, 前后还要回到测试调度器上,
     * 所以在 IO 线程上等, 测试调度器照常跑.
     */
    private suspend fun awaitReal(timeout: Duration = 3.seconds, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!condition() && System.nanoTime() < deadline) withContext(Dispatchers.IO) { Thread.sleep(10) }
    }

    /** 统计订阅要等真实时间里的统计才把选中的文件写进记录, 测试里直接写上 */
    private suspend fun recordPath(episodeId: String, pathInTorrent: String) {
        val row = assertNotNull(torrentInfoDatabase.getEpisode(testMedia.mediaId, episodeId))
        torrentInfoDatabase.upsertEpisode(row.copy(pathInTorrent = pathInTorrent))
    }

    private suspend fun TorrentMediaCacheEngine.TorrentMediaCache.requestingPriority(): FilePriority {
        val entry = assertNotNull(fileHandle.entry.first())
        return (entry as AbstractTorrentFileEntry).requestingPriority
    }

    private fun TestScope.createStorage(
        engine: TorrentMediaCacheEngine = createEngine(),
        engineAvailability: Flow<Boolean> = flowOf(true),
    ): TorrentMediaCacheStorage {
        return TorrentMediaCacheStorage(
            CACHE_MEDIA_SOURCE_ID,
            metadataStore,
            engine.also { cacheEngine = it },
            MutableStateFlow(1.2f),
            "本地",
            this.coroutineContext,
            engineAvailability,
        ).also {
            storages.add(it)
        }
    }

    private fun mediaCacheMetadata(autoCached: Boolean = false, episodeId: String = "1") = MediaCacheMetadata(
        subjectId = "1",
        episodeId = episodeId,
        subjectNameCN = "1",
        subjectNames = emptyList(),
        episodeSort = EpisodeSort("02"),
        episodeEp = EpisodeSort("02"),
        episodeName = "测试剧集",
        autoCached = autoCached,
    )

    ///////////////////////////////////////////////////////////////////////////
    // downloader status
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `downloader status reports the engine state and connected peers`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertEquals(
            DownloaderStatus.Torrent(
                serviceConnected = true,
                startup = DownloaderStatus.TorrentStartup.STARTED,
                state = TorrentHandleState.DOWNLOADING,
                connectedPeers = 0,
                seeds = 0,
            ),
            cache.downloaderStatus.first(),
        )
    }

    @Test
    fun `downloader status reports startup timeout when torrent info never arrives`() = runTest {
        // 引擎从不报告种子信息, 启动阶段在虚拟时间中超时.
        val storage = createStorage(createEngine())
        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertEquals(
            DownloaderStatus.Torrent(
                serviceConnected = true,
                startup = DownloaderStatus.TorrentStartup.TIMED_OUT,
                state = null,
                connectedPeers = 0,
                seeds = 0,
            ),
            cache.downloaderStatus.first(),
        )
        assertEquals(MediaCacheState.FAILED, cache.state.first())
    }

    ///////////////////////////////////////////////////////////////////////////
    // simple create, restore, find
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `create cache then get from listFlow`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get("dmhy.2"))
    }

    private suspend fun TorrentMediaCacheStorage.cache(
        media: DefaultMedia,
        metadata: MediaCacheMetadata,
        resume: Boolean
    ) = cache(
        media,
        metadata,
        EpisodeMetadata("Test", null, EpisodeSort(1)), // doesn't matter, as we only test BT engine.
        resume,
    )

    @Test
    fun `create cache saves metadata`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(1, size)
            assertEquals(cache.origin.mediaId, first().origin.mediaId)
        }

        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    @Test
    fun `create same cache twice`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)
        assertSame(cache, storage.listFlow.first().single())
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
        assertSame(cache, storage.cache(testMedia, mediaCacheMetadata(), resume = false))
        assertSame(cache, storage.listFlow.first().single())
        assertEquals(1, torrentInfoDatabase.getAll().first().size)
    }

    @Test
    fun `concurrent cache calls reuse one persisted record`() = runTest {
        val storage = createStorage(createEngine(onDownloadStarted = { it.onTorrentChecked() }))
        val caches = List(6) {
            async { storage.cache(testMedia, mediaCacheMetadata(), resume = false) }
        }.awaitAll()
        caches.forEach { assertSame(caches.first(), it) }
        assertSame(caches.first(), storage.listFlow.first().single())
        assertEquals(1, metadataFlow.first().size)
        assertEquals(1, torrentInfoDatabase.getAll().first().size)
    }

    @Test
    fun `create and delete`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(1, size)
            assertEquals(cache.origin.mediaId, first().origin.mediaId)
        }
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))

        assertNotNull(cache.fileHandle.state.first()).run {
            assertNotNull(handle)
            assertNotNull(entry)
        }

        assertEquals(cache, storage.listFlow.first().single())
        assertEquals(true, storage.delete(cache))

        metadataFlow.first().filter { it.origin.mediaId == cache.origin.mediaId }.run {
            assertEquals(0, size)
        }
        assertEquals(null, storage.listFlow.first().firstOrNull())
        assertNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    ///////////////////////////////////////////////////////////////////////////
    // restore
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `restorePersistedCaches - nothing`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )
        storage.restorePersistedCaches()
        assertEquals(0, storage.listFlow.first().size)
    }

    @Test
    fun `restorePersistedCaches restores cache when requested immediately after construction`() = runTest {
        val originalStorage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )
        val metadata = mediaCacheMetadata()
        val originalCache = originalStorage.cache(testMedia, metadata, resume = false)
        originalStorage.close()

        val restoredStorage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        restoredStorage.restorePersistedCaches()

        val restoredCache = restoredStorage.listFlow.first { it.isNotEmpty() }.single()
        assertEquals(originalCache.origin.mediaId, restoredCache.origin.mediaId)
        assertEquals(metadata, restoredCache.metadata)
    }

    ///////////////////////////////////////////////////////////////////////////
    // cacheMediaSource
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `query cacheMediaSource`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val metadata = mediaCacheMetadata()
        val cache = storage.cache(testMedia, metadata, resume = false)

        assertEquals(
            cache.getCachedMedia().unwrapCached(),
            storage.cacheMediaSource.fetch(
                MediaFetchRequest(
                    subjectId = "1",
                    episodeId = "1",
                    subjectNames = metadata.subjectNames,
                    episodeSort = metadata.episodeSort,
                    episodeName = metadata.episodeName,
                ),
            ).results.toList().single().media.unwrapCached(),
        )
        assertNotNull(torrentInfoDatabase.get(cache.origin.mediaId))
    }

    /**
     * 合集资源里每一集的 [CachedMedia] 共用同一个 `origin.mediaId`, 只有 [CachedMedia.cacheEpisodeId]
     * 能把它们分开 —— `MediaSelectorFilterSortAlgorithm` 正是靠它判断"这条缓存属不属于当前这一集",
     * 为空时那道过滤会被整个跳过, 于是播任意一集都可能被交出另一集的文件; 碰上残缺的那一份就是
     * `IO_READ_POSITION_OUT_OF_RANGE`, 表现为"缓存显示已完成却播不了", 然后自动切到在线源。
     *
     * 2026-09-20 的上游 rebase 正好把 `.forRecord(cache.metadata)` 这个调用吃掉过 (上游在这一行加它,
     * fork 在同一行包了 try/catch, 合并取了 fork 侧), 所以专门钉在这里。
     * 注意不能用 `unwrapCached()` 比较 —— 那会退回 origin, 两边都一样, 正好漏掉这个回归。
     */
    @Test
    fun `cacheMediaSource narrows each record to its own episode`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val metadata = mediaCacheMetadata()
        storage.cache(testMedia, metadata, resume = false)

        val media = storage.cacheMediaSource.fetch(
            MediaFetchRequest(
                subjectId = "1",
                episodeId = "1",
                subjectNames = metadata.subjectNames,
                episodeSort = metadata.episodeSort,
                episodeName = metadata.episodeName,
            ),
        ).results.toList().single().media

        val cached = assertIs<CachedMedia>(media)
        assertEquals(metadata.episodeId, cached.cacheEpisodeId)
        assertEquals(EpisodeRange.single(metadata.episodeSort), cached.episodeRange)
    }

    ///////////////////////////////////////////////////////////////////////////
    // metadata
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `cached media id`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val cache = storage.cache(testMedia, mediaCacheMetadata(), resume = false)

        assertNotNull(cache.fileHandle.state.first()).run {
            assertNotNull(handle)
        }

        val cachedMedia = cache.getCachedMedia()
        assertEquals("$CACHE_MEDIA_SOURCE_ID:${testMedia.mediaId}", cachedMedia.mediaId)
        assertEquals(CACHE_MEDIA_SOURCE_ID, cachedMedia.mediaSourceId)
        assertEquals(testMedia, cachedMedia.origin)
    }

    @Test
    fun `create two caches with same episode id`() = runTest {
        val storage = createStorage(
            createEngine(
                onDownloadStarted = {
                    it.onTorrentChecked()
                },
            ),
        )

        val metadata = mediaCacheMetadata()
        val testMedia2 = createTestDefaultMedia(
            mediaId = "dmhy.3",
            mediaSourceId = "dmhy",
            originalTitle = "夜晚的水母不会游泳 02 测试剧集2",
            download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:2"),
            originalUrl = "https://example.com/2",
            publishedTime = 1724493292759,
            episodeRange = EpisodeRange.single(EpisodeSort(2)),
            properties = createTestMediaProperties(),
            kind = MediaSourceKind.BitTorrent,
            location = MediaSourceLocation.Online,
        )

        storage.cache(testMedia, metadata, resume = false)
        storage.cache(testMedia2, metadata, resume = false)

        assertEquals(2, storage.listFlow.first().size)
        assertEquals(2, torrentInfoDatabase.getAll().first().size)
    }
}
