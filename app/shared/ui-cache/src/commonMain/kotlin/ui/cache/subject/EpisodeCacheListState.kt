/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.cache.subject

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CancellationException
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngine
import me.him188.ani.app.domain.media.cache.requester.CacheRequestStage
import me.him188.ani.app.domain.media.cache.requester.EpisodeCacheRequester
import me.him188.ani.app.domain.media.cache.requester.trySelectSingle
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.app.domain.media.fetch.create
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest

@Stable
interface EpisodeCacheListState {
    /**
     * 该条目的所有剧集的缓存状态列表. 初始值为 `emptyList()`, 表示正在加载.
     */
    val episodes: List<EpisodeCacheState>

//    /**
//     * 是否有任何一个剧集正在进行缓存操作. 当剧集列表仍然在加载时, 该值总是为 `false`.
//     */
//    val anyEpisodeActionRunning: Boolean


    /**
     * 当前正在展示 [currentSelectMediaTask] 或 [currentSelectMediaTask] 的 [EpisodeCacheState]
     */
    val currentEpisode: EpisodeCacheState?

    /**
     * 当前需要展示给用户选择的 [MediaSelector]
     */
    val currentSelectMediaTask: SelectMediaTask?
    fun selectMedia(media: Media)
    fun cancelMediaSelector(task: SelectMediaTask)

    /**
     * 选资源时编辑了查询请求: 这次查询换上新请求 (各源重搜). 默认实现只作用于这一次.
     */
    suspend fun updateFetchRequest(task: SelectMediaTask, request: MediaFetchRequest) {
        task.fetchSession.setFetchRequest(request)
    }

    /**
     * 由 Bangumi 信息生成、未套用用户改动的请求, 供编辑框里「恢复 Bangumi 名称」用. 拿不到时为 `null`.
     */
    fun defaultFetchRequest(task: SelectMediaTask): MediaFetchRequest? = null


    /**
     * 当前需要展示给用户选择的存储位置列表
     */
    val currentSelectStorageTask: SelectStorageTask?
    fun selectStorage(storage: MediaCacheStorage)
    fun cancelStorageSelector(task: SelectStorageTask)

    fun cancelRequest()


    /**
     * 开始请求缓存一个剧集.
     *
     * 将会加载条目和剧集元数据, 尝试自动选择缓存. 在无法自动选择时, 将会展示 [MediaSelector].
     * @param autoSelectCached [CacheRequestStage.SelectMedia.tryAutoSelectByCachedSeason]
     */
    fun requestCache(episode: EpisodeCacheState, autoSelectCached: Boolean)

    /**
     * 删除一个剧集的现有缓存.
     */
    fun deleteCache(episode: EpisodeCacheState)
}

/**
 * 单个条目的缓存管理页面的状态
 *
 * ## 流程
 *
 * 1. 用户点击一个剧集的下载按钮, 调用 [requestCache]
 * 2. [requestCache] 调用 [onRequestCache] 发起请求 ([EpisodeCacheRequester]). [onRequestCache] 将会自动尝试选择目标 media 和 storage.
 * 3. 如果没有自动选择 media, [currentSelectMediaTask] 会变为非 `null`, UI 弹出 media selector, 待用户选择后调用 [selectMedia]
 * 4. 如果没有自动选择 storage, [currentSelectStorageTask] 会变为非 `null`, UI 对应弹出选择, 待用户选择后调用 [selectStorage]
 * 5. 当 media 和 storage 都选择完成, [onRequestCacheComplete] 被调用, 开始缓存
 *
 * @param onRequestCache 当需要开始为一个剧集创建缓存时调用. 用于从用户侧收集目标 [Media] 和 [MediaCacheStorage].
 * 通常使用 [EpisodeCacheRequester] 发起 [EpisodeCacheRequester.request], 然后尝试自动选择缓存.
 *
 * @param onRequestCacheComplete 当成功收集到用户选择的 [Media] 和 [MediaCacheStorage] 时调用.
 * 通常操作 [MediaCacheEngine] 开始缓存.
 *
 * @param onDeleteCache 当需要删除一个剧集的现有缓存时调用.
 *
 * @param onFetchRequestEdited 选资源时改了查询请求, 在换上新请求之前调用 (`default` 见 [defaultFetchRequest]).
 * 通常按条目记住搜索名、清掉本条目的旧搜索缓存 (同播放页).
 */ // See 连续缓存季度全集剧集 #376
@Stable
class EpisodeCacheListStateImpl(
    episodes: State<List<EpisodeCacheState>>,
    currentEpisode: State<EpisodeCacheState?>,
    // background scope
    private val onRequestCache: suspend (episode: EpisodeCacheState, autoSelectByCached: Boolean) -> CacheRequestStage?,
    private val onRequestCacheComplete: suspend (episode: EpisodeCacheTargetInfo) -> Unit,
    private val onDeleteCache: suspend (episode: EpisodeCacheState) -> Unit,
    private val onFetchRequestEdited: suspend (edited: MediaFetchRequest, default: MediaFetchRequest) -> Unit = { _, _ -> },
) : EpisodeCacheListState {
    override val episodes: List<EpisodeCacheState> by episodes

//    override val anyEpisodeActionRunning: Boolean by derivedStateOf {
//        this.episodes.any { it.actionTasker.isRunning }
//    }

    override val currentEpisode: EpisodeCacheState? by currentEpisode
    override val currentSelectMediaTask: SelectMediaTask? by derivedStateOf {
        val current = this.currentEpisode
        val stage = current?.currentStage
        // 只要是 working 就要有这个, 否则选 storage 的时候 media 的 sheet 就被关闭了
        if (stage !is CacheRequestStage.Working) return@derivedStateOf null
        SelectMediaTask(
            episode = current,
            fetchSession = stage.fetchSession,
            mediaSelector = stage.mediaSelector,
            attemptedTrySelect = stage.attemptedTrySelect,
        )
    }

    override fun selectMedia(media: Media) {
        val episode = currentEpisode ?: return
        episode.actionTasker.launch {
            (episode.cacheRequester.stage.value as? CacheRequestStage.SelectMedia)?.select(media)
                ?.trySelectSingle()
                ?.let { done ->
                    callComplete(episode, done)
                }
        }
    }

    override fun cancelMediaSelector(task: SelectMediaTask) {
        val episode = task.episode
        episode.actionTasker.launch {
            (episode.cacheRequester.stage.value as? CacheRequestStage.SelectMedia)?.cancel()
        }
    }

    override fun defaultFetchRequest(task: SelectMediaTask): MediaFetchRequest? =
        (task.episode.cacheRequester.stage.value as? CacheRequestStage.Working)?.request
            ?.let { MediaFetchRequest.create(it.subjectInfo, it.episodeInfo) }

    override suspend fun updateFetchRequest(task: SelectMediaTask, request: MediaFetchRequest) {
        defaultFetchRequest(task)?.let { onFetchRequestEdited(request, it) }
        task.fetchSession.setFetchRequest(request)
    }

    override val currentSelectStorageTask: SelectStorageTask? by derivedStateOf {
        val current = this.currentEpisode
        val stage = current?.currentStage
        if (stage !is CacheRequestStage.SelectStorage) return@derivedStateOf null
        SelectStorageTask(
            episode = current,
            options = stage.storages,
            attemptedTrySelect = stage.attemptedTrySelect,
        )
    }

    override fun selectStorage(storage: MediaCacheStorage) {
        val episode = currentEpisode ?: return
        episode.actionTasker.launch {
            (episode.cacheRequester.stage.value as? CacheRequestStage.SelectStorage)
                ?.select(storage)
                ?.let { done ->
                    callComplete(episode, done)
                }
        }
    }

    override fun cancelStorageSelector(task: SelectStorageTask) {
        val episode = task.episode
        episode.actionTasker.launch {
            (episode.cacheRequester.stage.value as? CacheRequestStage.SelectStorage)
                ?.cancel()
                ?.mediaSelector?.unselect() // 取消选中曾经选中的 Media, 否则那个 Media 会一直显示进度条
        }
    }

    override fun cancelRequest() {
        val episode = currentEpisode ?: return
        episode.actionTasker.launch {
            episode.cacheRequester.cancelRequest()
        }
    }

    override fun requestCache(episode: EpisodeCacheState, autoSelectCached: Boolean) {
        // 同一个 episode 已经有任务时, 忽略请求
        if (episode.actionTasker.isRunning.value) return
        episode.actionTasker.launch {
            currentEpisode
                ?.takeIf { it !== episode }
                ?.cacheRequester
                ?.cancelRequest()
            // TODO: 处理错误
            onRequestCache(episode, autoSelectCached)?.let {
                if (it is CacheRequestStage.Done) {
                    callComplete(episode, it)
                }
            }
        }
    }

    private suspend inline fun EpisodeCacheListStateImpl.callComplete(
        episode: EpisodeCacheState,
        done: CacheRequestStage.Done
    ) {
        onRequestCacheComplete(
            EpisodeCacheTargetInfo(
                episode = episode,
                request = done.request,
                media = done.media,
                storage = done.storage,
                metadata = done.metadata,
            ),
        )
    }

    override fun deleteCache(episode: EpisodeCacheState) {
        if (episode.actionTasker.isRunning.value) return
        episode.actionTasker.launch {
            try {
                onDeleteCache(episode)
            } catch (_: CancellationException) {
                // 用户主动取消
            } catch (e: Exception) {
                // TODO: 处理 requestDelete exception
                throw e
                // errorMessage.value = ErrorMessage.simple("删除缓存失败", e)
            }
        }
    }
}
