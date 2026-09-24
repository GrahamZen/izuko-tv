/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Stable
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.launchAsLazyPagingItemsIn
import androidx.paging.filter
import androidx.paging.flatMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.subject.subjectInfo
import me.him188.ani.app.data.network.RecommendationRepository
import me.him188.ani.app.data.network.TrendsRepository
import me.him188.ani.app.data.repository.subject.FollowedSubjectsRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.ui.exploration.ExplorationPageState
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.utils.coroutines.flows.FlowRestarter
import me.him188.ani.utils.coroutines.flows.restartable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Stable
class ExplorationPageViewModel : AbstractViewModel(), KoinComponent {
    private val trendsRepository: TrendsRepository by inject()
    private val recommendationRepository: RecommendationRepository by inject()
    private val sessionManager: SessionManager by inject()
    private val followedSubjectsRepository: FollowedSubjectsRepository by inject()
    private val settingsRepository: SettingsRepository by inject()

    private val nsfwSettingFlow = settingsRepository.uiSettings.flow.map { it.searchSettings.nsfwMode }
    private val horizontalScrollTipFlow =
        settingsRepository.oneshotActionConfig.flow.map { it.horizontalScrollTip }

    // 继续观看栏目的强制刷新 (TV 端长按播放键). 它平时只跟着仓库里一小时一跳的 ticker 走 ——
    // 那一跳会先同步服务器最近改动的收藏再重算每部的播放进度, restart 等价于立刻走一遍
    private val followedSubjectsRestarter = FlowRestarter()

    val explorationPageState: ExplorationPageState = ExplorationPageState(
        trendingSubjectInfoPager = trendsRepository.trendsInfoPager()
            .map { pagingData ->
                pagingData.flatMap { it.subjects.take(10) }
            }
            .cachedIn(backgroundScope)
            .launchAsLazyPagingItemsIn(backgroundScope),
//        TrendingSubjectsState(
//            suspend { trendsRepository.getTrendsInfo() }
//                .asFlow()
//                .retryWithBackoffDelay()
//                .map { it.subjects }
//                .produceState(null),
//        ),
        followedSubjectsPager = combine(
            settingsRepository.uiSettings.flow.map { it.searchSettings.nsfwMode },
            followedSubjectsRepository.followedSubjectsPager().restartable(followedSubjectsRestarter),
        ) { nsfwMode, subjects ->
            if (nsfwMode != NsfwMode.HIDE) return@combine subjects
            subjects.filter { !it.subjectInfo.nsfw }
        }.cachedIn(backgroundScope).launchAsLazyPagingItemsIn(backgroundScope),
        onRefreshFollowedSubjects = { followedSubjectsRestarter.restart() },
        onShuffleRecommendations = { recommendationRepository.requestRefresh(force = true) },
        // 推荐只读 Room 缓存, 进页零请求; 重算是另一条线, 见下面的 requestRefresh
        recommendationPager = recommendationRepository.recommendedSubjectsPager()
            .cachedIn(backgroundScope).launchAsLazyPagingItemsIn(backgroundScope),
        recommendationGroups = recommendationRepository.recommendationGroups()
            .stateIn(backgroundScope, SharingStarted.Eagerly, emptyList()),
        recommendationsRefreshing = recommendationRepository.isRefreshing,
        horizontalScrollTipFlow = horizontalScrollTipFlow,
        onSetDisableHorizontalScrollTip = {
            backgroundScope.launch {
                settingsRepository.oneshotActionConfig.update { copy(horizontalScrollTip = false) }
            }
        },
//            .onStart<List<FollowedSubjectInfo?>> {
//                emit(arrayOfNulls<FollowedSubjectInfo>(10).toList())
//            }
    )

    init {
        // 过期了才会真去算, 而且要等首帧宽限过去 —— 这里调用是不花钱的, 每次进页调一次即可.
        // 结果落 Room 后由 Room 自己推给上面那个 pager.
        recommendationRepository.requestRefresh()
        reloadAfterConnectionIssues(explorationPageState.trendingSubjectInfoPager)
    }

    /**
     * 轮播 (热度榜第一页) 只在建页时取一次, Paging 失败后不会自己再试 —— 不管的话整个会话都空着.
     *
     * - 出错后按 [trendingRetryDelay] 退避重试.
     * - 连接设置 (代理 / Bangumi 连接方式) 一改, 轮播还没取到就立刻按新设置重取: 典型是启动时官方连不上,
     *   用户在弹窗里同意改用镜像 —— 那时热度榜的请求多半还挂在官方上等超时, 等它失败再重试要白等半分钟.
     *   「继续观看」也立刻重新同步, 否则要等仓库一小时一跳.
     */
    private fun reloadAfterConnectionIssues(trending: LazyPagingItems<*>) {
        backgroundScope.launch {
            merge(
                settingsRepository.proxySettings.flow.distinctUntilChanged().drop(1),
                settingsRepository.bangumiEndpointSettings.flow.distinctUntilChanged().drop(1),
            ).collect {
                followedSubjectsRestarter.restart()
                if (trending.loadState.refresh !is LoadState.NotLoading) trending.refresh()
            }
        }
        backgroundScope.launch {
            retryFailedLoads(trending.refreshLoadStates(), ::trendingRetryDelay, trending::retry)
        }
    }
}

/** 轮播连续失败 [failures] 次后隔多久再试: 5 秒起翻倍, 最长 5 分钟. */
internal fun trendingRetryDelay(failures: Int): Duration =
    (5.seconds * (1 shl (failures - 1).coerceIn(0, 6))).coerceAtMost(5.minutes)

/**
 * [states] 每进一次 [LoadState.Error] 就等 [delayFor] (参数是连续失败次数) 再 [retry]; 回到 [LoadState.NotLoading] 算成功, 次数清零.
 * 等的时候状态变了 (别处已经重试或刷新) 就不再等.
 */
internal suspend fun retryFailedLoads(
    states: Flow<LoadState>,
    delayFor: (failures: Int) -> Duration,
    retry: () -> Unit,
) {
    var failures = 0
    states.collectLatest { state ->
        when (state) {
            is LoadState.Error -> {
                failures++
                delay(delayFor(failures))
                retry()
            }

            is LoadState.NotLoading -> failures = 0
            is LoadState.Loading -> {}
        }
    }
}

/** 首屏 (REFRESH) 那次加载的状态; 注册时先给一次当前状态. */
private fun LazyPagingItems<*>.refreshLoadStates(): Flow<LoadState> = callbackFlow {
    val listener: (CombinedLoadStates) -> Unit = { trySend(it.refresh) }
    addLoadStateListener(listener)
    awaitClose { removeLoadStateListener(listener) }
}.distinctUntilChanged()
