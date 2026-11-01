/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import androidx.datastore.core.DataStore
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheKey
import me.him188.ani.app.domain.media.cache.engine.TorrentMediaCacheEngine
import me.him188.ani.app.domain.media.cache.mediaCacheKey
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.coroutines.RestartableCoroutineScope
import me.him188.ani.utils.coroutines.update
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class TorrentMediaCacheStorage(
    override val mediaSourceId: String,
    private val store: DataStore<List<MediaCacheSave>>,
    private val torrentEngine: TorrentMediaCacheEngine,
    private val shareRatioLimitFlow: Flow<Float>,
    private val displayName: String,
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    engineAvailability: Flow<Boolean> = flowOf(true),
) : AbstractDataStoreMediaCacheStorage(
    mediaSourceId, store, torrentEngine, displayName, parentCoroutineContext,
) {
    private val statSubscriptionScope = RestartableCoroutineScope(scope.coroutineContext)

    /**
     * 每条缓存的统计订阅 Job: 删除时在同一临界区内摘除, 由后台的物理清理阶段 cancelAndJoin.
     *
     * 注意 cancelAndJoin 发生在逻辑删除**之后**, 所以它不是"行不被复活"的正确性前提 ——
     * 真正兜住的是 engine 侧的 owner token 与"行不存在就停写、绝不凭空重建"
     * ([TorrentMediaCacheEngine.TorrentMediaCache.subscribeStats] 会在服务重连时 upsertFile).
     * 本 map 的职责是让那个订阅确实停下来, 不再写入也不再占着 torrent 服务.
     */
    private val statJobs = MutableStateFlow(persistentMapOf<MediaCacheKey, Job>())

    /**
     * Locks access to mutable operations.
     */
    private val lock = Mutex()

    /** 同一时间只做一次恢复 (见 [refreshCache]): 不持 [lock] 的启动恢复与插拔盘、搬缓存触发的刷新同时跑, 后合并的会把先恢复出来的当成被换掉的去掉. 先拿它再拿 [lock] */
    private val refreshLock = Mutex()

    /** 不持锁的启动恢复与同一集的新建、删除怎么错开 */
    private val claims = TorrentRestoreClaims()

    private fun launchStatsSubscription(cache: TorrentMediaCacheEngine.TorrentMediaCache) {
        val job = statSubscriptionScope.launch {
            cache.subscribeStats(shareRatioLimitFlow)
        }
        // 复用已有记录时再订阅一次: 换下的那个要停掉, 否则它失去引用、删除时停不到, 一直占着 torrent 服务
        val previous = statJobs.value[cache.mediaCacheKey]
        statJobs.update { put(cache.mediaCacheKey, job) }
        previous?.cancel()
    }

    /**
     * App 必须先在启动时候恢复过一次之后才能 refresh caches
     */
    private val requestStartupRestore = Channel<Unit>(Channel.CONFLATED)

    private val startupRestored = CompletableDeferred<Unit>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previousConnection: Boolean? = null
            val serviceConnected = combine(torrentEngine.isServiceConnected, engineAvailability) { connected, available ->
                connected to available
            }.distinctUntilChanged().buffer(Channel.RENDEZVOUS).produceIn(this)

            while (true) {
                select<Unit> {
                    // 如果在 APP 启动时 serviceConnected 状态变了, 忽略处理
                    serviceConnected.onReceive {
                        val connectionUnchanged = previousConnection == it.first
                        previousConnection = it.first
                        if (!it.second) return@onReceive
                        if (!startupRestored.isCompleted) {
                            logger.warn { "Startup torrent cache restoration is not completed, skip restore on service connected." }
                            return@onReceive
                        }
                        logger.debug { "Refreshing torrent caches on service connection changed, connected: $it." }
                        if (connectionUnchanged) restoreMissingCaches() else refreshCache()
                    }

                    requestStartupRestore.onReceive {
                        logger.debug { "Restoring persisted torrent caches on startup." }
                        // 启动恢复失败不能拖垮本循环: 异常逃出去会让这个协程直接死掉, 之后服务
                        // 重连也不再刷新缓存列表 (整个进程内缓存页都不会再更新). 也必须放行
                        // startupRestored —— 否则后续 refreshCache 会被永久挡住, 连重试的机会都没有.
                        try {
                            refreshLock.withLock {
                                val allRecovered = refreshCacheUnlocked()
                                lock.withLock {
                                    // 清扫期间持 storage 锁, 防止 cache() 在快照之后创建目录又被本次清扫当作垃圾删除.
                                    // 快照是恢复出来的加上恢复期间新建的; 恢复期间删掉的目录由删除自己回收, 清扫照旧不碰
                                    val current = listFlow.value
                                    val inUse = allRecovered + current.filter { cache -> allRecovered.none { it === cache } }
                                    torrentEngine.deleteUnusedCaches(inUse)
                                    torrentEngine.discardFilesWithoutRecords(allRecovered.filter { cache -> current.any { it === cache } })
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to restore persisted torrent caches on startup" }
                        } finally {
                            startupRestored.complete(Unit)
                        }
                    }
                }
            }
        }
    }

    override suspend fun restorePersistedCaches() {
        requestStartupRestore.send(Unit)
    }

    private suspend fun restoreMissingCaches() = lock.withLock {
        // 可用性恢复只补回缺失记录, 已有下载继续使用其文件句柄和统计订阅.
        for (save in metadataFlow.first()) {
            if (listFlow.value.any { isSameMediaAndEpisode(it, save) }) continue
            restoreFile(save.origin, save.metadata) { cache ->
                listFlow.value = listFlow.value + cache
            }
        }
    }

    private suspend fun refreshCacheLocked(): List<MediaCache> {
        statSubscriptionScope.restart()
        statJobs.value = persistentMapOf()
        val previous = listFlow.value
        return super.refreshCache().also { closeReplacedTorrentCaches(previous, listFlow.value) }
    }

    override suspend fun refreshCache(): List<MediaCache> {
        return refreshLock.withLock { lock.withLock { refreshCacheLocked() } }
    }

    /**
     * 同 [refreshCacheLocked], 只在开头与末尾持锁, 启动恢复用. 被系统杀掉、覆盖安装之后种子没有新的续传数据,
     * 要把盘上已有的数据整个重新校验 (外接盘上 1 GB 十来分钟), 恢复要等它认完文件; 持锁等会挡住这段时间里的新建与删除.
     * 恢复期间新建的与删掉的由 [refreshCache] 的合并规则留下与去掉, 同一集由 [claims] 错开.
     */
    private suspend fun refreshCacheUnlocked(): List<MediaCache> {
        val previous = lock.withLock {
            statSubscriptionScope.restart()
            statJobs.value = persistentMapOf()
            claims.beginRestore(metadataFlow.first().map { MediaCacheKey(it.origin.mediaId, it.metadata) })
            listFlow.value.also { claims.unlockedRefreshBase = it }
        }
        try {
            val recovered = super.refreshCache()
            lock.withLock { closeReplacedTorrentCaches(previous, listFlow.value) }
            return recovered
        } finally {
            claims.unlockedRefreshBase = null
            claims.endRestore()
        }
    }

    override suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean {
        // 必须与 refreshCache/cache 互斥: 服务连接抖动触发的 refreshCache 可能持有删除前的 datastore 快照,
        // 恢复完成后会把刚删除的条目重新发布回 listFlow, 表现为 "删除无效".
        // 物理清理 (阶段 2) 放在锁外的后台: 它需要 torrent 服务, 服务不可用时会挂起,
        // 在持锁状态下等待会卡住所有 refreshCache 和后续删除.
        var restoring: MediaCacheKey? = null
        val removed = lock.withLock {
            val cache = listFlow.value.firstOrNull(predicate) ?: return false
            // 这一集正在恢复 (启动恢复不持锁, 见 init): 删掉现在这份, 恢复出来的那份又会放回列表. 等它恢复完再删
            if (claims.isBusy(cache.mediaCacheKey)) {
                restoring = cache.mediaCacheKey
                return@withLock null
            }
            // 阶段 1 (逻辑删除) 在返回前同步完成: 只动本地数据库, 立即完成, 不依赖 torrent 服务.
            // 不能交给后台 —— 后台要等服务冷启动 (数十秒), 期间进程退出或 storage 关闭会把
            // completed=true 的孤儿 DAO 行永久留下 (启动清扫只删磁盘不清 DAO, 重缓存不覆盖已有行),
            // 之后重缓存会被旧行判为"已完成"而交出不完整文件.
            //
            // 整个逻辑提交必须在 NonCancellable 内一次做完: 页面协程随时可能取消, 若在
            // "datastore 已删、DAO 行未删"之间被打断, 留下的就是上述孤儿行. 锁在外面获取,
            // 保持可取消 —— 否则等一个正在恢复缓存的 refreshCache 会变成不可取消的卡顿.
            //
            // 顺序刻意是 DAO 行 → datastore/list: 进程硬终止只可能停在两者之间, 而此序的中间
            // 态 (DAO 行已删、datastore 记录尚存) 是良性的 —— 那条记录不会被判成已完成, 最坏
            // 是下次启动按未完成恢复; 反序的中间态才会留下毒化重缓存的 completed 孤儿行.
            withContext(NonCancellable) {
                cache.deletePersistedRows()
                removeFirstFromListAndStore { it === cache }
                // stats Job 必须与缓存在同一个临界区内摘除: 所有插入 (cache()/restoreFile) 都持本锁,
                // 若锁外再查 map, 同一集的接任缓存可能已覆盖条目 —— 会误取消新 Job,
                // 而旧 Job 失去引用继续 upsertFile 复活刚删的行.
                val job = statJobs.value[cache.mediaCacheKey]
                statJobs.update { remove(cache.mediaCacheKey) }
                cache to job
            }
        }
        if (removed == null) {
            claims.awaitFree(checkNotNull(restoring))
            return deleteFirst(predicate)
        }
        val (cache, statJob) = removed
        // 阶段 2 (物理回收) 放进 storage 自己的 scope:
        // - 删除已完成 BT 缓存可能需要冷启动服务并等待会话关闭, 真机可耗时数十秒;
        // - 调用方通常是页面协程, 不能让删除按钮一直转圈, 也不能让页面取消中断清理;
        // - 同集在清理完成前重新缓存时, engine 会在目录锁内现查引用数, 自动放弃整目录回收;
        // - 失败或进程退出时只留下磁盘残留, 由下次启动清扫回收.
        scope.launch {
            try {
                statJob?.cancelAndJoin()
                cache.closeAndDeleteFiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete files of cache ${cache.cacheId}" }
            }
        }
        return true
    }

    // 暂停过的下载恢复出来仍暂停 (MediaCacheMetadata.paused)
    override suspend fun resumeRestored(cache: MediaCache) {
        if (cache.metadata.paused) cache.pause() else cache.resume()
    }

    override suspend fun restoreFile(
        origin: Media,
        metadata: MediaCacheMetadata,
        reportRecovered: suspend (MediaCache) -> Unit,
    ): MediaCache? = withContext(Dispatchers.IO_) {
        val key = MediaCacheKey(origin.mediaId, metadata)
        try {
            // 同一集正在新建, 或不持锁的启动恢复期间已经新建好了 (见 refreshCacheUnlocked): 用新建的那份, 不再恢复一份
            if (claims.isCreating(key)) return@withContext null
            val base = claims.unlockedRefreshBase
            if (base != null && listFlow.value.any { it.mediaCacheKey == key && base.none { old -> old === it } }) return@withContext null
            // 接着下载 (或保持暂停) 与统计订阅都做完才列出来: 列出来以后同一集的新建会直接复用它
            var recovered: MediaCache? = null
            val cache = super.restoreFile(origin, metadata) { recovered = it }

            when (cache) {
                is TorrentMediaCacheEngine.TorrentMediaCache -> {
                    logger.info { "Cache resumed: $cache, subscribe to media cache stats." }
                    cache.onMetadataUpdated = { persistMetadata(cache, it) }
                    launchStatsSubscription(cache)
                }

                else -> {
                    logger.info { "Cache resumed: $cache" }
                }
            }

            recovered?.let { reportRecovered(it) }
            cache
        } catch (e: Exception) {
            logger.error(e) { "Failed to restore cache for ${origin.mediaId}" }
            null
        } finally {
            claims.restored(key)
        }
    }

    override suspend fun cache(
        media: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        resume: Boolean
    ): TorrentMediaCacheEngine.TorrentMediaCache {
        var promoting = false
        val key = MediaCacheKey(media.mediaId, metadata)
        return (lock.withLock {
            // 这一集正在恢复 (启动恢复不持锁, 见 init; 排着还没轮到的也算): 等它恢复出来再复用, 不另开一份下载
            if (claims.isRestoring(key)) return@withLock null
            // 已存在同一资源同一剧集的记录时直接复用; 统计订阅只对新建的记录进行, 避免同一记录被重复订阅.
            val existing = listFlow.value.firstOrNull { isSameMediaAndEpisode(it, media, metadata) }
            if (existing == null && !claims.tryCreate(key)) return@withLock null
            promoting = existing != null && existing.metadata.autoCached && !metadata.autoCached
            val cache = try {
                existing ?: super.cache(media, metadata, episodeMetadata, false)
            } finally {
                if (existing == null) claims.created(key)
            }
            check(cache is TorrentMediaCacheEngine.TorrentMediaCache) { "Cache does not implement TorrentMediaCache." }

            if (existing == null) cache.onMetadataUpdated = { persistMetadata(cache, it) }
            launchStatsSubscription(cache)

            cache
        } ?: run {
            claims.awaitFree(key)
            return cache(media, metadata, episodeMetadata, resume)
        }).also {
            when {
                // 显式添加必须清掉 autoCached, 否则播放结束时这条记录会被当作自动记录一并删除,
                // 用户看到添加成功而下载消失. 启动恢复一类的 resume 不走这条路.
                promoting -> it.resumeByUser()
                resume -> it.resume()
            }
        }
    }

    override fun close() {
        torrentEngine.close()
        statSubscriptionScope.close()
        super.close()
    }

}
