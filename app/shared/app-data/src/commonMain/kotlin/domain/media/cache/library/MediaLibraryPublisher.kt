/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.isFinished
import me.him188.ani.app.domain.media.cache.location.MOVING_SUFFIX
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.copyTree
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.app.domain.media.download.DownloadSpaceGuard
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaExtraFiles
import me.him188.ani.datasources.api.Subtitle
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.usableSpace
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 把下完的缓存发布进片库: 复制到「缓存位置」那块盘 (新缓存下到哪, 见 [MediaCacheLocations.locationForNewCache]) 的公开片库里
 * (命名见 [MediaLibraryLayout], 本地存的外挂字幕放在视频旁边, 写说明文件; 别的盘上下完的跨盘复制),
 * 片库认下之后删掉原来的缓存 (只删缓存, 不删弹幕). 认下与删原缓存之间同一集两边各有一条, 下载那边的先列, 不会显示两份.
 *
 * 一份一份来, 任何一步出错这一份留在原处照常用, 下一遍再试 (复制了一半的临时文件下一遍开头清掉).
 * 下一遍发现片库里已经有这一集这个资源 (上次认下了、原缓存没删成) 时只删原缓存.
 * 视频最后改成正式的名字: 之前出错, 片库扫描看不到它; 改名之后说明文件已经在了, 扫描按说明文件认回原资源, 不会再发布一份.
 *
 * 跳过的: 在播的番 (同一部的任何一集, 播放占着原文件; 播完再发布), 没有条目或剧集 ID 的, 不是本地文件或扩展名认不出的,
 * 目标盘剩余空间不够时这一遍停下. 正在搬缓存 ([isMoving]) 时整遍不做. 种子缓存发布之后不再做种.
 */
class MediaLibraryPublisher(
    private val library: LibraryMediaCacheStorage,
    private val storages: () -> List<MediaCacheStorage>,
    private val locations: MediaCacheLocations,
    /** 这部番正在播 (条目 id) */
    private val isPlayingSubject: (subjectId: String) -> Boolean,
    /** 正在把缓存搬到别的位置 (MediaCacheMover) */
    private val isMoving: () -> Boolean,
    private val freeBytes: (SystemPath) -> Long? = { it.usableSpace() },
) {
    /** 一遍发布的结果 */
    data class Result(val published: Int, val failed: Int, val skipped: Int)

    /** 正在发布的那一份: [label] 是「番名 - 集序」 */
    data class Progress(val label: String, val bytesTotal: Long, val bytesDone: Long)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val lock = Mutex()

    /**
     * 在 [scope] 里一直跑: [changes] 每变一次 (缓存下完、播放停了、搬缓存结束、片库开关、权限、插拔盘) 稍等一下发布一遍.
     * 片库能用的位置变了 (打开、关掉、权限给了或收回) 时先让片库重扫.
     */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, changes: Flow<*>, settle: Duration = 3.seconds): Job = scope.launch {
        var lastRoots: Set<LibraryPlace>? = null
        changes.debounce(settle).conflate().collect {
            try {
                val roots = library.availableRoots().keys
                if (lastRoots != null && roots != lastRoots) library.refreshCache()
                lastRoots = roots
                publishCompleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to publish completed caches into the media library" }
            }
        }
    }

    /** 发布一遍: 片库没打开、没权限、缓存位置那块盘不能用或正在搬缓存时什么都不做 */
    suspend fun publishCompleted(): Result = lock.withLock {
        val (place, root) = target() ?: return@withLock Result(0, 0, 0)
        if (isMoving()) return@withLock Result(0, 0, 0)
        withContext(Dispatchers.IO_) { library.availableRoots().values.forEach { removeLeftovers(it) } }
        var published = 0
        var failed = 0
        var skipped = 0
        loop@ for (storage in storages()) {
            if (storage === library) continue
            for (cache in storage.listFlow.first()) {
                if (isMoving()) break@loop
                when (publishOne(storage, cache, place, root)) {
                    Outcome.PUBLISHED -> published++
                    Outcome.FAILED -> failed++
                    Outcome.SKIPPED -> skipped++
                    Outcome.FULL -> {
                        skipped++
                        break@loop
                    }

                    Outcome.NOT_CANDIDATE -> {}
                }
            }
        }
        Result(published, failed, skipped).also {
            if (it != Result(0, 0, 0)) logger.info { "Published completed caches into the media library: $it" }
        }
    }

    /**
     * 还没放进片库、可以放进去的缓存有多少 (片库没打开也算, 给打开前的确认框用; 在播的也算, 它们播完再放);
     * [otherDriveBytes] 是不在目标那块盘上、要跨盘复制的
     */
    data class Pending(val units: Int, val bytes: Long, val otherDriveBytes: Long = 0)

    /** 打开片库、缓存位置是 [targetLocation] 时会放进去的缓存. 没有「所有文件访问」时放不进去, 为 0 */
    suspend fun pending(targetLocation: String?): Pending {
        if (library.publicRoots().isEmpty()) return Pending(0, 0)
        var units = 0
        var bytes = 0L
        var otherDriveBytes = 0L
        for (storage in storages()) {
            if (storage === library) continue
            for (cache in storage.listFlow.first()) {
                val candidate = candidateOf(cache) ?: continue
                if (library.publishedEntry(cache.origin.mediaId, cache.metadata) != null) continue
                units++
                bytes += candidate.size
                if (cache.locationId != targetLocation) otherDriveBytes += candidate.size
            }
        }
        return Pending(units, bytes, otherDriveBytes)
    }

    /** 下完了、能放进片库: 有条目与剧集 ID、是扩展名认得的本地文件 */
    suspend fun isPublishable(cache: MediaCache): Boolean = candidateOf(cache) != null

    /** 发布到的片库: 缓存位置那块盘 (新缓存下到哪) 的公开片库; 片库没打开、没权限、那块盘不能写时为 `null` */
    private suspend fun target(): Pair<LibraryPlace, SystemPath>? {
        val location = locations.locationForNewCache()
        if (locations.locations.value.none { it.id == location && it.unusable == null }) return null
        val place = LibraryPlace(location, public = true)
        val root = library.availableRoots()[place] ?: return null
        return place to root
    }

    private enum class Outcome { PUBLISHED, FAILED, SKIPPED, FULL, NOT_CANDIDATE }

    /** 能放进片库的一份: 视频是 [source] */
    private class Candidate(val cached: CachedMedia, val source: Path, val extension: String, val size: Long)

    /** [cache] 能不能放进片库: 下完了、有条目与剧集 ID、是扩展名认得的本地文件 */
    private suspend fun candidateOf(cache: MediaCache): Candidate? {
        if (cache.isLocationUnavailable || cache.isDeleted.value || !MediaLibraryLayout.canPlace(cache.metadata)) return null
        if (!cache.isFinished()) return null
        val cached = cache.getCachedMedia()
        val source = (cached.download as? ResourceLocation.LocalFile)?.filePath?.let { Path(it) } ?: return null
        val extension = source.name.substringAfterLast('.', "").lowercase()
        if (extension !in MediaLibraryLayout.videoExtensions) return null
        val size = withContext(Dispatchers.IO_) { SystemFileSystem.metadataOrNull(source)?.size } ?: return null
        return Candidate(cached, source, extension, size)
    }

    private suspend fun publishOne(storage: MediaCacheStorage, cache: MediaCache, place: LibraryPlace, root: SystemPath): Outcome {
        val metadata = cache.metadata
        val candidate = candidateOf(cache) ?: return Outcome.NOT_CANDIDATE
        if (isPlayingSubject(metadata.subjectId)) return Outcome.SKIPPED
        val cached = candidate.cached
        val source = candidate.source
        val extension = candidate.extension

        library.publishedEntry(cache.origin.mediaId, metadata)?.let { entry ->
            if (library.rootOf(entry.place)?.resolve(entry.relativePath)?.exists() == true) return deleteOriginal(storage, cache)
        }
        val size = candidate.size
        val free = freeBytes(root)
        if (free != null && free - size < DownloadSpaceGuard.LOW_SPACE_BYTES) {
            logger.warn { "Only $free bytes left on ${place.location ?: "internal"}, not publishing ${cache.cacheId} ($size bytes)" }
            return Outcome.FULL
        }

        val save = MediaCacheSave(withoutLocalSubtitles(cache.origin), metadata, LibraryEngineKey)
        val localSubtitles = cached.extraFiles.subtitles.filter { it.uri.startsWith(FILE_SCHEME) }
        try {
            _progress.value = Progress("${MediaLibraryLayout.displayName(metadata)} - ${MediaLibraryLayout.sortText(metadata.episodeSort)}", size, 0)
            val relativePath = withContext(Dispatchers.IO_) {
                copyIntoLibrary(root, metadata, source, extension, localSubtitles, save) { done ->
                    _progress.value = _progress.value?.copy(bytesDone = done)
                }
            }
            library.adopt(place, relativePath, save)
            logger.info { "Published ${cache.cacheId} into the media library as $relativePath" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to publish ${cache.cacheId} into the media library, it stays where it was" }
            return Outcome.FAILED
        } finally {
            _progress.value = null
        }
        return deleteOriginal(storage, cache)
    }

    /** 片库认下了, 删掉原缓存. 这时候开始播这部了就先留着 (下一遍再删), 两边都有时列的是原缓存 */
    private suspend fun deleteOriginal(storage: MediaCacheStorage, cache: MediaCache): Outcome {
        if (isPlayingSubject(cache.metadata.subjectId)) return Outcome.SKIPPED
        return try {
            storage.delete(cache)
            Outcome.PUBLISHED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Published ${cache.cacheId} but failed to delete the original cache, retrying next time" }
            Outcome.FAILED
        }
    }

    companion object {
        /** [storages] 里下完了的缓存 (cacheId); 有缓存下完、新建或删掉时变. 给 [start] 当 changes 用 */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun completedCacheIds(storages: List<MediaCacheStorage>): Flow<Set<String>> {
            if (storages.isEmpty()) return flowOf(emptySet())
            return combine(storages.map { it.listFlow }) { lists -> lists.flatMap { it } }
                .flatMapLatest { caches ->
                    if (caches.isEmpty()) return@flatMapLatest flowOf(emptySet())
                    combine(caches.map { cache -> cache.state.map { state -> cache.cacheId.takeIf { state == MediaCacheState.COMPLETED } } }) {
                        it.filterNotNullTo(HashSet())
                    }
                }
                .distinctUntilChanged()
        }

        private val logger = logger<MediaLibraryPublisher>()
        private const val FILE_SCHEME = "file://"

        /** 说明文件里记的原资源去掉本地字幕 (那些是原缓存目录里的, 会跟着原缓存删掉; 片库里的按文件名找, 见 [librarySubtitlesOf]) */
        private fun withoutLocalSubtitles(origin: Media): Media {
            if (origin !is DefaultMedia || origin.extraFiles.subtitles.none { it.uri.startsWith(FILE_SCHEME) }) return origin
            return origin.copy(extraFiles = MediaExtraFiles(origin.extraFiles.subtitles.filterNot { it.uri.startsWith(FILE_SCHEME) }))
        }

        /**
         * 复制进片库, 返回相对片库根目录的路径. 视频先复制到以 `.` 开头的临时名字 (扫描不认), 说明文件与字幕写好后最后改成正式的名字.
         * 同一集已经有别的文件占着名字时用 ` (2)`、` (3)`.
         */
        private suspend fun copyIntoLibrary(
            root: SystemPath,
            metadata: MediaCacheMetadata,
            source: Path,
            extension: String,
            subtitles: List<Subtitle>,
            save: MediaCacheSave,
            onCopied: (done: Long) -> Unit,
        ): String {
            val dir = root.resolve(MediaLibraryLayout.subjectDirName(metadata))
            dir.createDirectories()
            var copy = 1
            while (dir.resolve("${MediaLibraryLayout.episodeBaseName(metadata, copy)}.$extension").exists()) copy++
            val base = MediaLibraryLayout.episodeBaseName(metadata, copy)
            val video = dir.resolve("$base.$extension")
            val tmp = dir.resolve(".${video.name}$MOVING_SUFFIX")
            var done = 0L
            // 改名之前出错时连说明文件与字幕一起清掉: 留着的话, 以后同名的另一份会被当成带着它们
            val written = mutableListOf(tmp, librarySidecarOf(video))
            try {
                copyTree(source, Path(tmp.absolutePath)) { bytes ->
                    done += bytes
                    onCopied(done)
                }
                writeLibrarySidecar(video, save)
                subtitles.forEachIndexed { index, subtitle ->
                    val file = Path(subtitle.uri.removePrefix(FILE_SCHEME))
                    if (!SystemFileSystem.exists(file)) return@forEachIndexed
                    val subtitleExtension = file.name.substringAfterLast('.', "").lowercase()
                        .takeIf { it in librarySubtitleExtensions } ?: return@forEachIndexed
                    val target = dir.resolve("$base.${subtitleTag(subtitles, index)}.$subtitleExtension")
                    written += target
                    copyTree(file, Path(target.absolutePath)) {}
                }
                tmp.moveTo(video)
            } catch (e: Throwable) {
                written.forEach { runCatching { it.delete() } }
                throw e
            }
            return "${dir.name}/${video.name}"
        }

        /** 字幕文件名里的标签: 语言代码 (只含英文字母、数字与 `-` `_` 时; 播放器按它认语言), 同一语言有几条时加序号; 否则是序号 */
        internal fun subtitleTag(subtitles: List<Subtitle>, index: Int): String {
            val language = subtitles[index].language
                ?.takeIf { it.isNotEmpty() && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' } }
                ?: return "${index + 1}"
            return if (subtitles.count { it.language == language } > 1) "$language-${index + 1}" else language
        }

        /** 上次没复制完留下的临时文件 (只有发布会写它们, 发布持着锁) */
        private fun removeLeftovers(root: SystemPath) {
            if (!root.exists() || !root.isDirectory()) return
            for (dir in root.list().map { it.inSystem }) {
                if (MediaLibraryLayout.parseSubjectDir(dir.name) == null || !dir.isDirectory()) continue
                for (file in dir.list().map { it.inSystem }) {
                    if (file.name.startsWith(".") && file.name.endsWith(MOVING_SUFFIX)) {
                        runCatching { file.delete() }
                        logger.info { "Removed an unfinished media library copy: ${file.absolutePath}" }
                    }
                }
            }
        }
    }
}
