/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as runCoroutineTest
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveTarget
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.media.cache.location.TestMediaCacheLocations
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.download.DownloadTestStorage
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 缓存放在哪 ([MediaStorageSetup]): 换盘时片库里的视频跟着搬到那块盘的片库; 关掉片库时移回应用目录 (没移回之前与之后都照样列出)
 * 或留在文件夹里 (不再列出); 搬走后原处空了的条目文件夹随即删掉; 在播跳过的停播后 (或重开应用后) 接着搬, 进度从已经搬好的接上
 * (设置改了、取消了就不搬); 打开片库时关掉「标记看过后删除缓存」; 计划里的条数与跨盘的大小.
 */
class MediaStorageSetupTest {
    @TempDir
    lateinit var dir: File

    private val internalRoot: File get() = File(dir, "internal").apply { mkdirs() }
    private val usbRoot: File get() = File(dir, "usb").apply { mkdirs() }
    private val locations by lazy { TestMediaCacheLocations(internalRoot.toKtPath().inSystem) }
    private val datastore = MemoryDataStore(emptyList<MediaCacheSave>())
    private val settings = TestSettings(MediaCacheSettings(libraryEnabled = true))
    private val access = object : MediaLibraryAccess {
        override val isSupported: Boolean get() = true
        override fun isGranted(): Boolean = true
        override fun libraryRootOf(location: MediaCacheLocation): SystemPath = location.root.resolve(MediaLibraryAccess.LIBRARY_DIR_NAME)
    }
    private val downloads = DownloadTestStorage()

    /** 在播的番 (条目 id) */
    private val playing = MutableStateFlow<String?>(null)

    private class TestSettings<T>(initial: T) : Settings<T> {
        override val flow = MutableStateFlow(initial)
        override suspend fun set(value: T) {
            flow.value = value
        }
    }

    /** 等停播的记在哪 (见 [MediaCacheMover.waiting]) */
    private val waitingFile: File get() = File(dir, "move-waiting.json")

    private class Env(
        val library: LibraryMediaCacheStorage,
        val mover: MediaCacheMover,
        val setup: MediaStorageSetup,
        /** 重开应用: 新的一个搬家 (读回存下的等停播) 与用它的设置 */
        val restart: () -> Pair<MediaCacheMover, MediaStorageSetup>,
    )

    private fun runTest(testBody: suspend TestScope.(Env) -> Unit) = runCoroutineTest(timeout = 30.seconds) {
        locations.connect("usb", usbRoot.toKtPath().inSystem)
        val library = createLibraryMediaCacheStorage(
            mediaSourceId = MediaDownloadManager.LOCAL_FS_MEDIA_SOURCE_ID,
            datastore = datastore,
            indexFile = File(dir, "index.json").toKtPath().inSystem,
            locations = locations,
            access = access,
            enabled = { settings.flow.value.libraryEnabled },
            parentCoroutineContext = backgroundScope.coroutineContext,
        )
        val storages = { listOf(downloads, library) }
        fun createSetup(): Pair<MediaCacheMover, MediaStorageSetup> {
            val mover = MediaCacheMover(
                locations, storages, isPlayingSubject = { it == playing.value }, scope = backgroundScope, settleDelay = Duration.ZERO,
                freeBytes = { null }, waitingFile = waitingFile.toKtPath().inSystem,
            )
            val publisher = MediaLibraryPublisher(
                library, storages, locations, isPlayingSubject = { false }, isMoving = { mover.isRunning }, freeBytes = { null },
            )
            return mover to MediaStorageSetup(settings, mover, publisher, library)
        }
        val (mover, setup) = createSetup()
        try {
            library.restorePersistedCaches()
            testBody(Env(library, mover, setup, ::createSetup))
        } finally {
            library.close()
        }
    }

    /** 片库里放一个视频 (带说明文件与一条字幕) */
    private fun putLibraryVideo(libraryRoot: File, episodeId: String = "11", sort: String = "01", subjectId: String = "1"): File {
        val video = File(libraryRoot, "番 [bgm-$subjectId]/番 - $sort [bgmep-$episodeId].mkv").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(256) { it.toByte() })
        }
        File(video.parentFile, "番 - $sort [bgmep-$episodeId].chs.ass").writeText("[Script Info]")
        writeLibrarySidecar(
            video.toKtPath().inSystem,
            MediaCacheSave(originMedia("dmhy.$episodeId"), metadata(episodeId, sort, subjectId), LibraryEngineKey),
        )
        return video
    }

    private suspend fun TestScope.applyAndWait(env: Env, block: suspend () -> Boolean): MediaCacheMover.Result {
        val result = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { env.mover.finished.first() }
        assertTrue(block())
        return result.await()
    }

    private val internalLibrary get() = File(internalRoot, MediaLibraryAccess.LIBRARY_DIR_NAME)
    private val usbLibrary get() = File(usbRoot, MediaLibraryAccess.LIBRARY_DIR_NAME)
    private val internalAppLibrary get() = File(internalRoot, LibraryMediaCacheStorage.PRIVATE_DIR_NAME)

    @Test
    fun `changing the drive moves library videos into the library on that drive`() = runTest { env ->
        val video = putLibraryVideo(internalLibrary)
        env.library.refreshCache()

        val result = applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = true) }

        assertEquals(1, result.moved)
        val moved = File(usbLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv")
        assertEquals(256, moved.length())
        assertTrue(File(moved.path + MediaLibraryLayout.SIDECAR_SUFFIX).exists(), "the sidecar moves with it")
        assertTrue(File(moved.parentFile, "番 - 01 [bgmep-11].chs.ass").exists(), "subtitles move with it")
        assertFalse(video.exists(), "the old copy is deleted")
        val cache = assertIs<LocalFileMediaCache>(env.library.listFlow.first().single())
        assertEquals("usb", cache.locationId)
        assertEquals("dmhy.11", cache.origin.mediaId, "the original media is kept")
        assertEquals("usb", settings.flow.value.defaultLocation)
    }

    @Test
    fun `turning the library off moves its videos back into the app folder`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        env.library.refreshCache()

        applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice(null, library = false), moveExisting = true) }

        assertFalse(File(internalLibrary, "番 [bgm-1]").exists(), "the emptied folder is removed right after the move")
        env.library.refreshCache()
        assertTrue(File(internalAppLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").exists())
        assertFalse(settings.flow.value.libraryEnabled)
        assertEquals(null, assertIs<LocalFileMediaCache>(env.library.listFlow.first().single()).locationId, "still listed with the library off")
    }

    @Test
    fun `keeping the library in its folder moves nothing and stops listing it`() = runTest { env ->
        val video = putLibraryVideo(internalLibrary)
        env.library.refreshCache()

        val result = applyAndWait(env) {
            env.setup.apply(MediaStorageSetup.Choice(null, library = false), moveExisting = true, keepLibraryInFolder = true)
        }
        env.library.refreshCache()

        assertEquals(0, result.moved)
        assertTrue(video.exists())
        assertTrue(env.library.listFlow.first().isEmpty())
        assertTrue(env.setup.leftovers().total.isEmpty, "videos kept in the folder are not leftovers")
    }

    @Test
    fun `the plan counts what moves across drives and turning the library on turns off delete when done`() = runTest { env ->
        settings.set(settings.flow.value.copy(libraryEnabled = false, deleteWhenMarkedDone = true))
        putLibraryVideo(internalAppLibrary)
        env.library.refreshCache()

        val plan = env.setup.plan(MediaStorageSetup.Choice("usb", library = true))

        assertEquals(1, plan.library.units)
        assertTrue(plan.otherDriveBytes >= 256, "the video is on another drive")
        assertTrue(plan.turnsOffDeleteWhenDone)
        applyAndWait(env) { env.setup.apply(plan.choice, moveExisting = true) }
        assertFalse(settings.flow.value.deleteWhenMarkedDone)
        assertTrue(File(usbLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").exists(), "moved from the app folder into the public library")
    }

    @Test
    fun `a name taken on the target drive gets a number`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        File(usbLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(8))
        }
        env.library.refreshCache()

        applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = true) }

        assertTrue(File(usbLibrary, "番 [bgm-1]/番 - 01 [bgmep-11] (2).mkv").exists())
        assertTrue(File(usbLibrary, "番 [bgm-1]/番 - 01 [bgmep-11] (2).mkv" + MediaLibraryLayout.SIDECAR_SUFFIX).exists())
        assertEquals(8, File(usbLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").length(), "the other file is left alone")
    }

    @Test
    fun `choosing only new caches leaves the rest as leftovers`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        env.library.refreshCache()

        assertTrue(env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = false))

        val leftovers = env.setup.leftovers()
        assertEquals(1, leftovers.library.units)
        applyAndWait(env) { env.setup.moveLeftovers() }
        assertTrue(env.setup.leftovers().total.isEmpty)
    }

    @Test
    fun `library videos not moved back yet stay listed and are leftovers`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        env.library.refreshCache()

        assertTrue(env.setup.apply(MediaStorageSetup.Choice(null, library = false), moveExisting = false))

        assertEquals(null, assertIs<LocalFileMediaCache>(env.library.listFlow.first().single()).locationId, "still playable from the folder")
        assertEquals(1, env.setup.leftovers().library.units)
        applyAndWait(env) { env.setup.moveLeftovers() }
        assertTrue(File(internalAppLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").exists())
        assertTrue(env.setup.leftovers().total.isEmpty)
    }

    @Test
    fun `videos skipped while playing move once playback stops`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        env.library.refreshCache()
        playing.value = "1"
        env.setup.start(backgroundScope, playing)

        val skipped = applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = true) }
        assertEquals(1, skipped.skipped)
        assertFalse(env.mover.resumeSkipped(MediaCacheMoveTarget("usb", libraryPublic = true)), "still playing")

        val resumed = applyAndWait(env) {
            playing.value = null
            true
        }
        assertEquals(1, resumed.moved)
        assertTrue(File(usbLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").exists())
    }

    @Test
    fun `videos skipped while playing stay when the settings change before playback stops`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        env.library.refreshCache()
        playing.value = "1"

        applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = true) }
        env.setup.apply(MediaStorageSetup.Choice(null, library = true), moveExisting = false)
        playing.value = null

        assertFalse(env.mover.resumeSkipped(MediaCacheMoveTarget("usb", libraryPublic = true)))
        assertFalse(env.mover.resumeSkipped(MediaCacheMoveTarget(null, libraryPublic = true)))
        assertTrue(File(internalLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").exists())
    }

    @Test
    fun `a batch shows up before it knows what to move`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        env.library.refreshCache()
        val first = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { env.mover.progress.filterNotNull().first() }

        applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = true) }

        assertEquals(0, first.await().total, "listed while the caches to move are still being found")
    }

    @Test
    fun `videos still waiting for playback to stop move after the app restarts`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        env.library.refreshCache()
        playing.value = "1"
        applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = true) }
        assertEquals(1, env.mover.waiting.value?.units)

        // 应用被关掉: 播放会话没了, 等停播的从文件读回来, 启动时接着搬
        playing.value = null
        val (mover, setup) = env.restart()
        assertEquals(1, mover.waiting.value?.units)
        val result = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { mover.finished.first() }
        setup.start(backgroundScope, playing)

        assertEquals(1, result.await().moved)
        assertTrue(File(usbLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").exists())
        assertEquals(null, mover.waiting.value)
        assertFalse(waitingFile.exists())
    }

    @Test
    fun `cancelling the videos waiting for playback leaves them where they are`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        env.library.refreshCache()
        playing.value = "1"
        env.setup.start(backgroundScope, playing)
        applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = true) }

        env.mover.forgetSkipped()
        playing.value = null

        assertEquals(null, env.mover.waiting.value)
        assertFalse(waitingFile.exists())
        assertFalse(env.mover.resumeSkipped(MediaCacheMoveTarget("usb", libraryPublic = true)))
        assertTrue(File(internalLibrary, "番 [bgm-1]/番 - 01 [bgmep-11].mkv").exists())
        assertEquals(1, env.setup.leftovers().total.units, "still listed as not moved yet")
    }

    @Test
    fun `moving the videos skipped while playing picks up the progress where the batch stopped`() = runTest { env ->
        putLibraryVideo(internalLibrary)
        putLibraryVideo(internalLibrary, episodeId = "21", subjectId = "2")
        env.library.refreshCache()
        playing.value = "2"
        env.setup.start(backgroundScope, playing)

        val first = applyAndWait(env) { env.setup.apply(MediaStorageSetup.Choice("usb", library = true), moveExisting = true) }
        assertEquals(1, first.moved)
        assertEquals(1, first.skipped)
        val waiting = env.mover.waiting.value!!
        assertEquals(1, waiting.units)
        assertEquals(1, waiting.doneUnits)
        assertTrue(waiting.doneBytes > 0 && waiting.bytes > 0)

        val progress = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            env.mover.progress.filterNotNull().first { it.total > 0 }
        }
        val resumed = applyAndWait(env) {
            playing.value = null
            true
        }
        assertEquals(1, resumed.moved)
        // 第一个进度可能晚到一点 (已经开始复制), 只看它从哪里接上
        with(progress.await()) {
            assertEquals(2, total, "counts the one moved before")
            assertTrue(done >= 1, "the one moved before is done")
            assertEquals(waiting.doneBytes + waiting.bytes, bytesTotal)
            assertTrue(bytesDone >= waiting.doneBytes)
        }
        assertEquals(null, env.mover.waiting.value)
    }

    private fun metadata(episodeId: String, sort: String, subjectId: String = "1") = MediaCacheMetadata(
        subjectId = subjectId,
        episodeId = episodeId,
        subjectNameCN = "番",
        subjectNames = listOf("番"),
        episodeSort = EpisodeSort(sort),
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
            size = 256.bytes,
            subtitleKind = null,
        ),
        episodeRange = EpisodeRange.single(EpisodeSort("1")),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.BitTorrent,
    )
}
