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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.error
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class HttpMediaCacheStorage(
    override val mediaSourceId: String,
    private val store: DataStore<List<MediaCacheSave>>,
    private val dao: HttpCacheDownloadStateDao,
    private val httpEngine: MediaCacheEngine,
    private val displayName: String,
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractDataStoreMediaCacheStorage(mediaSourceId, store, httpEngine, displayName, parentCoroutineContext) {
    /**
     * Locks access to mutable operations.
     */
    private val lock = Mutex()

    override suspend fun restorePersistedCaches() {
        lock.withLock {
            val allRecovered = refreshCacheLocked()
            // 启动清扫必须和 cache/delete 共用同一临界区. allRecovered 是冻结快照,
            // 锁外清扫会把快照之后新建的缓存当作垃圾删除.
            httpEngine.deleteUnusedCaches(allRecovered)
        }
    }

    private suspend fun refreshCacheLocked(): List<MediaCache> = super.refreshCache()

    override suspend fun refreshCache(): List<MediaCache> {
        return lock.withLock {
            refreshCacheLocked()
        }
    }

    override suspend fun restoreFile(
        origin: Media,
        metadata: MediaCacheMetadata,
        reportRecovered: suspend (MediaCache) -> Unit,
    ): MediaCache? = withContext(Dispatchers.IO_) {
        try {
            super.restoreFile(origin, metadata, reportRecovered)
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
    ): MediaCache {
        return lock.withLock {
            listFlow.value.firstOrNull {
                isSameMediaAndEpisode(it, media, metadata)
            }?.let { return it }

            if (!engine.supports(media)) {
                throw UnsupportedOperationException("Engine does not support media: $media")
            }

            val pending = PendingHttpMediaCache(media, metadata)
            var createdForCleanup: MediaCache? = null
            listFlow.value += pending

            try {
                val createdCache = httpEngine.createCache(
                    media,
                    metadata,
                    episodeMetadata,
                    scope.coroutineContext,
                )
                createdForCleanup = createdCache

                withContext(Dispatchers.IO_) {
                    store.updateData { list ->
                        list + MediaCacheSave(createdCache.origin, createdCache.metadata, engine.engineKey)
                    }
                }

                pending.attach(createdCache, resume)
                // 用真身替换占位: attach 既不改 cacheId 也不改变 listFlow 的内容, 于是观察 listFlow
                // 的一方 (选源菜单的"新建缓存即时出现") 收不到任何变化 —— 占位期间 getCachedMedia()
                // 会抛而被跳过, 之后再没有第二次通知, 那一条要退出重进才出现.
                // 按身份换掉是安全的: 删除走 isSameMediaAndEpisode 匹配, 不看对象身份.
                listFlow.value = listFlow.value.map { if (it === pending) createdCache else it }
                pending
            } catch (e: Throwable) {
                listFlow.value = listFlow.value.filterNot { it === pending }
                createdForCleanup?.closeAndDeleteFiles()
                throw e
            }
        }
    }

    override suspend fun deleteFirst(predicate: (MediaCache) -> Boolean): Boolean {
        // 整个“持锁 + 逻辑删除 + 物理删除”都归 storage scope 所有. 调用方取消 await 只会
        // 放弃等待, 不会释放 lock 后任由旧清理与相同 downloadId 的新缓存并发 (ABA 误删).
        val deletion = scope.async {
            try {
                lock.withLock {
                    super@HttpMediaCacheStorage.deleteFirst(predicate)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error(e) { "Failed to delete HTTP media cache" }
                throw e
            }
        }
        return deletion.await()
    }
}

private class PendingHttpMediaCache(
    override val origin: Media,
    override val metadata: MediaCacheMetadata,
) : MediaCache {
    private val delegate = MutableStateFlow<MediaCache?>(null)
    private val desiredState = MutableStateFlow(MediaCacheState.IN_PROGRESS)
    override val isDeleted: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val state: Flow<MediaCacheState> = delegate.flatMapLatest {
        it?.state ?: desiredState
    }

    override val canPlay: Flow<Boolean> = delegate.flatMapLatest {
        it?.canPlay ?: flowOf(false)
    }

    override val fileStats: Flow<MediaCache.FileStats> = delegate.flatMapLatest {
        it?.fileStats ?: flowOf(MediaCache.FileStats.Unspecified)
    }

    override val sessionStats: Flow<MediaCache.SessionStats> = delegate.flatMapLatest {
        it?.sessionStats ?: flowOf(MediaCache.SessionStats.Unspecified)
    }

    suspend fun attach(cache: MediaCache, resume: Boolean) {
        delegate.value = cache
        if (isDeleted.value) {
            cache.closeAndDeleteFiles()
            return
        }

        when (desiredState.value) {
            MediaCacheState.IN_PROGRESS -> if (resume) cache.resume()
            MediaCacheState.PAUSED -> cache.pause()
            MediaCacheState.FAILED,
            MediaCacheState.COMPLETED,
                -> Unit
        }
    }

    override suspend fun getCachedMedia() =
        delegate.value?.getCachedMedia()
            ?: throw IllegalStateException("Cache is still being created")

    override suspend fun pause() {
        delegate.value?.pause() ?: run {
            desiredState.value = MediaCacheState.PAUSED
        }
    }

    override suspend fun close() {
        isDeleted.value = true
        delegate.value?.close()
    }

    override suspend fun resume() {
        delegate.value?.resume() ?: run {
            desiredState.value = MediaCacheState.IN_PROGRESS
        }
    }

    override suspend fun closeAndDeleteFiles() {
        isDeleted.value = true
        delegate.value?.closeAndDeleteFiles()
    }
}
