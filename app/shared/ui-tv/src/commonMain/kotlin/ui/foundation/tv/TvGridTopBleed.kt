/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 网格页 (追番 / 搜索 / 时间表网格) 卡片区的出血.
 *
 * **向上 ([top])**: 与探索页同一种观感, 离场的行越过网格顶边继续上移、边移边淡 ([tvGridItemTopFade]), 而不是在
 * 网格顶边被 90 度硬切 (用户 2026-09-11: 追番页 / 搜索页往上滑的卡片运动和探索页不一样).
 *
 * **向左 ([start])**: 首列卡聚焦放大时向左伸出几 dp, 网格自己 clipToBounds, 不出血就被左边界切掉 (见 [TV_GRID_START_BLEED]).
 *
 * 做法同探索页卡片区: 布局上仍按原尺寸参与排版, 只在测量时多要出血量、放置时反向挪同样的距离. 网格的
 * contentPadding 也要在对应一侧加上出血量 —— 聚焦行吸顶停在内边距之后那条线上 (TvScrollAnimator 按条目 offset 算,
 * offset 0 = 内边距之后), 于是停位与出血前完全一样; 按宽度算列数的地方也要先减掉 [start]. 出血必须做在 Lazy 容器
 * 外面的测量层: LazyVerticalGrid 自带主轴裁剪, 在它里面做不到. 容器里其余按边定位的东西 (空结果提示之类) 要自己补回.
 */
fun Modifier.tvGridBleed(top: Dp = 0.dp, start: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val b = if (constraints.hasBoundedHeight) top.roundToPx() else 0
    val s = if (constraints.hasBoundedWidth) start.roundToPx() else 0
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = if (s > 0) constraints.maxWidth + s else constraints.minWidth,
            maxWidth = if (s > 0) constraints.maxWidth + s else constraints.maxWidth,
            minHeight = if (b > 0) constraints.maxHeight + b else constraints.minHeight,
            maxHeight = if (b > 0) constraints.maxHeight + b else constraints.maxHeight,
        ),
    )
    // placeRelative: 从右往左的布局里 start 在右边, 出血跟着翻到右侧
    layout(placeable.width - s, placeable.height - b) { placeable.placeRelative(-s, -b) }
}

/**
 * 网格条目越过吸顶线 (内容区顶, contentPadding 之后那条线) 之后的位置驱动淡出, 同探索页的 rowTopFade: 越线多远
 * 就多淡, 越过"卡高一半 + 行距"时完全消失 —— 下一行进来一半时上一行刚好看不见 (探索页定的观感基准; 网格的卡高
 * 随列数变, 所以按条目自己的高度现算, 不写死 88dp).
 *
 * 全读在 graphicsLayer 的 lambda 里, 滚动每帧只失效图层, 零重组. [CompositingStrategy.ModulateAlpha]: 理由同
 * rowTopFade —— 默认 Auto 在 alpha < 1 时把整张卡先画进离屏缓冲.
 */
fun Modifier.tvGridItemTopFade(state: LazyGridState, index: Int, rowSpacing: Dp): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.ModulateAlpha
    val top = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.y ?: 0
    alpha = if (top >= 0) 1f else (1f + top / (size.height / 2f + rowSpacing.toPx())).coerceIn(0f, 1f)
}

/**
 * 吸顶线以下的第一个可见条目 (中线在线下; 滚动途中也取对). "从上方进网格落到看得见的第一行"必须用它,
 * **不能用 `visibleItemsInfo.firstOrNull()`**: 出血之后, 越过吸顶线正在淡出 (已几乎看不见) 的上一行也在
 * visibleItemsInfo 里, 取它当落点, 吸顶滚动就把整个网格往回翻一行 (2026-09-11 用户报: 追番页从侧边栏回标签行
 * 再按下, 卡片往上翻了一行).
 */
fun LazyGridState.firstItemBelowTopLine(): LazyGridItemInfo? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.offset.y + it.size.height / 2 >= 0 }

/** 网格页卡片区向上出血量: 要大于淡出距离 (卡高一半 + 行距, 默认卡约 90dp), 留点余量给列数少时变大的卡. */
val TV_GRID_TOP_BLEED = 120.dp
