/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.models.preference.resolveSavedOrder
import me.him188.ani.app.data.models.subject.ContinueWatchingStatus
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.collection_tv_empty
import me.him188.ani.app.ui.lang.subject_collection_doing
import me.him188.ani.app.ui.lang.subject_collection_done
import me.him188.ani.app.ui.lang.subject_collection_dropped
import me.him188.ani.app.ui.lang.subject_collection_on_hold
import me.him188.ani.app.ui.lang.subject_collection_wish
import me.him188.ani.app.ui.pad.foundation.PadEmptyHint
import me.him188.ani.app.ui.pad.foundation.PadGlassTab
import me.him188.ani.app.ui.pad.foundation.PadGlassTabBar
import me.him188.ani.app.ui.pad.foundation.PadPosterGrid
import me.him188.ani.app.ui.pad.foundation.PadPosterPlaceholder
import me.him188.ani.app.ui.pad.foundation.PadPosterWallTheme
import me.him188.ani.app.ui.pad.foundation.PadSubjectCard
import me.him188.ani.app.ui.pad.foundation.rememberPadCollectionMenu
import me.him188.ani.app.ui.pad.foundation.rememberPadPosterWallLayout
import me.him188.ani.app.ui.search.isLoadingFirstPageOrRefreshing
import me.him188.ani.app.ui.subject.collection.COLLECTION_TABS_SORTED
import me.him188.ani.app.ui.subject.collection.UserCollectionsState
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource

/**
 * 平板的追番页, 照电视版 (ui-tv 的 TvCollectionPage): 左上一条玻璃标签栏 —— 各个收藏分类, 顺序同电视版设置里排的, 各带条数;
 * 下面是这一类的海报墙网格, 卡片从标签栏底下滚过. 进页时当前分类没有条目就落到第一个有条目的分类 (同电视版).
 *
 * 交互是触屏: 点标签或左右滑换分类, 点卡片进详情页, 长按弹收藏菜单.
 */
@Composable
fun PadCollectionPage(state: UserCollectionsState, modifier: Modifier = Modifier) {
    PadPosterWallTheme {
        PadCollectionPageContent(state, modifier)
    }
}

@Composable
private fun PadCollectionPageContent(state: UserCollectionsState, modifier: Modifier) {
    val navigator = LocalNavigator.current
    val nsfw = rememberNsfwPolicy()
    val menu = rememberPadCollectionMenu()
    val playHistoryRepository = remember { GlobalKoin.get<EpisodePlayHistoryRepository>() }
    val playHistories by playHistoryRepository.flow.collectAsStateWithLifecycle(emptyList())
    val savedOrder = LocalThemeSettings.current.tvCollectionTabOrder
    val tabs = remember(savedOrder) { resolveSavedOrder(savedOrder, PAD_COLLECTION_TABS) }
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOf(COLLECTION_TABS_SORTED.getOrNull(state.selectedTypeIndex)).coerceAtLeast(0),
    ) { tabs.size }
    val scope = rememberCoroutineScope()

    // 进页时当前分类没有条目就换到第一个有条目的 (同电视版), 只在条数第一次到的时候做
    LaunchedEffect(state, tabs) {
        val counts = snapshotFlow { state.collectionCounts }.filterNotNull().first()
        val current = tabs.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (counts.getCount(current) == 0) {
            val target = tabs.firstOrNull { counts.getCount(it) > 0 } ?: return@LaunchedEffect
            pagerState.scrollToPage(tabs.indexOf(target))
        }
    }
    // 换页落定后记进共享状态: 它记着选中的分类, 从详情页回来停在原处
    LaunchedEffect(state, pagerState, tabs) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val index = COLLECTION_TABS_SORTED.indexOf(tabs.getOrNull(page) ?: return@collect)
            if (index >= 0 && state.selectedTypeIndex != index) state.selectTypeIndex(index)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wall = rememberPadPosterWallLayout(maxWidth)
        val tabBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PAD_TOP_BAR_TOP
        HorizontalPager(pagerState, Modifier.fillMaxSize(), key = { tabs[it].name }) { page ->
            val type = tabs[page]
            val typeIndex = COLLECTION_TABS_SORTED.indexOf(type)
            val items = state.getCollectionLazyPagingItems(typeIndex)
            if (items.itemCount == 0 && !items.isLoadingFirstPageOrRefreshing) {
                PadEmptyHint(stringResource(Lang.collection_tv_empty))
            } else {
                PadPosterGrid(
                    count = items.itemCount,
                    layout = wall,
                    topPadding = tabBarTop + PAD_TOP_BAR_HEIGHT + PAD_GRID_TOP_GAP,
                    state = state.getGridState(typeIndex),
                ) { index ->
                    val collection = items[index]
                    if (collection == null) {
                        PadPosterPlaceholder(Modifier.fillMaxWidth())
                    } else {
                        val info = collection.subjectInfo
                        PadSubjectCard(
                            subjectId = info.subjectId,
                            title = info.displayName,
                            coverUrl = info.imageLarge,
                            nsfw = nsfw,
                            menu = menu,
                            onClick = {
                                navigator.navigateSubjectDetails(
                                    info.subjectId,
                                    SubjectDetailPlaceholder(id = info.subjectId, name = info.displayName, coverUrl = info.imageLarge),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            progress = padCollectionCardProgress(collection, playHistories),
                        )
                    }
                }
            }
        }
        PadGlassTabBar(Modifier.padding(start = wall.margin, top = tabBarTop, end = wall.margin)) {
            tabs.forEachIndexed { index, type ->
                PadGlassTab(
                    label = collectionTypeLabel(type),
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    detail = state.collectionCounts?.getCount(type)?.toString(),
                )
            }
        }
    }
}

@Composable
private fun collectionTypeLabel(type: UnifiedCollectionType): String = when (type) {
    UnifiedCollectionType.WISH -> stringResource(Lang.subject_collection_wish)
    UnifiedCollectionType.DOING -> stringResource(Lang.subject_collection_doing)
    UnifiedCollectionType.DONE -> stringResource(Lang.subject_collection_done)
    UnifiedCollectionType.ON_HOLD -> stringResource(Lang.subject_collection_on_hold)
    UnifiedCollectionType.DROPPED -> stringResource(Lang.subject_collection_dropped)
    UnifiedCollectionType.NOT_COLLECTED -> ""
}

/**
 * 追番卡的进度条, 同电视版 (ui-tv 的 tvCollectionCardProgress): 看完最新一集在等更新 = 满条; 接着看下一集 = 那一集的播放位置; 别的不画.
 */
private fun padCollectionCardProgress(info: SubjectCollectionInfo, playHistories: List<EpisodeHistory>): Float? {
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

/** 默认的分类顺序, 同电视版 (TV_COLLECTION_TABS); 设置里排过的照设置. */
private val PAD_COLLECTION_TABS = listOf(
    UnifiedCollectionType.WISH,
    UnifiedCollectionType.DOING,
    UnifiedCollectionType.ON_HOLD,
    UnifiedCollectionType.DONE,
    UnifiedCollectionType.DROPPED,
)

/** 顶栏 (玻璃标签栏) 离状态栏的距离、自身高度、到第一行卡片的间距. */
private val PAD_TOP_BAR_TOP = 12.dp
private val PAD_TOP_BAR_HEIGHT = 48.dp
private val PAD_GRID_TOP_GAP = 20.dp
