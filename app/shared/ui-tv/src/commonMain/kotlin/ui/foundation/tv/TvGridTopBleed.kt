/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compose 网格 (时间表网格) 卡片区的出血; 原生海报墙网格的出血见 TvNativeGridMetrics.
 *
 * **向上 ([top])**: 离场的行越过网格顶边继续上移 (由条目自己按位置淡出或压暗), 而不是在网格顶边被 90 度硬切.
 *
 * **向左 ([start])**: 首列卡聚焦放大时向左伸出几 dp, 网格自己 clipToBounds, 不出血就被左边界切掉 (见 [TV_GRID_START_BLEED]).
 *
 * 做法: 布局上仍按原尺寸参与排版, 只在测量时多要出血量、放置时反向挪同样的距离. 网格的 contentPadding 也要在对应一侧
 * 加上出血量 —— 停位按条目 offset 算 (TvScrollAnimator, offset 0 = 内边距之后), 于是停位与出血前完全一样; 按宽度算列数的
 * 地方也要先减掉 [start]. 出血必须做在 Lazy 容器外面的测量层: LazyVerticalGrid 自带主轴裁剪, 在它里面做不到. 容器里其余
 * 按边定位的东西 (空结果提示之类) 要自己补回.
 *
 * **向下 ([bottom])**: 网格往屏幕底边外多排一截, 屏外的下一行就一直是组合好、排好的 —— 焦点走过去时滚动器能按它的位置跑
 * spring; 否则目标没组合, 送焦会先 `scrollToItem` 瞬移、滚动器也退回自带的快动画, 看着就是"闪现". 底部补白要把这一截加回去,
 * 否则末行滚不到位.
 */
fun Modifier.tvGridBleed(top: Dp = 0.dp, start: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val b = if (constraints.hasBoundedHeight) top.roundToPx() else 0
    val e = if (constraints.hasBoundedHeight) bottom.roundToPx() else 0
    val s = if (constraints.hasBoundedWidth) start.roundToPx() else 0
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = if (s > 0) constraints.maxWidth + s else constraints.minWidth,
            maxWidth = if (s > 0) constraints.maxWidth + s else constraints.maxWidth,
            minHeight = if (b + e > 0) constraints.maxHeight + b + e else constraints.minHeight,
            maxHeight = if (b + e > 0) constraints.maxHeight + b + e else constraints.maxHeight,
        ),
    )
    // placeRelative: 从右往左的布局里 start 在右边, 出血跟着翻到右侧
    layout(placeable.width - s, placeable.height - b - e) { placeable.placeRelative(-s, -b) }
}

/**
 * 卡片越过停靠线后的压暗渐变距离: 在此距离内从全亮渐变到 [TV_CARD_PAST_DIM_ALPHA] 并保持. 海报墙的横滑行 (越过行首)
 * 与网格 (越过顶线) 共用, 见原生视图 (TvNativeRowView / TvNativeGridView).
 */
internal val TV_CARD_FADE_DISTANCE = 64.dp

/**
 * 越过停靠线的离场卡片的压暗亮度 (Prime 式暗区, 同选集轮播的左侧压暗): 横滑行的卡滑过行首进左侧出血区 (侧边栏底下)、
 * 网格海报墙的卡滑到标签行 / 搜索栏底下时压暗可见, 不是硬切消失.
 */
internal const val TV_CARD_PAST_DIM_ALPHA = 0.45f

/** 网格海报墙 (追番 / 搜索) 卡片区向上出血量: 越过网格顶线的行在这一截里压暗着继续上移 (画在顶栏底下), 到出血边才裁掉. */
val TV_GRID_TOP_BLEED = 120.dp
