/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.app.ui.foundation.HasBackgroundScope
import me.him188.ani.utils.coroutines.flows.flowOfEmptyList

abstract class MediaCacheManager(
    val storagesIncludingDisabled: List<MediaCacheStorage>,
    final override val backgroundScope: CoroutineScope,
) : HasBackgroundScope { // available via inject
    val enabledStorages: Flow<List<MediaCacheStorage>> = flowOf(storagesIncludingDisabled)

    private val cacheListFlow: Flow<List<MediaCache>> by lazy {
        val flows = storagesIncludingDisabled.map { it.listFlow }
        if (flows.isEmpty()) {
            flowOfEmptyList()
        } else combine(flows) {
            it.asSequence().flatten().toList()
        }
    }

    /**
     * [episodeId] 这一集里当前**不能播放**的缓存所对应的
     * [me.him188.ani.datasources.api.CachedMedia.mediaId], 实时更新.
     *
     * 用途: 选源菜单要把这些缓存显示成"不可用"而不是直接藏掉 (藏掉的话用户看不出"我明明缓存过").
     * 判据统一用 [MediaCache.canPlay] —— BT 缓存边下边播, 恒为 true; web (m3u8) 缓存只有下完才为 true.
     *
     * **按剧集收窄**: 这里的 id 拼法是 `storageId:origin.mediaId`, 不含剧集 —— 合集资源 (一个种子
     * 覆盖多集) 会让不同集的缓存共用同一个 origin.mediaId. 若跨集聚合, 某一集没下完就会把另一集
     * 已下完的那份也标成不可用. 选源菜单本来就只关心当前这一集, 收窄即可.
     *
     * 之所以必须"实时": 选源菜单的资源列表是一次性快照 (见 MediaSourceMediaFetcher 的
     * runningFold + distinctBy), 下载完成后重新 fetch 也会因 mediaId 相同而被丢弃 —— 只能靠这条流
     * 让 MediaSelectorContext 变化, 从而让筛选重算, 警告当场消失.
     */
    fun unplayableCacheMediaIds(subjectId: Int, episodeId: Int): Flow<Set<String>> {
        val subjectIdString = subjectId.toString()
        val episodeIdString = episodeId.toString()
        val flows = storagesIncludingDisabled.map { storage ->
            storage.listFlow
                .map { caches ->
                    caches.filter {
                        it.metadata.subjectId == subjectIdString && it.metadata.episodeId == episodeIdString
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { caches ->
                    if (caches.isEmpty()) return@flatMapLatest flowOf(emptyList())
                    combine(
                        caches.map { cache ->
                            cache.canPlay.map { canPlay ->
                                // 拼法必须与 CachedMedia.mediaId 一致
                                "${storage.mediaSourceId}:${cache.origin.mediaId}" to canPlay
                            }
                        },
                    ) { it.toList() }
                }
        }
        return if (flows.isEmpty()) flowOf(emptySet())
        else combine(flows) { perStorage ->
            // **一个 id 可能对应多条缓存, 有一条能播就算能播**: 所有本地缓存 storage 共用
            // `local-file-system` 作 mediaSourceId, 而这个 id 只由它和 origin.mediaId 拼成,
            // 不含引擎 —— 同一个磁力资源在开了 PikPak 时两个引擎都 supports, 可以各存一份.
            // 按"有一条不能播就算不能播"取并集的话, 已经下完的那份会被没下完的那份连坐标成
            // 不可用, 自动选源也跟着跳过它, 用户看到的是"我明明缓存好了却用不了".
            // 反过来最坏是选中一条坏的, 那会当场报错并自动换源 —— 响的错好过哑的错.
            val playableById = mutableMapOf<String, Boolean>()
            perStorage.forEach { list ->
                list.forEach { (id, canPlay) ->
                    playableById[id] = (playableById[id] ?: false) || canPlay
                }
            }
            playableById.filterValues { !it }.keys.toMutableSet()
        }
            // 防御性收窄: 上游任何一条缓存状态流多发一次重复值, 都会让 MediaSelectorContext
            // 重新 emit, 从而让整条筛选+排序流水线空转重算 (缓存时界面会明显变卡)
            .distinctUntilChanged()
    }

    @Stable
    fun listCacheForSubject(
        subjectId: Int,
    ): Flow<List<MediaCache>> {
        val subjectIdString = subjectId.toString()
        return cacheListFlow.map { list ->
            list.filter { cache ->
                cache.metadata.subjectId == subjectIdString
            }
        }
    }

    /**
     * Returns the cache status for the episode, updated lively and sampled for 1000ms.
     */
    @Stable
    fun cacheStatusForEpisode(
        subjectId: Int,
        episodeId: Int,
    ): Flow<EpisodeCacheStatus> {
        val subjectIdString = subjectId.toString()
        val episodeIdString = episodeId.toString()
        return cacheListFlow.transformLatest { list ->
            var hasAnyCached: MediaCache? = null
            var hasAnyCaching: MediaCache? = null

            for (mediaCache in list) {
                if (mediaCache.metadata.subjectId == subjectIdString && mediaCache.metadata.episodeId == episodeIdString) {
                    when (mediaCache.state.first()) {
                        MediaCacheState.COMPLETED -> hasAnyCached = mediaCache
                        MediaCacheState.IN_PROGRESS,
                        MediaCacheState.PAUSED,
                            -> hasAnyCaching = mediaCache

                        MediaCacheState.FAILED -> Unit
                    }
                }
            }

            val target = hasAnyCached ?: hasAnyCaching
            if (target == null) {
                emit(EpisodeCacheStatus.NotCached)
            } else {
                emitAll(
                    target.state.combine(target.fileStats) { state, stats ->
                        when (state) {
                            MediaCacheState.COMPLETED -> EpisodeCacheStatus.Cached(totalSize = stats.totalSize)
                            MediaCacheState.IN_PROGRESS,
                            MediaCacheState.PAUSED,
                                -> EpisodeCacheStatus.Caching(
                                progress = stats.downloadProgress,
                                totalSize = stats.totalSize,
                            )

                            MediaCacheState.FAILED -> EpisodeCacheStatus.NotCached
                        }
                    },
                )
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun deleteCache(cache: MediaCache): Boolean {
        for (storage in enabledStorages.first()) {
            if (storage.delete(cache)) {
                return true
            }
        }
        return false
    }

    suspend fun deleteFirstCache(filter: (MediaCache) -> Boolean): Boolean {
        for (storage in enabledStorages.first()) {
            if (storage.deleteFirst(filter)) {
                return true
            }
        }
        return false
    }

    suspend fun findFirstCache(filter: (MediaCache) -> Boolean): MediaCache? {
        for (storage in enabledStorages.first()) {
            storage.listFlow.first().find(filter)?.let {
                return it
            }
        }
        return null
    }

    suspend fun findAllCaches(filter: (MediaCache) -> Boolean): List<MediaCache> {
        val result = mutableListOf<MediaCache>()
        for (storage in enabledStorages.first()) {
            val caches = storage.listFlow.first().filter(filter)
            result.addAll(caches)
        }
        return result
    }

    suspend fun closeAllCaches() = supervisorScope {
        for (storage in enabledStorages.first()) {
            for (mediaCache in storage.listFlow.first()) {
                launch { mediaCache.close() }
            }
        }
    }

    companion object {
        /**
         * 本地数据源不允许有多个示例. 必须是 Factory:MediaSource:Instance = 1:1:1 的关系.
         */
        const val LOCAL_FS_MEDIA_SOURCE_ID = "local-file-system"
    }
}

class MediaCacheManagerImpl(
    storagesIncludingDisabled: List<MediaCacheStorage>,
    backgroundScope: CoroutineScope,
) : MediaCacheManager(storagesIncludingDisabled, backgroundScope)
