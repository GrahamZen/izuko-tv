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
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.TestMediaCache
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.app.domain.media.cache.location.TestMediaCacheLocations
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.download.DownloadSpaceGuard
import me.him188.ani.app.domain.media.download.DownloadTestStorage
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.Subtitle
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.resolve
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
 * 发布 ([MediaLibraryPublisher]): 下完的缓存复制进同一个位置的片库, 片库认下后删原缓存;
 * 在播的、没下完的、没编号的不动; 出错时原缓存照留; 上次认下了没删成的这次只删原缓存.
 */
class MediaLibraryPublisherTest {
    @TempDir
    lateinit var dir: File

    private val internalRoot: File get() = File(dir, "internal").apply { mkdirs() }
    private val libraryRoot: File get() = File(internalRoot, MediaLibraryAccess.LIBRARY_DIR_NAME)
    private val locations by lazy { TestMediaCacheLocations(internalRoot.toKtPath().inSystem) }
    private val datastore = MemoryDataStore(emptyList<MediaCacheSave>())
    private val access = object : MediaLibraryAccess {
        override val isSupported: Boolean get() = true
        override fun isGranted(): Boolean = true
        override fun libraryRootOf(location: MediaCacheLocation): SystemPath = location.root.resolve(MediaLibraryAccess.LIBRARY_DIR_NAME)
    }
    private var enabled = true
    private var playing: String? = null
    private var free: Long? = null
    private val downloads = DownloadTestStorage()
    private val deleted = mutableListOf<MediaCache>()
    private val libraries = mutableListOf<LibraryMediaCacheStorage>()

    private fun runTest(testBody: suspend TestScope.() -> Unit) = runCoroutineTest(timeout = 30.seconds) {
        downloads.onDelete = { deleted += it }
        try {
            testBody()
        } finally {
            libraries.forEach { it.close() }
            libraries.clear()
        }
    }

    private fun TestScope.createLibrary(): LibraryMediaCacheStorage = createLibraryMediaCacheStorage(
        mediaSourceId = MediaDownloadManager.LOCAL_FS_MEDIA_SOURCE_ID,
        datastore = datastore,
        indexFile = File(dir, "index.json").toKtPath().inSystem,
        locations = locations,
        access = access,
        enabled = { enabled },
        parentCoroutineContext = backgroundScope.coroutineContext,
    ).also { libraries += it }

    private fun publisher(library: LibraryMediaCacheStorage) = MediaLibraryPublisher(
        library = library,
        storages = { listOf(downloads, library) },
        locations = locations,
        isPlayingSubject = { it == playing },
        isMoving = { false },
        freeBytes = { free },
    )

    /** 下载那边一份缓存, 文件在内部存储的缓存目录里 */
    private fun completedCache(
        subjectId: String = "1",
        episodeId: String = "11",
        fileName: String = "video.mkv",
        subtitles: List<Subtitle> = emptyList(),
        state: MediaCacheState = MediaCacheState.COMPLETED,
    ): TestMediaCache {
        val file = File(internalRoot, "cache/$episodeId/$fileName").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(128) { it.toByte() })
        }
        val origin = originMedia("dmhy.$episodeId").copy(extraFiles = MediaExtraFiles(subtitles))
        val cached = CachedMedia(
            origin, MediaDownloadManager.LOCAL_FS_MEDIA_SOURCE_ID, ResourceLocation.LocalFile(file.absolutePath),
            extraFiles = MediaExtraFiles(subtitles),
        )
        return TestMediaCache(cached, metadata(subjectId, episodeId)).also {
            it.state.value = state
            downloads.listFlow.value += it
        }
    }

    private fun subtitleFile(name: String): Subtitle {
        val file = File(internalRoot, "izuko-subtitles/$name").apply {
            parentFile.mkdirs()
            writeText("[Script Info]")
        }
        return Subtitle(uri = "file://" + file.absolutePath, mimeType = "text/x-ssa", language = "chs")
    }

    @Test
    fun `a completed cache is copied into the library and the original is deleted`() = runTest {
        val library = createLibrary()
        library.restorePersistedCaches()
        val cache = completedCache()

        val result = publisher(library).publishCompleted()

        assertEquals(1, result.published)
        val video = File(libraryRoot, "番 [bgm-1]/番 - 01 [bgmep-11].mkv")
        assertTrue(video.exists())
        assertEquals(128, video.length())
        assertEquals(listOf<MediaCache>(cache), deleted, "the original cache is deleted through its storage")
        val published = assertIs<LocalFileMediaCache>(library.listFlow.first().single())
        assertEquals(cache.cacheId, published.cacheId, "the library keeps the cache id")
        assertEquals("dmhy.11", readLibrarySidecar(video.toKtPath().inSystem)?.origin?.mediaId)
        assertEquals(listOf(LibraryEngineKey), datastore.data.first().map { it.engine })
    }

    @Test
    fun `local subtitles are copied next to the video`() = runTest {
        val library = createLibrary()
        library.restorePersistedCaches()
        completedCache(subtitles = listOf(subtitleFile("0.ass")))

        publisher(library).publishCompleted()

        assertTrue(File(libraryRoot, "番 [bgm-1]/番 - 01 [bgmep-11].chs.ass").exists())
        val published = library.listFlow.first().single()
        val subtitle = published.getCachedMedia().extraFiles.subtitles.single()
        assertEquals("chs", subtitle.language)
        assertTrue(subtitle.uri.endsWith("番 - 01 [bgmep-11].chs.ass"), subtitle.uri)
        val sidecar = readLibrarySidecar(File(libraryRoot, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").toKtPath().inSystem)
        assertTrue(sidecar!!.origin.extraFiles.subtitles.isEmpty(), "the sidecar does not point into the old cache folder")
    }

    @Test
    fun `caches of the playing subject wait`() = runTest {
        val library = createLibrary()
        library.restorePersistedCaches()
        completedCache()
        playing = "1"

        val result = publisher(library).publishCompleted()

        assertEquals(1, result.skipped)
        assertFalse(libraryRoot.exists())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `unfinished caches and caches without ids are left alone`() = runTest {
        val library = createLibrary()
        library.restorePersistedCaches()
        completedCache(episodeId = "12", state = MediaCacheState.IN_PROGRESS)
        completedCache(subjectId = "", episodeId = "13")
        completedCache(episodeId = "14", fileName = "video.rmvb")

        val result = publisher(library).publishCompleted()

        assertEquals(MediaLibraryPublisher.Result(0, 0, 0), result)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `pending counts what turning the library on would move`() = runTest {
        enabled = false
        val library = createLibrary()
        library.restorePersistedCaches()
        completedCache()
        completedCache(episodeId = "12", state = MediaCacheState.IN_PROGRESS)
        completedCache(subjectId = "", episodeId = "13")
        val publisher = publisher(library)

        assertEquals(MediaLibraryPublisher.Pending(1, 128), publisher.pending(null), "counted even while the library is off")

        enabled = true
        publisher.publishCompleted()
        assertEquals(MediaLibraryPublisher.Pending(0, 0), publisher.pending(null))
    }

    @Test
    fun `nothing is published while the library is off`() = runTest {
        enabled = false
        val library = createLibrary()
        library.restorePersistedCaches()
        completedCache()

        publisher(library).publishCompleted()

        assertFalse(libraryRoot.exists())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `low space stops publishing on that drive`() = runTest {
        val library = createLibrary()
        library.restorePersistedCaches()
        completedCache()
        free = DownloadSpaceGuard.LOW_SPACE_BYTES

        val result = publisher(library).publishCompleted()

        assertEquals(1, result.skipped)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `a failed delete is retried without copying again`() = runTest {
        val library = createLibrary()
        library.restorePersistedCaches()
        val cache = completedCache()
        var failDelete = true
        downloads.onDelete = {
            if (failDelete) error("busy")
            deleted += it
        }
        val publisher = publisher(library)

        assertEquals(1, publisher.publishCompleted().failed)
        assertEquals(listOf<MediaCache>(cache), downloads.listFlow.value, "the original stays when it cannot be deleted")

        failDelete = false
        assertEquals(1, publisher.publishCompleted().published)
        assertEquals(listOf<MediaCache>(cache), deleted)
        assertEquals(1, File(libraryRoot, "番 [bgm-1]").listFiles()!!.count { it.name.endsWith(".mkv") }, "no second copy")
    }

    @Test
    fun `another release of the same episode gets a numbered name`() = runTest {
        File(libraryRoot, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(8))
        }
        val library = createLibrary()
        library.restorePersistedCaches()
        completedCache()

        publisher(library).publishCompleted()

        assertTrue(File(libraryRoot, "番 [bgm-1]/番 - 01 [bgmep-11] (2).mkv").exists())
        assertEquals(2, library.listFlow.first().size)
    }

    @Test
    fun `an unfinished copy left by a crash is cleaned up`() = runTest {
        val leftover = File(libraryRoot, "番 [bgm-1]/.番 - 01 [bgmep-11].mkv.izuko-moving").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(8))
        }
        val library = createLibrary()
        library.restorePersistedCaches()

        publisher(library).publishCompleted()

        assertFalse(leftover.exists())
    }

    @Test
    fun `subtitle tags use the language and number repeats`() {
        val subtitles = listOf(
            Subtitle(uri = "file:///a.ass", language = "chs"),
            Subtitle(uri = "file:///b.ass", language = "chs"),
            Subtitle(uri = "file:///c.ass", language = "简体中文"),
            Subtitle(uri = "file:///d.ass"),
        )
        assertEquals(listOf("chs-1", "chs-2", "3", "4"), subtitles.indices.map { MediaLibraryPublisher.subtitleTag(subtitles, it) })
    }

    private fun metadata(subjectId: String, episodeId: String) = MediaCacheMetadata(
        subjectId = subjectId,
        episodeId = episodeId,
        subjectNameCN = "番",
        subjectNames = listOf("番"),
        episodeSort = EpisodeSort("1"),
        episodeName = "",
    )

    private fun originMedia(mediaId: String) = DefaultMedia(
        mediaId = mediaId,
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
            size = 128.bytes,
            subtitleKind = null,
        ),
        episodeRange = EpisodeRange.single(EpisodeSort("1")),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.BitTorrent,
    )
}
