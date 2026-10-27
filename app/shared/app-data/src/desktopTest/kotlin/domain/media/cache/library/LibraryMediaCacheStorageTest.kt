/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as runCoroutineTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.app.domain.media.cache.location.TestMediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.UnavailableMediaCache
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 片库 ([LibraryMediaCacheStorage]): 片库文件夹里按约定命名的视频当成下完的缓存列出来; 文件夹变了 (删了、拷进来) 下次扫描跟着变;
 * 盘没接上、「所有文件访问」被关掉时给占位不删记录; 片库关着时不扫公开片库, 已经认得的照常列出, 留在文件夹里的 (forgetPublic) 不再列出.
 */
class LibraryMediaCacheStorageTest {
    @TempDir
    lateinit var dir: File

    private val internalRoot: File get() = File(dir, "internal").apply { mkdirs() }
    private val usbRoot: File get() = File(dir, "usb").apply { mkdirs() }
    private val locations by lazy { TestMediaCacheLocations(internalRoot.toKtPath().inSystem) }
    private val datastore = MemoryDataStore(emptyList<MediaCacheSave>())
    private val access = FakeAccess()
    private var enabled = true
    private val storages = mutableListOf<LibraryMediaCacheStorage>()

    private class FakeAccess : MediaLibraryAccess {
        var granted = true
        override val isSupported: Boolean get() = true
        override fun isGranted(): Boolean = granted
        override fun volumeRootOf(location: MediaCacheLocation): SystemPath = location.root
    }

    private fun runTest(testBody: suspend TestScope.() -> Unit) = runCoroutineTest(timeout = 30.seconds) {
        try {
            testBody()
        } finally {
            storages.forEach { it.close() }
            storages.clear()
        }
    }

    private fun TestScope.createStorage(indexFile: File? = File(dir, "index.json")): LibraryMediaCacheStorage =
        createLibraryMediaCacheStorage(
            mediaSourceId = MediaDownloadManager.LOCAL_FS_MEDIA_SOURCE_ID,
            datastore = datastore,
            indexFile = indexFile?.toKtPath()?.inSystem,
            locations = locations,
            access = access,
            enabled = { enabled },
            parentCoroutineContext = backgroundScope.coroutineContext,
        ).also { storages += it }

    /** 片库里放一个视频: `<root>/Izuko/<番> [bgm-<条目>]/<番> - <集> [bgmep-<剧集>].mkv` */
    private fun putVideo(root: File, subjectId: String = "1", episodeId: String = "11", sort: String = "01", name: String = "番"): File =
        File(root, "Izuko/$name [bgm-$subjectId]/$name - $sort [bgmep-$episodeId].mkv").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(64))
        }

    private suspend fun LibraryMediaCacheStorage.caches(): List<MediaCache> = listFlow.first()

    @Test
    fun `videos named by the layout are listed as completed caches`() = runTest {
        val video = putVideo(internalRoot)
        val storage = createStorage()
        storage.restorePersistedCaches()

        val cache = assertIs<LocalFileMediaCache>(storage.caches().single())
        assertEquals("1", cache.metadata.subjectId)
        assertEquals("11", cache.metadata.episodeId)
        assertEquals(EpisodeSort("1"), cache.metadata.episodeSort)
        assertEquals("番", cache.metadata.subjectNameCN)
        assertEquals(video.absolutePath, File(cache.file.absolutePath).absolutePath)
        assertEquals(LibraryEngineKey, datastore.data.first().single().engine, "the fast lane reads this record")
    }

    @Test
    fun `the sidecar supplies the original media and metadata`() = runTest {
        val video = putVideo(internalRoot)
        val metadata = MediaCacheMetadata(
            subjectId = "1", episodeId = "11", subjectNameCN = "番", subjectNames = listOf("番", "Ban"),
            episodeSort = EpisodeSort("1"), episodeName = "第一集",
        )
        writeLibrarySidecar(video.toKtPath().inSystem, MediaCacheSave(originMedia(), metadata, LibraryEngineKey))
        val storage = createStorage()
        storage.restorePersistedCaches()

        val cache = storage.caches().single()
        assertEquals("dmhy.1", cache.origin.mediaId)
        assertEquals("字幕组", cache.origin.properties.alliance)
        assertEquals("第一集", cache.metadata.episodeName)
        assertEquals(MediaCache.calculateCacheId("dmhy.1", metadata), cache.cacheId, "the same cache id as before it was published")
    }

    @Test
    fun `a sidecar for another episode is not trusted`() = runTest {
        val video = putVideo(internalRoot)
        val other = MediaCacheMetadata(
            subjectId = "1", episodeId = "99", subjectNames = listOf("番"), episodeSort = EpisodeSort("9"), episodeName = "",
        )
        writeLibrarySidecar(video.toKtPath().inSystem, MediaCacheSave(originMedia(), other, LibraryEngineKey))
        val storage = createStorage()
        storage.restorePersistedCaches()

        assertEquals("11", storage.caches().single().metadata.episodeId)
    }

    @Test
    fun `deleting removes the video with its sidecar and the empty folder`() = runTest {
        val video = putVideo(internalRoot)
        writeLibrarySidecar(video.toKtPath().inSystem, MediaCacheSave(originMedia(), metadataOf(video), LibraryEngineKey))
        val storage = createStorage()
        storage.restorePersistedCaches()

        assertTrue(storage.delete(storage.caches().single()))

        assertFalse(video.exists())
        assertFalse(File(video.path + MediaLibraryLayout.SIDECAR_SUFFIX).exists())
        assertFalse(video.parentFile.exists(), "the empty subject folder is removed too")
        assertTrue(storage.caches().isEmpty())
        assertTrue(datastore.data.first().isEmpty())
    }

    @Test
    fun `videos changed outside the app follow on the next scan`() = runTest {
        val video = putVideo(internalRoot)
        val storage = createStorage()
        storage.restorePersistedCaches()
        assertEquals(1, storage.caches().size)

        video.delete()
        putVideo(internalRoot, subjectId = "2", episodeId = "22", name = "别的番")
        storage.refreshCache()

        assertEquals(listOf("22"), storage.caches().map { it.metadata.episodeId })
        assertEquals(listOf("22"), datastore.data.first().map { it.metadata.episodeId })
    }

    @Test
    fun `an unplugged drive keeps its library entries as unavailable`() = runTest {
        locations.connect("usb", usbRoot.toKtPath().inSystem)
        putVideo(usbRoot)
        val storage = createStorage()
        storage.restorePersistedCaches()
        assertEquals("usb", assertIs<LocalFileMediaCache>(storage.caches().single()).locationId)

        locations.disconnect("usb")
        storage.refreshCache()
        val placeholder = assertIs<UnavailableMediaCache>(storage.caches().single())
        assertEquals("usb", placeholder.location)
        assertFalse(placeholder.accessLost)
        assertEquals(1, datastore.data.first().size, "the record is kept while the drive is away")

        locations.connect("usb", usbRoot.toKtPath().inSystem)
        storage.refreshCache()
        assertIs<LocalFileMediaCache>(storage.caches().single())
    }

    @Test
    fun `an unplugged drive is remembered across restarts`() = runTest {
        locations.connect("usb", usbRoot.toKtPath().inSystem)
        putVideo(usbRoot)
        createStorage().restorePersistedCaches()
        storages.forEach { it.close() }
        storages.clear()

        locations.disconnect("usb")
        val restarted = createStorage()
        restarted.restorePersistedCaches()
        assertIs<UnavailableMediaCache>(restarted.caches().single())
    }

    @Test
    fun `with all files access revoked the entries stay but are unavailable`() = runTest {
        putVideo(internalRoot)
        val storage = createStorage()
        storage.restorePersistedCaches()

        access.granted = false
        storage.refreshCache()
        val placeholder = assertIs<UnavailableMediaCache>(storage.caches().single())
        assertFalse(placeholder.reconnected.first(), "not reported as a reconnected drive")
        assertTrue(placeholder.accessLost, "shown as a lost permission, not an unplugged drive")
        assertEquals(1, datastore.data.first().size)

        access.granted = true
        storage.refreshCache()
        assertIs<LocalFileMediaCache>(storage.caches().single())
    }

    @Test
    fun `with the library off known videos stay listed and new ones are not scanned`() = runTest {
        putVideo(internalRoot)
        val storage = createStorage()
        storage.restorePersistedCaches()

        enabled = false
        putVideo(internalRoot, subjectId = "2", episodeId = "22", name = "别的番")
        storage.refreshCache()
        assertEquals(listOf("11"), storage.caches().map { it.metadata.episodeId }, "not moved back yet, still playable")
        assertIs<LocalFileMediaCache>(storage.caches().single())
    }

    @Test
    fun `with the library off known videos stay listed after a restart`() = runTest {
        putVideo(internalRoot)
        createStorage().restorePersistedCaches()

        enabled = false
        val restarted = createStorage()
        restarted.restorePersistedCaches()
        assertEquals(listOf("11"), restarted.caches().map { it.metadata.episodeId }, "the index remembers it, not moved back yet")
        assertIs<LocalFileMediaCache>(restarted.caches().single())
    }

    @Test
    fun `videos left in the folder are forgotten and found again when the library is turned on`() = runTest {
        putVideo(internalRoot)
        val storage = createStorage()
        storage.restorePersistedCaches()

        enabled = false
        storage.forgetPublic()
        assertTrue(storage.caches().isEmpty())
        assertTrue(datastore.data.first().isEmpty())
        storage.refreshCache()
        assertTrue(storage.caches().isEmpty(), "the public library is not scanned while it is off")

        enabled = true
        storage.refreshCache()
        assertEquals(listOf("11"), storage.caches().map { it.metadata.episodeId })
    }

    @Test
    fun `hidden folders and unrelated files are ignored`() = runTest {
        File(internalRoot, "Izuko/.下载中/番 - 01 [bgmep-11].mkv").apply { parentFile.mkdirs(); writeBytes(ByteArray(8)) }
        File(internalRoot, "Izuko/番 [bgm-1]/番 - 01 [bgmep-11].mkv.part").apply { parentFile.mkdirs(); writeBytes(ByteArray(8)) }
        File(internalRoot, "Izuko/番 [bgm-1]/说明.txt").writeText("x")
        File(internalRoot, "Izuko/没有编号的番/番 - 01 [bgmep-11].mkv").apply { parentFile.mkdirs(); writeBytes(ByteArray(8)) }
        val storage = createStorage()
        storage.restorePersistedCaches()

        assertTrue(storage.caches().isEmpty())
    }

    @Test
    fun `the library does not take new downloads`() = runTest {
        val storage = createStorage()
        assertFalse(storage.engine.supports(originMedia()))
    }

    private fun metadataOf(video: File): MediaCacheMetadata {
        val parsed = MediaLibraryLayout.parseEpisodeFile(video.name)!!
        return MediaCacheMetadata(
            subjectId = MediaLibraryLayout.parseSubjectDir(video.parentFile.name)!!.subjectId,
            episodeId = parsed.episodeId,
            subjectNames = listOf("番"),
            episodeSort = EpisodeSort(parsed.sort!!),
            episodeName = "",
        )
    }

    private fun originMedia() = DefaultMedia(
        mediaId = "dmhy.1",
        mediaSourceId = "dmhy",
        originalUrl = "https://example.com/1",
        download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:1"),
        originalTitle = "[字幕组] 番 01",
        publishedTime = 0,
        properties = MediaProperties(
            subjectName = "番",
            episodeName = null,
            subtitleLanguageIds = listOf("CHS"),
            resolution = "1080P",
            alliance = "字幕组",
            size = 64.bytes,
            subtitleKind = null,
        ),
        episodeRange = EpisodeRange.single(EpisodeSort("1")),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.BitTorrent,
    )
}
