/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.focus.TV_FOCUS_MOVE_MAX_PER_SECOND_HORIZONTAL
import me.him188.ani.app.ui.foundation.focus.TV_FOCUS_MOVE_MAX_PER_SECOND_VERTICAL
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PAST_DIM_ALPHA

/**
 * 原生海报墙的一条横滑行 (探索页的每一行、详情页的关联条目), 行为见 [TvNativeStripView]: 按需挪, 行首按左默认放出去 (探索页的按键在
 * TvNativeExploreView 里就接住了, 到不了这里; 详情页单独成行时吞掉, 见 TvNativePosterStrip), 行尾按右吞掉, 上下键不管.
 *
 * 滑过行首的卡压暗着出屏 (越线 [fadeDistancePx] 内压到 [TV_CARD_PAST_DIM_ALPHA]). 长按连发时下一张要总是已经排好 (领先滚动的张数见
 * [TV_NATIVE_STRIP_HOLD_LEAD_CARDS]): 探索页的行一直在行外左右各多排这么多张 —— 按键在 TvNativeExploreView 里就接住了, 行等不到自己的
 * 第一次左右键, 按住时焦点一路换卡又一直推迟按焦点起算的延时; [standalone] (详情页 / 人物页单独成行) 平时只多排一张, 行里有焦点之后
 * 再补齐 (见 TvNativeStripView 的 aheadLayoutPx).
 */
@SuppressLint("ViewConstructor")
class TvNativeRowView(
    context: Context,
    val style: TvNativeWallStyle,
    sketch: Sketch,
    pool: RecyclerView.RecycledViewPool?,
    startPx: Int,
    endPx: Int,
    bottomPx: Int = 0,
    private val fadeDistancePx: Float,
    topPx: Int = 0,
    standalone: Boolean = false,
) : TvNativeStripView(
    context,
    stepPx = style.cardWidthPx + style.columnSpacingPx,
    spacingPx = style.columnSpacingPx,
    extraLayoutPx = (style.cardWidthPx + style.columnSpacingPx) * if (standalone) 1 else TV_NATIVE_STRIP_HOLD_LEAD_CARDS,
    prefetchItems = style.prefetchItems,
    startPx = startPx,
    endPx = endPx,
    topPx = topPx,
    bottomPx = bottomPx,
    aheadLayoutPx = if (standalone) (style.cardWidthPx + style.columnSpacingPx) * (TV_NATIVE_STRIP_HOLD_LEAD_CARDS - 1) else 0,
) {
    val cards = TvNativeCardAdapter(style, sketch)

    /** 整行的淡化 (hero 态越线淡没, 0..1), 与越过行首的压暗相乘. */
    var rowAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            cards.refreshDim(this)
        }

    init {
        pool?.let { setRecycledViewPool(it) }
        cards.dimOf = { _, view -> pastStartDim(view) * rowAlpha }
        adapter = cards
    }

    override fun onRowMoved() = cards.refreshDim(this)

    /**
     * 换一行的数据 ([leftIndex] = 回收前 / 跨导航记下的行首, [columns] = 屏上完整放得下几张, [focusIndex] = 选中哪张, 落在屏上那几张里;
     * -1 = 行首那张), 见 [placeAfterSubmit].
     */
    fun bind(cards: List<TvNativeCard?>, keyOf: (Int) -> Long, leftIndex: Int, columns: Int, focusIndex: Int = -1) {
        this.cards.submit(cards, keyOf)
        placeAfterSubmit(cards.size, leftIndex, columns, focusIndex)
    }

    private fun pastStartDim(view: View): Float {
        val x = view.left - paddingLeft
        if (x >= 0) return 1f
        val t = (-x / fadeDistancePx).coerceIn(0f, 1f)
        return 1f - t * (1f - TV_CARD_PAST_DIM_ALPHA)
    }
}

/** 长按方向键时横向最快多久挪一次 (上限同 tvFocusMoveRateLimit, [TV_FOCUS_MOVE_MAX_PER_SECOND_HORIZONTAL]). */
internal const val TV_NATIVE_HORIZONTAL_REPEAT_MILLIS = 1000L / TV_FOCUS_MOVE_MAX_PER_SECOND_HORIZONTAL

/** 纵向 ([TV_FOCUS_MOVE_MAX_PER_SECOND_VERTICAL]). */
internal const val TV_NATIVE_VERTICAL_REPEAT_MILLIS = 1000L / TV_FOCUS_MOVE_MAX_PER_SECOND_VERTICAL

/**
 * 横滑行按需挪: 焦点走到第 [target] 张 (整行 [count] 张, 屏上完整放得下 [columns] 张) 后, 行首该是第几张. 在屏上完整露出的
 * 几张之间走时不动; 走到右边露一截的那张, 整行往左挪到它刚好完整露出 (贴右), 往左同理贴左. 聚焦卡落在屏上第
 * `target - 行首` 列.
 *
 * @param current 现在的行首.
 */
internal fun tvStripLeftIndex(current: Int, target: Int, count: Int, columns: Int): Int {
    val maxLeft = (count - columns).coerceAtLeast(0)
    var left = current.coerceIn(0, maxLeft)
    if (target < left) left = target
    if (target > left + columns - 1) left = target - columns + 1
    return left.coerceIn(0, maxLeft)
}
