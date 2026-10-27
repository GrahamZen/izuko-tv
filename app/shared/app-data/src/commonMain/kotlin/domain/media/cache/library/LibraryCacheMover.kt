/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.location.MOVING_SUFFIX
import me.him188.ani.app.domain.media.cache.location.MediaCacheEngineMover
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveAbortedException
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveTarget
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveUnit
import me.him188.ani.app.domain.media.cache.location.copyTree
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.io.resolveSibling

/** 片库里的一个视频在 MediaCacheLocations.moving 里的键 */
internal fun libraryMoveKey(entry: MediaLibraryIndexEntry): String = "library:${entry.mediaId}:${entry.subjectId}:${entry.episodeId}"

/**
 * 片库里的视频的搬法: 一个视频 (连说明文件与外挂字幕) 是一份, 按同样的相对路径搬进目标位置的片库 —— 公开的还是应用目录里的按
 * [MediaCacheMoveTarget.libraryPublic] (`null` = 照旧). 目标处已经有同名的别的文件时用 ` (2)`、` (3)`.
 *
 * 视频先复制到以 `.` 开头的临时名字 (片库扫描不认), 说明文件与字幕写好后改成正式名字, 改本机索引; 原处的视频随即改成以 `.` 开头的名字,
 * 搬家重新恢复时扫描只看到新的那份, 原处的由搬家最后删掉, 条目文件夹空了随即删掉. 改名之前出错时目标处写了的都清掉, 原处不动.
 */
class LibraryCacheMover internal constructor(private val storage: LibraryMediaCacheStorage) : MediaCacheEngineMover {
    override suspend fun units(): List<MediaCacheMoveUnit> = withContext(Dispatchers.IO_) {
        storage.entries.mapNotNull { entry ->
            val root = storage.rootOf(entry.place) ?: return@mapNotNull null
            val video = root.resolve(entry.relativePath)
            if (!video.exists()) return@mapNotNull null
            MediaCacheMoveUnit(
                key = libraryMoveKey(entry),
                location = entry.location,
                bytes = (listOf(video) + companionsOf(video)).sumOf { it.length() },
                label = entry.relativePath,
                matches = { cache -> cache.isEntry(entry) },
                libraryPublic = entry.public,
            )
        }
    }

    override suspend fun move(unit: MediaCacheMoveUnit, target: String?, onCopied: (bytes: Long) -> Unit): List<Path> =
        move(unit, MediaCacheMoveTarget(target), onCopied)

    override suspend fun move(unit: MediaCacheMoveUnit, target: MediaCacheMoveTarget, onCopied: (bytes: Long) -> Unit): List<Path> {
        val entry = storage.entries.firstOrNull { libraryMoveKey(it) == unit.key }
            ?: throw MediaCacheMoveAbortedException("${unit.key} is no longer in the media library")
        val targetPlace = LibraryPlace(target.location, target.libraryPublic ?: entry.public)
        val sourceRoot = storage.rootOf(entry.place) ?: throw IOException("The media library at ${entry.place} is not available")
        val targetRoot = storage.rootOf(targetPlace) ?: throw IOException("The media library at $targetPlace is not available")
        return withContext(Dispatchers.IO_) {
            val video = sourceRoot.resolve(entry.relativePath)
            val companions = companionsOf(video)
            val relativePath = freeRelativePath(targetRoot, entry.relativePath)
            val targetVideo = targetRoot.resolve(relativePath)
            val tmp = targetVideo.resolveSibling(".${targetVideo.name}$MOVING_SUFFIX")
            val written = mutableListOf(tmp)
            try {
                copyTree(Path(video.absolutePath), Path(tmp.absolutePath)) { bytes -> onCopied(bytes) }
                val oldBase = video.name.substringBeforeLast('.')
                val newBase = targetVideo.name.substringBeforeLast('.')
                for (companion in companions) {
                    val copy = targetVideo.resolveSibling(newBase + companion.name.removePrefix(oldBase))
                    written += copy
                    copyTree(Path(companion.absolutePath), Path(copy.absolutePath)) { bytes -> onCopied(bytes) }
                }
                tmp.moveTo(targetVideo)
            } catch (e: Throwable) {
                written.forEach { runCatching { it.delete() } }
                throw e
            }
            storage.moved(entry, targetPlace, relativePath)
            // 原处的视频藏起来: 重新恢复时扫描只认新的那份
            val hidden = video.resolveSibling(".${video.name}$MOVING_SUFFIX")
            val source = if (runCatching { video.moveTo(hidden) }.isSuccess) hidden else video
            (listOf(source) + companions).map { Path(it.absolutePath) }
        }
    }

    /** 原处的条目文件夹空了就删 (公开片库在电脑上看时不留空文件夹) */
    override suspend fun afterSourcesDeleted(sources: List<Path>) {
        withContext(Dispatchers.IO_) {
            sources.firstOrNull()?.parent?.inSystem?.let { removeIfEmptySubjectDir(it) }
        }
    }

    private companion object {
        /** 视频旁边跟着它走的文件: 说明文件与外挂字幕 */
        fun companionsOf(video: SystemPath): List<SystemPath> =
            listOfNotNull(librarySidecarOf(video).takeIf { it.exists() }) + librarySubtitleFilesOf(video)

        /** [relativePath] 在 [root] 下没被占用时就用它, 否则在扩展名前加 ` (2)`、` (3)` */
        fun freeRelativePath(root: SystemPath, relativePath: String): String {
            if (!root.resolve(relativePath).exists()) return relativePath
            val dir = relativePath.substringBeforeLast('/', "")
            val name = relativePath.substringAfterLast('/')
            val base = name.substringBeforeLast('.')
            val extension = name.substringAfterLast('.', "")
            var copy = 2
            while (true) {
                val candidate = (if (dir.isEmpty()) "" else "$dir/") + "$base ($copy).$extension"
                if (!root.resolve(candidate).exists()) return candidate
                copy++
            }
        }

        fun MediaCache.isEntry(entry: MediaLibraryIndexEntry): Boolean =
            origin.mediaId == entry.mediaId && metadata.subjectId == entry.subjectId && metadata.episodeId == entry.episodeId
    }
}
