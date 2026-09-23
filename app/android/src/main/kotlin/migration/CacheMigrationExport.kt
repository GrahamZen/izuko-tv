/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.migration

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.dataStores
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationManifest
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationManifest.CacheFile
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationPlanner
import me.him188.ani.app.domain.media.cache.migration.CacheMigrationReceipt
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.torrent.service.AniTorrentService
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.core.Koin
import java.io.File

/**
 * 旧包 (跳板包) 这边的缓存导出, 由 [SettingsMigrationProvider] 调用, 调用方已经过签名校验.
 *
 * 新包按 [manifest] 逐个取文件, 每取完一个就让这边删掉 ([deleteFile]) —— 电视存储普遍很小, 先整份复制再删
 * 需要多出一倍空间, 逐个搬只需多出一个文件的空间.
 */
internal object CacheMigrationExport {
    private val logger = logger<CacheMigrationExport>()

    private fun baseDir(koin: Koin): File = File(koin.get<MediaSaveDirProvider>().saveDir)

    /** 要搬的缓存与文件, 见 [CacheMigrationPlanner.plan]. */
    suspend fun manifest(context: Context, koin: Koin): CacheMigrationManifest {
        val baseDir = baseDir(koin)
        val db = koin.get<AniDatabase>()
        return CacheMigrationPlanner.plan(
            saves = context.dataStores.mediaCacheMetadataStore.data.first(),
            torrents = db.torrentCacheInfoDao().getAll().first(),
            episodes = db.torrentCacheInfoDao().getAllEpisodes().first(),
            httpStates = db.httpCacheDownloadStateDao().getAll().first(),
        ) { relative -> listFiles(baseDir, relative) }
    }

    private fun listFiles(baseDir: File, relative: String): List<CacheFile> {
        val root = resolveInside(baseDir, relative) ?: return emptyList()
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { CacheFile(it.relativeTo(baseDir).invariantSeparatorsPath, it.length()) }
            .toList()
    }

    /**
     * 把相对路径解析成缓存根目录里的文件; 越出根目录 (`..`、符号链接) 的一律返回 `null`.
     */
    fun resolveInside(baseDir: File, relative: String): File? {
        if (!CacheMigrationPlanner.isSafeRelativePath(relative)) return null
        val base = baseDir.canonicalFile
        val file = File(base, relative).canonicalFile
        return file.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    fun openFile(koin: Koin, relative: String): File? =
        resolveInside(baseDir(koin), relative)?.takeIf { it.isFile }

    /** 删掉一个已被新包取走的文件, 顺手删掉因此变空的上级目录 (到缓存根目录为止). */
    fun deleteFile(koin: Koin, relative: String): Boolean {
        val base = baseDir(koin).canonicalFile
        val file = resolveInside(base, relative) ?: return false
        if (file.exists() && !file.delete()) return false
        var dir = file.parentFile
        while (dir != null && dir != base && dir.path.startsWith(base.path) && dir.list()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile
        }
        return true
    }

    /**
     * 停掉 BT 引擎: 它在后台可能还作为前台服务在下载或做种, 会一直改写种子目录里的文件与续传数据.
     *
     * 先请它自己退出: onDestroy 里先把各会话的进度写进续传数据再关 (见 AniTorrentService), 没下完的缓存
     * 搬过去才能从停下的地方接着下. 等不到它退出才直接结束那个进程 —— 这时上次写入之后下的那段进度会丢,
     * 新包重下. 旧包这时在后台, 界面不在前台, 服务不会被重新拉起来 (见 TorrentServiceConnectionManager:
     * 应用不在前台时不保持服务).
     */
    fun stopTorrentEngine(context: Context) {
        runCatching { context.stopService(Intent(context, AniTorrentService.actualServiceClass)) }
            .onFailure { logger.warn(it) { "migration: 停止 BT 服务失败" } }
        // onDestroy 里写进度最多等 2 秒、关会话最多等 3 秒
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && torrentProcessPid(context) != null) {
            Thread.sleep(100)
        }
        torrentProcessPid(context)?.let {
            logger.info { "migration: BT 服务没有自行退出, 结束进程 $it" }
            Process.killProcess(it)
        }
    }

    private fun torrentProcessPid(context: Context): Int? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return am.runningAppProcesses?.firstOrNull { it.processName.endsWith(":torrent_service") }?.pid
    }

    /**
     * 新包收下之后: 忘掉[回执][receipt]里的缓存记录, 让旧包 (用户还没卸载时) 不再列出一堆打不开的缓存.
     *
     * 只按新包的回执来, 不自己看"文件还在不在": 路径解析不了、目录本来就缺都会被当成"搬走了",
     * 没搬的记录跟着一起没了 (2026-09-22 真机踩到).
     */
    suspend fun forgetReceived(context: Context, koin: Koin, receipt: CacheMigrationReceipt) {
        val db = koin.get<AniDatabase>()
        var before = 0
        var after = 0
        context.dataStores.mediaCacheMetadataStore.updateData { saves ->
            before = saves.size
            CacheMigrationPlanner.withoutReceived(saves, receipt).also { after = it.size }
        }
        receipt.torrentMediaIds.forEach { mediaId ->
            db.torrentCacheInfoDao().deleteByMediaId(mediaId)
            db.torrentCacheInfoDao().getAllEpisodes().first()
                .filter { it.mediaId == mediaId }
                .forEach { db.torrentCacheInfoDao().deleteEpisode(it.mediaId, it.episodeId) }
        }
        receipt.httpDownloadIds.forEach { db.httpCacheDownloadStateDao().deleteById(DownloadId(it)) }
        logger.info { "migration: 旧包按回执忘掉 ${before - after} 条缓存 (BT ${receipt.torrentMediaIds.size}, 网页 ${receipt.httpDownloadIds.size})" }
    }
}
