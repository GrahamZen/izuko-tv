/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.TvCardFocusStyle
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 网格页 (追番 / 搜索 / 时间表网格) 的**聚焦格**: 聚焦卡停稳后所在的那一格. 聚焦框画在格上, 卡片按
 * "离格多近"放大 —— 而不是"谁有焦点就放大谁".
 *
 * 为什么按位置而不按焦点: 追番 / 搜索网格聚焦行吸顶, 上下键时屏幕上的聚焦位置不动, 动的是整个网格.
 * 按焦点放大的话每按一下都是"旧卡原地缩回、新卡从下面滑上来再放大", 框在同一个位置闪一下. 按位置算就和
 * 探索页的固定框一样: 框钉在格上, 卡从框下滑过, 离开的边走边缩、进来的边走边放大.
 *
 * **换格** (左右键; 时间表网格在可见的几行之间上下) 则是旧格淡出、新格就地淡入, 框立刻到位, 不在两格之间滑动.
 * 每个格子各有一段淡入淡出, 连按时几格各走各的, 中途换目标不会跳变.
 *
 * 换格的淡入淡出是 [TV_CARD_FOCUS_TRANSITION_MILLIS]; 上下翻页时放大跟着滚动走, 没有单独的时长.
 *
 * [TvCardFocusStyle.Ring] (原版) 不经过这里: 卡片自己画描边、不放大 (见 [usesCardRing]).
 *
 * 坐标: 格 = (列号, 相对"内容区顶线"的行数). 顶线即 contentPadding 之后那条线 (`LazyGridItemInfo.offset.y == 0`);
 * 追番 / 搜索吸顶, 行恒为 0; 时间表网格 = 聚焦行 − 顶行. 行高按条目实测 (条目高 + 行距), 时间表卡连下方标题一起算.
 *
 * 全部在绘制阶段读 (卡片的 graphicsLayer / 框的 drawBehind): 焦点移动、淡入淡出与滚动都不触发重组.
 */
@Stable
class TvGridFocusSlot internal constructor() {
    /** 正在显示或淡出中的格子; 目标格淡到 1, 其余淡到 0 后移除. */
    internal val cells = mutableStateListOf<Cell>()

    /** 框与放大整体的显隐: 焦点在网格里 (或长按菜单开着) 时为 1. */
    internal val presence = Animatable(0f)

    internal var style by mutableStateOf(TvCardFocusStyle.ScaleAndRing)
    internal var animated = true
    internal var scope: CoroutineScope? = null

    // 以下只在回调里读写, 不参与绘制
    private var gridFocused = false
    private var held = false
    private var shown = false
    private var targetColumn = Int.MIN_VALUE
    private var targetRow = Int.MIN_VALUE

    internal class Cell(val column: Int, val row: Int, val fade: Animatable<Float, AnimationVector1D>)

    /** 聚焦格上那张卡的放大倍数 (随设置的样式; 1 = 不放大). */
    val focusScale: Float get() = style.focusScale

    /**
     * 原版样式: 描边由卡片自己画 (跟着焦点走, `TvPortraitCard(showFocusRing = true)`), 不放大, 聚焦格什么都不画.
     * 在组合里读 (决定卡片参数), 设置变了会重组.
     */
    val usesCardRing: Boolean get() = style == TvCardFocusStyle.Ring

    /**
     * 聚焦格换到第 [row] 行 (相对顶线) 第 [column] 列. 框还没显示 (焦点刚进网格) 时直接落位, 不做淡入淡出.
     */
    fun moveTo(column: Int, row: Int) {
        if (column == targetColumn && row == targetRow) return
        targetColumn = column
        targetRow = row
        val s = scope
        if (s == null || !animated || presence.value < PRESENCE_SNAP_THRESHOLD) {
            cells.clear()
            cells.add(Cell(column, row, Animatable(1f)))
            return
        }
        val target = cells.firstOrNull { it.column == column && it.row == row }
            ?: Cell(column, row, Animatable(0f)).also { cells.add(it) }
        for (cell in cells.toList()) {
            val isTarget = cell === target
            s.launch {
                // 新的一次 animateTo 抢走锁, 取消还在跑的上一段 (连按时从当前值接着走)
                cell.fade.animateTo(if (isTarget) 1f else 0f, FADE_SPEC)
                if (!isTarget && cell.fade.value == 0f && !(cell.column == targetColumn && cell.row == targetRow)) {
                    cells.remove(cell)
                }
            }
        }
    }

    /** 焦点在不在这个网格里. */
    fun setGridFocused(focused: Boolean) {
        gridFocused = focused
        syncPresence()
    }

    /** 长按菜单开着: 焦点进了菜单, 框与放大照旧保持 (缩回去看起来就像焦点丢了). */
    fun setHeld(held: Boolean) {
        this.held = held
        syncPresence()
    }

    private fun syncPresence() {
        val show = gridFocused || held
        if (show == shown) return
        shown = show
        val s = scope ?: return
        val target = if (show) 1f else 0f
        s.launch {
            if (animated) presence.animateTo(target, FADE_SPEC) else presence.snapTo(target)
        }
    }

    /**
     * 第 [index] 项此刻离聚焦格有多近 (0..1): 不在格的那一列即 0, 行方向差一个行高即 0; 再乘格的淡入与整体显隐.
     * 在 graphicsLayer / 绘制 lambda 里调.
     */
    internal fun weightOf(state: LazyGridState, index: Int, rowSpacingPx: Float): Float {
        val p = presence.value
        if (p <= 0f || cells.isEmpty()) return 0f
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0f
        val pitch = info.size.height + rowSpacingPx
        if (pitch <= 0f) return 0f
        var w = 0f
        for (cell in cells) {
            if (cell.column != info.column) continue
            val f = cell.fade.value
            if (f <= 0f) continue
            val wy = 1f - abs(info.offset.y - cell.row * pitch) / pitch
            if (wy > 0f) w += f * wy
        }
        return p * w.coerceAtMost(1f)
    }

    /** 第 [index] 项此刻的放大倍数. */
    fun scaleOf(state: LazyGridState, index: Int, rowSpacingPx: Float): Float {
        val s = focusScale
        if (s == 1f) return 1f
        return 1f + (s - 1f) * weightOf(state, index, rowSpacingPx)
    }

    internal companion object {
        /** 显隐低于它就算"框还没出来", 换格直接落位. */
        const val PRESENCE_SNAP_THRESHOLD = 0.05f

        private val FADE_SPEC = tween<Float>(TV_CARD_FOCUS_TRANSITION_MILLIS, easing = FastOutSlowInEasing)
    }
}

/** 各聚焦样式聚焦格上的放大倍数; 1 = 不放大. */
internal val TvCardFocusStyle.focusScale: Float
    get() = when (this) {
        TvCardFocusStyle.ScaleAndRing -> TV_CARD_FOCUS_SCALE
        TvCardFocusStyle.Scale -> TV_CARD_FOCUS_SCALE_WITHOUT_RING
        TvCardFocusStyle.Ring -> 1f
    }

/** 每个网格实例一份 (追番页换 tab 时新旧两个网格各一份, 各自随网格滑入滑出). */
@Composable
fun rememberTvGridFocusSlot(): TvGridFocusSlot {
    val settings = LocalThemeSettings.current
    // 样式在创建时就定下来: 首帧卡片要按它决定自己画不画描边 (见 usesCardRing), 等 SideEffect 就晚了一帧
    val slot = remember { TvGridFocusSlot().apply { style = settings.tvCardFocusStyle } }
    val scope = rememberCoroutineScope()
    SideEffect {
        slot.scope = scope
        slot.style = settings.tvCardFocusStyle
        // 流畅档不做过渡, 直接到位 (与该档其它瞬切一致)
        slot.animated = settings.visualEffects.transitions
    }
    return slot
}

/** 卡片按离聚焦格的远近放大 (按中心). 挂在要一起放大的那一层上. */
fun Modifier.tvGridFocusSlotScale(
    slot: TvGridFocusSlot,
    state: LazyGridState,
    index: Int,
    rowSpacing: Dp,
): Modifier = graphicsLayer {
    val s = slot.scaleOf(state, index, rowSpacing.toPx())
    scaleX = s
    scaleY = s
}

/**
 * 聚焦框: 叠在网格**上方**, 与网格同一个容器、同样铺满 (两者坐标原点一致). [contentStart] / [contentTop] 是网格的
 * contentPadding 起始与顶部 —— 条目 offset 从内边距之后算起.
 *
 * 框的尺寸取条目实测宽度 (与卡片同宽), 高按竖版封面宽高比算 (时间表卡下方还有标题, 条目高不是封面高).
 */
@Composable
fun TvGridFocusSlotRing(
    slot: TvGridFocusSlot,
    state: LazyGridState,
    contentStart: Dp,
    contentTop: Dp,
    rowSpacing: Dp,
    modifier: Modifier = Modifier,
) {
    // 只有"放大 + 描边"由聚焦格画框; 原版的框在卡片上, 仅放大不画框
    if (slot.style != TvCardFocusStyle.ScaleAndRing) return
    val brush = TvFocusRing.defaultBrush
    Box(
        modifier.fillMaxSize().drawBehind {
            val p = slot.presence.value
            if (p <= 0f) return@drawBehind
            val items = state.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) return@drawBehind
            for (cell in slot.cells) {
                val f = cell.fade.value
                if (f <= 0f) continue
                val ref = items.firstOrNull { it.column == cell.column } ?: continue
                val w = ref.size.width
                val h = (w / TV_PORTRAIT_CARD_COVER_RATIO).roundToInt()
                val pitch = ref.size.height + rowSpacing.toPx()
                drawTvFocusRingAt(
                    topLeft = Offset(contentStart.toPx() + ref.offset.x, contentTop.toPx() + cell.row * pitch),
                    size = Size(w.toFloat(), h.toFloat()),
                    cornerRadius = TV_PORTRAIT_CARD_CORNER + TvFocusRing.Gap,
                    brush = brush,
                    scale = 1f + (slot.focusScale - 1f) * p * f,
                    alpha = p * f,
                )
            }
        },
    )
}
