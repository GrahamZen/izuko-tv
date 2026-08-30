/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.tools.Progress
import me.him188.ani.app.tools.toProgress
import me.him188.ani.app.torrent.api.files.averageRate
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaCacheProperties
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.httpdownloader.finalOutputRelativePath
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadOptions
import me.him188.ani.utils.httpdownloader.DownloadProgress
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.httpdownloader.MediaType
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.actualSize
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext

class HttpMediaCacheEngine(
    private val downloader: HttpDownloader,
    private val saveDir: Path,
    private val mediaResolver: MediaResolver,
    private val mediaSourceId: String,
    private val dao: HttpCacheDownloadStateDao,
    private val pikpakConfig: () -> PikPakConfig = { PikPakConfig.Default },
) : MediaCacheEngine {
    override val engineKey: MediaCacheEngineKey = MediaCacheEngineKey.WebM3u

    override val stats: Flow<MediaStats> = run {
        val downloadSpeedFlow =
            downloader.downloadStatesFlow
                .map { list ->
                    list.sumOf { it.downloadedBytes }
                }
                .averageRate()

        combine(downloader.downloadStatesFlow, downloadSpeedFlow) { list, speed ->
            MediaStats(
                uploaded = FileSize.Zero,
                downloaded = list.sumOf { it.downloadedBytes }.bytes,
                uploadSpeed = FileSize.Zero,
                downloadSpeed = speed.bytes,
            )
        }
    }

    override fun supports(media: Media): Boolean {
        // Check that the media is not already cached
        when (media) {
            is CachedMedia -> return false
            is DefaultMedia -> {} // for smart cast
        }

        return when (media.download) {
            is ResourceLocation.HttpStreamingFile -> mediaResolver.supports(media)
            is ResourceLocation.HttpTorrentFile,
            is ResourceLocation.MagnetLink,
                -> pikpakConfig().enabled && mediaResolver.supports(media)

            is ResourceLocation.LocalFile,
                -> {
                false
            }

            is ResourceLocation.WebVideo -> mediaResolver.supports(media)
        }
    }


    @Composable
    override fun ComposeContent(): Unit = mediaResolver.ComposeContent()

    override suspend fun restore(
        origin: Media,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext,
    ): MediaCache? {
        if (!supports(origin)) throw UnsupportedOperationException("Media is not supported by this engine $this: ${origin.download}")

        logger.info { "Restarting cache '${origin.mediaId}'" }
        val downloadId = origin.toSafeDownloadId()

        // 注意, getState 一般不会返回 null, 除非 downloader 的 persistent datastore 出问题了 (例如文件损坏).
        if (downloader.getState(downloadId) != null) {
            downloader.resume(downloadId) // ignore result.
            // Task already exists
            logger.info { "Resumed download $downloadId" }
            return HttpMediaCache(origin, downloadId, metadata)
        }

        val persistentState = dao.getById(downloadId) ?: kotlin.run {
            logger.error { "Failed to find download state $downloadId from persistent storage while recreating cache." }
            return null
        }

        logger.info { "Download not found, recreating $downloadId" }
        downloader.downloadWithId(
            downloadId = downloadId,
            persistentState.url,
            options = DownloadOptions(headers = persistentState.requestHeaders),
        )
        return HttpMediaCache(origin, downloadId, metadata)
    }

    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext,
    ): MediaCache {
        if (!supports(origin)) throw UnsupportedOperationException("Media is not supported by this engine $this: ${origin.download}")

        val mediaDataProvider = mediaResolver.resolve(origin, episodeMetadata)
        when (val mediaData = mediaDataProvider.open(CoroutineScope(parentContext))) {
            is SeekableInputMediaData -> {
                // This should not happen.
                throw UnsupportedOperationException("SeekableInputMediaData is not supported")
            }

            is UriMediaData -> {
                // TODO: 用 [Media.mediaId] 当作 DownloadId 好吗?
                val downloadId = origin.toSafeDownloadId()
                var options = DownloadOptions(headers = mediaData.headers)
                if (origin.kind == MediaSourceKind.BitTorrent) {
                    val config = pikpakConfig()
                    options = options.copy(
                        maxConcurrentSegments = config.downloadConcurrency.coerceIn(
                            PikPakConfig.MIN_DOWNLOAD_CONCURRENCY,
                            PikPakConfig.MAX_DOWNLOAD_CONCURRENCY,
                        ),
                        // PikPak CDN rejects Ktor's default JSON Accept header with 406.
                        headers = options.headers + ("Accept" to "application/octet-stream"),
                    )
                }
                val state = downloader.downloadWithId(
                    downloadId = downloadId,
                    mediaData.uri,
                    options = options,
                ) ?: throw UnsupportedOperationException("Failed to create download job of $downloadId, state is null.")

                return HttpMediaCache(
                    origin,
                    downloadId,
                    metadata,
                )
            }
        }
    }

    override suspend fun deleteUnusedCaches(all: List<MediaCache>) {
        if (!(SystemFileSystem.exists(saveDir))) return


        val allowedAbsolute = buildSet {
            for (mediaCache in all.filterIsInstance<HttpMediaCache>()) {
                downloader.getState(mediaCache.downloadId)?.let { state ->
                    add(Path(saveDir, state.relativeOutputPath).inSystem.absolutePath)
                    add(Path(saveDir, state.relativeSegmentCacheDir).inSystem.absolutePath)
                }
            }
        }
        withContext(Dispatchers.IO_) {
            val saves = SystemFileSystem.list(saveDir)
            for (save in saves) {
                val myPath = save.inSystem.absolutePath
                if (allowedAbsolute.none {
                        myPath.startsWith(it)
                    }) {
                    logger.warn { "本地 WEB 缓存文件未找到匹配的 MediaCache, 已释放 ${save.inSystem.actualSize().bytes}: ${save.inSystem.absolutePath}" }
                    SystemFileSystem.deleteRecursively(save)
                }
            }
        }

    }

    inner class HttpMediaCache(
        override val origin: Media,
        internal val downloadId: DownloadId,
        override val metadata: MediaCacheMetadata,
    ) : MediaCache {
        // 同 [canPlay]: 状态只有四档, 每段完成都重发一遍会让缓存列表整屏跟着重组
        override val state: Flow<MediaCacheState> =
            downloader.getProgressFlow(downloadId).map { it.status.toMediaCacheState() }
                .distinctUntilChanged()

        // **必须 distinctUntilChanged**: 上游 getProgressFlow 的 distinct 比的是整个 DownloadProgress
        // (含已下载字节), 下载中每段完成都会发一次 —— 而这里只关心一个布尔. 不收窄的话每秒
        // 几次的重复 false 会一路推到 MediaSelectorContext, 让整条筛选+排序流水线空转重算
        // (每个资源都要做条目名相似度匹配), 缓存时界面明显变卡.
        override val canPlay: Flow<Boolean>
            get() = downloader.getProgressFlow(downloadId).map {
                it.status == DownloadStatus.COMPLETED
            }.distinctUntilChanged()

        override val fileStats: Flow<MediaCache.FileStats> = downloader.getProgressFlow(downloadId).map {
            val totalSize = it.totalBytes
            val downloadedBytes = it.downloadedBytes
            MediaCache.FileStats(
                totalSize = totalSize.bytes,
                downloadedBytes = downloadedBytes.bytes,
                downloadProgress = it.toHttpCacheProgress(),
            )
        }
        override val sessionStats: Flow<MediaCache.SessionStats> = run {
            val downloadSpeedFlow = fileStats.map { it.downloadedBytes.inBytes }.averageRate()

            combine(downloadSpeedFlow, fileStats) { speed, stats ->
                MediaCache.SessionStats(
                    totalSize = stats.totalSize,
                    downloadedBytes = stats.downloadedBytes,
                    downloadSpeed = speed.bytes,
                    uploadedBytes = FileSize.Zero,
                    uploadSpeed = FileSize.Zero,
                    downloadProgress = stats.downloadProgress,
                )
            }
        }
        override val isDeleted: MutableStateFlow<Boolean> = MutableStateFlow(false)
        private val closeMutex = Mutex()

        override suspend fun getCachedMedia(): CachedMedia {
            val state = downloader.getState(downloadId)
                ?: throw IllegalStateException("Download state not found for $downloadId")

            return when (state.status) {
                DownloadStatus.INITIALIZING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.MERGING,
                DownloadStatus.PAUSED,
                    -> {
                    // **没下完也要给出 CachedMedia, 不能抛** (2026-08-29): 抛出去的话这条缓存连同
                    // 同一存储里已经下完的那些会一起从选源菜单消失 (见 MediaCacheStorageSource.fetch),
                    // 用户看到的是"我明明缓存过却找不到". 由选源器按 MediaCache.canPlay 标成
                    // "缓存未完成"并禁止选中 (见 MediaExclusionReason.CacheNotReady).
                    //
                    // **download 必须指向最终的本地文件, 不能回退到 origin.download** (第一版这么写,
                    // 当天真机就炸了): web 源的 origin.download 是 [ResourceLocation.WebVideo] ——
                    // 一个要靠 WebView 现抓地址的网页, 而解析器是按 download 的类型派发的
                    // (AndroidWebMediaResolver.supports = download is WebVideo). 于是这条"缓存"会被
                    // 拿去重新解析网页, 报 NO_MATCHING_RESOURCE.
                    //
                    // 更要命的是**选源菜单的资源列表是一次性快照** (MediaSourceMediaFetcher 的
                    // runningFold + distinctBy, 同 mediaId 重发会被丢弃): 下载完成后这个对象不会被
                    // 换掉, 而 CacheNotReady 的排除又会随 canPlay 解除 —— 于是变成一颗"看着可用、
                    // 点了必失败"的地雷. 而 relativeOutputPath 从创建那一刻就定死了, 所以这里直接
                    // 用最终路径: 没下完时它被排除、点不了; 下完之后这个快照恰好就是正确的.
                    CachedMedia(
                        origin,
                        cacheMediaSourceId = mediaSourceId,
                        download = ResourceLocation.LocalFile(
                            Path(saveDir, state.finalOutputRelativePath()).inSystem.absolutePath,
                            state.toFileType(),
                            originalUri = state.url,
                        ),
                        cacheProperties = MediaCacheProperties(
                            totalSegments = state.totalSegments,
                            httpDownloaderStatus = state.status.toString(),
                            cacheId = cacheId,
                        ),
                    )
                }

                DownloadStatus.COMPLETED -> {
                    val actualFileSize = withContext(Dispatchers.IO_) {
                        try {
                            Path(saveDir, state.relativeOutputPath).inSystem.actualSize().bytes
                        } catch (_: Exception) {
                            FileSize.Unspecified
                        }
                    }
                    CachedMedia(
                        origin,
                        cacheMediaSourceId = mediaSourceId,
                        download = ResourceLocation.LocalFile(
                            Path(saveDir, state.relativeOutputPath).inSystem.absolutePath,
                            state.toFileType(),
                            originalUri = state.url,
                        ),
                        properties = origin.properties.copy(
                            size = if (actualFileSize.isUnspecified) {
                                origin.properties.size
                            } else {
                                actualFileSize
                            },
                        ),
                        cacheProperties = MediaCacheProperties(
                            totalSegments = state.totalSegments,
                            httpDownloaderStatus = state.status.toString(),
                            cacheId = cacheId,
                        ),
                    )
                }

                DownloadStatus.FAILED,
                DownloadStatus.CANCELED,
                    -> {
                    error("Download failed or canceled")
                }
            }
        }

        override suspend fun pause() {
            downloader.pause(downloadId)
        }

        override suspend fun close() {
            if (isDeleted.value) return
            closeMutex.withLock {
                if (isDeleted.value) return
                downloader.cancel(downloadId)
            }
        }

        override suspend fun resume() {
            downloader.resume(downloadId)
        }

        override suspend fun closeAndDeleteFiles() {
            if (isDeleted.value) return
            closeMutex.withLock {
                if (isDeleted.value) return
                val removed = downloader.remove(downloadId)
                if (!removed) {
                    dao.getById(downloadId)?.let { state ->
                        deleteDownloadFiles(state)
                    }
                }
                isDeleted.value = true
            }
        }
    }

    private suspend fun deleteDownloadFiles(state: DownloadState) {
        withContext(Dispatchers.IO_) {
            val outputPath = Path(saveDir, state.relativeOutputPath).inSystem
            if (outputPath.exists()) {
                outputPath.delete()
            }

            val cacheDir = Path(saveDir, state.relativeSegmentCacheDir).inSystem
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        }
        dao.deleteById(state.downloadId)
    }

    private fun Media.toSafeDownloadId(): DownloadId {
        return DownloadId(mediaId.replace(PATH_AFFECTING_CHARS_REGEX, "-"))
    }

    companion object {
        private val logger = logger<HttpMediaCacheEngine>()
        private val PATH_AFFECTING_CHARS_REGEX = Regex("[\\\\/:*?\"<>|]")

        @Deprecated("Use HttpMediaCacheEngine.MEDIA_CACHE_DIR instead")
        const val LEGACY_MEDIA_CACHE_DIR = "web-m3u-cache"
        const val MEDIA_CACHE_DIR = "web-m3u"
    }
}

internal fun DownloadStatus.toMediaCacheState(): MediaCacheState {
    return when (this) {
        DownloadStatus.DOWNLOADING,
        DownloadStatus.MERGING,
            -> MediaCacheState.IN_PROGRESS

        DownloadStatus.INITIALIZING,
        DownloadStatus.PAUSED,
            -> MediaCacheState.PAUSED

        DownloadStatus.FAILED,
        DownloadStatus.CANCELED,
            -> MediaCacheState.FAILED

        DownloadStatus.COMPLETED,
            -> MediaCacheState.COMPLETED
    }
}

internal fun DownloadProgress.toHttpCacheProgress(): Progress {
    if (status == DownloadStatus.COMPLETED) {
        return 1f.toProgress()
    }

    return when (mediaType) {
        MediaType.M3U8 -> {
            if (totalSegments <= 0) {
                Progress.Unspecified
            } else {
                (downloadedSegments.toFloat() / totalSegments.toFloat()).toProgress()
            }
        }

        MediaType.MP4,
        MediaType.MKV,
            -> {
            if (totalBytes <= 0L) {
                Progress.Unspecified
            } else {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).toProgress()
            }
        }
    }
}

private fun DownloadState.toFileType(): ResourceLocation.LocalFile.FileType? {
    return when {
        relativeOutputPath.endsWith(".ts", ignoreCase = true) -> ResourceLocation.LocalFile.FileType.MPTS
        else -> ResourceLocation.LocalFile.FileType.CONTAINED
    }
}
