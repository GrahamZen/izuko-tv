/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.migration

import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheEpisodeEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.engine.HttpMediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.torrent.anitorrent.AnitorrentTorrentDownloader
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 换分发包名时搬运缓存的清单: 旧包列出要搬的缓存记录与文件, 新包照着把文件取过来、把记录写进自己的库.
 *
 * 路径一律相对于各自的缓存根目录 (`MediaSaveDirProvider.saveDir`). 缓存的库记录本来就只存相对路径
 * ([TorrentCacheInfoEntity.relativeDir]、[DownloadState.relativeOutputPath]), 所以文件原样放到新包的
 * 缓存根目录下, 记录一个字都不用改.
 */
@Serializable
data class CacheMigrationManifest(
    val mediaCacheSaves: List<MediaCacheSave>,
    val torrentCaches: List<TorrentCache>,
    val torrentEpisodes: List<TorrentEpisode>,
    val httpDownloads: List<DownloadState>,
    val files: List<CacheFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    /** 可序列化的 [TorrentCacheInfoEntity]. 种子数据是二进制, 用 Base64 装进 JSON. */
    @Serializable
    data class TorrentCache(
        val mediaId: String,
        val torrentDataBase64: String,
        val relativeDir: String,
        val completed: Boolean,
        val pathInTorrent: String,
        val downloadSize: Long,
        val uploadSize: Long,
    ) {
        @OptIn(ExperimentalEncodingApi::class)
        fun toEntity() = TorrentCacheInfoEntity(
            mediaId = mediaId,
            torrentData = Base64.decode(torrentDataBase64),
            relativeDir = relativeDir,
            completed = completed,
            pathInTorrent = pathInTorrent,
            downloadSize = downloadSize,
            uploadSize = uploadSize,
        )

        companion object {
            @OptIn(ExperimentalEncodingApi::class)
            fun of(entity: TorrentCacheInfoEntity) = TorrentCache(
                mediaId = entity.mediaId,
                torrentDataBase64 = Base64.encode(entity.torrentData),
                relativeDir = entity.relativeDir,
                completed = entity.completed,
                pathInTorrent = entity.pathInTorrent,
                downloadSize = entity.downloadSize,
                uploadSize = entity.uploadSize,
            )
        }
    }

    /** 可序列化的 [TorrentCacheEpisodeEntity]. */
    @Serializable
    data class TorrentEpisode(
        val mediaId: String,
        val episodeId: String,
        val completed: Boolean,
        val pathInTorrent: String,
        val downloadSize: Long,
        val uploadSize: Long,
    ) {
        fun toEntity() = TorrentCacheEpisodeEntity(
            mediaId = mediaId,
            episodeId = episodeId,
            completed = completed,
            pathInTorrent = pathInTorrent,
            downloadSize = downloadSize,
            uploadSize = uploadSize,
        )

        companion object {
            fun of(entity: TorrentCacheEpisodeEntity) = TorrentEpisode(
                mediaId = entity.mediaId,
                episodeId = entity.episodeId,
                completed = entity.completed,
                pathInTorrent = entity.pathInTorrent,
                downloadSize = entity.downloadSize,
                uploadSize = entity.uploadSize,
            )
        }
    }

    /** 一个要搬的文件. [path] 相对于缓存根目录, 分隔符是 `/`. */
    @Serializable
    data class CacheFile(val path: String, val size: Long)
}

/**
 * 新包确认收到了的缓存. 旧包只忘掉这些 —— 不能凭"文件不在了"自己推断: 路径解析不了、目录本来就缺,
 * 看起来都像"搬走了", 那样会把没搬的记录一起删掉 (2026-09-22 真机踩到).
 */
@Serializable
data class CacheMigrationReceipt(
    val torrentMediaIds: List<String>,
    val httpDownloadIds: List<String>,
)

object CacheMigrationPlanner {
    /**
     * 旧包这边: 挑出要搬的缓存, 列出它们的文件.
     *
     * - BT 缓存全搬, 连同没下完的: 导出前先停掉旧包的 BT 引擎, 种子目录 (含续传数据) 不会再变,
     *   新包接着下即可.
     * - 网页缓存只搬下完的: 旧包进程一起来就会接着下没完成的任务 (`HttpMediaCacheEngine` 恢复时 resume),
     *   边下边搬会搬到写了一半的文件.
     * - 对不上下载记录的缓存条目不搬: 搬过去也恢复不出来.
     *
     * @param listFiles 列出某个相对路径 (文件或目录) 下的全部文件, 不存在时返回空
     */
    fun plan(
        saves: List<MediaCacheSave>,
        torrents: List<TorrentCacheInfoEntity>,
        episodes: List<TorrentCacheEpisodeEntity>,
        httpStates: List<DownloadState>,
        listFiles: (relativePath: String) -> List<CacheMigrationManifest.CacheFile>,
    ): CacheMigrationManifest {
        val torrentsById = torrents.associateBy { it.mediaId }
        val httpById = httpStates.associateBy { it.downloadId }

        val keptSaves = mutableListOf<MediaCacheSave>()
        val keptTorrents = linkedMapOf<String, TorrentCacheInfoEntity>()
        val keptHttp = linkedMapOf<DownloadId, DownloadState>()
        for (save in saves) {
            if (save.engine == MediaCacheEngineKey.WebM3u) {
                val state = listOf(
                    HttpMediaCacheEngine.downloadIdOf(save.origin, save.metadata),
                    HttpMediaCacheEngine.legacyDownloadIdOf(save.origin),
                ).firstNotNullOfOrNull { httpById[it] } ?: continue
                if (state.status != DownloadStatus.COMPLETED) continue
                keptSaves += save
                keptHttp[state.downloadId] = state
            } else {
                val torrent = torrentsById[save.origin.mediaId] ?: continue
                keptSaves += save
                keptTorrents[torrent.mediaId] = torrent
            }
        }

        val files = linkedMapOf<String, CacheMigrationManifest.CacheFile>()
        for (torrent in keptTorrents.values) {
            listFiles(normalize(torrent.relativeDir)).forEach { files[it.path] = it }
        }
        for (state in keptHttp.values) {
            for (relative in listOf(state.relativeOutputPath, state.relativeSegmentCacheDir)) {
                if (relative.isBlank()) continue
                listFiles(httpPath(relative)).forEach { files[it.path] = it }
            }
        }

        return CacheMigrationManifest(
            mediaCacheSaves = keptSaves,
            torrentCaches = keptTorrents.values.map(CacheMigrationManifest.TorrentCache::of),
            torrentEpisodes = episodes.filter { it.mediaId in keptTorrents }.map(CacheMigrationManifest.TorrentEpisode::of),
            httpDownloads = keptHttp.values.toList(),
            files = files.values.filter { isSafeRelativePath(it.path) }.sortedByDescending { it.isTorrentResumeData() },
        )
    }

    /**
     * 续传数据排在所有文件前面搬.
     *
     * 旧包的 BT 引擎万一在搬运中途又跑起来 (用户这时打开了旧版), 会接着往数据文件里写. 先取续传数据, 它记着
     * "已下完"的 piece 在之后才取的数据文件里都有; 先取数据文件的话, 新包会拿到一份声称下完了、数据却没搬过来的
     * 续传数据, 把缺块的文件当成完整的缓存播放.
     */
    private fun CacheMigrationManifest.CacheFile.isTorrentResumeData() =
        path.substringAfterLast('/') == AnitorrentTorrentDownloader.FAST_RESUME_FILENAME

    /**
     * 新包这边, 提交之前: 只留下文件真的搬到了的缓存.
     *
     * 旧包中途被卸载、空间不够时, 一部分文件没过来. 这些缓存的记录不写进库: 写了也只是一条点开就失败的缓存.
     * BT 缓存看种子目录在不在 (没下完的本来就只有一部分文件); 网页缓存看最终文件在不在.
     *
     * @param exists 相对路径 (文件或目录) 在新包的缓存根目录下是否存在
     */
    fun keepArrived(manifest: CacheMigrationManifest, exists: (relativePath: String) -> Boolean): CacheMigrationManifest {
        val torrents = manifest.torrentCaches.filter { exists(normalize(it.relativeDir)) }
        val torrentIds = torrents.mapTo(mutableSetOf()) { it.mediaId }
        val http = manifest.httpDownloads.filter { exists(httpPath(it.relativeOutputPath)) }
        val httpIds = http.mapTo(mutableSetOf()) { it.downloadId }
        val saves = manifest.mediaCacheSaves.filter { save ->
            if (save.engine == MediaCacheEngineKey.WebM3u) {
                HttpMediaCacheEngine.downloadIdOf(save.origin, save.metadata) in httpIds ||
                        HttpMediaCacheEngine.legacyDownloadIdOf(save.origin) in httpIds
            } else {
                save.origin.mediaId in torrentIds
            }
        }
        return manifest.copy(
            mediaCacheSaves = saves,
            torrentCaches = torrents,
            torrentEpisodes = manifest.torrentEpisodes.filter { it.mediaId in torrentIds },
            httpDownloads = http,
        )
    }

    /** [manifest] 里的缓存, 作为回执交给旧包. */
    fun receiptOf(manifest: CacheMigrationManifest) = CacheMigrationReceipt(
        torrentMediaIds = manifest.torrentCaches.map { it.mediaId },
        httpDownloadIds = manifest.httpDownloads.map { it.downloadId.value },
    )

    /** 旧包这边: [saves] 里除去回执中已被新包收下的缓存. */
    fun withoutReceived(saves: List<MediaCacheSave>, receipt: CacheMigrationReceipt): List<MediaCacheSave> {
        val torrentIds = receipt.torrentMediaIds.toSet()
        val httpIds = receipt.httpDownloadIds.toSet()
        return saves.filterNot { save ->
            if (save.engine == MediaCacheEngineKey.WebM3u) {
                HttpMediaCacheEngine.downloadIdOf(save.origin, save.metadata).value in httpIds ||
                        HttpMediaCacheEngine.legacyDownloadIdOf(save.origin).value in httpIds
            } else {
                save.origin.mediaId in torrentIds
            }
        }
    }

    /**
     * 库里存的相对路径规整成本清单用的形式.
     *
     * BT 缓存的 [TorrentCacheInfoEntity.relativeDir] 是拿绝对路径 `substringAfter(缓存根目录)` 截出来的,
     * **带前导 `/`** (`/anitorrent/pieces/…`). 不先去掉, 它过不了 [isSafeRelativePath], 结果一个文件都列不出来.
     */
    fun normalize(path: String): String = path.trim().trimStart('/', '\\')

    private fun httpPath(relative: String) = "${HttpMediaCacheEngine.MEDIA_CACHE_DIR}/${normalize(relative)}"

    /**
     * [path] 是不是一个老老实实待在缓存根目录里的相对路径: 非空, 不以分隔符或盘符开头, 没有 `.`、`..` 与空段.
     *
     * 旧包按这个路径把文件交出去、删掉, 新包按它写进自己的目录 —— 两边都必须先过这一关, 否则一个
     * `../` 就能读到或删掉缓存目录以外的东西.
     */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || ':' in path) return false
        return path.split('/', '\\').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
