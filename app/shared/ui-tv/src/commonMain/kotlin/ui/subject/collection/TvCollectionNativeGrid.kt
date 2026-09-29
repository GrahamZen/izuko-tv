/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectWithLifecycle
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.tools.WeekFormatter
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.stateOf
import me.him188.ani.app.ui.foundation.tv.TV_CARD_HERO_BACKDROP_GEOMETRY
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaPipelineState
import me.him188.ani.app.ui.foundation.tv.TvNextEpisodeMedia
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeBackdropTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageCallbacks
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageHost
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroStatus
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroText
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextSpan
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.collection_tv_empty
import me.him188.ani.app.ui.lang.exploration_tv_air_date
import me.him188.ani.app.ui.lang.exploration_tv_all_caught_up
import me.him188.ani.app.ui.lang.exploration_tv_minutes_left
import me.him188.ani.app.ui.lang.exploration_tv_next_episode
import me.him188.ani.app.ui.lang.exploration_tv_watched_latest
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.subject_progress_continue_watching
import me.him188.ani.app.ui.lang.subject_progress_start_watching
import me.him188.ani.app.ui.lang.subject_progress_updates_on
import me.him188.ani.app.ui.search.isLoadingFirstPageOrRefreshing
import me.him188.ani.app.ui.subject.AiringLabelState
import me.him188.ani.app.ui.subject.rememberSubjectStatusStrings
import me.him188.ani.datasources.api.toLocalDateOrNull
import org.jetbrains.compose.resources.stringResource

/*
 * 追番页海报墙的原生视图: 页面 (TvCollectionPage) 管标签行、数据、hero 媒体流水线、网格送焦框架 (TvGridFocusState)、
 * 返回键分层、导航; 背景图 / hero 文字 / 网格在 TvNativeGridPageView (接线见 TvNativeGridPageHost). 本文件只放追番页自己的那部分:
 * 卡片 (进度条)、hero 文字 (个人观看状态行)、空分类提示、按标签换网格.
 */

/**
 * 追番页的原生海报墙: 选中标签 [selectedTab] (存储下标) 的网格显示 [items] (本页唯一的收集者), 换标签时
 * 新旧两份按显示顺序 ([tabOrderIndex]) 水平滑过. [landingIndex] 见 [TvNativeGridPageHost].
 */
@Composable
internal fun TvCollectionNativeGrid(
    state: TvNativeGridPageState,
    metrics: TvNativeGridPageMetrics,
    cardWidth: Dp,
    selectedTab: Int,
    tabOrderIndex: (tab: Int) -> Int,
    items: LazyPagingItems<SubjectCollectionInfo>,
    countSaysEmpty: Boolean,
    playHistories: () -> List<EpisodeHistory>,
    heroRaw: () -> SubjectCollectionInfo?,
    heroDisplay: () -> SubjectCollectionInfo?,
    heroText: () -> SubjectCollectionInfo?,
    heroPipeline: TvHeroMediaPipelineState,
    episodeStillCache: Map<Int, TvNextEpisodeMedia>,
    summaryFallbackCache: Map<Int, String>,
    fadeColor: Color,
    gridFocus: TvGridFocusState,
    farJump: () -> Boolean,
    onFarJumpConsumed: () -> Unit,
    callbacks: TvNativeGridPageCallbacks<SubjectCollectionInfo>,
    menuFor: (SubjectCollectionInfo) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    landingIndex: Int = -1,
) {
    val density = LocalDensity.current
    val tabItems = items.collectWithLifecycle()
    val histories = playHistories()
    TvNativeGridPageHost(
        state = state,
        metrics = metrics,
        cardWidth = cardWidth,
        gridKey = selectedTab,
        slideDirection = { from, to -> if (tabOrderIndex(to) > tabOrderIndex(from)) 1 else -1 },
        items = tabItems,
        cardsKey = histories,
        cardOf = { info ->
            TvNativeCard(
                imageUrl = info.subjectInfo.imageLarge,
                title = info.subjectInfo.displayName,
                progress = tvCollectionCardProgress(info, histories),
                subjectId = info.subjectId,
            )
        },
        source = null,
        fadeColor = fadeColor,
        treatment = remember(fadeColor) {
            tvPageBackdropTreatment(1f, topScrim = true, fadeColor = fadeColor, geometry = TV_CARD_HERO_BACKDROP_GEOMETRY)
        },
        gridFocus = gridFocus,
        farJump = farJump,
        onFarJumpConsumed = onFarJumpConsumed,
        callbacks = callbacks,
        menuFor = menuFor,
        modifier = modifier,
        landingIndex = landingIndex,
    ) {
        // 空分类提示: 在网格可见的那一段里居中
        if (tabItems.itemCount == 0 && !tabItems.isLoadingFirstPageOrRefreshing && !tabItems.loadState.hasError) {
            Box(
                Modifier.fillMaxSize().padding(
                    top = with(density) { metrics.gridTopPx.toDp() },
                    bottom = with(density) { metrics.grid.bottomBleedPx.toDp() },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Lang.collection_tv_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
    // 数据到了但这个标签是空的, 目标卡永远不会出现: 取消在途的送焦请求
    LaunchedEffect(tabItems.itemCount, tabItems.isLoadingFirstPageOrRefreshing, countSaysEmpty, gridFocus.switching) {
        if (tabItems.itemCount == 0 && (countSaysEmpty || !tabItems.isLoadingFirstPageOrRefreshing) && gridFocus.switching) {
            gridFocus.cancel()
        }
    }
    TvCollectionNativeSource(
        state = state,
        heroPipeline = heroPipeline,
        heroRaw = heroRaw,
        heroDisplay = heroDisplay,
        heroText = heroText,
        episodeStillCache = episodeStillCache,
        summaryFallbackCache = summaryFallbackCache,
        playHistories = playHistories,
    )
}

/** hero 内容交给原生 (停稳后的背景图 / 文字, 真实目标用来按下即压暗). 单独一个小组合: 只让这一块随换卡重组. */
@Composable
private fun TvCollectionNativeSource(
    state: TvNativeGridPageState,
    heroPipeline: TvHeroMediaPipelineState,
    heroRaw: () -> SubjectCollectionInfo?,
    heroDisplay: () -> SubjectCollectionInfo?,
    heroText: () -> SubjectCollectionInfo?,
    episodeStillCache: Map<Int, TvNextEpisodeMedia>,
    summaryFallbackCache: Map<Int, String>,
    playHistories: () -> List<EpisodeHistory>,
) {
    val display = heroDisplay()
    val raw = heroRaw()
    val spec = display?.toHeroMediaSpec()
    val url = heroPipeline.backdropUrl(spec)
    val underlay = heroPipeline.underlayUrl(spec)
    val backdrop = if (url != null && display != null) TvNativeBackdropTarget(url, display.subjectId, underlay) else null
    val text = heroText()?.let { tvCollectionNativeHeroText(it, episodeStillCache, summaryFallbackCache, playHistories) }
    val source = TvNativeHeroSource(
        backdrop = backdrop,
        dimming = raw?.subjectId != display?.subjectId,
        rawSubjectId = raw?.subjectId,
        text = text,
    )
    val view = state.view
    SideEffect { view?.setSource(source) }
}

/**
 * hero 文字: 标题 (至多两行); ★评分、开播状态 · 总集数、开播年月; 个人观看状态行 (主题色: 继续观看 /
 * 开始观看 / 下一集 + 集号 · 集名 · 剩余分钟 / 已看完); 简介优先下一集的 TMDB 单集简介, 再整部简介, 再 Bangumi 兜底.
 */
@Composable
private fun tvCollectionNativeHeroText(
    info: SubjectCollectionInfo,
    episodeStillCache: Map<Int, TvNextEpisodeMedia>,
    summaryFallbackCache: Map<Int, String>,
    playHistories: () -> List<EpisodeHistory>,
): TvNativeHeroText {
    val secondary = tvHeroSecondaryContentColor().toArgb()
    val onSurface = LocalContentColor.current.toArgb()
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val strings = rememberSubjectStatusStrings()
    val airing = remember(info) { AiringLabelState(stateOf(info.airingInfo), stateOf(info.progressInfo)) }
    val meta = buildList {
        airing.progressText(strings)?.let { add(TvNativeTextSpan(it, secondary)) }
        airing.totalEpisodesText(strings)?.let {
            add(TvNativeTextSpan(" · ", onSurface))
            add(TvNativeTextSpan(it, onSurface))
        }
        val airDate = info.subjectInfo.airDate
        if (airDate.isValid) {
            add(TvNativeTextSpan("    " + stringResource(Lang.exploration_tv_air_date, airDate.year, airDate.month), secondary))
        }
    }
    val status = tvCollectionNativeStatus(info, playHistories, primary)
    // 两张表是进程级共享的 (邻居预取会写进来): 收进 derivedStateOf, 写入别的条目时不重组
    val summary by remember(info) {
        derivedStateOf {
            val nextEpisodeOverview = info.stillEpisodeIdOrNull()
                ?.let { episodeStillCache[info.subjectId]?.overview }
                ?.takeIf { it.isNotBlank() }
            nextEpisodeOverview ?: info.subjectInfo.summary.trim().ifBlank { summaryFallbackCache[info.subjectId].orEmpty() }
        }
    }
    return TvNativeHeroText(
        subjectId = info.subjectInfo.subjectId,
        title = info.subjectInfo.displayName,
        infoReady = true,
        rating = info.subjectInfo.ratingInfo.score.takeIf { (it.toFloatOrNull() ?: 0f) > 0f },
        meta = meta,
        status = status,
        summary = summary,
    )
}

/** 个人观看状态行: 继续观看 / 开始观看 / 下一集, 追平时集号后标已看完; 未开播不显示. */
@Composable
private fun tvCollectionNativeStatus(
    info: SubjectCollectionInfo,
    playHistories: () -> List<EpisodeHistory>,
    color: Int,
): TvNativeHeroStatus? {
    val status = info.progressInfo.continueWatchingStatus
    val nextEp = info.progressInfo.nextEpisodeIdToPlay?.let { id -> info.episodes.firstOrNull { it.episodeId == id } }
    if (nextEp == null || status is ContinueWatchingStatus.NotOnAir) return null
    val epLabel = stringResource(Lang.playback_history_episode_label, nextEp.episodeInfo.sort.toString())
    val epName = nextEp.episodeInfo.nameCn.ifBlank { nextEp.episodeInfo.name }
    val caughtUp = status is ContinueWatchingStatus.Watched || status is ContinueWatchingStatus.Done
    val remainingMinutes = if (caughtUp) {
        null
    } else {
        playHistories().firstOrNull { it.episodeId == nextEp.episodeId }?.let { history ->
            val duration = history.durationMillis
            if (duration != null && duration > 0 && history.positionMillis > 0) {
                // 向上取整: 剩 30 秒也显示 1 分钟
                (((duration - history.positionMillis).coerceAtLeast(0L) + 59_999) / 60_000).toInt().coerceAtLeast(1)
            } else {
                null
            }
        }
    }
    val head = when {
        caughtUp -> epLabel
        status is ContinueWatchingStatus.Continue -> stringResource(Lang.subject_progress_continue_watching, epLabel)
        status is ContinueWatchingStatus.Start -> stringResource(Lang.subject_progress_start_watching) + " · " + epLabel
        else -> stringResource(Lang.exploration_tv_next_episode, epLabel)
    }
    val tail = when {
        caughtUp -> {
            val watchedStatus = status as? ContinueWatchingStatus.Watched
            val updatesOn = watchedStatus?.nextEpisodeAirDate?.toLocalDateOrNull()?.let { date ->
                stringResource(Lang.subject_progress_updates_on, WeekFormatter.System.format(date))
            }
            val caught = if (watchedStatus != null) {
                stringResource(Lang.exploration_tv_watched_latest)
            } else {
                stringResource(Lang.exploration_tv_all_caught_up)
            }
            " · " + caught + (updatesOn?.let { " · $it" } ?: "")
        }

        remainingMinutes != null -> " · " + stringResource(Lang.exploration_tv_minutes_left, remainingMinutes)
        else -> null
    }
    return TvNativeHeroStatus(lead = head, name = epName.ifBlank { null }, tail = tail, color = color)
}

/** 收藏卡的进度条: 看到一半按播放位置, 追平连载满条, 其余不画. */
internal fun tvCollectionCardProgress(info: SubjectCollectionInfo, playHistories: List<EpisodeHistory>): Float? {
    val progressInfo = info.progressInfo
    return when (progressInfo.continueWatchingStatus) {
        is ContinueWatchingStatus.Watched -> 1f
        is ContinueWatchingStatus.Continue -> progressInfo.nextEpisodeIdToPlay
            ?.let { nextId -> playHistories.firstOrNull { it.episodeId == nextId } }
            ?.let { history ->
                val duration = history.durationMillis
                if (duration != null && duration > 0) (history.positionMillis.toFloat() / duration).coerceIn(0f, 1f) else null
            }

        else -> null
    }
}
