/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LineHeightSpan
import android.util.FloatProperty
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Immutable
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.TvNativeImages
import me.him188.ani.app.ui.foundation.tv.TV_OBSCURED_COVER_LONG_EDGE_PX
import kotlin.math.ceil

/**
 * 原生海报墙的一张卡的数据.
 *
 * @param subtitle 给了就番名只占一行、下面一行小字 (详情页关联条目的「续集」「前传」), 块高不变.
 * @param progress 继续观看的集数进度 (0..1), 贴封面底缘一条细进度条; null 不画.
 * @param obscure NSFW 打码: 封面只解一张很小的图, 放大成糊图.
 */
@Immutable
data class TvNativeCard(
    val imageUrl: String?,
    val title: String,
    val subtitle: String? = null,
    val progress: Float? = null,
    val obscure: Boolean = false,
)

/**
 * 原生海报墙的卡片: 可聚焦的外框 (卡宽 × (卡高 + 番名块)) 里放海报与番名. 外观取值见 [TvNativeWallStyle]: 圆角封面、1 像素玻璃边
 * (Android TV 上 Apple TV App 的卡片), 静止贴身一层淡影 / 聚焦时抬高成一大片软影 (系统阴影, 同 Google TV 桌面卡片的 elevation)、放大、
 * 番名没聚焦时暗一档 (Apple TV 卡片标题的两档色)、放大时往下让开 (番名不放大, 让出海报多伸出来的那截).
 *
 * 聚焦效果照 Google TV 桌面卡片的做法由状态动画 (StateListAnimator) 驱动, 只动 RenderNode 属性、不重录卡片内容: 动画只推一个
 * 聚焦程度 [focusProgress] (0..1, [TvNativeWallStyle.focusMillis] FastOutSlowIn), 放大倍数、高度、番名亮度与位移都由它推出来.
 * 压暗 ([dim]) 与番名显隐 ([titleVisibility]) 乘在各自的透明度上.
 */
@SuppressLint("ViewConstructor")
class TvNativeCardView(context: Context, private val style: TvNativeWallStyle) : FrameLayout(context) {
    private val cover = TvNativeCoverView(context, style)
    private val label = TvNativeLabelView(context)
    private val title = TvNativeTextView(context)
    private val subtitle = TvNativeTextView(context)
    private var hasSubtitle = false
    private var marquee = false
    private val confirmKey = TvNativeConfirmKey()
    private val windowXY = IntArray(2)

    /** 确定键长按的回调 (收藏菜单), 参数是封面在窗口里的框 (菜单锚点); null = 没有长按, 按住也只算点击. */
    var longPressHandler: ((Rect) -> Unit)? = null

    /** 聚焦程度 (0..1), 由状态动画推 (外框拿到 / 失去焦点时动画到 1 / 0), 见类说明. */
    var focusProgress: Float = 0f
        set(value) {
            field = value
            applyFocus()
        }

    /**
     * 整张卡的淡化 / 压暗 (0..1, 1 = 不淡): 横滑行越过行首的离场卡、hero 态越线淡没的行. 乘在海报与番名自己的透明度上, 系统阴影色再乘
     * 平方 —— 半透明的封面按透明遮挡投影, 封面底下整块阴影会透出来.
     */
    var dim: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyFocus()
            applyShadowColor()
        }

    /** 番名的显隐 (0..1): 页面切到 hero 态时淡出. */
    var titleVisibility: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyFocus()
        }

    /**
     * 远跳途中 (返回键回首卡) 一律画成未聚焦: 一路滚过的卡不挨个放大, 到位再恢复 —— 同 Apple TV 回顶时只有落点那张有聚焦效果.
     *
     * 放开时没有焦点的卡, 状态动画先跳到失焦态的终点再画: 远跳出发时聚焦的那张, 压着期间焦点一直在它身上, 焦点给到落点那一刻才起步
     * 失焦动画, 紧接着放开的话它按动画起点画成放大态、再缩回去 (返回回首卡时原来那张闪一下放大).
     */
    var focusEffectSuppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value && !isFocused) {
                refreshDrawableState()
                stateListAnimator?.jumpToCurrentState()
            }
            applyFocus()
        }

    init {
        layoutParams = LayoutParams(style.cardWidthPx, style.cardHeightPx + style.labelHeightPx)
        // 触摸模式下也可聚焦: 窗口进了触摸模式时卡照样拿得到焦点, 焦点不会因此停到行 / 网格上或丢掉
        isFocusableInTouchMode = true
        // 系统默认的聚焦高亮 (API 26 起给没有聚焦态背景的 View 叠一层) 不要: 聚焦只由放大与投影表达
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        clipChildren = false
        clipToPadding = false
        addView(
            cover,
            LayoutParams(style.coverWidthPx, style.coverHeightPx).apply {
                leftMargin = style.gapPx
                topMargin = style.gapPx
            },
        )
        label.orientation = LinearLayout.VERTICAL
        // 番名排在海报之后画: 系统阴影在外框的 Z 排序区里按 Z 画, 番名的 Z 高于海报才压在海报投影上面
        label.translationZ = style.focusedElevationPx + 1f
        label.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        label.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(
            label,
            LayoutParams(style.cardWidthPx, style.labelHeightPx - style.titleTopGapPx).apply {
                topMargin = style.cardHeightPx + style.titleTopGapPx
            },
        )
        style.applyTitleText(title)
        style.applyTitleText(subtitle)
        subtitle.setTextColor(style.subtitleColor)
        subtitle.visibility = GONE
        stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusAnimator(1f))
            addState(intArrayOf(), focusAnimator(0f))
        }
        applyShadowColor()
        applyFocus()
    }

    fun bind(card: TvNativeCard, sketch: Sketch) {
        contentDescription = card.title
        title.text = card.title
        val sub = card.subtitle
        hasSubtitle = sub != null
        if (sub != null) {
            title.maxLines = 1
            subtitle.text = sub
            subtitle.visibility = VISIBLE
        } else {
            title.maxLines = 2
            subtitle.visibility = GONE
        }
        marquee = hasSubtitle && style.marquee
        updateMarquee()
        cover.progress = card.progress
        TvNativeImages.loadCover(
            sketch, cover, card.imageUrl, style.coverWidthPx, style.coverHeightPx, style.crossfade,
            obscureLongEdgePx = if (card.obscure) TV_OBSCURED_COVER_LONG_EDGE_PX else null,
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, longPressHandler?.let { handler -> { handler(coverRectInWindow()) } }) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, null) || super.onKeyUp(keyCode, event)

    /** 封面此刻在窗口里的框 (未放大时的位置): 长按菜单锚在这里. */
    fun coverRectInWindow(): Rect {
        cover.getLocationInWindow(windowXY)
        return Rect(windowXY[0], windowXY[1], windowXY[0] + cover.width, windowXY[1] + cover.height)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        updateMarquee()
    }

    /** 带副标题的卡番名只有一行, 聚焦时放不下就跑马灯 (同 tvOS 聚焦单元里截断的标签); 流畅档不滚. */
    private fun updateMarquee() {
        val run = marquee && isFocused
        title.ellipsize = if (run) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        title.isSelected = run
    }

    private fun applyFocus() {
        val p = if (focusEffectSuppressed) 0f else focusProgress
        val s = 1f + (style.focusScale - 1f) * p
        cover.scaleX = s
        cover.scaleY = s
        cover.elevation = style.idleElevationPx + (style.focusedElevationPx - style.idleElevationPx) * p
        cover.alpha = dim
        label.translationY = style.titleShiftPx * p
        label.alpha = (style.titleIdleAlpha + (1f - style.titleIdleAlpha) * p) * titleVisibility * dim
    }

    private fun applyShadowColor() {
        if (Build.VERSION.SDK_INT < 28) return
        val base = style.shadowColor
        val a = (Color.alpha(base) * dim * dim).toInt().coerceIn(0, 255)
        val c = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
        cover.outlineAmbientShadowColor = c
        cover.outlineSpotShadowColor = c
    }

    private fun focusAnimator(target: Float) = AnimatorSet().apply {
        play(ObjectAnimator.ofFloat<TvNativeCardView>(null, TV_NATIVE_FOCUS_PROGRESS, target))
        duration = style.focusMillis
        interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
    }
}

/**
 * 海报. 圆角靠 outline 裁剪 (同 Google TV 桌面卡片), 系统阴影按同一个 outline 投; 加载前露出占位底色. 集数进度条画在图上
 * (贴封面底缘居中, 同 TvPortraitCard), 玻璃边是前景里一圈 1 像素描边 (圆角减半像素, 画在封面边缘以内).
 * 尺寸由布局参数定死: 换图时 ImageView 会按图片尺寸 requestLayout, 那会冒泡到 Compose 让整棵原生树重新测量 —— 量过一次之后挡掉,
 * 图片矩阵由 setImageDrawable 自己重算.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
private class TvNativeCoverView(context: Context, private val style: TvNativeWallStyle) : ImageView(context) {
    var progress: Float? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    // 不在 Paint 的 apply 里读 style: Paint 自己也有 style 属性, 会被它遮住
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = style.progressTrackColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = style.progressFillColor }
    private val barRect = RectF()

    init {
        scaleType = ScaleType.CENTER_CROP
        setBackgroundColor(style.placeholderColor)
        foreground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = style.cornerPx - 0.5f
            setStroke(1, style.edgeColor)
        }
        val corner = style.cornerPx
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        clipToOutline = true
        isDuplicateParentStateEnabled = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = progress ?: return
        if (p <= 0f) return
        val h = style.progressBarHeightPx
        val left = (width - style.progressBarLengthPx) / 2f
        val bottom = height - style.progressBarBottomGapPx
        barRect.set(left, bottom - h, left + style.progressBarLengthPx, bottom)
        canvas.drawRoundRect(barRect, h / 2f, h / 2f, trackPaint)
        barRect.right = left + style.progressBarLengthPx * p.coerceIn(0f, 1f)
        canvas.drawRoundRect(barRect, h / 2f, h / 2f, fillPaint)
    }

    override fun requestLayout() {
        if (!isLaidOut) super.requestLayout()
    }
}

/** 番名块: 一两行字竖排; 透明度逐绘制指令乘 (两行不重叠, 不走离屏层). */
private class TvNativeLabelView(context: Context) : LinearLayout(context) {
    init {
        isDuplicateParentStateEnabled = true
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 定尺寸的文字: 字号 / 行高 / 字距由 [TvNativeTextStyle.applyTo] 设. */
@SuppressLint("AppCompatCustomView")
internal class TvNativeTextView(context: Context) : TextView(context) {
    /**
     * 每行的固定高度 (px), 0 = 字体自然行高. 同 Compose M3 排版的 LineHeightStyle(Center, Trim.None) (见 [TvNativeLineHeightSpan]):
     * 每行恰好这么高, 字在行内居中. 不用 TextView.setLineHeight —— 它按主字体 (Roboto) 的度量折算行距, 中文行按回落字体 (更高) 的
     * 度量排, 行比设定的高, 定高的框里最后一行被裁掉一截.
     */
    var fixedLineHeightPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            text = text
        }

    init {
        includeFontPadding = false
        gravity = Gravity.TOP or Gravity.START
        ellipsize = TextUtils.TruncateAt.END
        setHorizontallyScrolling(false)
        marqueeRepeatLimit = -1
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        // 父类构造时就会调一次 (那时行高还是 0)
        super.setText(tvNativeWithLineHeight(text, fixedLineHeightPx), type)
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 给整段字挂上 [heightPx] 的固定行高 (换掉已挂的; [heightPx] ≤ 0 = 摘掉). */
private fun tvNativeWithLineHeight(text: CharSequence?, heightPx: Int): CharSequence? {
    if (text.isNullOrEmpty()) return text
    val existing = (text as? Spanned)?.getSpans(0, text.length, TvNativeLineHeightSpan::class.java).orEmpty()
    if (heightPx <= 0 && existing.isEmpty()) return text
    if (existing.size == 1 && existing[0].heightPx == heightPx) {
        val spanned = text as Spanned
        if (spanned.getSpanStart(existing[0]) == 0 && spanned.getSpanEnd(existing[0]) == text.length) return text
    }
    return SpannableString(text).also { s ->
        for (span in existing) s.removeSpan(span)
        if (heightPx > 0) s.setSpan(TvNativeLineHeightSpan(heightPx), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }
}

/**
 * 固定行高, 算法同 Compose 的 LineHeightStyleSpan (Fixed 模式, Alignment.Center, Trim.None; M3 排版的默认): 按首行的字体度量
 * (含回落字体) 算一次目标 ascent / descent —— 与设定行高的差额上下各分一半 (差额为负时同样对半压) —— 之后每行都用这组值.
 * 每次排版从首行重算 (字号变了跟着变).
 */
private class TvNativeLineHeightSpan(val heightPx: Int) : LineHeightSpan {
    private var ascent = 0
    private var descent = 0

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val current = fm.descent - fm.ascent
        if (current <= 0) return
        if (start == 0 || ascent == descent) {
            descent = fm.descent + ceil((heightPx - current) * 0.5f).toInt()
            ascent = descent - heightPx
        }
        fm.ascent = ascent
        fm.descent = descent
    }
}

/** 卡片的聚焦程度属性 (Property 版, 省掉 ObjectAnimator 按名字反射). */
private val TV_NATIVE_FOCUS_PROGRESS = object : FloatProperty<TvNativeCardView>("focusProgress") {
    override fun setValue(view: TvNativeCardView, value: Float) {
        view.focusProgress = value
    }

    override fun get(view: TvNativeCardView): Float = view.focusProgress
}
