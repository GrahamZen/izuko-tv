/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.math.abs

/**
 * 海报墙三页 (探索 / 追番 / 搜索结果) 卡片的缩放系数 ([ThemeSettings.tvPosterWallScale]), 1 = 与其余界面一样大. 在 [TvPosterWallScaled] 里面才不是 1.
 *
 * 只作用于卡片: 海报与番名、卡片之间的间距、卡片自己的圆角 / 投影 / 进度条 (见 rememberTvNativeWallStyle), 以及由它们定下的列数与行高
 * (见 [tvPosterWallGrid]). hero 的标题与简介、轮播、组标题、标签行、搜索栏与侧边栏都不跟着变, 只跟界面缩放.
 */
val LocalTvPosterWallScale: ProvidableCompositionLocal<Float> = staticCompositionLocalOf { 1f }

/** 让 [content] 里的海报墙卡片按 [scale] 缩放 (见 [LocalTvPosterWallScale]). */
@Composable
fun TvPosterWallScaled(
    scale: Float = LocalThemeSettings.current.effectivePosterWallScale,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTvPosterWallScale provides scale, content = content)
}

/** 海报墙一排放几张 ([columns]) 与卡宽 ([cardWidth], 屏幕上的 dp, 含聚焦框空隙). 见 [tvPosterWallGrid]. */
@Immutable
data class TvPosterWallGrid(val columns: Int, val cardWidth: Dp)

/**
 * 卡片按 [scale] 缩放时 [availableWidth] 宽的卡片区怎么排: 等于卡片的最小宽度与卡片间距都乘上 [scale] 之后照常排 (见 [tvPosterWallColumns] /
 * [tvPosterWallCardWidth]) —— 缩小时一排放得下更多张, 卡片之间的空隙跟着卡片一起缩, 与卡片样式里的间距一致.
 */
fun Density.tvPosterWallGrid(availableWidth: Dp, scale: Float): TvPosterWallGrid {
    val columns = tvPosterWallColumns(availableWidth / scale)
    return TvPosterWallGrid(columns, tvPosterWallCardWidth(availableWidth / scale, columns) * scale)
}

/**
 * 网格页 (追番 / 搜索结果) 海报墙卡片区的内容宽度: 窗口宽减去收起的侧边栏、网格左出血与右边距. 列数与卡宽按它算, 「海报墙大小」滑块的档位
 * 也按它算. 窗口还没测量时按 1080p 电视的 960dp 算.
 */
@Composable
fun tvGridPageWallContentWidth(): Dp {
    val width = LocalWindowInfo.current.containerSize.width
    val windowWidth = with(LocalDensity.current) { if (width > 0) width.toDp() else 960.dp }
    return windowWidth - TvNavigationRailDefaults.CollapsedWidth - TV_GRID_START_BLEED - TV_PAGE_END_PAD
}

/**
 * 「海报墙大小」滑块上的档位 (百分数, 从小到大): 一档一种每排张数.
 *
 * 卡片铺满整排, 列数不变时放大只是把列距撑开, 卡宽差不到 1dp —— 按 [stepPercent] 一格一格走的话大半格按下去海报看不出变化 (1080p 上
 * 50%~150% 的 21 格只有 9 种列数), 却都要把假页面整个重建一次. 所以一档 = 换一种列数: 同一列数的那一段里取离 100% 最近的百分数
 * (100% 所在的那一段就是 100%), 番名与间距在这一档里最接近原样.
 *
 * [availableWidth] = 卡片区的内容宽度 (三页相同, 见各页的海报墙几何).
 */
fun Density.tvPosterWallScaleStops(availableWidth: Dp, minPercent: Int, maxPercent: Int, stepPercent: Int): List<Int> =
    (minPercent..maxPercent step stepPercent)
        .groupBy { tvPosterWallGrid(availableWidth, it / 100f).columns }
        .values
        .map { percents -> percents.minBy { abs(it - 100) } }
        .sorted()

/**
 * 从 [currentPercent] 往大 ([delta] > 0) 或往小走一档 (见 [tvPosterWallScaleStops]): 落到列数与现在不同的最近那一档; 已经到头返回 null.
 * [currentPercent] 不必是档位 (以前按 5% 一格存下的值), 照它此刻的列数算.
 */
fun Density.tvPosterWallScaleStep(
    availableWidth: Dp,
    stops: List<Int>,
    currentPercent: Int,
    delta: Int,
): Int? {
    fun columnsAt(percent: Int) = tvPosterWallGrid(availableWidth, percent / 100f).columns
    val current = columnsAt(currentPercent)
    // 越大列数越少
    return if (delta > 0) {
        stops.firstOrNull { columnsAt(it) < current }
    } else {
        stops.lastOrNull { columnsAt(it) > current }
    }
}
