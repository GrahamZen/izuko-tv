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
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.widgets.ANI_SCROLL_FADE
import me.him188.ani.app.ui.foundation.widgets.aniFocusContainerColor
import me.him188.ani.app.ui.foundation.widgets.aniFocusContentColor
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.subject.details.sections.focusEpisodeCardColors

/**
 * 全部选集弹窗的原生网格: 网格同「查看全部」画廊 ([TvNativeGalleryGridView]: 聚焦那一行对准视口中线、两头贴边, 方向键网格自己走,
 * 触屏点即点击), 格子二选一 ——
 * - 剧照卡 ([numbers] = false): 同详情页选集行的 [TvNativeEpisodeCardView] (剧照、集号集名、进度条, 聚焦换播放三角、集名跑马灯),
 *   聚焦框跟着聚焦那张画 ([TvNativeEpisodeGridRing], 同选集行那个框的样子);
 * - 只显示集号 ([numbers] = true): [TvNativeEpisodeNumberView], 聚焦铺实底 (小控件的示焦).
 *
 * 列数按给的宽排: 每格不窄于 [TV_NATIVE_EPISODE_GRID_STILL_MIN_WIDTH] / [TV_NATIVE_EPISODE_GRID_NUMBER_MIN_WIDTH], 格宽把一行铺满;
 * 剧照卡 16:9. 高 = 内容高 (父布局给的上限之内), 超出就在网格里滚, 滚不完的那边在上下让出的一截里渐隐.
 *
 * @param cards 要排的集 (剧照卡与集号格用同一份数据).
 * @param selectedIndex 焦点在网格外时选中第几格: 网格把它滚进来, 焦点送进网格时落在它上面. 焦点在网格里时不动.
 * @param touchTaps 触屏: 格子不可聚焦, 点一下就是点击, 长按开详情.
 * @param modifier 挂在装网格的那一层上 (按宽排列数; 送焦的锚点也挂这里: 焦点送进来时落在 [selectedIndex]).
 */
@Composable
fun TvNativeEpisodeGrid(
    cards: List<TvNativeEpisodeCard>,
    numbers: Boolean,
    selectedIndex: Int,
    touchTaps: Boolean,
    onFocused: (index: Int) -> Unit,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val sketch = LocalSketch.current
        val animatedScroll = LocalThemeSettings.current.visualEffects.animatedScroll
        val gap = if (numbers) TV_NATIVE_EPISODE_GRID_NUMBER_GAP else TV_NATIVE_EPISODE_GRID_STILL_GAP
        val minWidth = if (numbers) TV_NATIVE_EPISODE_GRID_NUMBER_MIN_WIDTH else TV_NATIVE_EPISODE_GRID_STILL_MIN_WIDTH
        val sidePx = with(density) { TV_NATIVE_EPISODE_GRID_SIDE_INSET.roundToPx() }
        val verticalPx = with(density) { ANI_SCROLL_FADE.roundToPx() }
        val gapPx = with(density) { gap.roundToPx() }
        val widthPx = constraints.maxWidth
        val columns = tvNativeGridColumns(widthPx - sidePx * 2, with(density) { minWidth.roundToPx() }, gapPx)
        val cellWidthPx = ((widthPx - sidePx * 2 - gapPx * (columns - 1)) / columns).coerceAtLeast(1)
        val cellWidth = with(density) { cellWidthPx.toDp() }
        val cellHeightPx = if (numbers) {
            with(density) { TV_NATIVE_EPISODE_GRID_NUMBER_HEIGHT.roundToPx() }
        } else {
            (cellWidthPx / TV_NATIVE_EPISODE_STILL_RATIO).toInt()
        }
        val cardStyle = rememberTvNativeEpisodeStyle(cellWidth, with(density) { cellHeightPx.toDp() }, gap, monochrome = false, glass = true)
        val numberStyle = rememberTvNativeEpisodeNumberStyle(cellWidthPx, cellHeightPx)
        var canScrollUp by remember { mutableStateOf(false) }
        var canScrollDown by remember { mutableStateOf(false) }
        val currentOnFocused by rememberUpdatedState(onFocused)
        val currentOnClick by rememberUpdatedState(onClick)
        val currentOnLongPress by rememberUpdatedState(onLongPress)
        val rows = (cards.size + columns - 1) / columns
        val contentHeightPx = rows * cellHeightPx + (rows - 1).coerceAtLeast(0) * gapPx + verticalPx * 2
        TvNativeHost(
            factory = { context ->
                TvNativeGalleryGridView(context, columns, gapPx, gapPx, sidePx, verticalPx).also { grid ->
                    grid.touchTaps = touchTaps
                    // 第一行按上回到标题行的按钮 (由 Compose 按位置找)
                    grid.releaseUpAtTop = true
                    // 触屏上格子一律不可聚焦: 网格只让格子「触摸模式下」不可聚焦, 而窗口不在触摸模式时 (接了键盘鼠标、adb 注入的点击)
                    // 弹窗一开系统就把默认焦点给第一格. 只有点, 没有焦点 (同标题行的按钮)
                    if (touchTaps) {
                        grid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                            override fun onChildViewAttachedToWindow(view: View) {
                                view.isFocusable = false
                            }

                            override fun onChildViewDetachedFromWindow(view: View) = Unit
                        })
                    }
                    val press = TvNativeEpisodeGridPress(grid, cardStyle.pressScale)
                    val listener = object : TvNativeEpisodeGridListener {
                        override fun onFocusChanged(index: Int, focused: Boolean) {
                            // 剧照卡的聚焦框画在网格上 (见 TvNativeEpisodeGridRing): 换了聚焦的那张要重画网格
                            grid.invalidate()
                            if (focused) currentOnFocused(index)
                        }

                        override fun onClick(index: Int) = currentOnClick(index)
                        override fun onLongPress(index: Int) = currentOnLongPress(index)
                    }
                    grid.adapter = if (numbers) {
                        TvNativeEpisodeNumberAdapter(numberStyle).also {
                            it.listener = listener
                            it.onPressedChanged = press::onPressedChanged
                            it.readyToFire = { grid.scrollState == RecyclerView.SCROLL_STATE_IDLE }
                        }
                    } else {
                        TvNativeEpisodeAdapter(cardStyle, sketch).also {
                            it.listener = listener.asRowListener()
                            it.onPressedChanged = press::onPressedChanged
                            it.readyToFire = { grid.scrollState == RecyclerView.SCROLL_STATE_IDLE }
                        }.also { grid.addItemDecoration(TvNativeEpisodeGridRing(cardStyle)) }
                    }
                    grid.onTouchLongPress = { currentOnLongPress(it) }
                    grid.onScrollChanged = {
                        canScrollUp = grid.hasMoreAbove()
                        canScrollDown = grid.hasMoreBelow()
                    }
                }
            },
            update = { grid ->
                grid.animatedScroll = animatedScroll
                grid.touchTaps = touchTaps
                when (val cells = grid.adapter) {
                    is TvNativeEpisodeNumberAdapter -> cells.submit(cards)
                    is TvNativeEpisodeAdapter -> cells.submit(cards)
                }
                // 焦点在外面时把选中的那格滚进来, 送焦进来就落在它上面; 在里面时由方向键走, 不动
                if (!grid.hasFocus() && selectedIndex in cards.indices && grid.selectedPosition != selectedIndex) {
                    grid.selectedPosition = selectedIndex
                }
            },
            modifier = Modifier
                .height(with(density) { contentHeightPx.toDp() })
                .aniVerticalScrollFade({ canScrollUp }, { canScrollDown }, fade = ANI_SCROLL_FADE),
            // 列数、格子尺寸与配色、格子种类在建网格时定死: 变了就整个重建
            rebuildKey = listOf(numbers, columns, cardStyle, numberStyle),
        )
    }
}

/** 宽 [availablePx] 里放得下几列不窄于 [minCellPx]、列距 [gapPx] 的格子 (至少 1 列). */
internal fun tvNativeGridColumns(availablePx: Int, minCellPx: Int, gapPx: Int): Int =
    ((availablePx + gapPx) / (minCellPx + gapPx).coerceAtLeast(1)).coerceAtLeast(1)

/** 选集网格的事件; [index] 是格子在网格里的下标. */
private interface TvNativeEpisodeGridListener {
    fun onFocusChanged(index: Int, focused: Boolean)
    fun onClick(index: Int)
    fun onLongPress(index: Int)
}

/** 剧照卡沿用选集行的适配器, 事件转成网格的. */
private fun TvNativeEpisodeGridListener.asRowListener() = object : TvNativeEpisodeRowListener {
    override fun onFocused(index: Int) = onFocusChanged(index, true)
    override fun onFocusLost(index: Int) = onFocusChanged(index, false)
    override fun onClick(index: Int) = this@asRowListener.onClick(index)
    override fun onLongPress(index: Int) = this@asRowListener.onLongPress(index)
    override fun onScrollingChanged(scrolling: Boolean) = Unit
}

/**
 * 剧照卡的聚焦框: 画在网格上 (每次网格重画, 跟着聚焦那张走, 滚动中也贴着), 样子同选集行钉住的那个框 (比卡大一圈, 主题色渐变描边);
 * 按住确定时随卡一起缩 (按卡的缩放画, 同心).
 */
private class TvNativeEpisodeGridRing(private val style: TvNativeEpisodeStyle) : RecyclerView.ItemDecoration() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.strokeWidth = style.ringStrokePx
        it.color = style.ringStartColor
    }
    private val rect = RectF()
    private var shaderWidth = -1
    private var shaderHeight = -1

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val card = parent.focusedChild as? TvNativeEpisodeCardView ?: return
        val outset = style.ringOutsetPx
        val w = card.width + outset * 2
        val h = card.height + outset * 2
        if (w != shaderWidth || h != shaderHeight) {
            shaderWidth = w
            shaderHeight = h
            paint.shader = if (style.ringStartColor == style.ringEndColor) {
                null
            } else {
                LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), style.ringStartColor, style.ringEndColor, Shader.TileMode.CLAMP)
            }
        }
        val half = style.ringStrokePx / 2f
        rect.set(half, half, w - half, h - half)
        val radius = (style.ringCornerPx - half).coerceAtLeast(0f)
        val save = canvas.save()
        canvas.translate(card.left + card.translationX - outset, card.top + card.translationY - outset)
        canvas.scale(card.scaleX, card.scaleY, w / 2f, h / 2f)
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.restoreToCount(save)
    }
}

/**
 * 按住确定时那一格缩一下、到长按弹回 (同选集行的卡与框, 同一条曲线): 一次只管一格. 剧照卡的聚焦框按卡的缩放画, 每帧重画网格.
 */
private class TvNativeEpisodeGridPress(private val grid: RecyclerView, private val pressScale: Float) {
    private var pressed: View? = null
    private var scale = 1f
    private var animator: ValueAnimator? = null

    fun onPressedChanged(view: View, isPressed: Boolean) {
        if (isPressed) {
            if (pressed !== view) pressed?.let { reset(it) }
            pressed = view
        } else if (pressed !== view) {
            return
        }
        animate(if (isPressed) pressScale else 1f)
    }

    private fun animate(target: Float) {
        // 先摘再取消: 取消会回调结束, 那一下不能把刚换上的按压格清掉
        val running = animator
        animator = null
        running?.cancel()
        if (scale == target) {
            if (target == 1f) pressed = null
            return
        }
        animator = ValueAnimator.ofFloat(scale, target).apply {
            duration = TV_NATIVE_EPISODE_PRESS_MILLIS
            interpolator = TV_NATIVE_EPISODE_PRESS_INTERPOLATOR
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    if (target == 1f) pressed = null
                }
            })
            start()
        }
    }

    private fun apply(value: Float) {
        scale = value
        pressed?.let {
            it.scaleX = value
            it.scaleY = value
        }
        grid.invalidate()
    }

    private fun reset(view: View) {
        view.scaleX = 1f
        view.scaleY = 1f
        scale = 1f
    }
}

/**
 * 集号格的尺寸 (px) 与配色, 由 [rememberTvNativeEpisodeNumberStyle] 按主题算好.
 *
 * @property palette 四种状态的配色, 顺序同 [TvNativeEpisodeStyle.palette] (取自同一个 focusEpisodeCardColors).
 * @property progressShade 进度那层深色 (从左铺到看了多少, 看完的整格铺满).
 */
@Immutable
data class TvNativeEpisodeNumberStyle(
    val cellWidthPx: Int,
    val cellHeightPx: Int,
    val cornerPx: Float,
    val palette: List<TvNativeEpisodeColors>,
    val focusedContainer: Int,
    val focusedContent: Int,
    val progressShade: Int,
    val text: TvNativeTextStyle,
    val icon: Bitmap,
    val iconPx: Int,
    val iconGapPx: Int,
) {
    fun colors(playing: Boolean, watched: Boolean): TvNativeEpisodeColors =
        palette[(if (playing) 2 else 0) + (if (watched) 1 else 0)]
}

@Composable
private fun rememberTvNativeEpisodeNumberStyle(cellWidthPx: Int, cellHeightPx: Int): TvNativeEpisodeNumberStyle {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val palette = listOf(
        focusEpisodeCardColors(isPlaying = false, isWatched = false, monochrome = false, glass = true),
        focusEpisodeCardColors(isPlaying = false, isWatched = true, monochrome = false, glass = true),
        focusEpisodeCardColors(isPlaying = true, isWatched = false, monochrome = false, glass = true),
        focusEpisodeCardColors(isPlaying = true, isWatched = true, monochrome = false, glass = true),
    ).map {
        TvNativeEpisodeColors(it.container.toArgb(), it.sort.toArgb(), it.name.toArgb(), it.progress.toArgb(), it.track.toArgb())
    }
    val focusedContainer = aniFocusContainerColor().toArgb()
    val focusedContent = aniFocusContentColor().toArgb()
    val icon = rememberTvNativeIcon(Icons.Rounded.GraphicEq, TV_NATIVE_EPISODE_GRID_NUMBER_ICON)
    return remember(density, typography, palette, focusedContainer, focusedContent, icon, cellWidthPx, cellHeightPx) {
        with(density) {
            TvNativeEpisodeNumberStyle(
                cellWidthPx = cellWidthPx,
                cellHeightPx = cellHeightPx,
                cornerPx = TV_NATIVE_EPISODE_GRID_NUMBER_CORNER.toPx(),
                palette = palette,
                focusedContainer = focusedContainer,
                focusedContent = focusedContent,
                progressShade = Color.Black.copy(alpha = TV_NATIVE_EPISODE_GRID_NUMBER_SHADE_ALPHA).toArgb(),
                text = typography.titleSmall.toTvNativeTextStyle(density, Color.White),
                icon = icon,
                iconPx = TV_NATIVE_EPISODE_GRID_NUMBER_ICON.roundToPx(),
                iconGapPx = 4.dp.roundToPx(),
            )
        }
    }
}

/**
 * 只显示集号时的一格: 集号居中 (正在播放的前面画声浪图标), 底色与字色同剧照卡 (正在播放 / 看过 / 没看); 进度画成格子本身从左边起铺
 * 一层深色, 不另画进度条 (密排时每格一条细线连成一片, 太吵). 聚焦铺实底. 确定键点击, 按住确定长按 (同剧照卡, [TvNativeConfirmKey]).
 * 尺寸定死, 整格自己画 (没有子视图).
 */
@SuppressLint("ViewConstructor")
class TvNativeEpisodeNumberView(context: Context, private val style: TvNativeEpisodeNumberStyle) : View(context) {
    private val confirmKey = TvNativeConfirmKey()
    private var card: TvNativeEpisodeCard? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textSize = style.text.sizePx
        it.letterSpacing = style.text.letterSpacingEm
        it.typeface = tvNativeTypeface(style.text.weight)
    }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var iconColor = 0
    private val rect = RectF()
    private val clip = Path()
    private var lastPressed = false

    /** 确定键长按的回调 (本集详情); null = 按住也只算点击. */
    var longPressHandler: (() -> Unit)? = null

    /** 按压态变化 (按下即缩小、到长按阈值弹回, 由网格缩放这一格). */
    var onPressedChanged: ((View, Boolean) -> Unit)? = null

    /** 长按的触发闸门 (网格还在滚时先别触发, 见 [TvNativeConfirmKey.readyToFire]). */
    var readyToFire: (() -> Boolean)?
        get() = confirmKey.readyToFire
        set(value) {
            confirmKey.readyToFire = value
        }

    init {
        layoutParams = ViewGroup.LayoutParams(style.cellWidthPx, style.cellHeightPx)
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    fun bind(card: TvNativeEpisodeCard) {
        this.card = card
        contentDescription = card.sort
        scaleX = 1f
        scaleY = 1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(style.cellWidthPx, style.cellHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        val c = card ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val focused = isFocused
        val colors = style.colors(c.playing, c.watched)
        rect.set(0f, 0f, w, h)
        clip.rewind()
        clip.addRoundRect(rect, style.cornerPx, style.cornerPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        fillPaint.color = if (focused) style.focusedContainer else colors.container
        canvas.drawRect(rect, fillPaint)
        val progress = (if (c.watched) 1f else c.progress ?: 0f).coerceIn(0f, 1f)
        if (progress > 0f) {
            fillPaint.color = style.progressShade
            canvas.drawRect(0f, 0f, w * progress, h, fillPaint)
        }
        canvas.restoreToCount(save)
        val content = if (focused) style.focusedContent else colors.sort
        textPaint.color = content
        val textWidth = textPaint.measureText(c.sort)
        val iconWidth = if (c.playing) style.iconPx + style.iconGapPx else 0
        var x = (w - textWidth - iconWidth) / 2f
        if (c.playing) {
            if (iconColor != content) {
                iconColor = content
                iconPaint.colorFilter = PorterDuffColorFilter(content, PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(style.icon, x, (h - style.iconPx) / 2f, iconPaint)
            x += iconWidth
        }
        val metrics = textPaint.fontMetrics
        canvas.drawText(c.sort, x, h / 2f - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, longPressHandler) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, null) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) confirmKey.reset()
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val pressed = isPressed
        if (pressed != lastPressed) {
            lastPressed = pressed
            onPressedChanged?.invoke(this, pressed)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 集号格的适配器. 稳定 id = 集 id: 只换内容 (看过、进度) 时只通知变了的那几格, 持焦的那格原地重绑, 焦点不丢 (同选集行的适配器). */
@SuppressLint("NotifyDataSetChanged")
private class TvNativeEpisodeNumberAdapter(
    private val style: TvNativeEpisodeNumberStyle,
) : RecyclerView.Adapter<TvNativeEpisodeNumberAdapter.Holder>() {
    private var cards: List<TvNativeEpisodeCard> = emptyList()

    var listener: TvNativeEpisodeGridListener? = null
    var onPressedChanged: ((View, Boolean) -> Unit)? = null
    var readyToFire: (() -> Boolean)? = null

    init {
        setHasStableIds(true)
    }

    fun submit(new: List<TvNativeEpisodeCard>) {
        val old = cards
        if (new == old) return
        cards = new
        if (old.size != new.size || old.indices.any { old[it].id != new[it].id }) {
            notifyDataSetChanged()
            return
        }
        for (i in new.indices) if (old[i] != new[i]) notifyItemChanged(i)
    }

    override fun getItemCount(): Int = cards.size

    override fun getItemId(position: Int): Long = cards[position].id.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(TvNativeEpisodeNumberView(parent.context, style))
        val cell = holder.cell
        cell.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) listener?.onFocusChanged(index, hasFocus)
        }
        cell.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) listener?.onClick(index)
        }
        cell.longPressHandler = {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) listener?.onLongPress(index)
        }
        cell.onPressedChanged = { view, pressed -> onPressedChanged?.invoke(view, pressed) }
        cell.readyToFire = { readyToFire?.invoke() ?: true }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.cell.bind(cards[position])
    }

    class Holder(val cell: TvNativeEpisodeNumberView) : RecyclerView.ViewHolder(cell)
}

/** 剧照卡的宽高比 (TMDB 剧照 16:9). */
private const val TV_NATIVE_EPISODE_STILL_RATIO = 16f / 9f

/** 剧照卡最窄多宽、卡距: 平板 / iPad 一行四张, 电视一行三张. */
private val TV_NATIVE_EPISODE_GRID_STILL_MIN_WIDTH = 180.dp
private val TV_NATIVE_EPISODE_GRID_STILL_GAP = 16.dp

/** 集号格: 最窄宽、高、圆角、间距, 声浪图标的边长. */
private val TV_NATIVE_EPISODE_GRID_NUMBER_MIN_WIDTH = 72.dp
private val TV_NATIVE_EPISODE_GRID_NUMBER_HEIGHT = 52.dp
private val TV_NATIVE_EPISODE_GRID_NUMBER_CORNER = 12.dp
private val TV_NATIVE_EPISODE_GRID_NUMBER_GAP = 10.dp
private val TV_NATIVE_EPISODE_GRID_NUMBER_ICON = 14.dp

/** 集号格的进度那层黑的不透明度. */
private const val TV_NATIVE_EPISODE_GRID_NUMBER_SHADE_ALPHA = 0.16f

/** 网格左右让出的一截: 聚焦框伸出格外的那一圈画在里面 (同「查看全部」画廊). */
private val TV_NATIVE_EPISODE_GRID_SIDE_INSET = 12.dp
