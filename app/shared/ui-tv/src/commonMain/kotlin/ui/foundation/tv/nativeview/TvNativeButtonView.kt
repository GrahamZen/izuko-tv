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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.ViewGroup
import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

/**
 * 按钮的样子: 一行字 ([label]), 圆角底. 平时 [bg] 底 [fg] 字, 聚焦时 [focusBg] 底 [focusFg] 字 ([touch] 时不画聚焦), 触屏按下罩一层
 * [pressedOverlay]. 高至少 [heightPx], 字上下至少留 [paddingVPx]; 左右留白: 字居中时 [paddingCenterPx], 靠左时 [paddingStartPx].
 */
@Immutable
internal data class TvNativeButtonStyle(
    val label: TvNativeTextStyle,
    val heightPx: Int,
    val cornerPx: Float,
    val paddingCenterPx: Int,
    val paddingStartPx: Int,
    val paddingVPx: Int,
    val bg: Int,
    val fg: Int,
    val focusBg: Int,
    val focusFg: Int,
    val pressedOverlay: Int,
    val touch: Boolean,
)

/**
 * 一组按钮的排法: 至多 [maxInRow] 颗横排 (间距 [rowGapPx], 各占自己的宽, 最多平分), 更多的一颗一行竖排 (定宽 [columnWidthPx],
 * 栏窄时按栏宽; 间距 [columnGapPx]).
 */
@Immutable
internal data class TvNativeButtonGroupStyle(
    val button: TvNativeButtonStyle,
    val maxInRow: Int,
    val rowGapPx: Int,
    val columnGapPx: Int,
    val columnWidthPx: Int,
)

/**
 * 一颗按钮: 圆角底 (聚焦实底, 触屏上不画聚焦), 一行字, [center] 时居中、否则靠左. 宽: 量给 EXACTLY 时占满, AT_MOST 时按字宽 (最宽占满),
 * UNSPECIFIED 时按字宽.
 */
@SuppressLint("ViewConstructor")
internal class TvNativeButtonView(context: Context, private var style: TvNativeButtonStyle) : ViewGroup(context) {
    private val label = tvNativeText(context, style.label)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 在所在那一组里的位置 (见 [TvNativeButtonGroupView]). */
    var index: Int = 0

    var center: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        addView(label)
    }

    val text: String get() = label.text.toString()

    /** 窗口被叠在上面的小窗拿走了焦点: 不画聚焦的样子 (不然小窗里的按钮与这一颗都画着焦点). */
    var covered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyColors()
        }

    fun bind(text: String, style: TvNativeButtonStyle) {
        if (this.style != style) {
            this.style = style
            style.label.applyTo(label)
        }
        label.setTextIfChanged(text)
        applyColors()
    }

    private fun focusLook() = isFocused && !style.touch && !covered

    private fun applyColors() {
        label.setTextColor(if (focusLook()) style.focusFg else style.fg)
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyColors()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun paddingH() = if (center) style.paddingCenterPx else style.paddingStartPx

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val room = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2 else size - paddingH() * 2
        label.measureWithin(room)
        val wrap = label.measuredWidth + paddingH() * 2
        val width = when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(wrap, size)
            else -> wrap
        }
        val height = max(s.heightPx, label.measuredHeight + s.paddingVPx * 2)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val x = if (center) (w - label.measuredWidth) / 2 else paddingH()
        label.tvNativeLayoutAt(x, (b - t - label.measuredHeight) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        canvas.drawTvNativePlate(rect, width, height, s.cornerPx, if (focusLook()) s.focusBg else s.bg, paint)
        if (isPressed && s.touch) canvas.drawTvNativePlate(rect, width, height, s.cornerPx, s.pressedOverlay, paint)
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/**
 * 一组按钮 (见 [TvNativeButtonGroupStyle] 的排法): 横排的字居中, 竖排的靠左. 按钮在内容变化时复用 (个数不变就只换字), 持焦的那颗不丢焦点.
 * 按了哪一颗、哪一颗得失焦点报给 [onClick] / [onFocus] (按位置).
 */
@SuppressLint("ViewConstructor")
internal class TvNativeButtonGroupView(context: Context, private var style: TvNativeButtonGroupStyle) : ViewGroup(context) {
    val buttons = ArrayList<TvNativeButtonView>()
    var onClick: ((Int) -> Unit)? = null
    var onFocus: ((Int, Boolean) -> Unit)? = null

    val inRow: Boolean get() = buttons.size <= style.maxInRow

    /** 窗口被叠在上面的小窗拿走了焦点 (见 [TvNativeButtonView.covered]). */
    var covered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            buttons.forEach { it.covered = value }
        }

    fun bind(labels: List<String>, style: TvNativeButtonGroupStyle) {
        this.style = style
        while (buttons.size > labels.size) {
            val removed = buttons.removeAt(buttons.lastIndex)
            removeView(removed)
        }
        while (buttons.size < labels.size) {
            val button = TvNativeButtonView(context, style.button)
            button.index = buttons.size
            button.covered = covered
            button.setOnClickListener { onClick?.invoke(button.index) }
            button.setOnFocusChangeListener { _, hasFocus -> onFocus?.invoke(button.index, hasFocus) }
            buttons += button
            addView(button)
        }
        val center = labels.size <= style.maxInRow
        labels.forEachIndexed { i, label ->
            buttons[i].center = center
            buttons[i].bind(label, style.button)
        }
        requestLayout()
    }

    /** 持焦的那一颗的位置; 没有为 -1. */
    fun focusedIndex(): Int = buttons.indexOfFirst { it.isFocused }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (buttons.isEmpty()) {
            setMeasuredDimension(width, 0)
            return
        }
        if (inRow) {
            val gaps = s.rowGapPx * (buttons.size - 1)
            val share = (width - gaps) / buttons.size
            var height = 0
            buttons.forEach {
                it.measure(tvNativeAtMost(share), TV_NATIVE_UNSPECIFIED)
                height = max(height, it.measuredHeight)
            }
            setMeasuredDimension(width, height)
        } else {
            val columnWidth = min(s.columnWidthPx, width)
            var height = 0
            buttons.forEachIndexed { i, button ->
                button.measure(tvNativeExactly(columnWidth), TV_NATIVE_UNSPECIFIED)
                height += button.measuredHeight + if (i > 0) s.columnGapPx else 0
            }
            setMeasuredDimension(width, height)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        if (inRow) {
            var x = 0
            val rowHeight = b - t
            buttons.forEach {
                it.layout(x, 0, x + it.measuredWidth, rowHeight)
                x += it.measuredWidth + s.rowGapPx
            }
        } else {
            var y = 0
            buttons.forEach {
                it.tvNativeLayoutAt(0, y)
                y += it.measuredHeight + s.columnGapPx
            }
        }
    }
}
