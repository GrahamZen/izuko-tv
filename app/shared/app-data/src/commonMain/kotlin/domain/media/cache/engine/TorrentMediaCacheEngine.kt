/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheFileEntity
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheKey
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.mediaCacheKey
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.TorrentMediaResolver
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.tools.toProgress
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.app.torrent.api.files.isFinished
import me.him188.ani.app.torrent.api.reclaimTorrentSaveDir
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.actualSize
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.isDirectory
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes


//private const val EXTRA_TORRENT_CACHE_FILE =
//    "torrentCacheFile" // MediaCache 所对应的视频文件. 该文件一定是 [EXTRA_TORRENT_CACHE_DIR] 目录中的文件 (的其中一个)

/**
 * 以 [TorrentEngine] 实现的 [MediaCacheEngine], 意味着通过 BT 缓存 media.
 * 为每个 [MediaCache] 创建一个 [TorrentSession].
 */
class TorrentMediaCacheEngine(
    /**
     * 创建的 [CachedMedia] 将会使用此 [mediaSourceId]
     */
    private val mediaSourceId: String,
    override val engineKey: MediaCacheEngineKey,
    val torrentEngine: TorrentEngine,
    private val engineAccess: TorrentEngineAccess,
    private val dao: TorrentCacheInfoDao,
    val flowDispatcher: CoroutineContext = Dispatchers.Default,
    private val baseSaveDirProvider: MediaSaveDirProvider,
    private val onDownloadStarted: suspend (session: TorrentSession) -> Unit = {},
) : MediaCacheEngine, AutoCloseable {
    companion object {
        private val logger = logger<TorrentMediaCacheEngine>()
        private val unspecifiedFileStatsFlow = flowOf(MediaCache.FileStats.Unspecified)
        private val unspecifiedSessionStatsFlow = flowOf(MediaCache.SessionStats.Unspecified)
        private val unspecifiedFileSizeFlow = flowOf(FileSize.Unspecified)

        const val LEGACY_MEDIA_CACHE_DIR = "torrent-caches"
    }

    val isServiceConnected = engineAccess.isServiceConnected

    /**
     * 同一 mediaId 的落库与清理互斥. 删除清理可能等待 torrent 服务很久 (真机实测过 29 秒),
     * 期间用户可以重新缓存同一 media; 清理若拿着等待前算好的"无人引用"快照, 会把新缓存刚写入的
     * 行和文件一并删掉. 所以引用计数必须在锁内现查现用, 破坏性动作也在锁内完成.
     *
     * 锁的层级: [dataLock] (mediaId) -> [dirLock] (relativeDir), 只能按这个顺序嵌套.
     * 目录引用是**跨 mediaId** 统计的 (同一种子被两个数据源收录时 mediaId 不同但目录相同),
     * 所以"计数 + 整目录删除"这对动作必须在目录锁内成对完成, 只有 mediaId 锁挡不住另一个
     * mediaId 的并发创建.
     *
     * 锁 map 随本引擎生命周期内见过的 mediaId/relativeDir 增长; 缓存数量相对很小, 先保持实现简单.
     */
    private val dataLocksGuard = SynchronizedObject()
    private val dataLocks = mutableMapOf<String, Mutex>()
    private fun dataLock(mediaId: String): Mutex = synchronized(dataLocksGuard) {
        dataLocks.getOrPut(mediaId) { Mutex() }
    }

    private val dirLocks = mutableMapOf<String, Mutex>()
    private fun dirLock(relativeDir: String): Mutex = synchronized(dataLocksGuard) {
        dirLocks.getOrPut(relativeDir) { Mutex() }
    }

    /**
     * 删除在等服务期间, 同一集可能被重新缓存并复用相同数据库主键. 每次创建/恢复都领取新的
     * 不可碰撞 token; 旧实例只能关闭自己的句柄, 不能再修改接任实例的行或文件.
     */
    internal class OwnerToken

    private val episodeOwners = mutableMapOf<MediaCacheKey, OwnerToken>()

    private fun claimEpisode(key: MediaCacheKey): OwnerToken = synchronized(dataLocksGuard) {
        OwnerToken().also { episodeOwners[key] = it }
    }

    private fun ownsEpisode(key: MediaCacheKey, token: OwnerToken): Boolean = synchronized(dataLocksGuard) {
        episodeOwners[key] === token
    }

    private fun releaseEpisode(key: MediaCacheKey, token: OwnerToken) = synchronized(dataLocksGuard) {
        if (episodeOwners[key] === token) {
            episodeOwners.remove(key)
        }
    }

    /**
     * 删除阶段 1 的结果: [owned] 为 `false` 表示本集已被新缓存接任, 未做任何破坏性动作;
     * [relativeDir] 为 `null` 表示父行缺失, 无法核对目录归属.
     */
    private class RowDeletionResult(val owned: Boolean, val relativeDir: String?)

    /**
     * 删除的阶段 1: 逻辑删除. 只动本地数据库, 不依赖 torrent 服务, 立即完成.
     *
     * 按集的文件行绝不能等慢速物理清理才删 (调用方会紧接着删掉 datastore 记录): 冷启动服务可能
     * 耗时数十秒, 期间进程退出或 scope 取消会把 `completed=true` 的孤儿行永久留下 —— 启动清扫只删
     * 磁盘目录不清 DAO, 重缓存也不覆盖已有行, [subscribeStats] 会信任旧行的 completed 直接判
     * 完成, 重启后 restore 还会把半下载的文件按本地完成快路径交出去.
     *
     * 种子级父行的整个生命周期都在本阶段的 [dataLock] 内: 它按 mediaId 记账, 而 mediaId 只由
     * dataLock 保护. 放到阶段 2 去删是错的 —— 那里只持有**旧** relativeDir 的 [dirLock], 与
     * "同一 mediaId 换了另一个种子 (因而是另一个目录) 重新缓存"完全不互斥, 可能删掉新缓存的
     * 父行: 父行一没, 新缓存的 dao.get 全线返回 null (stats 停写、重启不再恢复), 且
     * `countFilesByRelativeDir` 是 JOIN 父行的, 新目录从此不受引用计数保护, 别的集一删就会被
     * 整目录抹掉.
     *
     * 只取 [dataLock], **不取** [dirLock]: 后者会被阶段 2 的会话关闭 (最长 7.5 秒) 与递归删除
     * 长时间持有, 而本阶段是在调用方 (页面协程) 的 NonCancellable 区内同步执行的, 等在慢锁上
     * 会变成不可取消的卡顿. 删一个已无子行的父行不改变任何目录的引用计数 (它的贡献本就是 0),
     * 所以不需要目录锁.
     *
     * NonCancellable: 本阶段一旦开始必须做完, 半途取消同样会留下孤儿行.
     */
    private suspend fun deleteEpisodeRows(
        origin: Media,
        metadata: MediaCacheMetadata,
        ownerToken: OwnerToken,
    ): RowDeletionResult {
        val key = MediaCacheKey(origin.mediaId, metadata)
        val result = withContext(NonCancellable) {
            dataLock(origin.mediaId).withLock {
                if (!ownsEpisode(key, ownerToken)) {
                    // 本集已由新缓存接任: 不再动任何行, 调用方只需收尾旧句柄.
                    logger.info { "Cache re-created after this instance, skip deleting data: $key" }
                    return@withLock RowDeletionResult(owned = false, relativeDir = null)
                }
                dao.deleteFile(origin.mediaId, metadata.subjectId, metadata.episodeId)
                val relativeDir = dao.get(origin.mediaId)?.relativeDir
                // 本 mediaId 已无任何集引用时才删种子级父行. 见上: 必须在本锁内, 不能挪到阶段 2.
                if (dao.countFilesByMediaId(origin.mediaId) == 0) {
                    dao.deleteByMediaId(origin.mediaId)
                }
                RowDeletionResult(owned = true, relativeDir = relativeDir)
            }
        }
        // 只在本阶段成功后释放所有权: 中途抛异常时保留 token, 让幂等重试仍有权清理.
        // (失去所有权的情况本来就不持有 token, 这里是 identity 检查, 不会误删接任者的.)
        releaseEpisode(key, ownerToken)
        return result
    }

    /**
     * 删除的阶段 2: 关闭句柄并回收 [relativeDir] 这一个目录的物理空间. 可能需要冷启动 torrent
     * 服务, 可被取消, 失败或跳过都留给下次启动清扫. 只能在阶段 1 ([deleteEpisodeRows]) 成功
     * (owned) 后调用.
     *
     * 本阶段**不修改任何数据库行**: 它只持有旧目录的 [dirLock], 无法与"同一 mediaId 换目录重新
     * 缓存"互斥, 动行会误删新缓存的数据 (详见 [deleteEpisodeRows] 的说明).
     *
     * 引用计数在目录锁内现查现用: 阶段 1 之后同目录若被重新缓存, 计数非 0, 自动放弃整目录回收.
     */
    private suspend fun closeHandleAndReclaimDir(
        handle: TorrentFileHandle?,
        relativeDir: String?,
    ) {
        if (relativeDir == null) {
            // 父行缺失: 无法核对目录归属, 只关句柄; 物理残留交给启动清扫.
            if (handle == null) {
                logger.info { "Deleting torrent cache: No file selected" }
            } else {
                logger.info { "Closing torrent file handle only, torrent info is missing" }
                handle.close()
            }
            return
        }
        dirLock(relativeDir).withLock {
            when {
                handle == null -> {
                    logger.info { "Deleting torrent cache: No file selected" }
                }

                dao.countFilesByRelativeDir(relativeDir) == 0 -> {
                    // 服务侧代理同步执行; 返回时整目录删除已经结束. 会话仍被占用时 (如同种子的
                    // 磁力流播持有句柄) 会话侧会跳过并打 warn, 目录留给下次启动清扫 —— 单集文件
                    // 从不被单删, 跳过时磁盘与 piece 状态一致, 绝不能绕过会话直接删目录: 活会话
                    // 会重建文件/fastresume, 且随后的重缓存会按内容 hash 复用它的内存 piece 状态,
                    // 秒判完成交出稀疏坏文件. 彻底修法 (等会话完全移除后重查引用数再删) 需要
                    // 会话关闭信号的新 API, 与 force_recheck 一起另行立项.
                    logger.info { "Closing torrent file handle and deleting entire torrent" }
                    handle.closeAndDelete()
                }

                else -> {
                    // 不能删除单集文件: 同一个活跃 torrent 会话仍缓存着 piece 完成状态, 立即重新
                    // 缓存这一集时会复用该状态, 把被删文件按稀疏文件重建并秒判完成. 当前 anitorrent
                    // binding 没有 force_recheck, 只能保守保留单集文件, 等最后一个目录引用删除时
                    // 再整体回收空间.
                    logger.info {
                        "Closing torrent file handle only and retaining files, " +
                                "torrent is still referenced by other episodes"
                    }
                    handle.close()
                }
            }
        }
    }

    class FileHandle(val state: Flow<State?>) {
        val handle = state.map { it?.handle } // single emit
        val entry = state.map { it?.entry } // single emit
        val session = state.map { it?.session }

        suspend fun close() {
            handle.first()?.close()
        }

        class State(
            val session: TorrentSession,
            val entry: TorrentFileEntry?,
            val handle: TorrentFileHandle?,
        )
    }

    inner class TorrentMediaCache internal constructor(
        override val origin: Media,
        override val metadata: MediaCacheMetadata, // 注意, 我们不能写 check 检查这些属性, 因为可能会有旧版本的数据
        val fileHandle: FileHandle,
        /** 本实例创建/恢复时领取的所有权 token. */
        private val ownerToken: OwnerToken,
    ) : MediaCache {
        private val desiredState = MutableStateFlow(
            MediaCacheState.IN_PROGRESS,
        )
        private val deletionLock = Mutex()

        override suspend fun getCachedMedia(): CachedMedia {
            // 获取 cached media 不需要让 torrent engine 一直可用
            @OptIn(EnsureTorrentEngineIsAccessible::class)
            engineAccess.withServiceRequest("TorrentMediaCache#$this-getCachedMedia:${origin.mediaId}") {
                val file = fileHandle.handle.first()
                if (file != null && file.entry.isFinished()) {
                    val filePath = file.entry.resolveFile()
                    if (!filePath.exists()) {
                        error("TorrentFileHandle has finished but file does not exist: $filePath")
                    }
                    logger.info { "getCachedMedia: Torrent has already finished, returning file $filePath" }
                    return CachedMedia(
                        origin,
                        mediaSourceId,
                        download = ResourceLocation.LocalFile(filePath.toString()),
                    )
                } else {
                    logger.info { "getCachedMedia: Torrent has not yet finished, returning torrent" }
                    return CachedMedia(
                        origin,
                        mediaSourceId,
                        download = origin.download,
                    )
                }
            }
        }

        override val fileStats: Flow<MediaCache.FileStats> = fileHandle.entry.flatMapLatest { entry ->
            if (entry == null) return@flatMapLatest unspecifiedFileStatsFlow

            entry.fileStats.map { stats ->
                MediaCache.FileStats(
                    totalSize = entry.length.bytes,
                    downloadedBytes = stats.downloadedBytes.bytes,
                    downloadProgress = stats.downloadProgress.toProgress(),
                )
            }
        }.flowOn(flowDispatcher)

        override val sessionStats: Flow<MediaCache.SessionStats> = fileHandle.session.flatMapLatest { handle ->
            if (handle == null) return@flatMapLatest unspecifiedSessionStatsFlow
            handle.sessionStats
                .map { stats ->
                    if (stats == null) return@map MediaCache.SessionStats.Unspecified
                    MediaCache.SessionStats(
                        totalSize = stats.totalSizeRequested.bytes,
                        downloadedBytes = stats.downloadedBytes.bytes,
                        downloadSpeed = stats.downloadSpeed.bytes,
                        uploadedBytes = stats.uploadedBytes.bytes,
                        uploadSpeed = stats.uploadSpeed.bytes,
                        downloadProgress = stats.downloadProgress.toProgress(),
                    )
                }
        }.flowOn(flowDispatcher)

        override val state: Flow<MediaCacheState> =
            combine(desiredState, fileHandle.state, fileStats) { currentState, handleState, stats ->
                when {
                    handleState == null -> MediaCacheState.FAILED
                    stats.isDownloadFinished -> MediaCacheState.COMPLETED
                    currentState == MediaCacheState.PAUSED -> MediaCacheState.PAUSED
                    else -> MediaCacheState.IN_PROGRESS
                }
            }.flowOn(flowDispatcher)

        override suspend fun pause() {
            if (isDeleted.value) return
            desiredState.value = MediaCacheState.PAUSED
            fileHandle.handle.first()?.pause()
        }

        override suspend fun close() {
            if (isDeleted.value) return
            fileHandle.close()
        }

        override suspend fun resume() {
            if (isDeleted.value) return
            val file = fileHandle.handle.first()
            desiredState.value = MediaCacheState.IN_PROGRESS
            logger.info { "Resuming file: $file" }
            file?.resume(FilePriority.NORMAL)
        }

        override val isDeleted: MutableStateFlow<Boolean> = MutableStateFlow(false)

        private var rowDeletion: RowDeletionResult? = null

        override suspend fun deletePersistedRows() {
            deletionLock.withLock { ensureRowsDeletedLocked() }
        }

        private suspend fun ensureRowsDeletedLocked(): RowDeletionResult =
            rowDeletion ?: deleteEpisodeRows(origin, metadata, ownerToken).also { rowDeletion = it }

        override suspend fun closeAndDeleteFiles() = deletionLock.withLock {
            logger.info { "closeAndDeleteFiles is called" }
            if (isDeleted.value) return@withLock

            // 阶段 1: 逻辑删除, 不依赖服务 (通常已由 storage 在向 UI 返回前执行过, 幂等).
            // 置 isDeleted 必须在阶段 1 成功之后: 提前置位会让阶段 1 失败后的重试直接短路返回.
            val rows = ensureRowsDeletedLocked()
            isDeleted.value = true

            // 阶段 2: 物理回收, 可能需要等待 torrent 服务冷启动.
            @OptIn(EnsureTorrentEngineIsAccessible::class)
            engineAccess.withServiceRequest("TorrentMediaCache#$this-closeAndDeleteFiles:${origin.mediaId}") {
                logger.info { "Getting handle" }
                val handle = fileHandle.handle.first()
                if (!rows.owned) {
                    // 本集已由新缓存接任: 只收尾旧句柄, 不动文件或目录.
                    handle?.close()
                    return@withServiceRequest
                }
                closeHandleAndReclaimDir(handle, rows.relativeDir)
            }
        }

        /**
         * 在 dataLock→dirLock 内更新本集的文件行. 返回 `false` 表示行或父行已不存在
         * (缓存已被删除), 调用方应停止后续写入 —— 绝不能凭空重建, 会复活刚删掉的引用行.
         *
         * 文件行会参与种子目录的引用计数, 必须与删除的"计数后删目录"使用同一组锁,
         * 否则两者可以交错, 把刚增加引用的目录删掉.
         */
        private suspend fun updateFileRowLocked(
            update: (TorrentCacheFileEntity) -> TorrentCacheFileEntity,
        ): Boolean {
            if (isDeleted.value) return false
            return dataLock(origin.mediaId).withLock {
                // 只有当前 owner 允许写行: 旧 stats 订阅未及退出时不能污染接任缓存.
                if (!ownsEpisode(mediaCacheKey, ownerToken)) {
                    return@withLock false
                }
                val info = dao.get(origin.mediaId) ?: return@withLock false
                dirLock(info.relativeDir).withLock {
                    val row = dao.getFile(origin.mediaId, metadata.subjectId, metadata.episodeId)
                        ?: return@withLock false
                    dao.upsertFile(update(row))
                    true
                }
            }
        }

        /**
         * 订阅当前 TorrentMediaCache 的统计信息以更新它的 metadata.
         */
        suspend fun subscribeStats(shareRatioLimitFlow: Flow<Float>) {
            isServiceConnected.collectLatest { serviceStarted ->
                if (!serviceStarted) return@collectLatest
                // 已删除的缓存不能再 upsertFile: 会把 closeAndDeleteFiles 刚删掉的引用行复活.
                // 主保险是 storage 在删除前 cancelAndJoin 本订阅, 这里是二道防线.
                if (isDeleted.value) return@collectLatest

                coroutineScope {
                    val fileEntryFlow = fileHandle.entry.filterNotNull()
                        .shareIn(this, SharingStarted.Lazily, replay = 1)
                    val sessionStatsFlow = fileHandle.session.filterNotNull()
                        .flatMapLatest { it.sessionStats }.filterNotNull()
                        .shareIn(this, SharingStarted.Lazily, replay = 1)

                    val fileEntry = fileEntryFlow.first()

                    // 文件选择一完成就把 pathInTorrent 落库, 不等 file/session stats 就绪;
                    // 否则进程在 stats 就绪前退出会丢失已选择的文件路径, 下次无法走本地恢复快路径.
                    if (!updateFileRowLocked { it.copy(pathInTorrent = fileEntry.pathInTorrent) }) {
                        return@coroutineScope
                    }

                    val entryFileStats = fileEntry.fileStats.filterNotNull().first()
                    val sessionStats = sessionStatsFlow.first()

                    val currentShareRatioLimit = shareRatioLimitFlow.first()
                    val currentShareRatio = sessionStats.uploadedBytes /
                            entryFileStats.downloadedBytes.coerceAtLeast(1).toFloat()

                    // 按集读取文件行 (mediaId+subjectId+episodeId). 不能再用 mediaId 单键,
                    // 否则全集种子多集会互相覆盖 pathInTorrent. 行不存在 = 缓存已删, 停止订阅
                    // (行由 createCache/restore 同步补占位保证存在, 这里绝不重建).
                    val recordedCompleted = dao.getFile(origin.mediaId, metadata.subjectId, metadata.episodeId)
                        ?.completed ?: return@coroutineScope

                    val finished = recordedCompleted || // 已记录 true 表示已完成
                            (entryFileStats.isDownloadFinished && currentShareRatio >= currentShareRatioLimit) // 统计判断达到条件也是完成

                    // 无论如何都先更新一次数据 (已删除的除外, 见上)
                    if (!updateFileRowLocked {
                            it.copy(
                                pathInTorrent = fileEntry.pathInTorrent,
                                completed = finished,
                                downloadSize = entryFileStats.downloadedBytes,
                                uploadSize = sessionStats.uploadedBytes,
                            )
                        }
                    ) {
                        return@coroutineScope
                    }

                    // 如果种子任务已经完成了就不启动了
                    if (finished) {
                        logger.debug { "Cache task ${origin.mediaId} is already finished, ignore stats subscription." }
                        return@coroutineScope
                    }

                    // 最后一次有上传活动的时间
                    var lastUploadActivity = currentTimeMillis()
                    // 当更新完 metadata 后需要停止 stats collector
                    // 因为 TorrentMediaCache 没有订阅 metadata 的能力, 使用一个 flow 来辅助停止
                    val finishedFlow = MutableStateFlow(false) // always false initially

                    finishedFlow.collectLatest finished@{ f ->
                        if (f) {
                            logger.debug { "Cache task ${origin.mediaId} is finished, stop stats subscription." }
                            return@finished
                        }

                        logger.debug { "Subscribed stats of cache task ${origin.mediaId}." }

                        combine(
                            sessionStatsFlow,
                            fileEntryFlow.flatMapLatest { it.fileStats.filterNotNull() },
                            shareRatioLimitFlow,
                        ) task@{ sessionStats, fileStats, shareRatioLimit ->
                            if (!fileStats.isDownloadFinished) return@task

                            val shareRatio = sessionStats.uploadedBytes /
                                    fileStats.downloadedBytes.coerceAtLeast(1).toFloat()

                            // 没达到分享率才进入这里的逻辑, 达到分享率直接更新 metadata
                            if (shareRatio < shareRatioLimit) {
                                val currentTimeMillis = currentTimeMillis()

                                // 如果距离上次上传活动小于 10 分钟, 不能更新 metadata, 因为 10 分钟内还可能有上传
                                if (currentTimeMillis - lastUploadActivity < 10.minutes.inWholeMilliseconds) {
                                    // 如果有上传活动, 更新最后的活动时间
                                    if (sessionStats.uploadSpeed > 0L) {
                                        lastUploadActivity = currentTimeMillis
                                    }
                                    return@task
                                }
                                // 如果距离上次上传活动大于 10 分钟, 直接更新 metadata
                            }

                            if (!updateFileRowLocked {
                                    it.copy(
                                        pathInTorrent = fileEntry.pathInTorrent,
                                        completed = true,
                                        downloadSize = fileStats.downloadedBytes,
                                        uploadSize = sessionStats.uploadedBytes,
                                    )
                                }
                            ) {
                                // 行已被删除 (缓存已删), 没有可更新的东西了, 结束订阅.
                                finishedFlow.value = true
                                return@task
                            }

                            finishedFlow.value = true // side effect.
                        }.run {
                            try {
                                collect()
                            } catch (ex: CancellationException) {
                                logger.debug { "Stat subscription of cache task ${origin.mediaId} is cancelled." }
                                throw ex // re-throw it. 
                            }
                        }
                    }
                }
            }
        }

        override fun toString(): String {
            return "TorrentMediaCache(subjectName='${metadata.subjectNames.firstOrNull()}', " +
                    "episodeSort=${metadata.episodeSort}, " +
                    "episodeName='${metadata.episodeName}', " +
                    "origin.mediaSourceId='${origin.mediaSourceId}')"
        }
    }

    override val stats: Flow<MediaStats> = engineAccess.isServiceConnected
        .flatMapLatest { useEngine ->
            val finishedMediaStats = dao.getAllFiles().map { saveList ->
                var totalFinishedDownloaded = 0L.bytes
                var totalFinishedUploaded = 0L.bytes

                saveList.filter { it.completed }.forEach { save ->
                    val downloaded = save.downloadSize.bytes
                    val uploaded = save.uploadSize.bytes

                    if (downloaded != FileSize.Unspecified) totalFinishedDownloaded += downloaded
                    if (uploaded != FileSize.Unspecified) totalFinishedUploaded += uploaded
                }

                MediaStats(
                    uploaded = totalFinishedUploaded,
                    downloaded = totalFinishedDownloaded,
                    uploadSpeed = 0L.bytes,
                    downloadSpeed = 0L.bytes,
                )
            }

            if (!useEngine) {
                return@flatMapLatest finishedMediaStats
            }

            flow { emit(torrentEngine.getDownloader()) }
                .flatMapLatest {
                    combine(finishedMediaStats, it.totalStats) { finished, engineStats ->
                        MediaStats(
                            uploaded = engineStats.uploadedBytes.bytes + finished.uploaded,
                            downloaded = engineStats.downloadedBytes.bytes + finished.downloaded,
                            uploadSpeed = engineStats.uploadSpeed.bytes + finished.uploadSpeed,
                            downloadSpeed = engineStats.downloadSpeed.bytes + finished.downloadSpeed,
                        )
                    }
                }
        }
        .flowOn(flowDispatcher)

    override fun supports(media: Media): Boolean {
        return media.download is ResourceLocation.HttpTorrentFile
                || media.download is ResourceLocation.MagnetLink
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun restore(
        origin: Media,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext
    ): MediaCache? {
        if (!supports(origin)) throw UnsupportedOperationException("Media is not supported by this engine $this: ${origin.download}")
        val info = dao.get(origin.mediaId) ?: return null
        val data = info.torrentData

        // 21->22 迁移把按集表留空 (等 subscribeStats 异步自愈), 其间引用计数是 0. 而计数把守着
        // 文件/目录删除, 缺行会被误判成"无人引用" —— 升级后立刻删一集会删父行+整目录, 连累其余
        // 各集. 所以在发布这条缓存前先同步补上占位行 (镜像 createCache 的占位逻辑).
        // 目录锁内写: 占位行会改变跨 mediaId 的目录引用计数.
        // 每次 restore 都领取新 token; 往轮实例不再拥有这条数据库记录.
        val ownerToken = dataLock(origin.mediaId).withLock {
            dirLock(info.relativeDir).withLock {
                if (dao.getFile(origin.mediaId, metadata.subjectId, metadata.episodeId) == null) {
                    dao.upsertFile(
                        TorrentCacheFileEntity(
                            mediaId = origin.mediaId,
                            subjectId = metadata.subjectId,
                            episodeId = metadata.episodeId,
                        ),
                    )
                }
            }
            claimEpisode(MediaCacheKey(origin.mediaId, metadata))
        }

        val localFile = origin.resolveCompletedFromDataStore(metadata)
        if (localFile != null) {
            // 阶段 1 的结果由 LocalFileMediaCache 保证在阶段 2 之前恰好执行一次, 经此变量传递.
            var rowDeletion: RowDeletionResult? = null
            return LocalFileMediaCache(
                origin, metadata, localFile,
                onDeletePersistedRows = {
                    // token 在构造时捕获; 若本集已被新缓存接任, 阶段 1 会因失去所有权放弃.
                    rowDeletion = deleteEpisodeRows(origin, metadata, ownerToken)
                },
                onCloseAndDeleteFiles = {
                    // 阶段 1 由 LocalFileMediaCache 保证已成功执行 (失败会抛出, 不会走到这里).
                    val rows = checkNotNull(rowDeletion) { "Row deletion phase did not run" }
                    if (rows.owned) {
                        // 阶段 2: 已完成缓存不持有 torrent 句柄, 起临时会话拿句柄,
                        // 再决定整目录回收还是保留.
                        @OptIn(EnsureTorrentEngineIsAccessible::class)
                        engineAccess.withServiceRequest(
                            "LocalFileMediaCache#$this-closeAndDeleteFiles:${origin.mediaId}",
                        ) {
                            val handle = getFileHandle(
                                EncodedTorrentInfo.createRaw(data),
                                metadata,
                                coroutineContext,
                            ).handle.first()
                            closeHandleAndReclaimDir(handle, rows.relativeDir)
                        }
                    }
                },
            )
        }

        @OptIn(EnsureTorrentEngineIsAccessible::class)
        return engineAccess.withServiceRequest("TorrentMediaCacheEngine#$this-restore:${origin.mediaId}") {
            TorrentMediaCache(
                origin = origin,
                metadata = metadata,
                fileHandle = getFileHandle(EncodedTorrentInfo.createRaw(data), metadata, parentContext),
                ownerToken = ownerToken,
            )
        }
    }

    private suspend fun getFileHandle(
        encoded: EncodedTorrentInfo,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext,
    ): FileHandle {
        val downloader = torrentEngine.getDownloader()
        val res = kotlinx.coroutines.withTimeoutOrNull(30_000) {
            val session = downloader.startDownload(encoded, parentContext)
            logger.info { "$mediaSourceId: waiting for files" }
            onDownloadStarted(session)

            val files = session.getFiles()
            val selectedFile = TorrentMediaResolver.selectVideoFileEntry(
                files,
                { fileName },
                listOf(metadata.episodeName),
                episodeSort = metadata.episodeSort,
                episodeEp = metadata.episodeEp,
            )

            if (selectedFile == null) {
                logger.error {
                    """
                            $mediaSourceId: Selected null file to download. Diagnosis:
                            - Files: ${files.map { it.fileName }}
                            - Metadata: $metadata
                        """.trimIndent()
                }
            } else {
                // 打印文件路径而不是对象 (Android 上是 AIDL 代理, toString 无意义), 便于核对选中的是不是目标剧集.
                logger.info { "$mediaSourceId: Selected file to download: ${selectedFile.pathInTorrent}" }
            }

            val handle = selectedFile?.createHandle()
            if (handle == null) {
                session.closeIfNotInUse()
            }
            FileHandle.State(session, selectedFile, handle)
        }

        if (res == null) {
            logger.error { "$mediaSourceId: Timed out while starting download or selecting file. Returning null handle. episode name: ${metadata.episodeName}" }
        }

        return FileHandle(flowOf(res))
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext
    ): TorrentMediaCache {
        if (!supports(origin)) throw UnsupportedOperationException("Media is not supported by this engine $this: ${origin.download}")
        // 创建缓存需要保证 torrent engine 一直可用, 所以 getFileHandle 直接启动协程创建好缓存.
        @OptIn(EnsureTorrentEngineIsAccessible::class)
        engineAccess.withServiceRequest("TorrentMediaCacheEngine#$this-createCache:${origin.mediaId}") {
            val downloader = torrentEngine.getDownloader()
            val data = downloader.fetchTorrent(origin.download.uri)

            val relativeDir = downloader.getSaveDirForTorrent(data).absolutePath.let { path ->
                val stripped = path.substringAfter(baseSaveDirProvider.saveDir)
                if (path == stripped) {
                    throw UnsupportedOperationException(
                        "Failed to strip torrent save path of media ${origin.mediaId}, " +
                                "path: $path, base: ${baseSaveDirProvider.saveDir}",
                    )
                }
                stripped
            }

            // 与删除清理互斥: 清理在锁内现查引用计数并做破坏性动作, 这里的落库也要在同样的锁内
            // (含目录锁: 计数是跨 mediaId 按目录统计的), 否则"删除后立刻重新缓存"时新写入的行
            // 可能被旧清理按过期判定删掉. 落库后领取新 token, 让等待中的旧删除放弃.
            val ownerToken = dataLock(origin.mediaId).withLock {
                dirLock(relativeDir).withLock {
                    dao.upsert(
                        TorrentCacheInfoEntity(
                            mediaId = origin.mediaId,
                            torrentData = data.data,
                            relativeDir = relativeDir,
                        ),
                    )
                    // 按集占位行 (pathInTorrent/completed 由 subscribeStats 填充). 必须按集, 否则多集共用种子会串台.
                    if (dao.getFile(origin.mediaId, metadata.subjectId, metadata.episodeId) == null) {
                        dao.upsertFile(
                            TorrentCacheFileEntity(
                                mediaId = origin.mediaId,
                                subjectId = metadata.subjectId,
                                episodeId = metadata.episodeId,
                            ),
                        )
                    }
                }
                claimEpisode(MediaCacheKey(origin.mediaId, metadata))
            }

            return TorrentMediaCache(
                origin = origin,
                metadata = metadata,
                fileHandle = getFileHandle(data, metadata, parentContext),
                ownerToken = ownerToken,
            )
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun deleteUnusedCaches(all: List<MediaCache>) {
        // 只需要在删除缓存的时候 torrent engine 可用, 不需要保证一直可用
        @OptIn(EnsureTorrentEngineIsAccessible::class)
        engineAccess.withServiceRequest("TorrentMediaCacheEngine#$this-deleteUnusedCaches") {
            val downloader = torrentEngine.getDownloader()

            val allowedAbsolute = buildSet {
                dao.batchGet(all.map { it.origin.mediaId }).forEach {
                    add(Path(baseSaveDirProvider.saveDir).resolve(it.relativeDir).inSystem.absolutePath)
                    add(downloader.getSaveDirForTorrent(EncodedTorrentInfo.createRaw(it.torrentData)).absolutePath)
                }
            }

            withContext(Dispatchers.IO_) {
                val saves = downloader.listSaves()
                for (save in saves) {
                    if (save.absolutePath in allowedAbsolute) continue

                    // 单个目录出问题不能中断整个清扫: 抛出去会一路终止启动恢复流程
                    // (调用方连 startupRestored 都完成不了, 之后整个进程不再刷新缓存列表).
                    try {
                        reclaimUnusedSave(save)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to reclaim unused torrent save: ${save.absolutePath}" }
                    }
                }
            }
        }
    }

    /**
     * 回收一个无主的种子目录, 并如实记录结果.
     *
     * 大小统计只是日志用的可选信息: [actualSize] 会遍历整个目录树, 目录被并发删除、某个文件不可读
     * 都会抛异常, 绝不能因此中断回收或整个清扫. 也不能在真正删除之前就写"已释放" ——
     * [reclaimTorrentSaveDir] 可能因为 fastresume 删不掉而完全没有动这个目录.
     */
    private fun reclaimUnusedSave(save: SystemPath) {
        val size = runCatching { save.actualSize().bytes }.getOrNull()
        // 走统一的"先失效 fastresume 再删数据": 递归删除半途失败若留下陈旧 fastresume,
        // 用户在本次启动稍后重新缓存同一个种子时会秒判完成并拿到稀疏坏文件.
        val reclaimStarted = save.reclaimTorrentSaveDir { message, cause ->
            logger.warn(cause) { message }
        }
        val stillExists = runCatching { save.exists() }.getOrDefault(true)
        logger.warn {
            val sizeText = size?.toString() ?: "大小未知"
            when {
                reclaimStarted && !stillExists ->
                    "本地种子缓存文件未找到匹配的 MediaCache, 已释放 $sizeText: ${save.absolutePath}"

                reclaimStarted ->
                    "本地种子缓存文件未找到匹配的 MediaCache, 已部分释放 (最多 $sizeText), " +
                            "残留留待下次清扫: ${save.absolutePath}"

                else ->
                    "本地种子缓存文件未找到匹配的 MediaCache, 但本次未能回收 ($sizeText), " +
                            "留待下次清扫: ${save.absolutePath}"
            }
        }
    }

    override fun close() {
        torrentEngine.close()
    }

    private suspend fun Media.resolveCompletedFromDataStore(metadata: MediaCacheMetadata): SystemPath? {
        // relativeDir 是种子级 (mediaId); completed/pathInTorrent 是按集 (mediaId+subjectId+episodeId).
        val info = dao.get(mediaId) ?: return null
        val fileEntity = dao.getFile(mediaId, metadata.subjectId, metadata.episodeId) ?: return null

        if (!fileEntity.completed) return null
        val pathInTorrent = fileEntity.pathInTorrent.takeIf { it.isNotEmpty() } ?: return null

        val file = Path(baseSaveDirProvider.saveDir, info.relativeDir).resolve(pathInTorrent).inSystem
        if (!file.exists() || file.isDirectory()) {
            return null
        }

        return file
    }
}
