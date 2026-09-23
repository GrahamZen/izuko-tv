/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.migration

import me.him188.ani.app.data.persistent.database.dao.TorrentCacheEpisodeEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationManifest.CacheFile
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import me.him188.ani.utils.httpdownloader.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 换分发包名时搬哪些缓存、搬哪些文件.
 */
class CacheMigrationPlannerTest {
    private val torrentMedia = media("bt-1")
    private val httpDoneMedia = media("web-done")
    private val httpPartialMedia = media("web-partial")

    private val torrentSave = MediaCacheSave(torrentMedia, metadata("1"), MediaCacheEngineKey.Anitorrent)
    private val httpDoneSave = MediaCacheSave(httpDoneMedia, metadata("2"), MediaCacheEngineKey.WebM3u)
    private val httpPartialSave = MediaCacheSave(httpPartialMedia, metadata("3"), MediaCacheEngineKey.WebM3u)

    private val torrent = TorrentCacheInfoEntity(
        mediaId = "bt-1",
        torrentData = byteArrayOf(1, 2, 3),
        relativeDir = "anitorrent/pieces/123",
    )
    private val episode = TorrentCacheEpisodeEntity(mediaId = "bt-1", episodeId = "1", completed = false)
    private val httpDone = downloadState(httpDoneSave, DownloadStatus.COMPLETED, output = "done.mp4", segments = "segments/done")
    private val httpPartial =
        downloadState(httpPartialSave, DownloadStatus.DOWNLOADING, output = "partial.mp4", segments = "segments/partial")

    /** 模拟旧包的缓存根目录. */
    private val filesOnDisk = mapOf(
        "anitorrent/pieces/123" to listOf("anitorrent/pieces/123/ep1.mkv", "anitorrent/pieces/123/fastresume"),
        "web-m3u/done.mp4" to listOf("web-m3u/done.mp4"),
        "web-m3u/segments/done" to emptyList(), // 合并完就删了
        "web-m3u/partial.mp4" to listOf("web-m3u/partial.mp4"),
        "web-m3u/segments/partial" to listOf("web-m3u/segments/partial/0.ts"),
    )

    private fun listFiles(relative: String) = filesOnDisk[relative].orEmpty().map { CacheFile(it, 100) }

    private fun plan() = CacheMigrationPlanner.plan(
        saves = listOf(torrentSave, httpDoneSave, httpPartialSave),
        torrents = listOf(torrent),
        episodes = listOf(episode),
        httpStates = listOf(httpDone, httpPartial),
        listFiles = ::listFiles,
    )

    @Test
    fun `BT 缓存连同没下完的一起搬，带上种子目录里的全部文件`() {
        val manifest = plan()
        assertEquals(listOf("bt-1"), manifest.torrentCaches.map { it.mediaId })
        assertEquals(listOf("1"), manifest.torrentEpisodes.map { it.episodeId })
        assertTrue("anitorrent/pieces/123/fastresume" in manifest.files.map { it.path })
        assertTrue(manifest.torrentCaches.single().toEntity().torrentData.contentEquals(byteArrayOf(1, 2, 3)))
    }

    /** 旧包进程起来会接着下没完成的网页缓存, 边下边搬会搬到写了一半的文件. */
    @Test
    fun `网页缓存只搬下完的`() {
        val manifest = plan()
        assertEquals(listOf(httpDone.downloadId), manifest.httpDownloads.map { it.downloadId })
        assertEquals(listOf(torrentSave, httpDoneSave), manifest.mediaCacheSaves)
        val paths = manifest.files.map { it.path }
        assertTrue("web-m3u/done.mp4" in paths)
        assertFalse(paths.any { "partial" in it })
    }

    @Test
    fun `对不上记录的缓存条目不搬`() {
        val manifest = CacheMigrationPlanner.plan(
            saves = listOf(torrentSave, httpDoneSave),
            torrents = emptyList(),
            episodes = emptyList(),
            httpStates = emptyList(),
            listFiles = ::listFiles,
        )
        assertTrue(manifest.mediaCacheSaves.isEmpty())
        assertTrue(manifest.files.isEmpty())
    }

    @Test
    fun `只提交文件真的搬到了的缓存`() {
        val manifest = plan()
        val arrived = CacheMigrationPlanner.keepArrived(manifest) { it == "web-m3u/done.mp4" }
        assertTrue(arrived.torrentCaches.isEmpty())
        assertTrue(arrived.torrentEpisodes.isEmpty())
        assertEquals(listOf(httpDoneSave), arrived.mediaCacheSaves)

        val all = CacheMigrationPlanner.keepArrived(manifest) { true }
        assertEquals(manifest.mediaCacheSaves, all.mediaCacheSaves)
    }

    /**
     * 库里的 relativeDir 是 `substringAfter(缓存根目录)` 截出来的, 带前导 `/`.
     * 2026-09-22 真机: 没去掉它, 路径校验不过, 一个文件都没列出来.
     */
    @Test
    fun `库里带前导斜杠的相对路径照样列得出文件`() {
        val manifest = CacheMigrationPlanner.plan(
            saves = listOf(torrentSave),
            torrents = listOf(torrent.copy(relativeDir = "/anitorrent/pieces/123")),
            episodes = listOf(episode),
            httpStates = emptyList(),
            listFiles = ::listFiles,
        )
        assertEquals(2, manifest.files.size)
        val arrived = CacheMigrationPlanner.keepArrived(manifest) { it == "anitorrent/pieces/123" }
        assertEquals(listOf("bt-1"), arrived.torrentCaches.map { it.mediaId })
    }

    /** 旧包的 BT 引擎中途又跑起来时, 先搬的续传数据记着的 piece, 后搬的数据文件里都有. */
    @Test
    fun `续传数据排在数据文件前面`() {
        val paths = plan().files.map { it.path }
        assertEquals("anitorrent/pieces/123/fastresume", paths.first())
        assertEquals(paths.toSet(), filesOnDisk.values.flatten().filterNot { "partial" in it }.toSet())
    }

    /** 旧包只忘掉新包确认收下的, 其余原样留着. */
    @Test
    fun `按回执忘掉已收下的缓存`() {
        val manifest = plan()
        val receipt = CacheMigrationPlanner.receiptOf(CacheMigrationPlanner.keepArrived(manifest) { it == "web-m3u/done.mp4" })
        assertEquals(emptyList(), receipt.torrentMediaIds)
        assertEquals(listOf(httpDone.downloadId.value), receipt.httpDownloadIds)

        val remaining = CacheMigrationPlanner.withoutReceived(listOf(torrentSave, httpDoneSave, httpPartialSave), receipt)
        assertEquals(listOf(torrentSave, httpPartialSave), remaining)
    }

    @Test
    fun `越出缓存根目录的路径一律不认`() {
        assertTrue(CacheMigrationPlanner.isSafeRelativePath("anitorrent/pieces/123/ep1.mkv"))
        assertFalse(CacheMigrationPlanner.isSafeRelativePath(""))
        assertFalse(CacheMigrationPlanner.isSafeRelativePath("/data/data/x"))
        assertFalse(CacheMigrationPlanner.isSafeRelativePath("../shared_prefs/x.xml"))
        assertFalse(CacheMigrationPlanner.isSafeRelativePath("a/../../b"))
        assertFalse(CacheMigrationPlanner.isSafeRelativePath("a\\..\\b"))
        assertFalse(CacheMigrationPlanner.isSafeRelativePath("a//b"))
        assertFalse(CacheMigrationPlanner.isSafeRelativePath("C:/x"))
    }

    private fun downloadState(save: MediaCacheSave, status: DownloadStatus, output: String, segments: String) =
        DownloadState(
            downloadId = HttpMediaCacheEngine.downloadIdOf(save.origin, save.metadata),
            url = "https://example.com/${save.origin.mediaId}.m3u8",
            relativeOutputPath = output,
            segments = emptyList(),
            totalSegments = 0,
            downloadedBytes = 0,
            timestamp = 0,
            status = status,
            relativeSegmentCacheDir = segments,
            requestHeaders = emptyMap(),
            mediaType = MediaType.M3U8,
        )

    private fun media(id: String): Media = createTestDefaultMedia(
        mediaId = id,
        mediaSourceId = "source-1",
        originalUrl = "https://example.com/$id",
        download = ResourceLocation.HttpStreamingFile("https://example.com/$id.m3u8"),
        originalTitle = "Episode 1",
        publishedTime = 1L,
        properties = createTestMediaProperties(subjectName = "Test Subject", episodeName = "Episode 1"),
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )

    private fun metadata(episodeId: String) = MediaCacheMetadata(
        subjectId = "1",
        episodeId = episodeId,
        subjectNameCN = "Test Subject",
        subjectNames = listOf("Test Subject"),
        episodeSort = EpisodeSort(1),
        episodeEp = EpisodeSort(1),
        episodeName = "Episode 1",
    )
}
