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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings

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
