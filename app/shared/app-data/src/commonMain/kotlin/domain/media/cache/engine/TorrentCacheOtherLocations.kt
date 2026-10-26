/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.torrent.api.reclaimTorrentSaveDir
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.actualSize
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.list
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private val logger = logger<TorrentMediaCacheEngine>()

/**
 * 启动清扫外接盘上没有记录用着的种子目录 (内部存储上的由 [TorrentMediaCacheEngine.deleteUnusedCaches] 按下载引擎列出的目录清).
 * 只扫接着的盘. 归属按数据库里的全部种子记录算, 不只这次恢复出来的: 恢复时盘还没接上的记录是占位, 盘随后接上时它的目录也不能当成没人用.
 * 删的时候先失效 fastresume 再删数据 (见 [reclaimTorrentSaveDir]).
 *
 * @param engineSaveDir 下载引擎在内部存储上的目录; 各位置根目录下放种子目录的那一层, 与引擎的 `pieces` 目录相对 [baseSaveDir] 的位置相同
 */
internal suspend fun TorrentCacheInfoDao.deleteUnusedTorrentDirsOnOtherLocations(
    locations: MediaCacheLocations,
    engineSaveDir: SystemPath,
    baseSaveDir: String,
) {
    val piecesDir = piecesRelativeDir(engineSaveDir.absolutePath, baseSaveDir) ?: return
    val rows = getAll().first()
    for (location in locations.locations.value) {
        if (location.id == null) continue
        val pieces = Path(location.root.absolutePath, piecesDir).inSystem
        if (!pieces.exists()) continue
        val owned = rows.filter { it.location == location.id }
            .mapTo(HashSet()) { Path(location.root.absolutePath, it.relativeDir).inSystem.absolutePath }
        for (child in pieces.list()) {
            val save = child.inSystem
            if (save.absolutePath in owned) continue
            logger.warn { "外接盘上的种子缓存目录没有匹配的 MediaCache, 释放 ${save.actualSize().bytes}: ${save.absolutePath}" }
            save.reclaimTorrentSaveDir { message, cause -> logger.warn(cause) { message } }
        }
    }
}

/** 放种子目录的那一层相对根目录的路径 (内部存储上是引擎目录下的 pieces, 见 AnitorrentTorrentDownloader) */
private fun piecesRelativeDir(engineDir: String, baseSaveDir: String): String? {
    val relative = engineDir.substringAfter(baseSaveDir)
    if (relative == engineDir) {
        logger.warn { "Engine dir $engineDir is not under $baseSaveDir, skipping other locations" }
        return null
    }
    return "$relative/pieces"
}
