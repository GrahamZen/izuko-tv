/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.torrent.api

import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.resolve
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


/**
 * 下载管理器, 支持根据磁力链解析[种子信息][EncodedTorrentInfo], 然后根据种子信息创建下载会话 [TorrentSession].
 *
 * Must be closed when it is no longer needed.
 */
interface TorrentDownloader : AutoCloseable {
    /**
     * @see TorrentSession.Stats for comments
     */
    data class Stats(
        val totalSize: Long,
        /**
         * 所有文件的下载字节数之和.
         */
        val downloadedBytes: Long,
        /**
         * Bytes per second.
         */
        val downloadSpeed: Long,
        val uploadedBytes: Long,
        /**
         * Bytes per second.
         */
        val uploadSpeed: Long,
        /**
         * Bytes per second.
         */
        val downloadProgress: Float,
    )

    /**
     * 所有任务合计的统计信息.
     * @see TorrentSession.sessionStats
     */
    val totalStats: Flow<Stats>

    /**
     * Details about the underlying torrent library.
     */
    val vendor: TorrentLibInfo

    /**
     * Fetches a magnet link.
     *
     * @param uri supports magnet link or http link for the torrent file
     *
     * @throws FetchTorrentTimeoutException if timeout has been reached.
     */
    suspend fun fetchTorrent(uri: String, timeoutSeconds: Int = 60): EncodedTorrentInfo

    /**
     * Starts download of a torrent using the torrent data.
     *
     * This function may involve I/O operation e.g. to compare with local caches.
     */
    suspend fun startDownload(
        data: EncodedTorrentInfo,
        parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): TorrentSession

    fun getSaveDirForTorrent(
        data: EncodedTorrentInfo,
    ): SystemPath

    /**
     * 获取所有的种子保存目录列表
     */
    fun listSaves(): List<SystemPath>

    override fun close()
}

class FetchTorrentTimeoutException(
    override val message: String? = "Magnet fetch timeout",
    override val cause: Throwable? = null
) : Exception()

/**
 * 种子保存目录内记录 piece 完成状态的文件名. 下次打开同一个种子时会被加载, 用于跳过校验.
 */
const val TORRENT_FAST_RESUME_FILENAME = "fastresume"

/**
 * 回收一个种子保存目录: **先失效 fastresume, 再删数据文件**.
 *
 * 顺序是正确性要求, 不是风格问题. 递归删除不是原子的, 中途可能因文件被占用等原因失败, 于是有三种
 * 可能的残留状态:
 * - fastresume 已删 + 数据残缺: 安全. 下次打开这个种子会全量 recheck, 残缺部分重新下载.
 * - fastresume 与数据都完好: 安全. 相当于没删.
 * - **fastresume 完好 + 数据残缺: 会坏**. 新会话信任陈旧的 piece 位图, 秒判"已完成", 把被删的
 *   文件按稀疏文件重建后交给播放器 —— 真机上表现为"重新缓存立刻完成但播放失败". 先删 fastresume
 *   就是为了让这个状态不可能出现.
 *
 * 因此 fastresume 删不掉时**一个数据文件都不能碰**, 整个目录留给下一次清扫重试.
 *
 * @param onWarn 记录失败原因; 删除失败不抛出 —— 文件被占用属于可容忍情况, 留着即可.
 * @return 是否已经开始删除数据文件 (即 fastresume 已经失效). `false` 表示本次完全没有动这个目录.
 */
fun SystemPath.reclaimTorrentSaveDir(onWarn: (message: String, cause: Throwable?) -> Unit): Boolean {
    val fastResumeFile = resolve(TORRENT_FAST_RESUME_FILENAME)
    try {
        if (fastResumeFile.exists()) {
            fastResumeFile.delete()
        }
    } catch (e: Exception) {
        onWarn(
            "Failed to delete fast resume file ${fastResumeFile.absolutePath}, " +
                    "skipping deletion of torrent data to avoid stale piece state",
            e,
        )
        return false
    }

    try {
        deleteRecursively()
    } catch (e: Exception) {
        onWarn("Failed to delete torrent save directory $absolutePath", e)
    }
    return true
}

/**
 * 用于下载 `https://xxx.torrent`
 */
interface HttpFileDownloader : AutoCloseable {
    suspend fun download(url: String): ByteArray
}

class TorrentDownloaderConfig(
    val peerFingerprint: String = "-AL4000-",
    val userAgent: String = "ani_libtorrent/3.0.0", // "libtorrent/2.1.0.0", "ani_libtorrent/3.0.0"
    val handshakeClientVersion: String? = "3.0.0",
    /**
     * 0 means unlimited
     */
    val downloadRateLimitBytes: Int = 0,
    /**
     * 0 means unlimited
     */
    val uploadRateLimitBytes: Int = 0,
    /**
     * share ratio limit, 100 = 1.0
     */
    val shareRatioLimit: Int = 200,
    /**
     * 额外的 tracker 服务器, 将在 BT 下载开始前与内置 tracker 一起添加
     */
    val extraTrackers: List<String> = emptyList(),
)