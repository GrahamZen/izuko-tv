/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.PopupPositionProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_CORNER
import me.him188.ani.app.ui.foundation.widgets.TV_MENU_VERTICAL_MARGIN
import me.him188.ani.app.ui.foundation.widgets.TvDropdownMenuPositionProvider
import me.him188.ani.app.ui.foundation.widgets.TvMenuOption
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuLook
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuRequest
import me.him188.ani.app.ui.foundation.widgets.tvMenuTransformOrigin
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 原生菜单的像素与颜色, 由 [TvNativeMenuLook] 换算; 尺寸同 [me.him188.ani.app.ui.foundation.widgets.AniDropdownMenuItem]. */
internal class TvNativeMenuStyle(
    val text: TvNativeTextStyle,
    val containerColor: Int,
    val contentColor: Int,
    val focusContainerColor: Int,
    val focusContentColor: Int,
    val dividerColor: Int,
    val cornerPx: Float,
    val itemCornerPx: Float,
    val insetPx: Int,
    val paddingVerticalPx: Int,
    val itemHeightPx: Int,
    val itemPaddingPx: Int,
    val itemGapPx: Int,
    val checkSizePx: Int,
    val minItemWidthPx: Int,
    val maxItemWidthPx: Int,
    val verticalMarginPx: Int,
    val fadePx: Int,
    val dividerThicknessPx: Int,
    val dividerMarginPx: Int,
)

internal fun TvNativeMenuLook.toStyle(): TvNativeMenuStyle = with(density) {
    TvNativeMenuStyle(
        text = text.toTvNativeTextStyle(this@toStyle.density, content),
        containerColor = container.toArgb(),
        contentColor = content.toArgb(),
        focusContainerColor = focusContainer.toArgb(),
        focusContentColor = focusContent.toArgb(),
        dividerColor = divider.toArgb(),
        cornerPx = CENTERED_PANEL_CORNER.toPx(),
        itemCornerPx = (CENTERED_PANEL_CORNER - MENU_ITEM_INSET).toPx(),
        insetPx = MENU_ITEM_INSET.roundToPx(),
        paddingVerticalPx = 8.dp.roundToPx(),
        itemHeightPx = 48.dp.roundToPx(),
        itemPaddingPx = 12.dp.roundToPx(),
        itemGapPx = 12.dp.roundToPx(),
        checkSizePx = TV_NATIVE_MENU_CHECK_SIZE.roundToPx(),
        minItemWidthPx = 112.dp.roundToPx(),
        maxItemWidthPx = 280.dp.roundToPx(),
        verticalMarginPx = TV_MENU_VERTICAL_MARGIN.roundToPx(),
        fadePx = 20.dp.roundToPx(),
        dividerThicknessPx = 1.dp.roundToPx().coerceAtLeast(1),
        dividerMarginPx = 4.dp.roundToPx(),
    )
}

/** 菜单项高亮块与菜单左右边缘的间距 (同 AniDropdownMenuItem). */
private val MENU_ITEM_INSET = 8.dp

/** 当前项的勾. */
internal val TV_NATIVE_MENU_CHECK_SIZE = 18.dp

private const val DISABLED_CONTENT_ALPHA = 0.38f
private const val DIMMED_CONTENT_ALPHA = 0.5f

/** 开合过渡, 同 AniDropdownMenu 的定位菜单: 展开时缩放 120ms (从 0.8 倍起)、淡入 30ms, 收起时淡出 75ms. */
private const val MENU_IN_MILLIS = 120L
private const val MENU_ALPHA_IN_MILLIS = 30f
private const val MENU_OUT_MILLIS = 75L
private const val MENU_CLOSED_SCALE = 0.8f
private const val MENU_SCROLL_MILLIS = 120

/**
 * 原生菜单那一层: 铺满主窗口内容区, 盖在整个 Compose 界面上面; 没开菜单时 GONE (不挡触摸, 不画).
 * 不拿系统焦点 (焦点留在锚点上, Compose 那边什么都不变), 按键由 [TvNativeMenuHost] 在根部截下转给 [onKeyDown] / [onKeyUp].
 * 列表是 RecyclerView: 只建看得见的那十来行, 字幕上百条也一样快.
 */
internal class TvNativeMenuLayerView(context: Context) : FrameLayout(context) {
    interface Listener {
        fun onSelect(index: Int)
        fun onDismiss()
    }

    var listener: Listener? = null

    /** 白色的勾 (按字色着色). */
    var check: Bitmap? = null

    private val card = FrameLayout(context)
    private val cardBackground = GradientDrawable()
    private val list = RecyclerView(context)
    private val layoutManager = LinearLayoutManager(context)
    private val adapter = RowAdapter()

    private var style: TvNativeMenuStyle? = null
    private var options: List<TvMenuOption> = emptyList()
    private var anchor = IntRect.Zero
    private var positionProvider: PopupPositionProvider? = null

    /** 高亮的那一项; -1 = 没有能选的. */
    var highlight = -1
        private set

    /** 收起过渡中 (视图还在, 已经不算开着). */
    private var hiding = false
    private var enterPending = false
    private var animator: ValueAnimator? = null
    private var itemWidthPx = 0
    private var cardWidth = 0
    private var cardHeight = 0
    private val cardRect = Rect()

    val isShowing: Boolean get() = visibility == VISIBLE && !hiding

    init {
        visibility = GONE
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        card.background = cardBackground
        card.clipToOutline = true
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.itemAnimator = null
        // 滚出去的行直接进回收池 (下次用时重新绑定): 高亮换了只重绑看得见的行, 留在缓存里不重绑的行会带着旧高亮滚回来
        list.setItemViewCacheSize(0)
        list.overScrollMode = OVER_SCROLL_NEVER
        list.isVerticalFadingEdgeEnabled = true
        list.isFocusable = false
        list.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        list.addItemDecoration(DividerDecoration())
        card.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun show(request: TvNativeMenuRequest, style: TvNativeMenuStyle, animate: Boolean) {
        animator?.cancel()
        hiding = false
        applyRequest(request, style)
        highlight = enabledFrom(request.initialIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)), 1)
        adapter.notifyDataSetChanged()
        if (highlight > 0) layoutManager.scrollToPositionWithOffset(highlight, style.fadePx) else layoutManager.scrollToPosition(0)
        card.alpha = if (animate) 0f else 1f
        card.scaleX = 1f
        card.scaleY = 1f
        enterPending = animate
        visibility = VISIBLE
        requestLayout()
    }

    /** 开着时选项或锚点变了: 高亮停在原来那一项上 (项数变少就收到末项), 不重放展开过渡. */
    fun update(request: TvNativeMenuRequest, style: TvNativeMenuStyle) {
        if (!isShowing) return
        val previous = highlight
        applyRequest(request, style)
        highlight = if (options.isEmpty()) -1 else enabledFrom(previous.coerceIn(0, options.size - 1), 1)
        adapter.notifyDataSetChanged()
        requestLayout()
    }

    fun hide(animate: Boolean) {
        if (visibility != VISIBLE || hiding) return
        animator?.cancel()
        enterPending = false
        if (!animate) {
            visibility = GONE
            return
        }
        hiding = true
        val from = card.alpha
        animator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = MENU_OUT_MILLIS
            addUpdateListener { card.alpha = it.animatedValue as Float }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var canceled = false
                    override fun onAnimationCancel(animation: Animator) {
                        canceled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (canceled) return
                        hiding = false
                        visibility = GONE
                    }
                },
            )
            start()
        }
    }

    /** 按下 (含连发): 上下挪高亮; 别的键不做事. */
    fun onKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveHighlight(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveHighlight(1)
        }
    }

    /** 抬起 (只给菜单开着时见过按下的键): 确定选中高亮项, 返回收起. */
    fun onKeyUp(keyCode: Int) {
        when {
            tvNativeIsConfirmKey(keyCode) -> if (highlight in options.indices && options[highlight].enabled) listener?.onSelect(highlight)
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> listener?.onDismiss()
        }
    }

    private fun moveHighlight(delta: Int) {
        if (highlight < 0) return
        var target = highlight + delta
        while (target in options.indices && !options[target].enabled) target += delta
        if (target !in options.indices) return
        val old = highlight
        highlight = target
        rebindVisible(old)
        rebindVisible(target)
        scrollIntoView(target)
    }

    private fun rebindVisible(index: Int) {
        val holder = list.findViewHolderForAdapterPosition(index) as? RowHolder ?: return
        holder.bind(index)
    }

    /** 高亮项滚进来时离上下边留出渐隐那一截 (同 Compose 菜单的焦点项). */
    private fun scrollIntoView(index: Int) {
        val style = style ?: return
        val view = layoutManager.findViewByPosition(index)
        if (view == null || list.height == 0) {
            // 还在滚的那一段要先停下, 不然跳到位之后接着滚完剩下的距离
            list.stopScroll()
            val below = index > layoutManager.findLastVisibleItemPosition()
            layoutManager.scrollToPositionWithOffset(index, if (below) list.height - style.itemHeightPx - style.fadePx else style.fadePx)
            return
        }
        val top = view.top - style.fadePx
        val bottom = view.bottom + style.fadePx - list.height
        when {
            top < 0 -> list.smoothScrollBy(0, top, TV_NATIVE_LINEAR_OUT_SLOW_IN, MENU_SCROLL_MILLIS)
            bottom > 0 -> list.smoothScrollBy(0, bottom, TV_NATIVE_LINEAR_OUT_SLOW_IN, MENU_SCROLL_MILLIS)
        }
    }

    private fun enabledFrom(start: Int, step: Int): Int {
        var index = start
        while (index in options.indices) {
            if (options[index].enabled) return index
            index += step
        }
        index = start - step
        while (index in options.indices) {
            if (options[index].enabled) return index
            index -= step
        }
        return -1
    }

    private fun applyRequest(request: TvNativeMenuRequest, style: TvNativeMenuStyle) {
        val optionsChanged = request.options != options || style !== this.style
        this.style = style
        options = request.options
        anchor = request.anchorBounds
        positionProvider = request.positionProvider ?: with(request.look.density) {
            TvDropdownMenuPositionProvider(
                request.offset.x.roundToPx(),
                request.offset.y.roundToPx(),
                style.verticalMarginPx,
            )
        }
        cardBackground.setColor(style.containerColor)
        cardBackground.cornerRadius = style.cornerPx
        card.setPadding(0, style.paddingVerticalPx, 0, style.paddingVerticalPx)
        list.setFadingEdgeLength(style.fadePx)
        if (optionsChanged) itemWidthPx = measureItemWidth(style)
    }

    /** 同 Compose 菜单的固有宽度: 最宽那一项 (左右内边距 + 字 + 当前项的勾), 限在 112~280dp. */
    private fun measureItemWidth(style: TvNativeMenuStyle): Int {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = style.text.sizePx
            typeface = tvNativeTypeface(style.text.weight)
            letterSpacing = style.text.letterSpacingEm
        }
        var widest = 0
        for (option in options) {
            var width = style.itemPaddingPx * 2 + ceil(paint.measureText(option.text)).toInt() + 1
            if (option.selected) width += style.itemGapPx + style.checkSizePx
            widest = max(widest, width)
        }
        return widest.coerceIn(style.minItemWidthPx, style.maxItemWidthPx)
    }

    private fun contentHeight(style: TvNativeMenuStyle): Int {
        val dividers = options.count { it.dividerBefore }
        return options.size * style.itemHeightPx + dividers * (style.dividerThicknessPx + style.dividerMarginPx * 2)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val style = style ?: return
        cardWidth = min(itemWidthPx + style.insetPx * 2, width)
        val maxHeight = (height - style.verticalMarginPx * 2).coerceAtLeast(style.itemHeightPx)
        cardHeight = min(contentHeight(style) + style.paddingVerticalPx * 2, maxHeight)
        card.measure(
            MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val provider = positionProvider ?: return
        val location = IntArray(2)
        getLocationInWindow(location)
        val localAnchor = anchor.translate(-location[0], -location[1])
        val size = IntSize(cardWidth, cardHeight)
        val position: IntOffset = provider.calculatePosition(
            localAnchor,
            IntSize(width, height),
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            size,
        )
        card.layout(position.x, position.y, position.x + cardWidth, position.y + cardHeight)
        cardRect.set(position.x, position.y, position.x + cardWidth, position.y + cardHeight)
        val origin = tvMenuTransformOrigin(localAnchor, IntRect(position, size))
        card.pivotX = origin.pivotFractionX * cardWidth
        card.pivotY = origin.pivotFractionY * cardHeight
        if (enterPending) {
            enterPending = false
            startEnter()
        }
    }

    private fun startEnter() {
        animator?.cancel()
        card.scaleX = MENU_CLOSED_SCALE
        card.scaleY = MENU_CLOSED_SCALE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MENU_IN_MILLIS
            addUpdateListener {
                val fraction = it.animatedFraction
                val scale = MENU_CLOSED_SCALE + (1f - MENU_CLOSED_SCALE) * TV_NATIVE_LINEAR_OUT_SLOW_IN.getInterpolation(fraction)
                card.scaleX = scale
                card.scaleY = scale
                card.alpha = (fraction * MENU_IN_MILLIS / MENU_ALPHA_IN_MILLIS).coerceAtMost(1f)
            }
            start()
        }
    }

    /** 点菜单外面收起 (平板上装了 TV 包时); 开着时整层吞掉触摸, 不漏给下面的页面. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) return false
        if (event.actionMasked == MotionEvent.ACTION_UP && !cardRect.contains(event.x.toInt(), event.y.toInt())) {
            listener?.onDismiss()
        }
        return true
    }

    /** 别的窗口盖上来 (动作面板、弹窗) 时收起: 菜单在主窗口里, 会被盖在下面, 关掉那一层后还留着就成了没人知道的残留. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && isShowing) listener?.onDismiss()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount(): Int = options.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder = RowHolder(MenuRow(parent.context))

        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(position)
    }

    private inner class RowHolder(val row: MenuRow) : RecyclerView.ViewHolder(row) {
        init {
            row.setOnClickListener {
                val index = bindingAdapterPosition
                if (index in options.indices && options[index].enabled) listener?.onSelect(index)
            }
        }

        fun bind(index: Int) {
            val style = style ?: return
            val option = options[index]
            val highlighted = index == highlight
            val params = row.layoutParams as? RecyclerView.LayoutParams
                ?: RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, style.itemHeightPx)
            params.height = style.itemHeightPx
            params.leftMargin = style.insetPx
            params.rightMargin = style.insetPx
            row.layoutParams = params
            row.bind(option, highlighted, style, check)
        }
    }

    /** 选项上面的分隔线 (同 Compose 菜单里的 HorizontalDivider: 上下各留 4dp, 横向铺满菜单). */
    private inner class DividerDecoration : RecyclerView.ItemDecoration() {
        private val paint = Paint()

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val style = style ?: return
            val index = parent.getChildAdapterPosition(view)
            if (index in options.indices && options[index].dividerBefore) {
                outRect.top = style.dividerThicknessPx + style.dividerMarginPx * 2
            }
        }

        override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val style = style ?: return
            paint.color = style.dividerColor
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val index = parent.getChildAdapterPosition(child)
                if (index !in options.indices || !options[index].dividerBefore) continue
                val bottom = child.top - style.dividerMarginPx + child.translationY
                canvas.drawRect(0f, bottom - style.dividerThicknessPx, parent.width.toFloat(), bottom, paint)
            }
        }
    }
}

/** 一行: 高亮时内缩的圆角块铺主色, 字在左, 当前项右端一个勾. */
private class MenuRow(context: Context) : LinearLayout(context) {
    private val highlightShape = GradientDrawable()
    private val label = TextView(context)
    private val mark = ImageView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        background = highlightShape
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        label.includeFontPadding = false
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(mark)
    }

    fun bind(option: TvMenuOption, highlighted: Boolean, style: TvNativeMenuStyle, check: Bitmap?) {
        setPadding(style.itemPaddingPx, 0, style.itemPaddingPx, 0)
        highlightShape.cornerRadius = style.itemCornerPx
        highlightShape.setColor(if (highlighted) style.focusContainerColor else 0)
        val content = when {
            !option.enabled -> tvNativeWithAlpha(style.contentColor, DISABLED_CONTENT_ALPHA)
            highlighted -> style.focusContentColor
            else -> style.contentColor
        }
        style.text.applyTo(label)
        label.setTextColor(if (option.dimmed) tvNativeWithAlpha(content, DIMMED_CONTENT_ALPHA) else content)
        label.text = option.text
        val markParams = (mark.layoutParams as LayoutParams)
        markParams.width = style.checkSizePx
        markParams.height = style.checkSizePx
        markParams.marginStart = style.itemGapPx
        mark.layoutParams = markParams
        mark.visibility = if (option.selected) VISIBLE else GONE
        mark.scaleType = ImageView.ScaleType.FIT_CENTER
        if (option.selected) {
            mark.setImageBitmap(check)
            mark.colorFilter = PorterDuffColorFilter(content, PorterDuff.Mode.SRC_IN)
        }
    }
}
