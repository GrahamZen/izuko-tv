/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaFetchSessionRefresh
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.fetch.create
import me.him188.ani.app.domain.media.fetch.createFetchFetchSession
import me.him188.ani.app.domain.media.fetch.withRegionalAliases
import me.him188.ani.app.domain.media.fetch.withRegionalNames
import me.him188.ani.app.domain.media.fetch.withRegionalSeriesNames
import me.him188.ani.app.domain.media.fetch.withSeasonAliases
import me.him188.ani.app.domain.media.selector.DefaultMediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorContextFlowProducer
import me.him188.ani.app.domain.player.extension.holdSearchingForFinishedCache
import me.him188.ani.app.domain.usecase.UseCase
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.platform.collections.tupleOf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.CoroutineContext
import me.him188.ani.app.domain.media.fetch.withRequestedNumbers

/**
 * A use case that constructs [MediaFetchSelectBundle]s according to [MediaFetchRequest] or [SubjectEpisodeInfoBundle].
 *
 * It simply calls factories and does not perform I/O.
 *
 * @see MediaFetchSelectBundle
 */
fun interface CreateMediaFetchSelectBundleFlowUseCase : UseCase {

    /**
     * Creates a [MediaFetchSelectBundle] for the given [SubjectEpisodeInfoBundle].
     *
     * This function does not throw.
     */
    operator fun invoke(
        subjectEpisodeInfoBundleFlow: Flow<SubjectEpisodeInfoBundle?>,
    ): Flow<MediaFetchSelectBundle?>

    /**
     * 与 [invoke] 相同, 但查询会话由 [fetchSessions] 提供, 同一条目的各集共用一个会话.
     * 默认实现忽略 [fetchSessions].
     */
    operator fun invoke(
        subjectEpisodeInfoBundleFlow: Flow<SubjectEpisodeInfoBundle?>,
        fetchSessions: SubjectMediaFetchSessions,
    ): Flow<MediaFetchSelectBundle?> = invoke(subjectEpisodeInfoBundleFlow)
}

class CreateMediaFetchSelectBundleFlowUseCaseImpl(
    private val flowContext: CoroutineContext = Dispatchers.Default,
) : CreateMediaFetchSelectBundleFlowUseCase, KoinComponent {
    private val mediaSourceManager: MediaSourceManager by inject()
    private val episodePreferencesRepository: EpisodePreferencesRepository by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val fetchSessionRefresh: MediaFetchSessionRefresh by inject()
    private val mediaCacheManager: MediaDownloadManager by inject()

    override fun invoke(
        subjectEpisodeInfoBundleFlow: Flow<SubjectEpisodeInfoBundle?>
    ): Flow<MediaFetchSelectBundle?> = createBundleFlow(subjectEpisodeInfoBundleFlow) { request, _ ->
        mediaSourceManager.createFetchFetchSession(flowOf(request))
    }

    override fun invoke(
        subjectEpisodeInfoBundleFlow: Flow<SubjectEpisodeInfoBundle?>,
        fetchSessions: SubjectMediaFetchSessions,
    ): Flow<MediaFetchSelectBundle?> = createBundleFlow(subjectEpisodeInfoBundleFlow) { request, refreshTick ->
        fetchSessions.get(request, refreshTick)
    }

    private fun createBundleFlow(
        subjectEpisodeInfoBundleFlow: Flow<SubjectEpisodeInfoBundle?>,
        createFetchSession: suspend (MediaFetchRequest, refreshTick: Int) -> MediaFetchSession,
    ): Flow<MediaFetchSelectBundle?> {
        val bundleDistinct = subjectEpisodeInfoBundleFlow
            .distinctUntilChangedBy { bundle ->
                if (bundle == null) {
                    null
                } else {
                    // 这里需要指定所有需要的参数, 当这些参数变更时重新创建搜索
                    tupleOf(
                        bundle.subjectInfo.subjectId,
                        bundle.episodeInfo.episodeId,

                        bundle.subjectCollectionInfo.subjectInfo.nameCn,
                        bundle.subjectCollectionInfo.subjectInfo.name,
                        bundle.subjectCollectionInfo.subjectInfo.allNames,

                        bundle.episodeInfo.sort,
                        bundle.episodeInfo.ep,

                        bundle.episodeInfo.name,
                        bundle.episodeInfo.nameCn,

                        bundle.seriesInfo,
                        bundle.subjectCompleted,

                        // 剧集列表变化 (例如新集开播) 时重建查询
                        bundle.subjectCollectionInfo.episodes.map { it.episodeId },
                    )
                }
            }

        // 刚开始是 `null`. 带着建会话时的那份 bundle, 见 [SessionForBundle]
        val fetchRequestFlow: Flow<SessionForBundle?> = bundleDistinct
            // 为什么要 `filterNotNull`:
            // 如果本地有缓存, 我们会优先读取缓存, bundle 会不是 null. 如果缓存有过期, 此时会同时发起网络查询.
            // 如果加载发生网络错误, 然后用户点击 "重试", bundle 会变为 `null`.
            // 然而即使进行了网络查询, 新的数据很有可能跟旧的数据是一样的, 就没有必要重新查询.
            // 所以我们总是等待一个 not null SubjectEpisodeInfoBundle 比较.
            .filterNotNull()
            .map { bundle ->
                val request = MediaFetchRequest.create(
                    bundle.subjectCollectionInfo.subjectInfo,
                    bundle.episodeCollectionInfo.episodeInfo,
                    episodes = bundle.subjectCollectionInfo.episodes.map { it.episodeInfo },
                    seriesInfo = bundle.seriesInfo,
                ).let { withRegionalNames(it, bundle.seriesInfo) }
                // 用户为本条目改过的搜索关键词只在建会话时读一次: 会话内改动走 MediaFetchSession.setFetchRequest,
                // 不必因此重建整个会话 (选中项与播放都挂在会话上)
                bundle to (episodePreferencesRepository.searchKeywordsFlow(bundle.subjectId).first()?.applyTo(request) ?: request)
            }
            // 「重新搜索(含新数据源)」按一下就 +1: 会话创建时对数据源列表取快照, 改完数据源 / 更新订阅要让新源
            // 参与就只能重建会话 (见 MediaFetchSessionRefresh)。请求本身没变时, 它是唯一能放行的东西。
            // 计数要一路传给 createFetchSession: 条目级会话按「同一条目」复用 (见 SubjectMediaFetchSessions),
            // 只在这里放行的话, 拿回来的仍然是持有旧数据源快照的那一个会话.
            .combine(fetchSessionRefresh.ticks) { (bundle, req), tick -> Triple(bundle, req, tick) }
            .sessionsForBundles(createFetchSession) // 请求与计数都没变时沿用会话, very important to avoid re-query
            .onStart<SessionForBundle?> { emit(null) }

        return combine(bundleDistinct, fetchRequestFlow) { bundle, fetchSession ->
            tupleOf(bundle, fetchSession)
        }.transformLatest { (bundle, sessionForBundle) ->
            if (bundle == null || sessionForBundle == null) return@transformLatest emit(null)
            // 条目信息刚变、配给它的会话还没到: 不拿上一份的会话建选源器 (见 SessionForBundle)
            if (sessionForBundle.bundle != bundle) return@transformLatest
            val fetchSession = sessionForBundle.session
            // 与查询请求认部用的名字 (MediaFetchRequest.nameHints) 是同一组
            val subjectInfo = bundle.subjectInfo.withSeasonAliases(bundle.seriesInfo)

            val selector = DefaultMediaSelector(
                MediaSelectorContextFlowProducer(
                    // TODO: 2025/4/22 Collect all these information from the ani server
                    flowOf(bundle.subjectCompleted ?: false), // accesses network
                    mediaSourceManager.allInstances.map { list ->
                        list.map { it.mediaSourceId }
                    },
                    flowOf(withRegionalSeriesNames(bundle.seriesInfo, subjectInfo)),
                    flowOf(withRegionalAliases(subjectInfo, bundle.seriesInfo)),
                    fetchSession.latestRequest.map { bundle.episodeInfo.withRequestedNumbers(it) },
                    mediaSourceManager.mediaSourceTiersFlow(), // only access local settings
                    // 缓存没下完时在选源菜单里标成"不可用"而不是藏掉, 见 MediaExclusionReason.CacheNotReady
                    unplayableCacheMediaIds = mediaCacheManager.unplayableCacheMediaIds(bundle.subjectId, bundle.episodeId),
                ).flow,
                fetchSession.cumulativeResults,
                savedUserPreference = episodePreferencesRepository.mediaPreferenceFlow(bundle.subjectId), // only access local settings
                savedDefaultPreference = settingsRepository.defaultMediaPreference.flow,
                mediaSelectorSettings = settingsRepository.mediaSelectorSettings.flow,
                flowCoroutineContext = flowContext,
            )

            // 「有缓存时直接播, 不搜索」: 交出去之前停住缓存以外的源, 见 PlayCacheWithoutSearchingExtension
            holdSearchingForFinishedCache(fetchSession, settingsRepository, mediaCacheManager, bundle.subjectId, bundle.episodeId)
            emit(
                MediaFetchSelectBundle(
                    fetchSession,
                    selector,
                ),
            )
        }.flowOn(flowContext)
    }
}
