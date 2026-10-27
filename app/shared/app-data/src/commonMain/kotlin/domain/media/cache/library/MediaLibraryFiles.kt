/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.Subtitle
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.isRegularFile
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.list
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeText
import kotlinx.io.files.Path

/** 片库这个「引擎」: 它的记录在缓存元数据里以此区分 (见 LibraryMediaCacheStorage) */
internal val LibraryEngineKey = MediaCacheEngineKey("library")

/** 片库里扫到的一个视频: 它在索引里的那条、缓存元数据 (说明文件里的, 或按名字拼的) 与文件 */
internal class ScannedLibraryFile(
    val entry: MediaLibraryIndexEntry,
    val save: MediaCacheSave,
    val file: SystemPath,
)

/**
 * 扫 [root] 这个片库 ([place]): 两层, 条目文件夹下的视频 (命名见 [MediaLibraryLayout]).
 * 说明文件读得到且编号对得上就用它记的原资源与剧集信息, 否则按名字拼一份 (原资源当成本地文件).
 * 同一集同一资源扫到两份时只留先扫到的那份.
 */
internal fun scanMediaLibrary(root: SystemPath, place: LibraryPlace): List<ScannedLibraryFile> {
    if (!root.exists() || !root.isDirectory()) return emptyList()
    val result = LinkedHashMap<Triple<String, String, String>, ScannedLibraryFile>()
    for (dir in root.list().map { it.inSystem }) {
        val subject = MediaLibraryLayout.parseSubjectDir(dir.name) ?: continue
        if (!dir.isDirectory()) continue
        for (file in dir.list().map { it.inSystem }) {
            val episode = MediaLibraryLayout.parseEpisodeFile(file.name) ?: continue
            if (!file.isRegularFile()) continue
            val relativePath = "${dir.name}/${file.name}"
            val save = readLibrarySidecar(file)
                ?.takeIf { it.metadata.subjectId == subject.subjectId && it.metadata.episodeId == episode.episodeId }
                ?.copy(engine = LibraryEngineKey)
                ?: synthesizedSave(file, relativePath, subject, episode)
            val key = Triple(save.origin.mediaId, subject.subjectId, episode.episodeId)
            if (key in result) continue
            result[key] = ScannedLibraryFile(
                MediaLibraryIndexEntry(save.origin.mediaId, subject.subjectId, episode.episodeId, place.location, relativePath, place.public),
                save,
                file,
            )
        }
    }
    return result.values.toList()
}

/** 删掉 [root] 下空了的条目文件夹 (视频都删了、搬走了的) */
internal fun removeEmptySubjectDirs(root: SystemPath) {
    if (!root.exists() || !root.isDirectory()) return
    for (dir in root.list().map { it.inSystem }) removeIfEmptySubjectDir(dir)
}

/** [dir] 是条目文件夹且空了时删掉 */
internal fun removeIfEmptySubjectDir(dir: SystemPath) {
    if (MediaLibraryLayout.parseSubjectDir(dir.name) == null || !dir.exists() || !dir.isDirectory()) return
    if (dir.list().isEmpty()) runCatching { dir.delete() }
}

/** 视频旁边的说明文件 */
internal fun librarySidecarOf(video: SystemPath): SystemPath = video.resolveSibling(video.name + MediaLibraryLayout.SIDECAR_SUFFIX)

/** 读说明文件; 没有、读不了 (没权限) 或坏了时为 `null` */
internal fun readLibrarySidecar(video: SystemPath): MediaCacheSave? {
    val sidecar = librarySidecarOf(video)
    return try {
        if (!sidecar.exists()) null else DataStoreJson.decodeFromString(MediaCacheSave.serializer(), sidecar.readText())
    } catch (e: Exception) {
        null
    }
}

/** 写说明文件 (先写临时名字再改名, 不留半截) */
internal fun writeLibrarySidecar(video: SystemPath, save: MediaCacheSave) {
    val sidecar = librarySidecarOf(video)
    val tmp = sidecar.resolveSibling(".${sidecar.name}.tmp")
    tmp.writeText(DataStoreJson.encodeToString(MediaCacheSave.serializer(), save))
    tmp.moveTo(sidecar)
}

/** 片库里认作外挂字幕的扩展名 (小写) */
internal val librarySubtitleExtensions: Set<String> = setOf("ass", "ssa", "srt", "vtt")

/** 视频旁边的外挂字幕文件: `<视频名去掉扩展名>.<标签>.<扩展名>` 或 `<视频名去掉扩展名>.<扩展名>` */
internal fun librarySubtitleFilesOf(video: SystemPath): List<SystemPath> {
    val dir = Path(video.absolutePath).parent?.inSystem ?: return emptyList()
    val prefix = video.name.substringBeforeLast('.') + "."
    return dir.list().map { it.inSystem }
        .filter { it.name.startsWith(prefix) && it.name.substringAfterLast('.').lowercase() in librarySubtitleExtensions }
        .sortedBy { it.name }
}

/** 视频旁边的外挂字幕 (见 [librarySubtitleFilesOf]); 文件名里的标签当语言 (纯数字的只是序号) */
internal fun librarySubtitlesOf(video: SystemPath): List<Subtitle> {
    val prefix = video.name.substringBeforeLast('.') + "."
    return librarySubtitleFilesOf(video).map { file ->
        val rest = file.name.removePrefix(prefix)
        val tag = rest.substringBeforeLast('.', "")
        Subtitle(
            uri = "file://" + file.absolutePath,
            mimeType = subtitleMimeTypeOf(rest.substringAfterLast('.').lowercase()),
            language = tag.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) },
            label = tag.takeIf { it.isNotEmpty() },
        )
    }
}

internal fun subtitleMimeTypeOf(extension: String): String? = when (extension) {
    "ass", "ssa" -> "text/x-ssa"
    "srt" -> "application/x-subrip"
    "vtt" -> "text/vtt"
    else -> null
}

/** 删掉片库里的一个视频连同说明文件与外挂字幕; 条目文件夹空了也删 */
internal fun deleteLibraryFile(video: SystemPath) {
    librarySubtitleFilesOf(video).forEach { it.delete() }
    video.delete()
    librarySidecarOf(video).delete()
    Path(video.absolutePath).parent?.inSystem?.let { removeIfEmptySubjectDir(it) }
}

/** 没有说明文件时按名字拼的缓存元数据: 原资源当成片库里的本地文件 (按路径区分, 同一集几份各是一条) */
private fun synthesizedSave(
    file: SystemPath,
    relativePath: String,
    subject: MediaLibraryLayout.SubjectDir,
    episode: MediaLibraryLayout.EpisodeFile,
): MediaCacheSave {
    val name = episode.subjectName ?: subject.subjectName ?: ""
    val sort = EpisodeSort(episode.sort ?: "")
    val metadata = MediaCacheMetadata(
        subjectId = subject.subjectId,
        episodeId = episode.episodeId,
        subjectNameCN = name.takeIf { it.isNotEmpty() },
        subjectNames = listOfNotNull(name.takeIf { it.isNotEmpty() }),
        episodeSort = sort,
        episodeName = "",
        creationTime = 0,
    )
    val origin = DefaultMedia(
        mediaId = "library:$relativePath",
        mediaSourceId = MediaDownloadManager.LOCAL_FS_MEDIA_SOURCE_ID,
        originalUrl = ResourceLocation.LocalFile(file.absolutePath).uri,
        download = ResourceLocation.LocalFile(file.absolutePath),
        originalTitle = file.name.substringBeforeLast('.'),
        publishedTime = 0,
        properties = MediaProperties(
            subjectName = name.takeIf { it.isNotEmpty() },
            episodeName = null,
            subtitleLanguageIds = emptyList(),
            resolution = "",
            alliance = "",
            size = file.length().bytes,
            subtitleKind = null,
        ),
        episodeRange = EpisodeRange.single(sort),
        location = MediaSourceLocation.Local,
        kind = MediaSourceKind.LocalCache,
    )
    return MediaCacheSave(origin, metadata, LibraryEngineKey)
}
