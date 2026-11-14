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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.domain.media.TestMediaList
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
import me.him188.ani.utils.httpdownloader.DownloadSource
import me.him188.ani.utils.httpdownloader.DownloadSourceRefresher
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.httpdownloader.MediaType
import me.him188.ani.utils.httpdownloader.RefreshableHttpDownloader
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 在线源缓存的地址过期后重新解析原资源 ([HttpCacheSourceRefresher]): 新建与恢复的缓存都认得, 只在后台解析直链类的资源.
 */
class HttpCacheSourceRefresherTest {
    /** 每次解析给一个新的带签名地址 (像网盘直链), 记下解析时拿到的剧集 */
    private class SigningResolver : MediaResolver by TestUniversalMediaResolver {
        val resolved = mutableListOf<EpisodeMetadata>()

        override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
            resolved += episode
            val key = resolved.size
            return object : MediaDataProvider<UriMediaData> {
                override val extraFiles = MediaExtraFiles.EMPTY
                override suspend fun open(scopeForCleanup: CoroutineScope) =
                    UriMediaData("https://dl.drive.test/${episode.sort}.mkv?auth_key=$key", mapOf("Cookie" to "k=$key"))
            }
        }
    }

    private val driveMedia = TestMediaList.first().copy(
        kind = MediaSourceKind.WEB,
        // 网盘资源的占位地址: 不是能直接下的地址, 要解析才有直链
        download = ResourceLocation.HttpStreamingFile("https://placeholder.drive.test/file/1"),
    )

    @Test
    fun `a new cache gets a fresh source from its media`() = runTest {
        val resolver = SigningResolver()
        val downloader = RefreshableFakeDownloader()
        val engine = engine(downloader, resolver)
        engine.createCache(driveMedia, metadata(3), EpisodeMetadata("Episode 3", EpisodeSort(3), EpisodeSort(3)), backgroundScope.coroutineContext)
        val state = downloader.states.values.single()
        // 建缓存时解析了一次 (auth_key=1), 地址过期时再解析一次
        val refresher = assertNotNull(downloader.sourceRefresher)

        val fresh = assertNotNull(refresher.refresh(state))

        assertEquals(DownloadSource("https://dl.drive.test/03.mkv?auth_key=2", mapOf("Cookie" to "k=2")), fresh)
        assertEquals(EpisodeSort(3), resolver.resolved.last().sort)
    }

    @Test
    fun `a restored cache gets a fresh source too`() = runTest {
        val resolver = SigningResolver()
        val downloader = RefreshableFakeDownloader()
        val engine = engine(downloader, resolver)
        engine.createCache(driveMedia, metadata(1), EpisodeMetadata("Episode 1", EpisodeSort(1), EpisodeSort(1)), backgroundScope.coroutineContext)
        val state = downloader.states.values.single()

        // 应用重启: 新的引擎只从记录恢复, 没建过缓存
        val restarted = engine(downloader, resolver)
        restarted.restore(driveMedia, metadata(1), backgroundScope.coroutineContext)

        assertNotNull(assertNotNull(downloader.sourceRefresher).refresh(state))
    }

    @Test
    fun `web page sources are resolved again too and unknown downloads are not`() = runTest {
        val resolver = SigningResolver()
        val refresher = HttpCacheSourceRefresher(resolver)
        val webPage = driveMedia.copy(download = ResourceLocation.WebVideo("https://site.test/play/1"))
        refresher.register(DownloadId("web"), webPage, metadata(1))

        // 缓存的网页源也重新抓一遍 (播放不这么做, 见 resolveFreshLink)
        assertNotNull(refresher.refresh(stateOf(DownloadId("web"))))
        assertNull(refresher.refresh(stateOf(DownloadId("unknown"))))
        assertEquals(1, resolver.resolved.size)
    }

    private fun metadata(episode: Int) = MediaCacheMetadata(
        subjectId = "1",
        episodeId = episode.toString(),
        subjectNames = listOf("Subject"),
        episodeSort = EpisodeSort(episode),
        episodeEp = EpisodeSort(episode),
        episodeName = "Episode $episode",
    )

    private fun engine(downloader: RefreshableFakeDownloader, resolver: MediaResolver) = HttpMediaCacheEngine(
        downloader,
        Path("/unused-test-downloads"),
        resolver,
        "test-storage",
        object : HttpCacheDownloadStateDao {
            override fun getAll() = flowOf(downloader.states.values.toList())
            override suspend fun upsert(state: DownloadState) {
                downloader.states[state.downloadId] = state
            }

            override suspend fun updateStatus(id: DownloadId, status: DownloadStatus) = Unit
            override suspend fun deleteAll() = downloader.states.clear()
            override suspend fun deleteById(id: DownloadId) {
                downloader.states.remove(id)
            }

            override suspend fun getById(id: DownloadId) = downloader.states[id]
        },
    )
}

private fun stateOf(id: DownloadId, url: String = "https://dl.drive.test/old.mkv", headers: Map<String, String> = emptyMap()) =
    DownloadState(
        id, url, "${id.value}.mkv", emptyList(), 0, 0, 0, DownloadStatus.DOWNLOADING,
        relativeSegmentCacheDir = "segments_${id.value}", requestHeaders = headers, mediaType = MediaType.MKV,
    )

/** 只记任务的假下载器, 能挂 [DownloadSourceRefresher] */
private class RefreshableFakeDownloader : HttpDownloader, RefreshableHttpDownloader {
    override var sourceRefresher: DownloadSourceRefresher? = null
    val states = mutableMapOf<DownloadId, DownloadState>()

    override val progressFlow: Flow<DownloadProgress> = flowOf()
    override val downloadStatesFlow: Flow<List<DownloadState>> = flowOf(emptyList())
    override fun getProgressFlow(downloadId: DownloadId): Flow<DownloadProgress> = flowOf()
    override suspend fun init() = Unit
    override suspend fun download(url: String, options: DownloadOptions): DownloadId = error("unused")
    override suspend fun downloadWithId(downloadId: DownloadId, url: String, options: DownloadOptions): DownloadState =
        states.getOrPut(downloadId) { stateOf(downloadId, url, options.headers) }

    override suspend fun resume(downloadId: DownloadId) = true
    override suspend fun getActiveDownloadIds() = states.keys.toList()
    override suspend fun pause(downloadId: DownloadId) = true
    override suspend fun pauseAll() = states.keys.toList()
    override suspend fun cancel(downloadId: DownloadId) = true
    override suspend fun cancelAll() = Unit
    override suspend fun remove(downloadId: DownloadId) = states.remove(downloadId) != null
    override suspend fun getState(downloadId: DownloadId) = states[downloadId]
    override suspend fun getAllStates() = states.values.toList()
    override fun close() = Unit
}
