/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.ui.unit.Dp

/**
 * 网格页 (追番 / 搜索) 海报墙的几何: 列数、卡宽、交给原生视图的各项尺寸 ([TvNativeGridPageHost] 的 metrics / cardWidth).
 * 各页按自己的顶栏算好 (见 tvCollectionWallLayout / tvSearchWallLayout), 页面与「海报墙大小」的预览页共用同一个函数, 预览才与真页一模一样.
 */
class TvNativeGridWallLayout(
    val columns: Int,
    val cardWidth: Dp,
    val metrics: TvNativeGridPageMetrics,
)
