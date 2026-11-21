/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.ViewGroup
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextView

/**
 * 说明栏里的一串气泡 (见 [TvSettingsBubble], 如反馈的会话): 从上往下排, 别人的靠左、自己的靠右, 每个最宽占栏宽的 [MAX_WIDTH_FRACTION].
 * 高度给多少画多少: 放不下的那个按整行截掉 (至少露出抬头和一行正文, 否则不画), 再往后的不画.
 *
 * 只在换内容时改子视图的显隐; 截断靠排版时把正文的框排矮 (框外的行被裁掉), 不在测量里改行数 —— 改行数会再要一次布局, 每帧来回.
 */
@SuppressLint("ViewConstructor")
internal class TvSettingsBubblesView(context: Context, private var style: TvSettingsStyle) : ViewGroup(context) {
    private class Bubble(val meta: TvNativeTextView, val text: TvNativeTextView) {
        var mine = false
        var hasText = false

        /** 这一次排版里画不画、气泡的框 (相对本视图)、正文露出多高. */
        var shown = false
        val frame = RectF()
        var textHeight = 0
    }

    private val bubbles = ArrayList<Bubble>()
    private var items: List<TvSettingsBubble> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    fun applyStyle(style: TvSettingsStyle) {
        this.style = style
        for (b in bubbles) applyTo(b)
        requestLayout()
        invalidate()
    }

    fun setBubbles(items: List<TvSettingsBubble>) {
        if (this.items == items) return
        this.items = items
        while (bubbles.size < items.size) {
            val b = Bubble(TvNativeTextView(context), TvNativeTextView(context))
            for (v in arrayOf(b.meta, b.text)) {
                v.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                addView(v)
            }
            b.meta.maxLines = 1
            b.text.maxLines = Int.MAX_VALUE
            applyTo(b)
            bubbles += b
        }
        for ((i, b) in bubbles.withIndex()) {
            val item = items.getOrNull(i)
            b.mine = item?.mine == true
            b.hasText = !item?.text.isNullOrEmpty()
            b.meta.text = item?.meta.orEmpty()
            b.text.text = item?.text.orEmpty()
            b.meta.visibility = if (item != null) VISIBLE else GONE
            b.text.visibility = if (b.hasText) VISIBLE else GONE
        }
        requestLayout()
        invalidate()
    }

    private fun applyTo(b: Bubble) {
        style.bubbleMeta.applyTo(b.meta)
        style.bubbleText.applyTo(b.text)
    }

    private val padH get() = style.rowPaddingHPx * 3 / 4
    private val padV get() = style.qrGapPx * 2 / 3
    private val gap get() = style.qrGapPx * 2 / 3
    private val metaGap get() = style.qrGapPx / 4

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        val inner = ((width * MAX_WIDTH_FRACTION).toInt() - padH * 2).coerceAtLeast(0)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var bottom = 0
        var full = false
        for ((i, b) in bubbles.withIndex()) {
            b.shown = false
            if (i >= items.size) continue
            // 字换了要重新量 (同 TvSettingsView 的说明栏)
            b.meta.forceLayout()
            b.text.forceLayout()
            b.meta.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), unspecified)
            if (b.hasText) b.text.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST), unspecified)
            if (full) continue
            val top = if (bottom == 0) 0 else bottom + gap
            val head = padV + b.meta.measuredHeight
            var textHeight = if (b.hasText) b.text.measuredHeight else 0
            if (top + head + (if (b.hasText) metaGap else 0) + textHeight + padV > avail) {
                // 放不下: 正文截到能整行露出的地方, 一行都露不出就不画这个
                full = true
                val room = avail - top - head - metaGap - padV
                val layout = b.text.layout
                val lines = if (b.hasText && layout != null && room > 0) (0 until layout.lineCount).count { layout.getLineBottom(it) <= room } else 0
                if (lines == 0) continue
                textHeight = layout!!.getLineBottom(lines - 1)
            }
            val contentWidth = maxOf(b.meta.measuredWidth, if (b.hasText) b.text.measuredWidth else 0)
            val bubbleWidth = contentWidth + padH * 2
            val left = if (b.mine) width - bubbleWidth else 0
            val height = head + (if (b.hasText) metaGap + textHeight else 0) + padV
            b.frame.set(left.toFloat(), top.toFloat(), (left + bubbleWidth).toFloat(), (top + height).toFloat())
            b.textHeight = textHeight
            b.shown = true
            bottom = top + height
        }
        setMeasuredDimension(width, bottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (bubble in bubbles) {
            if (!bubble.shown) {
                bubble.meta.layout(0, 0, 0, 0)
                bubble.text.layout(0, 0, 0, 0)
                continue
            }
            val x = bubble.frame.left.toInt() + padH
            var y = bubble.frame.top.toInt() + padV
            bubble.meta.layout(x, y, x + bubble.meta.measuredWidth, y + bubble.meta.measuredHeight)
            y += bubble.meta.measuredHeight + metaGap
            // 截掉的行排在框外, 被裁掉
            if (bubble.hasText) bubble.text.layout(x, y, x + bubble.text.measuredWidth, y + bubble.textHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = style.cornerPx * 1.4f
        for (b in bubbles) {
            if (!b.shown) continue
            paint.color = if (b.mine) style.bubbleMineBg else style.activeBg
            canvas.drawRoundRect(b.frame, radius, radius, paint)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false

    private companion object {
        const val MAX_WIDTH_FRACTION = 0.88f
    }
}
