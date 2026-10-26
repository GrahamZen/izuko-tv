/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest as runCoroutineTest
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheEpisodeEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.location.TestMediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.UnavailableMediaCache
import me.him188.ani.app.domain.media.cache.storage.TorrentMediaCacheStorage
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.torrent.anitorrent.test.TestAnitorrentTorrentDownloader
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * BT 缓存放在外接盘上 (见 [me.him188.ani.app.domain.media.cache.location.MediaCacheLocations]): 新种子放到选的位置,
 * 同一个种子只放一个地方, 盘不在时记录照留.
 */
class TorrentMediaCacheLocationTest : AbstractTorrentMediaCacheEngineTest() {
    private companion object {
        const val PATH_IN_TORRENT = "a.mkv"
        const val FILE_SIZE = 1024L
    }

    private val storages = mutableListOf<TorrentMediaCacheStorage>()
    private val usbRoot: File get() = File(dir, "usb-drive").apply { mkdirs() }
    private val locations by lazy { TestMediaCacheLocations(File(dir, "internal-root").toKtPath().inSystem) }

    /** 存储的协程挂在测试的作用域下, 测试体结束前关掉, 不然 runTest 一直等它们 */
    private fun runTest(testBody: suspend TestScope.() -> Unit) = runCoroutineTest(timeout = 10.seconds) {
        try {
            testBody()
        } finally {
            storages.forEach { it.close() }
            storages.clear()
        }
    }

    @Test
    fun `a new torrent goes to the location chosen for new caches`() = runTest {
        locations.connect("usb", usbRoot.toKtPath().inSystem)
        locations.forNewCache = "usb"
        val storage = createStorage()

        val cache = storage.cache(metadata("1"))
        advanceUntilIdle()

        assertEquals("usb", cache.locationId)
        val row = assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
        assertEquals("usb", row.location)
        val handle = downloader().native.handles.single()
        assertTrue(
            File(handle.saveDir.toString()).absolutePath.startsWith(usbRoot.absolutePath),
            "the torrent was opened in ${handle.saveDir}",
        )
    }

    @Test
    fun `another episode of a torrent stays where the torrent is`() = runTest {
        locations.connect("usb", usbRoot.toKtPath().inSystem)
        locations.forNewCache = "usb"
        val storage = createStorage()
        storage.cache(metadata("1"))

        // 默认位置换回内部存储: 已经在外接盘上的种子不跟着换
        locations.forNewCache = null
        val ep2 = storage.cache(metadata("2"))
        val sameTorrent = storage.cache(metadata("3"), media = testMedia.copy(mediaId = "other-source.2", mediaSourceId = "other-source"))
        advanceUntilIdle()

        assertEquals("usb", ep2.locationId)
        assertEquals("usb", sameTorrent.locationId)
        assertEquals("usb", torrentInfoDatabase.get("other-source.2")?.location)
    }

    @Test
    fun `a torrent on a drive that is not connected restores as a placeholder and keeps its rows`() = runTest {
        createEngine(createTestAnitorrentEngine(backgroundScope.coroutineContext), locations = locations)
        insertRows(location = "usb", completed = true)

        val restored = cacheEngine.restore(testMedia, metadata("1"), coroutineContext)

        assertIs<UnavailableMediaCache>(restored)
        assertTrue(restored.isLocationUnavailable)
        assertEquals("usb", restored.locationId)
        assertEquals(MediaCacheState.FAILED, restored.state.first())
        assertFalse(restored.canPlay.first())
        assertTrue(downloader().native.handles.isEmpty(), "the torrent was opened for a missing drive")
        assertNotNull(torrentInfoDatabase.get(testMedia.mediaId))
        assertNotNull(torrentInfoDatabase.getEpisode(testMedia.mediaId, "1"))
    }

    @Test
    fun `deleting a placeholder removes its rows`() = runTest {
        createEngine(createTestAnitorrentEngine(backgroundScope.coroutineContext), locations = locations)
        insertRows(location = "usb", completed = true)

        val restored = assertIs<UnavailableMediaCache>(cacheEngine.restore(testMedia, metadata("1"), coroutineContext))
        restored.closeAndDeleteFiles()

        assertNull(torrentInfoDatabase.getEpisode(testMedia.mediaId, "1"))
        assertNull(torrentInfoDatabase.get(testMedia.mediaId))
    }

    @Test
    fun `a completed episode on a connected drive restores from that drive`() = runTest {
        locations.connect("usb", usbRoot.toKtPath().inSystem)
        createEngine(createTestAnitorrentEngine(backgroundScope.coroutineContext), locations = locations)
        insertRows(location = "usb", completed = true)
        val file = File(usbRoot, "torrent-dir/$PATH_IN_TORRENT").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(FILE_SIZE.toInt()))
        }

        val restored = assertIs<LocalFileMediaCache>(cacheEngine.restore(testMedia, metadata("1"), coroutineContext))

        assertEquals(file.absolutePath, File(restored.file.absolutePath).absolutePath)
    }

    @Test
    fun `the startup sweep keeps the directories of records on external drives`() = runTest {
        locations.connect("usb", usbRoot.toKtPath().inSystem)
        createEngine(createTestAnitorrentEngine(backgroundScope.coroutineContext), locations = locations)
        val pieces = File(usbRoot, "pieces")
        val orphan = File(pieces, "orphan").apply { mkdirs() }
        // 恢复时盘还没接上的记录 (这次恢复出来的是占位, 不在恢复结果里) 也算有主
        val owned = File(pieces, "owned").apply { mkdirs() }
        torrentInfoDatabase.upsert(
            TorrentCacheInfoEntity(
                mediaId = "owned-media",
                torrentData = byteArrayOf(1),
                relativeDir = File.separator + "pieces" + File.separator + "owned",
                location = "usb",
            ),
        )
        File(dir, "pieces").mkdirs() // 引擎目录存在才会清扫

        cacheEngine.deleteUnusedCaches(emptyList())

        assertFalse(orphan.exists(), "an unused directory on the drive was kept")
        assertTrue(owned.exists(), "a directory of a record on the drive was deleted")
    }

    private suspend fun downloader() = torrentEngine.getDownloader() as TestAnitorrentTorrentDownloader

    private fun TestScope.createStorage(): TorrentMediaCacheStorage {
        val engine = createEngine(onDownloadStarted = { it.onTorrentChecked() }, locations = locations)
        return TorrentMediaCacheStorage(
            CACHE_MEDIA_SOURCE_ID,
            metadataStore,
            engine,
            MutableStateFlow(1.2f),
            "本地",
            coroutineContext,
            flowOf(true),
        ).also { storages += it }
    }

    private suspend fun TorrentMediaCacheStorage.cache(
        metadata: MediaCacheMetadata,
        media: DefaultMedia = testMedia,
    ): TorrentMediaCacheEngine.TorrentMediaCache =
        cache(media, metadata, EpisodeMetadata("Test", null, EpisodeSort(1)), resume = false)
            as TorrentMediaCacheEngine.TorrentMediaCache

    private suspend fun insertRows(location: String?, completed: Boolean) {
        torrentInfoDatabase.upsert(
            TorrentCacheInfoEntity(
                mediaId = testMedia.mediaId,
                torrentData = byteArrayOf(0),
                relativeDir = "torrent-dir",
                location = location,
            ),
        )
        torrentInfoDatabase.upsertEpisode(
            TorrentCacheEpisodeEntity(
                mediaId = testMedia.mediaId,
                episodeId = "1",
                completed = completed,
                pathInTorrent = PATH_IN_TORRENT,
                downloadSize = FILE_SIZE,
            ),
        )
    }

    private fun metadata(episodeId: String) = MediaCacheMetadata(
        subjectId = "1",
        episodeId = episodeId,
        subjectNameCN = "1",
        subjectNames = emptyList(),
        episodeSort = EpisodeSort(episodeId),
        episodeEp = EpisodeSort(episodeId),
        episodeName = "测试剧集",
    )
}
