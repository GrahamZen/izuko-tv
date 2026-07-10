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
import kotlinx.collections.immutable.minus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheKey
import me.him188.ani.app.domain.media.cache.mediaCacheKey
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.cache.engine.sum
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.coroutines.childScope
import me.him188.ani.utils.coroutines.update
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.thisLogger
import kotlin.coroutines.CoroutineContext

abstract class AbstractDataStoreMediaCacheStorage(
    override val mediaSourceId: String,
    private val datastore: DataStore<List<MediaCacheSave>>,
    override val engine: MediaCacheEngine,
    private val displayName: String,
    parentCoroutineContext: CoroutineContext,
) : MediaCacheStorage {
    protected val logger = thisLogger()
    protected val scope: CoroutineScope = parentCoroutineContext.childScope()

    protected val metadataFlow = datastore.data
        .map { list ->
            list.filter { it.engine == engine.engineKey }
                .sortedBy { it.origin.mediaId } // consistent stable order
                .distinctBy { MediaCacheKey(it.origin.mediaId, it.metadata) }
        }

    /**
     * 已经恢复的 [LocalFileMediaCache] 的稳定键, 不会重复恢复.
     *
     * 必须按完整的集键而不是 mediaId 记账: 合集种子的多集共享同一个 mediaId, 按 mediaId 记会把
     * 第一集恢复完成后才轮到检查的其余各集当成"已恢复"跳过 —— 并发上限 8 时, 10 集合集会稳定
     * 丢失第 10 集起的条目.
     */
    private val restoredLocalFileMediaCacheKeys = MutableStateFlow(persistentSetOf<MediaCacheKey>())

    open suspend fun refreshCache(): List<MediaCache> {
        val allRecovered = MutableStateFlow(persistentListOf<MediaCache>())
        val metadataFlowSnapshot = metadataFlow.first()
        logger.info { "Restoring media cache, cache count in datastore: ${metadataFlowSnapshot.size}" }
        val semaphore = Semaphore(8)

        supervisorScope {
            metadataFlowSnapshot.forEach { (origin, metadata, _) ->
                if (MediaCacheKey(origin.mediaId, metadata) in restoredLocalFileMediaCacheKeys.value) {
                    return@forEach
                }

                semaphore.acquire()
                @OptIn(DelicateCoroutinesApi::class)
                launch(start = CoroutineStart.ATOMIC) {
                    try {
                        restoreFile(origin, metadata) { restored ->
                            if (restored is LocalFileMediaCache) {
                                restoredLocalFileMediaCacheKeys.update { plus(restored.mediaCacheKey) }
                            }
                            allRecovered.update { plus(restored) }
                            // 增量发布: 恢复未完成的 torrent 缓存需要等 torrent 服务连接, 可能长时间挂起.
                            // 若等全部恢复完才发布 listFlow, 任一挂起项会把整批 (包括已完成的缓存) 都扣住不显示.
                            // 这里让恢复完的条目 (尤其是走本地文件快路径的已完成缓存) 立即可见.
                            listFlow.update {
                                filterNot { it.mediaCacheKey == restored.mediaCacheKey } + restored
                            }
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }

        // 新 restore 的加上 list 中已经有的 LocalFileMediaCache.
        // 只补**往轮**恢复、本轮被跳过的那些 —— 本轮恢复的已经在 allRecovered 里,
        // 再加一遍会让每个已完成缓存在 listFlow 里出现两份: 删除时 minus 只移除一份,
        // 剩下的副本 state 恒为 COMPLETED, 表现为"删了还显示有缓存/要按两次删除".
        listFlow.update {
            val recovered = allRecovered.value
            val recoveredKeys = recovered.mapTo(HashSet()) { it.mediaCacheKey }
            recovered + listFlow.value.filter { cache ->
                cache.mediaCacheKey in restoredLocalFileMediaCacheKeys.value &&
                        cache.mediaCacheKey !in recoveredKeys
            }
        }
        return allRecovered.value
    }

    open suspend fun restoreFile(
        origin: Media,
        metadata: MediaCacheMetadata,
        reportRecovered: suspend (MediaCache) -> Unit,
    ): MediaCache? {
        val cache = engine.restore(origin, metadata, scope.coroutineContext) ?: return null
        logger.info { "Cache restored: ${origin.mediaId}, result=${cache}" }

        reportRecovered(cache)
        cache.resume()

        logger.info { "Cache resumed: $cache" }
        return cache
    }

    override val listFlow: MutableStateFlow<List<MediaCache>> = MutableStateFlow(emptyList())

    override val cacheMediaSource: MediaSource by lazy {
        MediaCacheStorageSource(this, displayName, MediaSourceLocation.Local)
    }
    override val stats: Flow<MediaStats> = listFlow.flatMapLatest { caches ->
        if (caches.isEmpty()) {
            return@flatMapLatest flowOf(MediaStats.Zero)
        }

        combine(
            caches.distinctBy { it.origin.download.uri }.map { cache ->
                cache.sessionStats.map { stats ->
                    MediaStats(
                        uploaded = stats.uploadedBytes,
                        downloaded = stats.downloadedBytes,
                        uploadSpeed = stats.uploadSpeed,
                        downloadSpeed = stats.downloadSpeed,
                    )
                }
            },
        ) { cacheStats ->
            cacheStats.sum()
        }
    }

    override suspend fun cache(
        media: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        resume: Boolean
    ): MediaCache {
        logger.info { "$mediaSourceId creating cache, metadata=$metadata" }
        listFlow.value.firstOrNull {
            isSameMediaAndEpisode(it, media, metadata)
        }?.let { return it }

        if (!engine.supports(media)) {
            throw UnsupportedOperationException("Engine does not support media: $media")
        }
        val cache = engine.createCache(
            media, metadata,
            episodeMetadata,
            scope.coroutineContext,
        )

        withContext(Dispatchers.IO_) {
            datastore.updateData { list ->
                list + MediaCacheSave(cache.origin, cache.metadata, engine.engineKey)
            }
        }

        listFlow.update { plus(cache) }

        if (resume) {
            cache.resume()
        }

        return cache
    }

    override suspend fun delete(cache: MediaCache): Boolean {
        return deleteFirst { isSameMediaAndEpisode(it, cache.origin, cache.metadata) }
    }

    override suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean {
        val cache = removeFirstFromListAndStore(predicate) ?: return false
        cache.closeAndDeleteFiles()
        return true
    }

    /**
     * 从 [listFlow] 与持久层移除第一个满足 [predicate] 的缓存, 返回被移除的缓存 (没有匹配则返回 `null`).
     *
     * 子类可在持锁状态下调用本方法以与 [refreshCache] 互斥, 同时把可能长时间挂起的文件删除
     * ([MediaCache.closeAndDeleteFiles], 例如需要等待 torrent 服务连接) 留在锁外执行.
     */
    protected suspend fun removeFirstFromListAndStore(predicate: (MediaCache) -> Boolean): MediaCache? {
        val cache = listFlow.value.firstOrNull(predicate) ?: return null
        listFlow.update { minus(cache) }
        restoredLocalFileMediaCacheKeys.update { minus(cache.mediaCacheKey) }
        withContext(Dispatchers.IO_) {
            datastore.updateData { list ->
                list.filterNot { isSameMediaAndEpisode(cache, it) }
            }
        }
        return cache
    }

    override fun close() {
        scope.cancel()
    }

    protected fun isSameMediaAndEpisode(
        cache: MediaCache,
        media: Media,
        metadata: MediaCacheMetadata = cache.metadata
    ) = cache.mediaCacheKey == MediaCacheKey(media.mediaId, metadata)

    protected fun isSameMediaAndEpisode(cache: MediaCache, save: MediaCacheSave): Boolean =
        isSameMediaAndEpisode(cache, save.origin, save.metadata)
}
