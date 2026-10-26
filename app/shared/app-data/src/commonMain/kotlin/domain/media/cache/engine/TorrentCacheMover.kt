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
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.domain.media.cache.location.MediaCacheEngineMover
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveAbortedException
import me.him188.ani.app.domain.media.cache.location.MediaCacheMoveUnit
import me.him188.ani.app.domain.media.cache.location.copyTree
import me.him188.ani.app.domain.media.cache.location.movingName
import me.him188.ani.app.torrent.api.TORRENT_FAST_RESUME_FILENAME
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.actualSize
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem

/** BT 种子目录在 [MediaCacheLocations.moving] 里的键 */
internal fun torrentMoveKey(relativeDir: String): String = "bt:$relativeDir"

/**
 * BT 缓存的搬法: 一个种子目录整个搬 (合集的各集、别的数据源缓存的同一个种子都在里面), 记录的位置一起改.
 *
 * 没下完的种子不带续传数据 (fastresume) 过去: 搬之前会话虽已关掉, 复制时 BT 服务可能还在落最后几块;
 * 到新位置后由引擎按哈希重新校验一遍, 数据不会因此出错, 代价是第一次打开要校验. 全下完的 (没有会话) 照原样搬.
 */
internal class TorrentCacheMover(
    private val dao: TorrentCacheInfoDao,
    private val locations: MediaCacheLocations,
    private val isCloud: Boolean,
    private val engineSaveDir: () -> SystemPath,
    private val baseSaveDir: () -> String,
) : MediaCacheEngineMover {
    override suspend fun units(): List<MediaCacheMoveUnit> {
        if (isCloud) return emptyList() // 网盘引擎的文件只放内部存储
        val pieces = piecesRelativeDir(engineSaveDir().absolutePath, baseSaveDir())?.let(::normalized) ?: return emptyList()
        return dao.getAll().first()
            .filter { normalized(it.relativeDir).startsWith("$pieces/") }
            .groupBy { it.location to it.relativeDir }
            .mapNotNull { (key, rows) ->
                val (location, relativeDir) = key
                val root = locations.rootOf(location) ?: return@mapNotNull null
                val dir = Path(root.absolutePath, relativeDir).inSystem
                if (!dir.exists()) return@mapNotNull null
                val mediaIds = rows.mapTo(HashSet()) { it.mediaId }
                MediaCacheMoveUnit(
                    key = torrentMoveKey(relativeDir),
                    location = location,
                    bytes = dir.actualSize(),
                    label = relativeDir,
                    matches = { it.origin.mediaId in mediaIds },
                )
            }
    }

    override suspend fun move(unit: MediaCacheMoveUnit, target: String?, onCopied: (bytes: Long) -> Unit): List<Path> {
        val relativeDir = unit.label
        val sourceRoot = locations.rootOf(unit.location) ?: throw IOException("Location ${unit.location} is not available")
        val targetRoot = locations.rootOf(target) ?: throw IOException("Location $target is not available")
        val source = Path(sourceRoot.absolutePath, relativeDir)
        val dest = Path(targetRoot.absolutePath, relativeDir)
        val temp = movingName(dest)
        val rows = rowsOf(unit)
        if (rows.isEmpty()) throw MediaCacheMoveAbortedException("The records of $relativeDir are gone")
        val mediaIds = rows.mapTo(HashSet()) { it.mediaId }
        val episodes = dao.getAllEpisodes().first().filter { it.mediaId in mediaIds }
        val complete = episodes.isNotEmpty() && episodes.all { it.completed }

        var updated = false
        try {
            SystemFileSystem.deleteRecursively(temp)
            copyTree(source, temp, skip = { !complete && it.name == TORRENT_FAST_RESUME_FILENAME }, onCopied = onCopied)
            SystemFileSystem.deleteRecursively(dest) // 上次没搬完留下的
            SystemFileSystem.atomicMove(temp, dest)
            // 搬的期间记录被删了 (占位上按了删除) 就不搬了
            val current = rowsOf(unit)
            if (current.isEmpty()) throw MediaCacheMoveAbortedException("The records of $relativeDir were deleted while moving")
            for (row in current) {
                dao.upsert(row.copy(location = target))
                updated = true
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                SystemFileSystem.deleteRecursively(temp)
                if (!updated) SystemFileSystem.deleteRecursively(dest)
            }
            throw e
        }
        return listOf(source)
    }

    private suspend fun rowsOf(unit: MediaCacheMoveUnit) =
        dao.getAll().first().filter { it.relativeDir == unit.label && it.location == unit.location }

    private fun normalized(path: String) = path.replace('\\', '/').trimEnd('/')
}
