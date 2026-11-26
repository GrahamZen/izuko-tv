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
import android.graphics.RectF
import android.view.ViewGroup
import androidx.compose.runtime.Immutable
import kotlin.math.max

/**
 * 气泡的样子: 抬头 ([meta], 谁、什么时候) 与正文 ([text]) 的字; 最宽占量给的宽的 [maxWidthFraction]; 内边距、抬头与正文的间距 [lineGapPx]、圆角;
 * 自己发的与别人的底色; 抬头旁没看过的点 (边长、与抬头的间距、颜色). [fitText] 时字按 measureWithin 量 (按内容宽多给一点, 末尾的字不被挤到
 * 下一行), 否则按最宽 AT_MOST 量.
 */
@Immutable
internal data class TvNativeBubbleStyle(
    val meta: TvNativeTextStyle,
    val text: TvNativeTextStyle,
    val maxWidthFraction: Float,
    val paddingHPx: Int,
    val paddingVPx: Int,
    val lineGapPx: Int,
    val cornerPx: Float,
    val mineBg: Int,
    val otherBg: Int,
    val dotPx: Int = 0,
    val dotGapPx: Int = 0,
    val dotColor: Int = 0,
    val fitText: Boolean = true,
)

/** 一个气泡的内容: 抬头、正文 (null = 只有抬头)、自己发的 (靠右)、没看过 (抬头旁一个点). */
@Immutable
internal data class TvNativeBubble(
    val meta: String,
    val text: String?,
    val mine: Boolean,
    val fresh: Boolean = false,
)

/**
 * 一个气泡 (来往记录里的一条): 抬头一行 (没看过的旁边一个点), 下面正文; 自己画圆角底, 不嵌套布局. 宽按内容, 最宽占量给的宽的
 * [TvNativeBubbleStyle.maxWidthFraction]; 靠左还是靠右由外面摆 (看 [mine]).
 *
 * 量的高给了上限 (不是 UNSPECIFIED) 而放不下时: 正文截到能整行露出的地方 ([cut]), 一行都露不出就不画 ([shown] 为 false, 量出 0 × 0).
 * 截断靠排版时把正文的框排矮 (框外的行被裁掉), 不改行数 —— 改行数会再要一次布局.
 */
@SuppressLint("ViewConstructor")
internal open class TvNativeBubbleView(context: Context, private var style: TvNativeBubbleStyle) : ViewGroup(context) {
    private val meta = tvNativeText(context, style.meta)
    private val text = tvNativeText(context, style.text, maxLines = Int.MAX_VALUE)
    private var fresh = false
    private var textHeight = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 自己发的: 外面把它摆在右边. */
    var mine: Boolean = false
        private set

    /** 这一次量下来画不画. */
    var shown: Boolean = true
        private set

    /** 这一次量下来正文截掉了一截 (或整个没画). */
    var cut: Boolean = false
        private set

    init {
        setWillNotDraw(false)
        addView(meta)
        addView(text)
    }

    fun applyStyle(style: TvNativeBubbleStyle) {
        if (this.style == style) return
        this.style = style
        style.meta.applyTo(meta)
        style.text.applyTo(text)
        requestLayout()
        invalidate()
    }

    fun bind(bubble: TvNativeBubble, style: TvNativeBubbleStyle) {
        applyStyle(style)
        mine = bubble.mine
        fresh = bubble.fresh
        meta.setTextIfChanged(bubble.meta)
        text.visibility = if (bubble.text == null) GONE else VISIBLE
        text.setTextIfChanged(bubble.text.orEmpty())
        requestLayout()
        invalidate()
    }

    private fun dotTakes() = if (fresh) style.dotPx + style.dotGapPx else 0

    private fun measureText(view: TvNativeTextView, room: Int) {
        if (style.fitText) view.measureWithin(room) else view.measure(tvNativeAtMost(room), TV_NATIVE_UNSPECIFIED)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val outer = (MeasureSpec.getSize(widthMeasureSpec) * s.maxWidthFraction).toInt()
        val inner = outer - s.paddingHPx * 2
        // 字常在布局途中换: 每次都重新量, 不按旧字的宽度排
        meta.forceLayout()
        text.forceLayout()
        measureText(meta, inner - dotTakes())
        val hasText = text.visibility != GONE
        if (hasText) measureText(text, inner)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        val head = s.paddingVPx + meta.measuredHeight
        val textGap = if (hasText) s.lineGapPx else 0
        textHeight = if (hasText) text.measuredHeight else 0
        shown = true
        cut = false
        if (head + textGap + textHeight + s.paddingVPx > limit) {
            // 放不下: 正文截到能整行露出的地方, 一行都露不出就不画
            cut = true
            val room = limit - head - s.lineGapPx - s.paddingVPx
            val layout = text.layout
            val lines = if (hasText && layout != null && room > 0) (0 until layout.lineCount).count { layout.getLineBottom(it) <= room } else 0
            if (layout == null || lines == 0) {
                shown = false
                setMeasuredDimension(0, 0)
                return
            }
            textHeight = layout.getLineBottom(lines - 1)
        }
        val contentWidth = max(meta.measuredWidth + dotTakes(), if (hasText) text.measuredWidth else 0)
        setMeasuredDimension(contentWidth + s.paddingHPx * 2, head + textGap + textHeight + s.paddingVPx)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!shown) {
            meta.layout(0, 0, 0, 0)
            text.layout(0, 0, 0, 0)
            return
        }
        val s = style
        meta.tvNativeLayoutAt(s.paddingHPx, s.paddingVPx)
        if (text.visibility != GONE) {
            // 截掉的行排在框外, 被裁掉
            val top = meta.bottom + s.lineGapPx
            text.layout(s.paddingHPx, top, s.paddingHPx + text.measuredWidth, top + textHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        val s = style
        canvas.drawTvNativePlate(rect, width, height, s.cornerPx, if (mine) s.mineBg else s.otherBg, paint)
        if (fresh) {
            val radius = s.dotPx / 2f
            paint.color = s.dotColor
            canvas.drawCircle(meta.right + s.dotGapPx + radius, (meta.top + meta.bottom) / 2f, radius, paint)
        }
    }
}

/**
 * 一串气泡 (如反馈的来往记录) 从上往下排, 别人的靠左、自己的靠右, 之间隔 [gapPx]. 高度给多少画多少: 放不下的那个正文按整行截掉
 * (至少露出抬头和一行正文, 否则不画), 再往后的不画 (见 [TvNativeBubbleView]).
 * 只在换内容时改子视图的显隐, 量的时候不改 (改了会再要一次布局, 每帧来回).
 */
@SuppressLint("ViewConstructor")
internal class TvNativeBubbleColumnView(
    context: Context,
    private var style: TvNativeBubbleStyle,
    private var gapPx: Int,
) : ViewGroup(context) {
    private val bubbles = ArrayList<TvNativeBubbleView>()
    private var items: List<TvNativeBubble> = emptyList()

    /** 这一次排版里各个气泡的左上角. */
    private var lefts = IntArray(0)
    private var tops = IntArray(0)

    init {
        clipChildren = true
    }

    fun applyStyle(style: TvNativeBubbleStyle, gapPx: Int) {
        this.style = style
        this.gapPx = gapPx
        for (b in bubbles) b.applyStyle(style)
        requestLayout()
        invalidate()
    }

    fun setBubbles(items: List<TvNativeBubble>) {
        if (this.items == items) return
        this.items = items
        while (bubbles.size < items.size) {
            val bubble = TvNativeBubbleView(context, style)
            bubble.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            bubbles += bubble
            addView(bubble)
        }
        if (lefts.size < bubbles.size) {
            lefts = IntArray(bubbles.size)
            tops = IntArray(bubbles.size)
        }
        bubbles.forEachIndexed { i, bubble ->
            val item = items.getOrNull(i)
            if (item != null) bubble.bind(item, style)
            bubble.visibility = if (item != null) VISIBLE else GONE
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unlimited = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
        val avail = MeasureSpec.getSize(heightMeasureSpec)
        var bottom = 0
        var full = false
        for ((i, bubble) in bubbles.withIndex()) {
            if (bubble.visibility == GONE) continue
            val top = if (bottom == 0) 0 else bottom + gapPx
            // 放满之后的按高 0 量: 一个都不画
            val heightSpec = when {
                full -> tvNativeAtMost(0)
                unlimited -> TV_NATIVE_UNSPECIFIED
                else -> tvNativeAtMost(avail - top)
            }
            // 字换了要重新量 (见 TvNativeBubbleView.onMeasure)
            bubble.forceLayout()
            bubble.measure(tvNativeAtMost(width), heightSpec)
            if (!bubble.shown || bubble.cut) full = true
            if (!bubble.shown) continue
            lefts[i] = if (bubble.mine) width - bubble.measuredWidth else 0
            tops[i] = top
            bottom = top + bubble.measuredHeight
        }
        setMeasuredDimension(width, bottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((i, bubble) in bubbles.withIndex()) {
            if (bubble.visibility == GONE) continue
            if (bubble.shown) bubble.tvNativeLayoutAt(lefts[i], tops[i]) else bubble.layout(0, 0, 0, 0)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false
}
