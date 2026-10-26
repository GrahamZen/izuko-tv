/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.domain.media.cache.location.MediaCacheEngineMover
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveAbortedException
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveUnit
import me.him188.ani.app.domain.media.cache.location.copyTree
import me.him188.ani.app.domain.media.cache.location.movingName
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.httpdownloader.finalOutputRelativePath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.actualSize
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.inSystem

/** 在线源任务在 [MediaCacheLocations.moving] 里的键 */
internal fun httpMoveKey(downloadId: DownloadId): String = "http:${downloadId.value}"

/** [location] 位置上放在线源缓存的目录; 那个盘没接上时为 `null` */
internal fun httpCacheDirOf(saveDir: Path, locations: MediaCacheLocations, location: String?): Path? =
    if (location == null) saveDir else locations.rootOf(location)?.let { Path(it.absolutePath, HttpMediaCacheEngine.MEDIA_CACHE_DIR) }

/**
 * 在线源缓存的搬法: 一个任务的文件 (下完的视频、没下完的分段目录) 整个搬, 再让下载器改记下的位置. 在下载的先停下.
 * 外挂字幕一直在内部存储, 不搬.
 */
internal class HttpCacheMover(
    private val dao: HttpCacheDownloadStateDao,
    private val downloader: HttpDownloader,
    private val saveDir: Path,
    private val locations: MediaCacheLocations,
) : MediaCacheEngineMover {
    override suspend fun units(): List<MediaCacheMoveUnit> = dao.getAll().first().mapNotNull { state ->
        val dir = httpCacheDirOf(saveDir, locations, state.location) ?: return@mapNotNull null
        val files = filesOf(state).map { Path(dir, it) }.filter { SystemFileSystem.exists(it) }
        if (files.isEmpty()) return@mapNotNull null
        val id = state.downloadId
        MediaCacheMoveUnit(
            key = httpMoveKey(id),
            location = state.location,
            bytes = files.sumOf { it.inSystem.actualSize() },
            label = id.value,
            matches = { (it as? HttpMediaCacheEngine.HttpMediaCache)?.downloadId == id },
        )
    }

    override suspend fun move(unit: MediaCacheMoveUnit, target: String?, onCopied: (bytes: Long) -> Unit): List<Path> {
        val id = DownloadId(unit.label)
        val state = downloader.getState(id) ?: dao.getById(id)
            ?: throw MediaCacheMoveAbortedException("The record of $id is gone")
        if (id in downloader.getActiveDownloadIds()) downloader.pause(id)
        val sourceDir = httpCacheDirOf(saveDir, locations, state.location) ?: throw IOException("Location ${state.location} is not available")
        val targetDir = httpCacheDirOf(saveDir, locations, target) ?: throw IOException("Location $target is not available")
        val relativePaths = filesOf(state).filter { SystemFileSystem.exists(Path(sourceDir, it)) }

        val copied = mutableListOf<Path>()
        try {
            for (relative in relativePaths) {
                val dest = Path(targetDir, relative)
                val temp = movingName(dest)
                SystemFileSystem.deleteRecursively(temp)
                copied += temp
                copyTree(Path(sourceDir, relative), temp, onCopied = onCopied)
                SystemFileSystem.deleteRecursively(dest) // 上次没搬完留下的
                SystemFileSystem.atomicMove(temp, dest)
                copied += dest
            }
            // 搬的期间任务被删了 (占位上按了删除) 就不搬了
            if (!downloader.relocate(id, target)) throw MediaCacheMoveAbortedException("The record of $id was deleted while moving")
            downloader.getState(id)?.let { dao.upsert(it) }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                copied.forEach { SystemFileSystem.deleteRecursively(it) }
            }
            throw e
        }
        return relativePaths.map { Path(sourceDir, it) }
    }

    private fun filesOf(state: DownloadState): List<String> =
        listOf(state.relativeOutputPath, state.finalOutputRelativePath(), state.relativeSegmentCacheDir).distinct()
}
