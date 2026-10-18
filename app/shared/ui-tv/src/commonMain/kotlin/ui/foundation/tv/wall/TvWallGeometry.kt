/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Immutable
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PAST_DIM_ALPHA

/*
 * Compose 版网格海报墙 (追番 / 搜索 / 新番时间表) 的停位, 与原生网格 (TvNativeGridView) 同一套规则: 聚焦行尽量停在视口垂直正中,
 * 开头几行与末尾几行放不到正中就贴边; hero 态里聚焦行的行顶停在 hero 线上, 网格滚不到的那部分交给整片平移.
 * 停位只由聚焦行决定, 不看网格现在停在哪.
 */

/**
 * 网格聚焦第 [row] 行时该滚到哪 (px, 首行顶贴视口顶时为 0): 这一行的中线对准视口中线, 两头放不到正中就贴边 —— 开头几行不滚
 * (不小于 0), 末尾停在末行底边离视口底 [endMarginPx] 处; 内容不满一屏时恒为 0.
 *
 * @param pitchPx 行高 + 行距.
 * @param rowHeightPx 行高 (海报 + 两行番名).
 * @param viewportPx 视口高 (去掉上下出血).
 */
internal fun tvWallCenteredScroll(
    row: Int,
    rowCount: Int,
    pitchPx: Int,
    rowHeightPx: Int,
    viewportPx: Int,
    endMarginPx: Int,
): Int {
    val centered = row * pitchPx + rowHeightPx / 2 - viewportPx / 2
    val max = ((rowCount - 1).coerceAtLeast(0) * pitchPx + rowHeightPx + endMarginPx - viewportPx).coerceAtLeast(0)
    return centered.coerceIn(0, max)
}

/** 网格停位: 滚到 [scrollPx], 整片再平移 [shiftPx] (正 = 往下推). */
@Immutable
internal data class TvWallGridStop(val scrollPx: Int, val shiftPx: Int)

/**
 * 网格海报墙的尺寸 (px). 卡片按 [rowHeightPx] 定高, 行与行的间隔就是精确的 [pitchPx], 网格滚过多少像素由首项下标与偏移直接换算.
 */
@Immutable
internal data class TvWallGridGeometry(
    val rowHeightPx: Int,
    val rowSpacingPx: Int,
    val viewportPx: Int,
    val endMarginPx: Int,
) {
    val pitchPx: Int get() = rowHeightPx + rowSpacingPx

    private fun maxScroll(rowCount: Int): Int =
        ((rowCount - 1).coerceAtLeast(0) * pitchPx + rowHeightPx + endMarginPx - viewportPx).coerceAtLeast(0)

    /** 聚焦第 [index] 项时网格该滚到的位置 (像素). */
    fun scrollFor(index: Int, columns: Int, itemCount: Int): Int {
        val cols = columns.coerceAtLeast(1)
        return tvWallCenteredScroll(
            row = index / cols,
            rowCount = (itemCount + cols - 1) / cols,
            pitchPx = pitchPx,
            rowHeightPx = rowHeightPx,
            viewportPx = viewportPx,
            endMarginPx = endMarginPx,
        )
    }

    /** 网格此刻滚过了多少像素. */
    fun scrolledOf(gridState: LazyGridState, columns: Int): Int =
        gridState.firstVisibleItemIndex / columns.coerceAtLeast(1) * pitchPx + gridState.firstVisibleItemScrollOffset

    /**
     * hero 态聚焦第 [index] 项时的停位: 这一行的顶边对准 [heroLinePx] (从网格顶线算). 网格滚不到的那部分 (开头几行要往下推,
     * 末尾几行滚到底还不够) 交给整片平移 [TvWallGridStop.shiftPx].
     */
    fun heroStopFor(index: Int, columns: Int, itemCount: Int, heroLinePx: Int): TvWallGridStop {
        val cols = columns.coerceAtLeast(1)
        val desired = index / cols * pitchPx - heroLinePx
        val scroll = desired.coerceIn(0, maxScroll((itemCount + cols - 1) / cols))
        return TvWallGridStop(scroll, scroll - desired)
    }

    /** 聚焦第 [index] 项时的停位 (hero 态见 [heroStopFor]). */
    fun stopFor(index: Int, columns: Int, itemCount: Int, hero: Boolean, heroLinePx: Int): TvWallGridStop =
        if (hero) heroStopFor(index, columns, itemCount, heroLinePx) else TvWallGridStop(scrollFor(index, columns, itemCount), 0)
}

/**
 * 网格第 [index] 张卡按位置的淡化 (1 = 不淡), 在卡片墙与 hero 态之间按 [TvWallHeroTimeline.above] 过渡:
 * - 卡片墙: 越过顶线往上走时在 [fadeDistancePx] 内压暗到 [TV_CARD_PAST_DIM_ALPHA] 并保持 (从顶栏底下照样看得见);
 * - hero 态: 越过聚焦行停的那条线 ([heroLinePx], 整片平移 [shiftPx] 之后的位置) 往上走时淡没.
 * 进出途中 (还没停稳) 不按位置淡: 聚焦行及以下不淡, 上面的行整行跟着进度淡 —— 它们途中要从 hero 线上方经过, 按位置淡就是先暗一下再亮回来.
 * 在绘制阶段调用.
 */
internal fun tvWallGridItemAlpha(
    state: LazyGridState,
    index: Int,
    hero: TvWallHeroTimeline,
    heroLinePx: Int,
    columns: Int,
    fadeDistancePx: Float,
    focusedIndex: Int,
    shiftPx: Float,
): Float {
    val top = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.y ?: 0
    val wallAlpha = if (top >= 0) 1f else 1f - (-top / fadeDistancePx).coerceIn(0f, 1f) * (1f - TV_CARD_PAST_DIM_ALPHA)
    val p = hero.above
    if (p <= 0f) return wallAlpha
    val cols = columns.coerceAtLeast(1)
    val heroAlpha = if (hero.content < 1f && focusedIndex >= 0) {
        if (index / cols >= focusedIndex / cols) 1f else 0f
    } else {
        val aboveLine = heroLinePx - (top + shiftPx)
        if (aboveLine <= 0f) 1f else 1f - (aboveLine / fadeDistancePx).coerceIn(0f, 1f)
    }
    return wallAlpha + (heroAlpha - wallAlpha) * p
}
