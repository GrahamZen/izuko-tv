/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_EDGE_WIDTH
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_FOCUS_ELEVATION
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_FOCUS_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_FOCUS_SCALE
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_TAB_BAR_PADDING
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_TAB_GAP
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_TAB_PADDING_HORIZONTAL
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_TAB_PADDING_VERTICAL
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_TAB_ROW_HEIGHT
import me.him188.ani.app.ui.foundation.tv.TV_GLASS_UNFOCUS_MILLIS
import me.him188.ani.app.ui.foundation.tv.tvContentSwapAnimated
import me.him188.ani.app.ui.foundation.tv.tvGlassColors
import me.him188.ani.app.ui.foundation.tv.tvGlassLabelStyle
import kotlin.math.max

/**
 * 原生的玻璃标签栏 (照 tvOS 顶部标签栏; 外观同 Compose 的 TvCollectionGlassTabBar / TvCollectionGlassTab, 尺寸与配色取同一份定义):
 * 一整条玻璃胶囊 ([fill] 底 + [edge] 细边), 里面横排签. 签没选中时字淡 ([idleContent]); 选中垫一块半透明片 ([selectedPlatter]);
 * 聚焦换成浅色实底配深色字 ([focusedPlatter] / [focusedContent]), 略放大 ([focusScale]) 并投一层影 ([focusElevationPx]).
 * 得焦按 [focusMillis] 过渡, 失焦按更短的 [unfocusMillis], 投影当场撤 (照 tvOS: 焦点离开的那个先落回去); [animated] 为 false (视觉效果的流畅档) 直接到位.
 * 签上可以跟一段小字 ([detail], 数量、日期), 没看过的东西在字的右上角画一个红点 ([dot]).
 * [pills] = 每个签各自是一枚玻璃胶囊 (新番时间表的日期: 签自己画底, 高 [barHeightPx], 签与签之间隔 [gapPx]), 不画整条胶囊.
 */
@Immutable
internal data class TvNativeGlassTabStyle(
    val label: TvNativeTextStyle,
    val detail: TvNativeTextStyle,
    val barHeightPx: Int,
    val barPaddingPx: Int,
    val gapPx: Int,
    val paddingHPx: Int,
    val paddingVPx: Int,
    val detailGapPx: Int,
    val edgePx: Float,
    val dotPx: Int,
    val dotOffsetPx: Int,
    val fill: Int,
    val edge: Int,
    val idleContent: Int,
    val selectedPlatter: Int,
    val selectedContent: Int,
    val focusedPlatter: Int,
    val focusedContent: Int,
    val dot: Int,
    val focusScale: Float,
    val focusElevationPx: Float,
    val focusMillis: Long,
    val unfocusMillis: Long,
    val animated: Boolean,
    /** 触屏 (平板模式): 签不可聚焦、不画聚焦的样子, 点了换签 */
    val touch: Boolean,
    val pills: Boolean = false,
)

/** 当前主题、界面缩放与视觉效果档下的 [TvNativeGlassTabStyle]; 红点用 [dot] (默认主题的 error 色). */
@Composable
internal fun rememberTvNativeGlassTabStyle(dot: Color = MaterialTheme.colorScheme.error): TvNativeGlassTabStyle {
    val density = LocalDensity.current
    val glass = tvGlassColors()
    val labelStyle = tvGlassLabelStyle()
    val typography: Typography = MaterialTheme.typography
    val animated = tvContentSwapAnimated()
    val touch = LocalTvTouchInputEnabled.current
    return remember(density, glass, labelStyle, typography, animated, touch, dot) {
        with(density) {
            TvNativeGlassTabStyle(
                label = labelStyle.toTvNativeTextStyle(density, glass.idleContent),
                detail = typography.labelMedium.toTvNativeTextStyle(density, glass.idleContent),
                barHeightPx = TV_GLASS_TAB_ROW_HEIGHT.roundToPx(),
                barPaddingPx = TV_GLASS_TAB_BAR_PADDING.roundToPx(),
                gapPx = TV_GLASS_TAB_GAP.roundToPx(),
                paddingHPx = TV_GLASS_TAB_PADDING_HORIZONTAL.roundToPx(),
                paddingVPx = TV_GLASS_TAB_PADDING_VERTICAL.roundToPx(),
                detailGapPx = TV_GLASS_TAB_DETAIL_GAP.roundToPx(),
                edgePx = TV_GLASS_EDGE_WIDTH.toPx(),
                dotPx = TV_GLASS_TAB_DOT_SIZE.roundToPx(),
                dotOffsetPx = TV_GLASS_TAB_DOT_OFFSET.roundToPx(),
                fill = glass.fill.toArgb(),
                edge = glass.edge.toArgb(),
                idleContent = glass.idleContent.toArgb(),
                selectedPlatter = glass.selectedPlatter.toArgb(),
                selectedContent = glass.selectedContent.toArgb(),
                focusedPlatter = glass.focusedPlatter.toArgb(),
                focusedContent = glass.focusedContent.toArgb(),
                dot = dot.toArgb(),
                focusScale = TV_GLASS_FOCUS_SCALE,
                focusElevationPx = TV_GLASS_FOCUS_ELEVATION.toPx(),
                focusMillis = TV_GLASS_FOCUS_MILLIS.toLong(),
                unfocusMillis = TV_GLASS_UNFOCUS_MILLIS.toLong(),
                animated = animated,
                touch = touch,
            )
        }
    }
}

/** 标签栏上的一个签: 键 [id]、字 [label]、跟在后面的小字 [detail] (数量、日期)、有没看过的 ([fresh], 选中的不画红点). */
@Immutable
internal data class TvNativeGlassTab(
    val id: String,
    val label: String,
    val detail: String? = null,
    val fresh: Boolean = false,
)

/**
 * 一个签 (见 [TvNativeGlassTabStyle]). 电视上可以聚焦, 方向键由装它的页面视图判 (见 [TvNativeGlassTabBar]); 触屏上不可聚焦、点了换签.
 */
@SuppressLint("ViewConstructor")
internal class TvNativeGlassTabView(context: Context, private var style: TvNativeGlassTabStyle) : ViewGroup(context) {
    var tab: TvNativeGlassTab? = null
        private set

    private val label = TvNativeTextView(context).apply { maxLines = 1; style.label.applyTo(this) }
    private val detail = TvNativeTextView(context).apply { maxLines = 1; style.detail.applyTo(this) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val edgeRect = RectF()
    private var selectedTab = false

    /** 聚焦的程度 (0..1): 片、字色与放大都跟它走 */
    private var lift = 0f
    private var animator: ValueAnimator? = null

    /** 窗口被叠在上面的小窗拿走了焦点: 焦点还在这里, 但不画聚焦的样子 (不然小窗里的按钮与这个签看着都聚焦) */
    var covered = false
        set(value) {
            if (field == value) return
            field = value
            applyLift(animate = false)
        }

    init {
        setWillNotDraw(false)
        clipChildren = false
        isClickable = true
        // 电视上可以聚焦, 窗口在触摸模式里也一样 (同原生卡片: 仪器测试与点过触屏的电视都在触摸模式); 触屏 (平板模式) 上不可聚焦
        isFocusable = !style.touch
        isFocusableInTouchMode = !style.touch
        addView(label)
        addView(detail)
        // 聚焦时投影的形状 (胶囊); 只在聚焦时抬起, 那时片是不透明的
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                outline.alpha = 1f
            }
        }
    }

    fun bind(tab: TvNativeGlassTab, selected: Boolean, style: TvNativeGlassTabStyle) {
        if (this.style != style) {
            this.style = style
            style.label.applyTo(label)
            style.detail.applyTo(detail)
            isFocusable = !style.touch
            isFocusableInTouchMode = !style.touch
            requestLayout()
        }
        val old = this.tab
        this.tab = tab
        if (old?.label != tab.label) label.text = tab.label
        if (old?.detail != tab.detail) detail.text = tab.detail.orEmpty()
        detail.visibility = if (tab.detail.isNullOrEmpty()) GONE else VISIBLE
        if (old?.label != tab.label || old.detail != tab.detail) requestLayout()
        selectedTab = selected
        applyColors()
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyLift(animate = true)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun applyLift(animate: Boolean) {
        val target = if (isFocused && !covered && !style.touch) 1f else 0f
        animator?.cancel()
        animator = null
        if (target == lift) return
        if (!animate || !style.animated || !isAttachedToWindow) {
            setLift(target)
            return
        }
        // 失焦时投影当场撤 (系统阴影要降到 0 才完全没有, 跟着缩回的曲线拖尾看着像原地留了一圈影)
        if (target < lift) translationZ = 0f
        animator = ValueAnimator.ofFloat(lift, target).apply {
            duration = if (target > lift) style.focusMillis else style.unfocusMillis
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener { setLift(it.animatedValue as Float) }
            start()
        }
    }

    private fun setLift(value: Float) {
        lift = value
        val scale = 1f + (style.focusScale - 1f) * value
        scaleX = scale
        scaleY = scale
        translationZ = if (isFocused && !covered) style.focusElevationPx * value else 0f
        applyColors()
        invalidate()
    }

    private fun applyColors() {
        val base = if (selectedTab) style.selectedContent else style.idleContent
        val color = tvNativeLerpColor(base, style.focusedContent, lift)
        label.setTextColor(color)
        detail.setTextColor(tvNativeWithAlpha(color, TV_GLASS_TAB_DETAIL_ALPHA))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        label.measure(UNSPECIFIED_SPEC, UNSPECIFIED_SPEC)
        var width = label.measuredWidth
        var height = label.measuredHeight + label.fontOverflowTopPx * 2
        if (detail.visibility != GONE) {
            detail.measure(UNSPECIFIED_SPEC, UNSPECIFIED_SPEC)
            width += s.detailGapPx + detail.measuredWidth
            height = max(height, detail.measuredHeight)
        }
        height += s.paddingVPx * 2
        // 各自成胶囊的签一样高 (同那一行的高), 字竖向居中
        if (s.pills) height = max(height, s.barHeightPx)
        setMeasuredDimension(width + s.paddingHPx * 2, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        val h = b - t
        val labelTop = (h - label.measuredHeight) / 2
        label.layout(s.paddingHPx, labelTop, s.paddingHPx + label.measuredWidth, labelTop + label.measuredHeight)
        if (detail.visibility != GONE) {
            val x = label.right + s.detailGapPx
            // 小字与大字底对齐 (同 Compose 版 Row 的居中排法里小字的基线大致落在大字基线上)
            val top = (h - detail.measuredHeight) / 2
            detail.layout(x, top, x + detail.measuredWidth, top + detail.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (s.pills) {
            // 各自成胶囊: 自己画玻璃底与细边 (同 Compose 版的 tvGlassBackground)
            paint.color = s.fill
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (s.edgePx > 0f) {
                val inset = s.edgePx / 2f
                edgeRect.set(inset, inset, width - inset, height - inset)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s.edgePx
                paint.color = s.edge
                canvas.drawRoundRect(edgeRect, radius, radius, paint)
                paint.style = Paint.Style.FILL
            }
        }
        // 选中片只在没聚焦时露出, 与聚焦片交叉淡入淡出 (两层各自只动透明度: 从「没有片」淡入不经过半透明的黑)
        val selectLayer = if (selectedTab) 1f - lift else 0f
        if (selectLayer > 0f) {
            paint.color = tvNativeWithAlpha(s.selectedPlatter, selectLayer)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        if (lift > 0f) {
            paint.color = tvNativeWithAlpha(s.focusedPlatter, lift)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        if (tab?.fresh == true && !selectedTab) {
            val dotRadius = s.dotPx / 2f
            paint.color = s.dot
            canvas.drawCircle(label.right + s.dotOffsetPx - dotRadius, label.top + dotRadius, dotRadius, paint)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/**
 * 原生的玻璃标签栏: 一整条胶囊底, 里面横排 [TvNativeGlassTabView]. 只管画与排版, 不判方向键 —— 方向键由装它的页面视图在自己的
 * `dispatchKeyEvent` 里判 (左右移到相邻的签 [focusTab]、聚焦即选中由页面定, 按下回内容), 照原生页面「方向键全部在页面根上判」的规矩.
 * 点签 ([onTabClick]) 与长按 ([onTabLongClick]) 是触屏与遥控器确认键共用的入口 (遥控器的确认键也由页面视图判, 调它们).
 */
@SuppressLint("ViewConstructor")
internal class TvNativeGlassTabBar(context: Context, style: TvNativeGlassTabStyle) : ViewGroup(context) {
    /** 此刻的外观 */
    var style: TvNativeGlassTabStyle = style
        private set

    private val views = ArrayList<TvNativeGlassTabView>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 此刻的签, 照显示顺序 */
    var tabs: List<TvNativeGlassTab> = emptyList()
        private set

    /** 选中的签的下标 (-1 = 没有) */
    var selectedIndex: Int = -1
        private set

    var onTabClick: ((index: Int) -> Unit)? = null
    var onTabLongClick: ((index: Int) -> Unit)? = null

    /** 某个签拿到 / 失去焦点 */
    var onTabFocusChanged: ((index: Int, focused: Boolean) -> Unit)? = null

    var covered = false
        set(value) {
            if (field == value) return
            field = value
            views.forEach { it.covered = value }
        }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    /** 换成 [tabs] (照显示顺序), 选中第 [selected] 个. 签的数目不变时原地换字, 不重建 (持焦的签不丢焦点). */
    fun setTabs(tabs: List<TvNativeGlassTab>, selected: Int, style: TvNativeGlassTabStyle = this.style) {
        if (this.style != style) {
            this.style = style
            requestLayout()
            invalidate()
        }
        while (views.size > tabs.size) removeView(views.removeAt(views.lastIndex))
        while (views.size < tabs.size) {
            val view = TvNativeGlassTabView(context, style)
            val index = views.size
            view.setOnClickListener { onTabClick?.invoke(index) }
            view.setOnLongClickListener {
                val handler = onTabLongClick ?: return@setOnLongClickListener false
                handler(index)
                true
            }
            view.setOnFocusChangeListener { _, focused -> onTabFocusChanged?.invoke(index, focused) }
            view.covered = covered
            views += view
            addView(view)
        }
        this.tabs = tabs
        selectedIndex = selected
        tabs.forEachIndexed { i, tab -> views[i].bind(tab, i == selected, style) }
    }

    /** 只换选中的那一个 (聚焦即选中时每移一格调一次; 不重绑字). */
    fun setSelected(index: Int) {
        if (index == selectedIndex) return
        selectedIndex = index
        tabs.forEachIndexed { i, tab -> views[i].bind(tab, i == index, style) }
    }

    fun tabView(index: Int): TvNativeGlassTabView? = views.getOrNull(index)

    /** [view] 是第几个签 (不是签为 -1). */
    fun indexOf(view: View?): Int = views.indexOfFirst { it === view }

    /** 焦点在哪个签上 (不在签上为 -1). */
    fun focusedIndex(): Int = views.indexOfFirst { it.isFocused }

    /** 焦点给第 [index] 个签; 给不上 (触屏、没有这个签) 返回 false. */
    fun focusTab(index: Int): Boolean = views.getOrNull(index)?.requestFocus() == true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        var width = s.barPaddingPx * 2
        views.forEachIndexed { i, view ->
            view.measure(UNSPECIFIED_SPEC, UNSPECIFIED_SPEC)
            width += view.measuredWidth + if (i > 0) s.gapPx else 0
        }
        val height = max(s.barHeightPx, views.maxOfOrNull { it.measuredHeight } ?: 0)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        var x = s.barPaddingPx
        val h = b - t
        for (view in views) {
            val top = (h - view.measuredHeight) / 2
            view.layout(x, top, x + view.measuredWidth, top + view.measuredHeight)
            x += view.measuredWidth + s.gapPx
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 各自成胶囊的签自己画底, 整排不画
        if (views.isEmpty() || style.pills) return
        val s = style
        val inset = s.edgePx / 2f
        rect.set(inset, inset, width - inset, height - inset)
        val radius = rect.height() / 2f
        paint.style = Paint.Style.FILL
        paint.color = s.fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (s.edgePx > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s.edgePx
            paint.color = s.edge
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }
}

private val UNSPECIFIED_SPEC = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

/** 签上小字 (数量、日期) 相对大字的不透明度 (同 Compose 版) */
private const val TV_GLASS_TAB_DETAIL_ALPHA = 0.7f

/** 签上大字与小字的间距、红点大小与它往字外探出去多少 (同 Compose 版) */
private val TV_GLASS_TAB_DETAIL_GAP = 6.dp
private val TV_GLASS_TAB_DOT_SIZE = 8.dp
private val TV_GLASS_TAB_DOT_OFFSET = 8.dp
