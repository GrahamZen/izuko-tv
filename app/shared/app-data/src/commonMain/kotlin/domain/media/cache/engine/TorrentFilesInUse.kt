/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private val logger = logger<TorrentMediaCacheEngine>()

/**
 * 种子目录 [relativeDir] (见 [TorrentCacheInfoEntity.relativeDir]) 里还有缓存记录用着的文件, 相对种子的路径.
 *
 * 按目录而不是按资源汇总: 同一个种子可能经不同数据源各缓存过几集, 它们共用目录与会话.
 * 只看数据库里的记录: 已下完的集恢复成本地文件, 不开种子句柄, 看句柄判断不出它还在用.
 * 有记录还没选定文件时返回 null —— 不知道它会用哪个.
 */
internal suspend fun TorrentCacheInfoDao.filesInUse(relativeDir: String): Set<String>? {
    val torrents = getAll().first().filter { it.relativeDir == relativeDir }
    val mediaIds = torrents.mapTo(HashSet()) { it.mediaId }
    val episodes = getAllEpisodes().first().filter { it.mediaId in mediaIds }
    val files = HashSet<String>()
    for (torrent in torrents) {
        val own = episodes.filter { it.mediaId == torrent.mediaId }
        // 已发布版本按资源记的文件, 迁成剧集记录之前只有它
        if (torrent.pathInTorrent.isNotEmpty()) files += torrent.pathInTorrent
        else if (own.isEmpty()) return null
        for (episode in own) {
            if (episode.pathInTorrent.isEmpty()) return null
            files += episode.pathInTorrent
        }
    }
    return files
}

/**
 * 一条剧集记录的行删掉之后, 处理它的文件句柄 [handle]. 同一个种子目录 [relativeDir] 里:
 * - 没有别的记录了: [TorrentFileHandle.closeAndDelete], 种子没人用时回收整个目录;
 * - 还有别的记录: [TorrentFileHandle.closeAndDiscard], 只丢这个文件的数据, 别的记录也用着这个文件时不丢;
 * - 有记录还没选定文件: 只关句柄, 什么都不删.
 */
internal suspend fun TorrentCacheInfoDao.releaseDeletedEpisodeFile(handle: TorrentFileHandle, relativeDir: String) {
    val inUse = filesInUse(relativeDir)
    when {
        inUse == null -> handle.close()
        inUse.isEmpty() -> handle.closeAndDelete()
        else -> handle.closeAndDiscard(inUse)
    }
}

/**
 * 丢掉 [caches] 里已经打开的种子中没有缓存记录用着的文件的数据. 旧版本删掉整季包里的一集时只关了句柄, 数据留在原处,
 * 要等整个种子删掉才回收; 启动恢复之后补做. 只按数据库里的记录判断 (见 [filesInUse]), 不看这次恢复出了哪些.
 * 全部下完、没有打开种子的不管.
 */
internal suspend fun TorrentCacheInfoDao.discardFilesWithoutRecords(caches: List<MediaCache>) {
    val byDir = caches.filterIsInstance<TorrentMediaCacheEngine.TorrentMediaCache>().groupBy { it.relativeDir }
    for ((relativeDir, group) in byDir) {
        try {
            val inUse = filesInUse(relativeDir) ?: continue
            if (inUse.isEmpty()) continue
            val session = group.firstNotNullOfOrNull { it.fileHandle.session.first() } ?: continue
            val discarded = session.discardFilesExcept(inUse)
            if (discarded > 0) logger.info { "Discarded the data of $discarded files without a cache record in $relativeDir" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to discard the files without a cache record in $relativeDir" }
        }
    }
}
