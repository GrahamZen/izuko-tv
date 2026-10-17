/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.number
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.exploration.schedule.AiringScheduleColumnItem
import me.him188.ani.app.ui.exploration.schedule.AiringScheduleItemPresentation
import me.him188.ani.app.ui.exploration.schedule.ScheduleDay
import me.him188.ani.app.ui.exploration.schedule.ScheduleItemDefaults
import me.him188.ani.app.ui.exploration.schedule.SchedulePagePresentation
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_schedule_episode
import me.him188.ani.app.ui.lang.exploration_schedule_episode_ep_and_sort
import me.him188.ani.app.ui.lang.exploration_schedule_last_weekday
import me.him188.ani.app.ui.lang.exploration_schedule_next_weekday
import me.him188.ani.app.ui.lang.exploration_schedule_this_weekday
import me.him188.ani.app.ui.lang.exploration_schedule_time_unknown
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_friday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_monday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_saturday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_sunday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_thursday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_tuesday
import me.him188.ani.app.ui.lang.exploration_schedule_weekday_wednesday
import me.him188.ani.app.ui.lang.exploration_tv_schedule_empty
import me.him188.ani.app.ui.lang.exploration_tv_schedule_today
import me.him188.ani.app.ui.pad.foundation.PadEmptyHint
import me.him188.ani.app.ui.pad.foundation.PadGlassTab
import me.him188.ani.app.ui.pad.foundation.PadPosterGrid
import me.him188.ani.app.ui.pad.foundation.PadPosterPlaceholder
import me.him188.ani.app.ui.pad.foundation.PadPosterWallTheme
import me.him188.ani.app.ui.pad.foundation.PadSubjectCard
import me.him188.ani.app.ui.pad.foundation.padGlassColors
import me.him188.ani.app.ui.pad.foundation.rememberPadCollectionMenu
import me.him188.ani.app.ui.pad.foundation.rememberPadPosterWallLayout
import me.him188.ani.app.ui.search.LoadErrorCard
import org.jetbrains.compose.resources.stringResource

/**
 * 平板的新番时间表, 照电视版的海报墙版式 (ui-tv 的 TvScheduleGridPage): 顶上一排日期胶囊 (「今天」「周三」「上周三」… 带月/日),
 * 下面是那一天的海报墙, 番名下面一行写放送时间与集数. 进页落在今天.
 *
 * 交互是触屏: 点日期或左右滑换天, 点卡片进详情页, 长按弹收藏菜单.
 */
@Composable
fun PadSchedulePage(presentation: SchedulePagePresentation, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    PadPosterWallTheme {
        PadSchedulePageContent(presentation, onRetry, modifier)
    }
}

@Composable
private fun PadSchedulePageContent(presentation: SchedulePagePresentation, onRetry: () -> Unit, modifier: Modifier) {
    val navigator = LocalNavigator.current
    val nsfw = rememberNsfwPolicy()
    val menu = rememberPadCollectionMenu()
    val days = presentation.days
    val todayIndex = remember(days) { days.indexOfFirst { it.kind == ScheduleDay.Kind.TODAY }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = todayIndex) { days.size }
    val dateListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timeUnknown = stringResource(Lang.exploration_schedule_time_unknown)

    // 选中的日期滚到日期行里看得见的地方
    LaunchedEffect(pagerState.currentPage) {
        val visible = dateListState.layoutInfo.visibleItemsInfo
        val page = pagerState.currentPage
        if (visible.none { it.index == page && it.offset >= 0 && it.offset + it.size <= dateListState.layoutInfo.viewportEndOffset }) {
            dateListState.animateScrollToItem((page - 1).coerceAtLeast(0))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wall = rememberPadPosterWallLayout(maxWidth)
        val railTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PAD_SCHEDULE_RAIL_TOP
        val gridTop = railTop + PAD_SCHEDULE_RAIL_HEIGHT + PAD_SCHEDULE_GRID_TOP_GAP
        HorizontalPager(pagerState, Modifier.fillMaxSize(), key = { days[it].date.toString() }) { page ->
            val items = remember(presentation, page) { dayItemsOf(presentation, days.getOrNull(page)) }
            when {
                presentation.isPlaceholder -> PadPosterGrid(count = PAD_SCHEDULE_PLACEHOLDER_COUNT, layout = wall, topPadding = gridTop) {
                    PadPosterPlaceholder(Modifier.fillMaxWidth())
                }

                items.isEmpty() && presentation.error == null -> PadEmptyHint(stringResource(Lang.exploration_tv_schedule_empty))
                else -> PadPosterGrid(count = items.size, layout = wall, topPadding = gridTop) { index ->
                    val item = items[index]
                    PadSubjectCard(
                        subjectId = item.subjectId,
                        title = item.subjectTitle,
                        coverUrl = item.imageUrl,
                        nsfw = nsfw,
                        menu = menu,
                        onClick = {
                            navigator.navigateSubjectDetails(
                                item.subjectId,
                                SubjectDetailPlaceholder(id = item.subjectId, name = item.subjectTitle, coverUrl = item.imageUrl),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        subtitle = ScheduleItemDefaults.renderTime(null, item.time, timeUnknownText = timeUnknown) +
                                " · " + episodeLabel(item),
                    )
                }
            }
        }
        Column(Modifier.padding(top = railTop)) {
            val glass = padGlassColors()
            LazyRow(
                Modifier.fillMaxWidth(),
                state = dateListState,
                contentPadding = PaddingValues(horizontal = wall.margin),
                horizontalArrangement = Arrangement.spacedBy(PAD_SCHEDULE_DATE_SPACING),
            ) {
                itemsIndexed(days, key = { _, day -> day.date.toString() }) { index, day ->
                    // 每一天一枚独立的玻璃胶囊 (同电视版的日期行)
                    PadGlassTab(
                        label = weekdayLabel(day),
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        modifier = Modifier.background(glass.fill, CircleShape).border(1.dp, glass.edge, CircleShape),
                        detail = "${day.date.month.number}/${day.date.day}",
                    )
                }
            }
            val error = presentation.error
            if (error != null) {
                LoadErrorCard(
                    error,
                    onRetry = onRetry,
                    Modifier.padding(start = wall.margin, end = wall.margin, top = PAD_SCHEDULE_GRID_TOP_GAP),
                )
            }
        }
    }
}

/** 某一天要显示的条目, 按放送时刻排好 (上游已排序; 当前时刻指示器与占位不占卡片位). */
private fun dayItemsOf(presentation: SchedulePagePresentation, day: ScheduleDay?): List<AiringScheduleItemPresentation> {
    day ?: return emptyList()
    val columnItems = presentation.airingSchedules.firstOrNull { it.date == day.date }?.episodes.orEmpty()
    return columnItems.mapNotNull { (it as? AiringScheduleColumnItem.Data)?.item }
}

/** 「第 3 话」, ep 与 sort 不同时「第 3 话 (总第 15 话)」; 同电视版. */
@Composable
private fun episodeLabel(item: AiringScheduleItemPresentation): String {
    val sortText = item.episodeSort.toString().removePrefix("0")
    val epText = item.episodeEp?.toString()?.removePrefix("0")
    return if (epText == null || item.episodeEp == item.episodeSort) {
        stringResource(Lang.exploration_schedule_episode, sortText)
    } else {
        stringResource(Lang.exploration_schedule_episode_ep_and_sort, epText, sortText)
    }
}

/** 「上周三」/「今天」/「周三」/「下周三」, 同电视版. */
@Composable
private fun weekdayLabel(day: ScheduleDay): String {
    if (day.kind == ScheduleDay.Kind.TODAY) return stringResource(Lang.exploration_tv_schedule_today)
    val weekday = when (day.dayOfWeek) {
        DayOfWeek.MONDAY -> stringResource(Lang.exploration_schedule_weekday_monday)
        DayOfWeek.TUESDAY -> stringResource(Lang.exploration_schedule_weekday_tuesday)
        DayOfWeek.WEDNESDAY -> stringResource(Lang.exploration_schedule_weekday_wednesday)
        DayOfWeek.THURSDAY -> stringResource(Lang.exploration_schedule_weekday_thursday)
        DayOfWeek.FRIDAY -> stringResource(Lang.exploration_schedule_weekday_friday)
        DayOfWeek.SATURDAY -> stringResource(Lang.exploration_schedule_weekday_saturday)
        else -> stringResource(Lang.exploration_schedule_weekday_sunday)
    }
    return when (day.kind) {
        ScheduleDay.Kind.LAST_WEEK -> stringResource(Lang.exploration_schedule_last_weekday, weekday)
        ScheduleDay.Kind.NEXT_WEEK -> stringResource(Lang.exploration_schedule_next_weekday, weekday)
        else -> stringResource(Lang.exploration_schedule_this_weekday, weekday)
    }
}

private val PAD_SCHEDULE_RAIL_TOP = 12.dp
private val PAD_SCHEDULE_RAIL_HEIGHT = 40.dp
private val PAD_SCHEDULE_GRID_TOP_GAP = 20.dp
private val PAD_SCHEDULE_DATE_SPACING = 8.dp
private const val PAD_SCHEDULE_PLACEHOLDER_COUNT = 12
