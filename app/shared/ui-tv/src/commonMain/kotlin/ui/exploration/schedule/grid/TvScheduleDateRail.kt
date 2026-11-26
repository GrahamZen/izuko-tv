/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.schedule.grid

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.number
import me.him188.ani.app.ui.exploration.schedule.ScheduleDay
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusRail
import me.him188.ani.app.ui.foundation.focus.tvFocusMoveRateLimit
import me.him188.ani.app.ui.foundation.focus.tvFocusRailKeys
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_FOCUS_BLEED
import me.him188.ani.app.ui.foundation.tv.TvRailWallSelection
import me.him188.ani.app.ui.foundation.tv.nativeview.TvGridPageExtension
import me.him188.ani.app.ui.foundation.tv.tvGlassBackground
import me.him188.ani.app.ui.subject.collection.TvCollectionGlassRailTab
import kotlin.math.roundToInt

/**
 * 日期行的 Compose 实现 (没有原生视图的端用, 见 [rememberTvScheduleDateRow]): 胶囊同追番页的标签 (选中垫浅灰片、聚焦浅色实底配黑字并抬起),
 * 放不下时横向滚, 聚焦的那枚滚到居中 (两端夹住); 海报墙跨天、触屏点选时把选中的那天滚到居中. 滚走与淡没照页面给的进度在绘制里挪.
 */
@Composable
internal fun rememberTvScheduleComposeDateRow(selection: TvRailWallSelection<Int>, focus: TvFocusScope): TvScheduleDateRow {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    return remember(selection, focus, listState, scope) { TvScheduleComposeDateRow(selection, focus, listState, scope) }
}

private class TvScheduleComposeDateRow(
    private val selection: TvRailWallSelection<Int>,
    private val focus: TvFocusScope,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) : TvScheduleDateRow {
    override val wallExtensions: List<TvGridPageExtension> get() = emptyList()

    override fun focusSelected() {
        val index = selection.selected
        focus.request(ScheduleDateFocus(index))
        // 胶囊还没组合出来: 先滚过去, 附着后请求自己送达 (聚焦时再居中, 见 TvScheduleCenterBringIntoView)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            scope.launch { runCatching { listState.scrollToItem(index) } }
        }
    }

    @Composable
    override fun Content(
        days: List<ScheduleDay>,
        scrollAway: () -> Float,
        fade: () -> Float,
        onDown: () -> Boolean,
        onFocused: () -> Unit,
        modifier: Modifier,
    ) {
        // 初始位置: 选中的那天居中 (从详情页返回时可能是跨天走到的某一天). 等一帧再算: 首帧 layoutInfo 还是空的
        LaunchedEffect(days.size) {
            withFrameNanos { }
            runCatching { listState.scrollToCenter(selection.selected) }
        }
        // 选中不是在日期行上用方向键换的 (海报墙跨天、触屏点选): 日期行不含焦点, 不会自己跟着滚, 把选中的那天带回视野
        var railFocused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            snapshotFlow { selection.selected }.collect { index ->
                if (!railFocused) runCatching { listState.scrollToCenter(index) }
            }
        }
        TvScheduleDateRail(
            days = days,
            selection = selection,
            focus = focus,
            listState = listState,
            onAnyFocused = onFocused,
            onNavigateDown = onDown,
            modifier = modifier
                .onFocusChanged { railFocused = it.hasFocus }
                .graphicsLayer {
                    alpha = 1f - fade()
                    translationY = -scrollAway()
                },
        )
    }
}

/**
 * 日期行的胶囊那一排: 左右键显式在胶囊间移动, 两端与上键都吃掉. 起点在 [modifier] 给的位置: 电视上是屏幕左缘 (第一天离边留一截,
 * 滑过去的切在屏幕边上), 平板上在左上角「返回」右边 (滑过去的切在那里, 不钻到它下面).
 *
 * 各胶囊自己判断选没选中 (见 TvCollectionGlassRailTab): 换天时只有选中态变了的两枚重组, 调用方与整行都不重组.
 */
@Composable
private fun TvScheduleDateRail(
    days: List<ScheduleDay>,
    selection: TvRailWallSelection<Int>,
    focus: TvFocusScope,
    listState: LazyListState,
    onAnyFocused: () -> Unit,
    onNavigateDown: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // 胶囊可能还没组合出来 (那时 requestFocus 是静默 no-op): 走 scope 请求 (悬挂到附着再送), 不在屏上时先滚过去
    val rail = rememberTvFocusRail(
        scope = focus,
        keyAt = { index -> ScheduleDateFocus(index) },
        onMove = { target ->
            focus.request(ScheduleDateFocus(target))
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                scope.launch { runCatching { listState.scrollToItem(target) } }
            }
        },
    )
    val touchInput = LocalTvTouchInputEnabled.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvScheduleCenterBringIntoView) {
        LazyRow(
            modifier
                // 长按方向键的移动频率上限 (全局上限, 高于系统连发; 同追番页的标签行)
                .tvFocusMoveRateLimit()
                .tvFocusRailKeys(
                    state = rail,
                    itemCount = { days.size },
                    onNavigateDown = onNavigateDown,
                    onNavigateUp = { true },
                ),
            state = listState,
            contentPadding = PaddingValues(start = if (touchInput) 0.dp else TV_SCHEDULE_SIDE_PAD, end = TV_GLASS_FOCUS_BLEED),
            horizontalArrangement = Arrangement.spacedBy(TV_SCHEDULE_DATE_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(days, key = { _, day -> day.date.toString() }) { index, day ->
                TvCollectionGlassRailTab(
                    rail = rail,
                    index = index,
                    item = index,
                    selection = selection,
                    label = renderTvScheduleWeekday(day),
                    detail = "${day.date.month.number}/${day.date.day}",
                    // 胶囊照 tvOS 标签栏的高度 (见 TV_SCHEDULE_DATE_RAIL_HEIGHT), 字竖向居中
                    modifier = Modifier.height(TV_SCHEDULE_DATE_RAIL_HEIGHT).tvGlassBackground(CircleShape),
                    onFocusChanged = { f -> if (f) onAnyFocused() },
                )
            }
        }
    }
}

private data class ScheduleDateFocus(val index: Int) : TvFocusKey

/** 日期行: 聚焦的胶囊滚到居中 (LazyRow 在两端自己夹住), 不按默认的"最小滚动到可见" —— 那样落位取决于上一次滚到哪, 观感随机. */
private val TvScheduleCenterBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2f - containerSize / 2f
}

/** 把第 [index] 项滚到居中 (不在屏上先滚到它, 下一帧按实际宽度对中; 两端夹住). */
private suspend fun LazyListState.scrollToCenter(index: Int) {
    if (index < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        withFrameNanos { }
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    scrollBy((item.offset + item.size / 2f - center).roundToInt().toFloat())
}
