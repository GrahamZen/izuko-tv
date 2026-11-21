/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.schedule.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.datetime.number
import me.him188.ani.app.ui.exploration.schedule.ScheduleDay
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_FOCUS_BLEED
import me.him188.ani.app.ui.foundation.tv.TvRailWallSelection
import me.him188.ani.app.ui.foundation.tv.nativeview.TvGridPageExtension
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGlassTab
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGlassTabStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTabBarExtension
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeGlassTabStyle
import kotlin.math.roundToInt

/**
 * 日期行的安卓实现 (见 [TvScheduleDateRow]): 原生玻璃标签栏 (每天一枚胶囊, 横向滚), 挂在原生网格页上 (见 [TvNativeTabBarExtension]),
 * 日期与海报墙之间的焦点都在原生里走. 左右移 = 聚焦即选中, 确定 = 当场换, 下键进海报墙; 第一天按左不动.
 */
@Composable
internal fun rememberTvScheduleNativeDateRow(selection: TvRailWallSelection<Int>): TvScheduleDateRow {
    val context = LocalContext.current
    val style = rememberTvScheduleDateTabStyle()
    return remember(selection) { TvScheduleNativeDateRow(TvNativeTabBarExtension(context, style), selection) }
}

/** 日期胶囊: 玻璃签各自成胶囊, 高同日期行, 胶囊之间留 [TV_SCHEDULE_DATE_SPACING]. */
@Composable
private fun rememberTvScheduleDateTabStyle(): TvNativeGlassTabStyle {
    val density = LocalDensity.current
    val base = rememberTvNativeGlassTabStyle()
    return remember(base, density) {
        with(density) {
            base.copy(
                pills = true,
                barHeightPx = TV_SCHEDULE_DATE_RAIL_HEIGHT.roundToPx(),
                barPaddingPx = 0,
                gapPx = TV_SCHEDULE_DATE_SPACING.roundToPx(),
            )
        }
    }
}

private class TvScheduleNativeDateRow(
    private val bar: TvNativeTabBarExtension,
    private val selection: TvRailWallSelection<Int>,
) : TvScheduleDateRow {
    override val wallExtensions: List<TvGridPageExtension> = listOf(bar)

    override fun focusSelected() {
        bar.focusTab(selection.selected)
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
        val style = rememberTvScheduleDateTabStyle()
        // 字 (今天 / 周三 / 上周三, 小字「月/日」) 组合当帧推进去; 选中变了 (日期行上移、海报墙跨天) 由效应推, 签栏随之滚到居中
        val labels = days.map { renderTvScheduleWeekday(it) }
        SideEffect {
            bar.setTabs(
                days.mapIndexed { i, day -> TvNativeGlassTab(id = day.date.toString(), label = labels[i], detail = "${day.date.month.number}/${day.date.day}") },
                selection.selected,
                style,
            )
        }
        LaunchedEffect(bar) {
            snapshotFlow { selection.selected }.collect { bar.setSelected(it) }
        }
        // 往上滚走: 签栏自己挪. 点开时的淡没读墙自己的进度 (与卡片同一帧, 见 TvNativeTabBarExtension.alphaOf), 不读 [fade]
        val currentScrollAway by rememberUpdatedState(scrollAway)
        LaunchedEffect(bar) {
            snapshotFlow { -currentScrollAway() }.collect { bar.setOffsetY(it) }
        }
        val currentOnDown by rememberUpdatedState(onDown)
        val currentOnFocused by rememberUpdatedState(onFocused)
        val callbacks = remember(bar) {
            object : TvNativeTabBarExtension.Callbacks {
                override fun onMoved(index: Int) = selection.moveTo(index)

                override fun onClick(index: Int) = selection.select(index, fromRail = true)

                override fun onDown() {
                    currentOnDown()
                }

                override fun onExitLeft() {}

                override fun onFocusChanged(index: Int) {
                    if (index >= 0) currentOnFocused()
                }
            }
        }
        // 电视上整行往左出血到屏幕边 (第一天离边留一截); 平板上从左上角「返回」右边起 (页面给的位置已让开), 滑过去的切在那里
        val touchInput = LocalTvTouchInputEnabled.current
        val density = LocalDensity.current
        SideEffect {
            bar.callbacks = callbacks
            with(density) {
                bar.startInsetPx = if (touchInput) 0 else TV_SCHEDULE_SIDE_PAD.roundToPx()
                bar.endInsetPx = TV_GLASS_FOCUS_BLEED.roundToPx()
                bar.bleedPx = TV_GLASS_FOCUS_BLEED.roundToPx()
            }
            bar.clipToViewport = touchInput
        }
        // 签那一排画在网格页上, 这里只占一块同样大小的地方、报它的窗口坐标
        Box(
            modifier.onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                bar.setAnchor(position.x.roundToInt(), position.y.roundToInt(), coordinates.size.width)
            },
        )
    }
}
