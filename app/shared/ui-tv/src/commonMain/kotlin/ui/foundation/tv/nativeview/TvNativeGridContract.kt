/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.runtime.Immutable

/**
 * 网格海报墙 (追番 / 搜索页) 的几何 (px). 视图本身往上下出血 [topBleedPx] / [bottomBleedPx], 网格顶线在视图里
 * [topBleedPx] 处, 视口 = 视图高 − 上下出血. [endMarginPx] = 滚到底时末行底边离视口底边的空 (tvPosterWallEndMargin).
 * [heroLinePx] = hero 态聚焦行行顶停在网格顶线下方多远 (TV_POSTER_WALL_HERO_ROW_TOP 换算到网格坐标).
 */
data class TvNativeGridMetrics(
    val columns: Int,
    val startPx: Int,
    val endPx: Int,
    val topBleedPx: Int,
    val bottomBleedPx: Int,
    val endMarginPx: Int,
    val heroLinePx: Int,
    /** hero 态里越过 hero 线的行在这段距离内淡没 (TV_CARD_FADE_DISTANCE). */
    val fadeDistancePx: Float,
)

/**
 * 网格页 (追番 / 搜索) 原生海报墙的几何 (px; 横坐标按让开侧边栏之后的内容区给, 纵坐标 = 页面坐标).
 *
 * @param gridTopPx 网格顶线离页面顶 (顶栏 / 标签行 / 筛选条之下).
 * @param heroLeftPx / [heroTopPx] / [heroWidthPx] / [heroHeightPx] hero 文字块 (不占布局, 画在网格底下).
 * @param bleedLeftPx 内容区左边、侧边栏底下那一截 (收起的侧边栏宽, 同探索页; 没有侧边栏的页为 0): 视图从屏幕左缘铺起 (页面铺满, 侧边栏
 *   盖在上面), 网格起点、hero 文字这些横坐标由本视图加上这一截; 网格画进这一截, 最左一列的放大与投影、换标签时滑出的网格从侧边栏底下画过.
 */
@Immutable
data class TvNativeGridPageMetrics(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val gridTopPx: Int,
    val grid: TvNativeGridMetrics,
    val backdropWidthPx: Int,
    val backdropHeightPx: Int,
    val heroLeftPx: Int,
    val heroTopPx: Int,
    val heroWidthPx: Int,
    val heroHeightPx: Int,
    val titleWidthPx: Int,
    val summaryWidthPx: Int,
    val bleedLeftPx: Int = 0,
)
