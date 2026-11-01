/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreMetrics
import kotlin.math.roundToInt

internal const val TV_WALL_POSITION_MIN_DP = -64
internal const val TV_WALL_POSITION_MAX_DP = 80
internal const val TV_WALL_POSITION_STEP_DP = 4

/** 移动首页海报行与停靠线, 保留标题、简介、卡片尺寸和行内间距; 下移受屏底可见空间约束. */
internal fun TvNativeExploreMetrics.withExplorationWallPosition(offsetDp: Int, density: Float): TvNativeExploreMetrics {
    if (offsetDp == 0) return this
    val requested = (offsetDp.coerceIn(TV_WALL_POSITION_MIN_DP, TV_WALL_POSITION_MAX_DP) * density).roundToInt()
    val minimum = -spacerPx.coerceAtLeast(0)
    val rowTop = listTopPx + headerPx + heroHeaderTopPx
    val maximum = (pageHeightPx - (16 * density).roundToInt() - rowTop - (rowPx - rowGapPx)).coerceAtLeast(0)
    val shift = requested.coerceIn(minimum, maximum)
    return copy(
        spacerPx = spacerPx + shift,
        heroHeaderTopPx = heroHeaderTopPx + shift,
        dotsCenterYPx = dotsCenterYPx + shift,
        overhangPx = (overhangPx - shift).coerceAtLeast(0),
    )
}

@Composable
internal fun tvExplorationPositionMetrics(metrics: TvNativeExploreMetrics): TvNativeExploreMetrics =
    metrics.withExplorationWallPosition(LocalThemeSettings.current.tvExplorationWallOffsetDp, LocalDensity.current.density)
