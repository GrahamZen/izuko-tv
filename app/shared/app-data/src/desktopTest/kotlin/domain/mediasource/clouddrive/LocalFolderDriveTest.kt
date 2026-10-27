/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import me.him188.ani.app.domain.media.cache.library.MediaLibraryAccess
import me.him188.ani.app.domain.media.player.data.SystemFileMediaDataProvider
import me.him188.ani.app.domain.media.resolver.CloudDriveMediaResolver
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalFolderDriveTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private class FakeRoots(var volumes: List<LocalFolderRoot>, var granted: Boolean = true) : LocalFolderRoots {
        override fun roots(): List<LocalFolderRoot> = volumes
        override fun isAccessible(): Boolean = granted
    }

    /** 一块盘 (临时目录), 系统给的名字是 [label] */
    private fun volume(label: String): LocalFolderRoot {
        val dir = createTempDirectory("local-folder").toFile().also { dirs += it }
        return LocalFolderRoot(label, dir.absolutePath.replace('\\', '/').trimEnd('/'))
    }

    /** 写一个 [size] 字节的文件 (只定长度不写内容: 视频要够大才不被当成样片) */
    private fun LocalFolderRoot.write(relative: String, size: Long = VIDEO_SIZE) {
        val file = File(path, relative)
        file.parentFile.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    private fun LocalFolderRoot.idOf(relative: String = ""): String =
        LocalFolderDrive.idOf(if (relative.isEmpty()) path else "$path/$relative")

    private val protocol = LocalFolderDrive.newProtocol("本地文件夹")

    private fun request(episode: Int) = MediaFetchRequest(
        subjectId = "375735",
        episodeId = episode.toString(),
        subjectNames = listOf("恋爱FLOPS"),
        episodeSort = EpisodeSort(episode),
        episodeName = "",
    )

    @Test
    fun `lists the drives at the root and skips system folders and the library on a drive`() = runTest {
        val usb = volume("U 盘").apply {
            write("动画/恋爱FLOPS/恋爱FLOPS - 01.mkv")
            write("动画/恋爱FLOPS/恋爱FLOPS - 01.chs.ass")
            write("Android/data/x/files/a.mkv")
            write("${MediaLibraryAccess.LIBRARY_DIR_NAME}/恋爱FLOPS [bgm-375735]/恋爱FLOPS - 01.mkv")
            write(".Trashes/a.mkv")
            write("LOST.DIR/a")
        }
        val client = LocalFolderClient(FakeRoots(listOf(usb)))

        val drives = client.list(LocalFolderDrive.ROOT_ID)
        assertEquals(listOf("U 盘"), drives.map { it.fileName })
        assertTrue(drives.single().dir)
        assertEquals(usb.idOf(), drives.single().fid)

        assertEquals(listOf("动画"), client.list(usb.idOf()).map { it.fileName })

        val files = client.list(usb.idOf("动画/恋爱FLOPS")).sortedBy { it.fileName }
        assertEquals(listOf("恋爱FLOPS - 01.chs.ass", "恋爱FLOPS - 01.mkv"), files.map { it.fileName })
        val video = files[1]
        assertTrue(video.isVideo)
        assertEquals(VIDEO_SIZE, video.size)
        assertEquals(usb.idOf("动画/恋爱FLOPS"), video.parentFid)
        // 占位地址里的文件 id 不能带 `/`
        assertTrue('/' !in video.fid)
        assertEquals("file://${usb.path}/动画/恋爱FLOPS/恋爱FLOPS - 01.mkv", LocalFolderDrive.urlOf(video.fid))
    }

    @Test
    fun `folders are not listed without the permission or once the drive is gone`() = runTest {
        val usb = volume("U 盘").apply { write("动画/a.mkv") }
        val roots = FakeRoots(listOf(usb), granted = false)
        val client = LocalFolderClient(roots)
        assertFailsWith<LocalFolderUnavailableException> { client.list(LocalFolderDrive.ROOT_ID) }
        assertFailsWith<LocalFolderUnavailableException> { client.list(usb.idOf("动画")) }

        roots.granted = true
        File(usb.path).deleteRecursively()
        assertFailsWith<LocalFolderUnavailableException> { client.list(usb.idOf("动画")) }
    }

    @Test
    fun `folders show the drive name and walk back up to the drive list`() {
        val usb = volume("U 盘")
        val client = LocalFolderClient(FakeRoots(listOf(usb)))
        val folder = usb.idOf("动画/2026-10")
        assertEquals("U 盘/动画/2026-10", client.displayPath(folder))
        assertEquals(usb.idOf("动画"), client.parentIdOf(folder))
        assertEquals(LocalFolderDrive.ROOT_ID, client.parentIdOf(usb.idOf()))
        assertEquals("", client.parentIdOf(LocalFolderDrive.ROOT_ID))
    }

    @Test
    fun `finds the subject in the media folders and plays the file from disk with its subtitles`() = realTimeTest {
        val usb = volume("U 盘").apply {
            write("动画/2026-10/恋爱FLOPS/恋爱FLOPS - 01.mkv")
            write("动画/2026-10/恋爱FLOPS/恋爱FLOPS - 02.mkv")
            write("动画/2026-10/恋爱FLOPS/恋爱FLOPS - 02.chs.ass")
        }
        val withLibrary = protocol.copy(library = DriveLibraryConfig(folders = listOf(usb.idOf("动画"))))
        val accounts = MemorySettings(CloudDriveAccounts.Default)
        val service = CloudDriveService(withLibrary, accounts, mockClient { error("unexpected request ${it.url}") }, localRoots = FakeRoots(listOf(usb)))
        // 不用登录
        assertTrue(service.account.first().isLoggedIn)

        val source = CloudDriveMediaSource(service, CloudDriveArguments(protocol = withLibrary))
        val media = source.fetch(request(2)).results.toList().map { it.media }
        assertEquals(listOf(EpisodeRange.single(EpisodeSort(1)), EpisodeRange.single(EpisodeSort(2))), media.map { it.episodeRange })

        val playback = service.resolvePlayback(assertNotNull(service.placeholders.fileIdOf(media[1].download.uri)))
        assertEquals("file://${usb.path}/动画/2026-10/恋爱FLOPS/恋爱FLOPS - 02.mkv", playback.url)
        assertEquals(listOf("file://${usb.path}/动画/2026-10/恋爱FLOPS/恋爱FLOPS - 02.chs.ass"), playback.subtitles.map { it.url })
    }

    @Test
    fun `without media folders the drives are searched from their top folders`() = realTimeTest {
        val internal = volume("内部存储").apply { write("Movies/恋爱FLOPS/恋爱FLOPS - 01.mkv") }
        val usb = volume("U 盘").apply { write("恋爱FLOPS/恋爱FLOPS - 02.mkv") }
        val service = CloudDriveService(
            protocol, MemorySettings(CloudDriveAccounts.Default), mockClient { error("unexpected request ${it.url}") },
            localRoots = FakeRoots(listOf(internal, usb)),
        )
        val media = CloudDriveMediaSource(service, CloudDriveArguments(protocol = protocol)).fetch(request(1)).results.toList().map { it.media }
        assertEquals(setOf(EpisodeRange.single(EpisodeSort(1)), EpisodeRange.single(EpisodeSort(2))), media.map { it.episodeRange }.toSet())
    }

    @Test
    fun `the resolver hands local files to the player as files`() = runTest {
        val usb = volume("U 盘").apply { write("恋爱FLOPS/恋爱FLOPS - 01.mkv") }
        val test = testRegistry(saves = listOf(driveSave(protocol)), localFolderRoots = FakeRoots(listOf(usb)))
        val drive = assertNotNull(test.registry.awaitService(protocol.id))
        val file = DriveFile(usb.idOf("恋爱FLOPS/恋爱FLOPS - 01.mkv"), fileName = "恋爱FLOPS - 01.mkv", parentFid = usb.idOf("恋爱FLOPS"), isVideo = true)
        val media = CloudDriveMediaSource.mediaFor(drive, file, EpisodeSort(1), null)
        val resolver = CloudDriveMediaResolver(test.registry)
        assertTrue(resolver.supports(media))

        val provider = assertIs<SystemFileMediaDataProvider>(resolver.resolve(media, EpisodeMetadata("第一集", EpisodeSort(1), EpisodeSort(1))))
        assertEquals("${usb.path}/恋爱FLOPS/恋爱FLOPS - 01.mkv", provider.path.toString().replace('\\', '/'))
    }

    private companion object {
        const val VIDEO_SIZE = 30L * 1024 * 1024
    }
}
