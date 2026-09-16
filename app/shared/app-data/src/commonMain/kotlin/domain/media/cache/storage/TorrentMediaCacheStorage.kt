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
import kotlin.time.Duration.Companion.minutes

class TorrentMediaCacheStorage(
    override val mediaSourceId: String,
    private val store: DataStore<List<MediaCacheSave>>,
    private val torrentEngine: TorrentMediaCacheEngine,
    private val shareRatioLimitFlow: Flow<Float>,
    private val displayName: String,
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractDataStoreMediaCacheStorage(mediaSourceId, store, torrentEngine, displayName, parentCoroutineContext) {
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

    private fun launchStatsSubscription(cache: TorrentMediaCacheEngine.TorrentMediaCache) {
        val job = statSubscriptionScope.launch {
            cache.subscribeStats(shareRatioLimitFlow)
        }
        statJobs.update { put(cache.mediaCacheKey, job) }
    }

    /**
     * App 必须先在启动时候恢复过一次之后才能 refresh caches
     */
    private val requestStartupRestore = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val startupRestored = CompletableDeferred<Unit>()
            val serviceConnected = torrentEngine.isServiceConnected.buffer(Channel.RENDEZVOUS).produceIn(this)

            while (true) {
                select<Unit> {
                    // 如果在 APP 启动时 serviceConnected 状态变了, 忽略处理
                    serviceConnected.onReceive { connected ->
                        if (!startupRestored.isCompleted) {
                            logger.warn { "Startup torrent cache restoration is not completed, skip restore on service connected." }
                            return@onReceive
                        }
                        // 连上 = 服务刚重新起来, 会话随旧进程一起没了 —— 这是除启动外唯一能安全清扫的时机, 顺手把
                        // 上一轮删剩的垃圾收掉 (删除当时目录常被同种子的别的集占着, 回收会被跳过, 以前只能等下次启动;
                        // 开着「退出后保留」时进程不退, 就一直等不到).
                        //
                        // connected 会变 false 的三种情况:
                        // 1. `onServiceDisconnected` —— 服务进程真的没了;
                        // 2. Android 15+ 的前台服务超时 —— 服务在 `onTimeout` 里发广播的同时就 `stopSelf()` 了;
                        // 3. 主进程被回收后由常驻服务拉起来 (新进程初始就是 false), 这时**服务进程可能还活着**.
                        // 前两种旧会话确实没了; 第三种旧会话还在, 但它们对应的缓存记录也都还在 (记录是持久化的),
                        // 于是都落在白名单里, 不会被删 —— 真正危险的"记录已删、会话还活着"在启动清扫里同样存在,
                        // 不是这条路径独有的.
                        if (connected) {
                            try {
                                // 启动那次刚扫完, 服务随即连上又来一次 —— 后一次必然什么都扫不到, 白白多一次遍历与删目录的机会
                                if (System.currentTimeMillis() - lastSweepAtMillis < SWEEP_DEBOUNCE.inWholeMilliseconds) {
                                    logger.debug { "Refreshing torrent caches on service connected (swept just now, skipping sweep)." }
                                    refreshCache()
                                } else {
                                    refreshAndSweep("service restarted")
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to sweep unused torrent caches after service restart" }
                            }
                        } else {
                            logger.debug { "Refreshing torrent caches on service connection changed, connected: false." }
                            refreshCache()
                        }
                    }

                    requestStartupRestore.onReceive {
                        // 启动恢复失败不能拖垮本循环: 异常逃出去会让这个协程直接死掉, 之后服务
                        // 重连也不再刷新缓存列表 (整个进程内缓存页都不会再更新). 也必须放行
                        // startupRestored —— 否则后续 refreshCache 会被永久挡住, 连重试的机会都没有.
                        try {
                            refreshAndSweep("startup")
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

    /**
     * 恢复缓存列表, 顺带把磁盘上没人认领的种子目录收掉.
     *
     * **只能在"没有任何活跃会话引用那些目录"时调**. 现在只有两个这样的时机: app 启动, 和 torrent 服务进程重启后
     * (会话随旧进程一起消失). 运行中随便扫会把活会话正在用的目录删掉, 会话随后把文件与 fastresume 重建成稀疏的,
     * 之后重新缓存按内存里的 piece 状态秒判完成, 交出一个"显示已完成、一播就报 Source error"的坏文件
     * —— 2026-09-15 真机踩过, 起因见 [TorrentMediaCacheEngine] `closeHandleAndReclaimDir` 的注释.
     *
     * 恢复出来的 `allRecovered` 是清扫的白名单 (冻结快照); 整段持 storage 锁, 防止 `cache()` 在快照之后新建的目录
     * 被本次清扫当成垃圾删掉.
     */
    private suspend fun refreshAndSweep(reason: String) {
        logger.debug { "Restoring torrent caches and sweeping unused ones ($reason)." }
        lock.withLock {
            val allRecovered = refreshCacheLocked()
            torrentEngine.deleteUnusedCaches(allRecovered)
            lastSweepAtMillis = System.currentTimeMillis()
        }
    }

    /** 上次清扫的时刻, 见 [SWEEP_DEBOUNCE]. */
    private var lastSweepAtMillis = 0L

    private suspend fun refreshCacheLocked(): List<MediaCache> {
        statSubscriptionScope.restart()
        statJobs.value = persistentMapOf()
        return super.refreshCache()
    }

    override suspend fun refreshCache(): List<MediaCache> {
        return lock.withLock { refreshCacheLocked() }
    }

    override suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean {
        // 必须与 refreshCache/cache 互斥: 服务连接抖动触发的 refreshCache 可能持有删除前的 datastore 快照,
        // 恢复完成后会把刚删除的条目重新发布回 listFlow, 表现为 "删除无效".
        // 物理清理 (阶段 2) 放在锁外的后台: 它需要 torrent 服务, 服务不可用时会挂起,
        // 在持锁状态下等待会卡住所有 refreshCache 和后续删除.
        val (cache, statJob) = lock.withLock {
            val cache = listFlow.value.firstOrNull(predicate) ?: return false
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

    override suspend fun restoreFile(
        origin: Media,
        metadata: MediaCacheMetadata,
        reportRecovered: suspend (MediaCache) -> Unit,
    ): MediaCache? = withContext(Dispatchers.IO_) {
        try {
            val cache = super.restoreFile(origin, metadata, reportRecovered)

            when (cache) {
                is TorrentMediaCacheEngine.TorrentMediaCache -> {
                    logger.info { "Cache resumed: $cache, subscribe to media cache stats." }
                    launchStatsSubscription(cache)
                }

                else -> {
                    logger.info { "Cache resumed: $cache" }
                }
            }

            cache
        } catch (e: Exception) {
            logger.error(e) { "Failed to restore cache for ${origin.mediaId}" }
            null
        }
    }

    override suspend fun cache(
        media: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        resume: Boolean
    ): TorrentMediaCacheEngine.TorrentMediaCache {
        return lock.withLock {
            val cache = super.cache(media, metadata, episodeMetadata, false)
            check(cache is TorrentMediaCacheEngine.TorrentMediaCache) { "Cache does not implement TorrentMediaCache." }

            launchStatsSubscription(cache)

            cache
        }.also {
            if (resume) {
                it.resume()
            }
        }
    }

    override fun close() {
        torrentEngine.close()
        statSubscriptionScope.close()
        super.close()
    }

    private companion object {
        /** 这么短的时间内不重复清扫: app 启动扫一次, 紧接着服务连上会再来一次, 后一次必然是空跑. */
        private val SWEEP_DEBOUNCE = 2.minutes
    }

}
