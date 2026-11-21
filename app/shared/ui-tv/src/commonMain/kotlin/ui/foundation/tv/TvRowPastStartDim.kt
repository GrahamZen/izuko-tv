/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Compose 横滑行 (LazyRow) 里第 [index] 张卡滑过行首 (contentPadding 的 start, 即停靠线) 后压暗着出屏, 同原生海报墙的横滑行
 * (TvNativeRowView 的 pastStartDim): 卡的左缘越线 [TV_CARD_FADE_DISTANCE] 内从全亮渐变到 [TV_CARD_PAST_DIM_ALPHA].
 * 往左出血的行 (平板详情页, 见 [tvGridBleed]) 滑到侧边栏底下的卡因此是淡的, 不跟侧边栏的图标抢.
 * 在绘制层读滚动位置, 滑动时只重画、不重组.
 */
fun Modifier.tvRowPastStartDim(state: LazyListState, index: Int): Modifier = graphicsLayer {
    val offset = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: 0
    alpha = if (offset >= 0) {
        1f
    } else {
        val t = (-offset / TV_CARD_FADE_DISTANCE.toPx()).coerceIn(0f, 1f)
        1f - t * (1f - TV_CARD_PAST_DIM_ALPHA)
    }
}
