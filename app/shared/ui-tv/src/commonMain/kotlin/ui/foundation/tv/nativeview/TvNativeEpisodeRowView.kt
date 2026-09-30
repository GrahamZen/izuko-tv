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
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.Interpolator
import android.widget.ImageView
import androidx.compose.runtime.Immutable
import androidx.leanback.widget.BaseGridView
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.TvNativeImages
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 原生选集行的一张卡 (详情页选集轮播 / 播放器选集条, 大脑见 FocusEpisodeCarousel).
 *
 * @param stillUrl 剧照 (卡片档 URL); null = 纯文字卡.
 * @param playing 正在播放 (详情页: 下一集要看) 的那一集: 没聚焦时行首画声浪图标, 底色换主色调.
 * @param progress 进度条 (0..1); null / 0 不画. 看过的集由调用方给 1 (满条).
 */
@Immutable
data class TvNativeEpisodeCard(
    val id: Int,
    val sort: String,
    val name: String,
    val stillUrl: String?,
    val playing: Boolean,
    val watched: Boolean,
    val progress: Float?,
)

/**
 * 原生选集行的事件. [index] 是卡在行里的下标.
 *
 * 行内移动时旧卡的 [onFocusLost] 先于新卡的 [onFocused] (View 焦点的派发顺序).
 */
interface TvNativeEpisodeRowListener {
    fun onFocused(index: Int)
    fun onFocusLost(index: Int)
    fun onClick(index: Int)
    fun onLongPress(index: Int)
    fun onScrollingChanged(scrolling: Boolean)
}

/**
 * 原生选集卡, 外观同 Compose 版 FocusEpisodeCard (几何与配色取自同一组常量, 见 [TvNativeEpisodeStyle]): 圆角卡片, 有剧照时图铺满、
 * 底部 scrim 上一行 "行首图标 集号 集名" (基线对齐), 没有剧照时玻璃 / 实心底上两行字顶对齐; 进度条贴底. 聚焦时行首换成播放三角、集名跑马灯;
 * 聚焦框不画在卡上 (行上固定锚位的那一个, 见 [TvNativeEpisodeRowLayout]).
 *
 * 尺寸定死 ([TvNativeBoundaryLayout]): 焦点变化 (行首图标、墨迹补偿、跑马灯) 与换图只在卡里重排, 不往上冒泡.
 * 行首墨迹对齐与图标竖向对齐的道理见 FocusEpisodeCard 里 leadingInkInset / leadingIconBaselineOffsetPx 的说明.
 */
@SuppressLint("ViewConstructor")
class TvNativeEpisodeCardView(context: Context, style: TvNativeEpisodeStyle) : TvNativeBoundaryLayout(context) {
    private var style: TvNativeEpisodeStyle = style
    private val still = TvNativeEpisodeStillView(context)
    private val sort = TvNativeTextView(context)
    private val name = TvNativeTextView(context)
    private val confirmKey = TvNativeConfirmKey()
    private var card: TvNativeEpisodeCard? = null
    private var sketch: Sketch? = null

    private var boundStillUrl: String? = null
    private var stillAttempt = 0
    private var stillRetry: Runnable? = null

    // 布局结果 (measureContent 里算)
    private var icon: Bitmap? = null
    private var iconLeft = 0
    private var iconTop = 0
    private var sortLeft = 0
    private var sortTop = 0
    private var nameLeft = 0
    private var nameTop = 0

    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()
    private val barClip = Path()

    private var dimTarget = 1f
    private var dimAnimator: ObjectAnimator? = null
    private var lastPressed = false

    /** 确定键长按的回调 (本集详情弹窗); null = 没有长按, 按住也只算点击. */
    var longPressHandler: (() -> Unit)? = null

    /** 按压态变化 (按下即缩小、到长按阈值弹回, 由行同步缩放卡与固定聚焦框). */
    var onPressedChanged: ((TvNativeEpisodeCardView, Boolean) -> Unit)? = null

    /** 长按的触发闸门 (卡片还在滑向固定聚焦框时先别触发, 见 [TvNativeConfirmKey.readyToFire]). */
    var readyToFire: (() -> Boolean)?
        get() = confirmKey.readyToFire
        set(value) {
            confirmKey.readyToFire = value
        }

    /** 页面不在前台时停住跑马灯 (见 [TvNativeAmbientAnimations]). */
    var marqueePaused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateMarquee()
        }

    init {
        layoutParams = ViewGroup.LayoutParams(style.cardWidthPx, style.cardHeightPx)
        // 触摸模式下也可聚焦 (同海报卡)
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, this@TvNativeEpisodeCardView.style.cornerPx)
            }
        }
        clipToOutline = true
        addView(still, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(sort, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        sort.maxLines = 1
        name.setSingleLine(true)
        applyTextStyles()
    }

    /** 换配色 / 字体 (主题变了): 尺寸不变. 之后重新 [bind] 才按新配色画. */
    fun applyStyle(style: TvNativeEpisodeStyle) {
        if (this.style == style) return
        this.style = style
        applyTextStyles()
        invalidateOutline()
    }

    private fun applyTextStyles() {
        style.sort.applyTo(sort)
        style.name.applyTo(name)
        updateMarquee()
    }

    fun bind(card: TvNativeEpisodeCard, sketch: Sketch) {
        this.card = card
        this.sketch = sketch
        contentDescription = "${card.sort}. ${card.name}"
        sort.text = card.sort
        name.text = card.name
        val colors = style.colors(card.playing, card.watched)
        val hasStill = card.stillUrl != null
        setBackgroundColor(colors.container)
        sort.setTextColor(if (hasStill) Color.WHITE else colors.sort)
        name.setTextColor(if (hasStill) style.imageNameColor else colors.name)
        iconPaint.colorFilter = if (hasStill) null else PorterDuffColorFilter(colors.sort, PorterDuff.Mode.SRC_IN)
        trackPaint.color = if (hasStill) style.imageTrackColor else colors.track
        fillPaint.color = colors.progress
        still.scrim = if (hasStill) style else null
        bindStill(card.stillUrl)
        scaleX = 1f
        scaleY = 1f
        requestLayout()
        invalidate()
    }

    private fun bindStill(url: String?) {
        if (url == boundStillUrl) return
        boundStillUrl = url
        stillAttempt = 0
        stillRetry?.let { removeCallbacks(it) }
        stillRetry = null
        if (url == null) {
            TvNativeImages.clear(still)
        } else {
            loadStill(url)
        }
    }

    private fun loadStill(url: String) {
        val sketch = sketch ?: return
        TvNativeImages.loadStill(sketch, still, url, style.crossfade) { onStillError(url) }
    }

    /**
     * 剧照加载失败: 按次数退避重试, 同 Compose 版的 rememberAsyncImageRetryState (快速滑过时的并发洪峰会让个别请求失败,
     * 同一个 URL 重新请求能加载).
     */
    private fun onStillError(url: String) {
        if (url != boundStillUrl || stillAttempt >= TV_NATIVE_STILL_MAX_ATTEMPTS) return
        stillAttempt++
        val retry = Runnable {
            stillRetry = null
            if (boundStillUrl == url) loadStill(url)
        }
        stillRetry = retry
        postDelayed(retry, TV_NATIVE_STILL_RETRY_BACKOFF_MILLIS * stillAttempt)
    }

    /** 整张卡的不透明度 (压暗分界左边的卡、倒计时态落点之后的卡): [animate] 时照 Compose 版的 tween 渐变过去. */
    fun setDim(target: Float, animate: Boolean) {
        val running = dimAnimator
        if (dimTarget == target) {
            // 已经在往这个值走 / 已经停在这个值上
            if (animate && running != null) return
            if (running == null && alpha == target) return
        }
        dimTarget = target
        dimAnimator = null
        running?.cancel()
        if (!animate || alpha == target) {
            alpha = target
            return
        }
        dimAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, alpha, target).apply {
            duration = style.dimMillis
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (dimAnimator === animation) dimAnimator = null
                }
            })
            start()
        }
    }

    override fun measureContent(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val w = s.cardWidthPx
        val h = s.cardHeightPx
        setMeasuredDimension(w, h)
        still.measure(exactly(w), exactly(h))
        val c = card
        val focusLook = isFocused
        val playing = c?.playing == true
        val hasStill = c?.stillUrl != null
        val barShown = (c?.progress ?: 0f) > 0f
        val shownIcon = when {
            focusLook -> s.playIcon
            playing -> s.playingIcon
            else -> null
        }
        icon = shownIcon
        val iconSize = when {
            focusLook -> s.playIconPx
            playing -> s.playingIconPx
            else -> 0
        }
        val inkInset = when {
            focusLook -> 0
            playing -> s.playingInkInsetPx
            else -> s.playInkInsetPx
        }
        val right = w - s.textSidePx
        var x = s.textSidePx + inkInset
        iconLeft = x
        if (shownIcon != null) x += iconSize + s.textGapPx
        sort.measure(atMost((right - x).coerceAtLeast(0)), UNSPECIFIED)
        sortLeft = x
        // Compose 的 Text 在有字距时把固有宽度多算半个像素再向上取整 (LayoutIntrinsics), 后面的集名跟着右移一个像素
        x += sort.measuredWidth + if (sort.letterSpacing != 0f) 1 else 0
        // 行首图标的对齐线: 图标中心在集号基线上方半个 cap height
        val iconLine = iconSize / 2 + s.iconBaselineOffsetPx
        if (hasStill) {
            // 一行: [图标] 集号 集名, 基线对齐, 整行贴底 (下内边距按有没有进度条两档)
            nameLeft = x + s.textGapPx
            name.measure(exactly((right - nameLeft).coerceAtLeast(0)), UNSPECIFIED)
            val sortLine = sort.baseline
            val nameLine = name.baseline
            var line = max(sortLine, nameLine)
            if (shownIcon != null) line = max(line, iconLine)
            var rowHeight = max(line - sortLine + sort.measuredHeight, line - nameLine + name.measuredHeight)
            if (shownIcon != null) rowHeight = max(rowHeight, line - iconLine + iconSize)
            val rowTop = h - (if (barShown) s.textBottomWithBarPx else s.textBottomNoBarPx) - rowHeight
            sortTop = rowTop + line - sortLine
            nameTop = rowTop + line - nameLine
            iconTop = rowTop + line - iconLine
        } else {
            // 两行顶对齐: [图标] 集号 / 集名 (集名补满一个三角内白, 与上一行的墨迹同线)
            val sortLine = sort.baseline
            var line = sortLine
            if (shownIcon != null) line = max(line, iconLine)
            var rowHeight = line - sortLine + sort.measuredHeight
            if (shownIcon != null) rowHeight = max(rowHeight, line - iconLine + iconSize)
            val rowTop = s.textTopPx
            sortTop = rowTop + line - sortLine
            iconTop = rowTop + line - iconLine
            nameLeft = s.textSidePx + s.playInkInsetPx
            name.measure(exactly((right - nameLeft).coerceAtLeast(0)), UNSPECIFIED)
            // 集名自成一行: 字体 (中文的回落字体) 比行高高时, Compose 版的字照字体本来的上沿排, 往下多出这一截
            nameTop = rowTop + rowHeight + s.textGapPx + name.fontOverflowTopPx
        }
    }

    override fun layoutContent(width: Int, height: Int) {
        still.layout(0, 0, width, height)
        sort.layout(sortLeft, sortTop, sortLeft + sort.measuredWidth, sortTop + sort.measuredHeight)
        name.layout(nameLeft, nameTop, nameLeft + name.measuredWidth, nameTop + name.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        icon?.let { canvas.drawBitmap(it, iconLeft.toFloat(), iconTop.toFloat(), iconPaint) }
        drawProgress(canvas)
    }

    /** 贴底的进度条: 轨道是胶囊形, 填充按进度从左端起、裁在轨道里 (同 Compose 版 FocusEpisodeProgressBar). */
    private fun drawProgress(canvas: Canvas) {
        val p = card?.progress ?: return
        if (p <= 0f) return
        val s = style
        val bottom = (height - s.barBottomPx).toFloat()
        barRect.set(s.barSidePx.toFloat(), bottom - s.barHeightPx, (width - s.barSidePx).toFloat(), bottom)
        val radius = s.barHeightPx / 2f
        canvas.drawRoundRect(barRect, radius, radius, trackPaint)
        barClip.rewind()
        barClip.addRoundRect(barRect, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(barClip)
        canvas.drawRect(barRect.left, barRect.top, barRect.left + barRect.width() * p.coerceIn(0f, 1f), barRect.bottom, fillPaint)
        canvas.restoreToCount(save)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, longPressHandler) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, null) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) confirmKey.reset()
        updateMarquee()
        // 行首图标与墨迹补偿随焦点换: 卡里重排一次
        requestLayout()
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

    /** 聚焦时集名跑马灯 (放不下才滚), 没聚焦时省略号; 流畅档聚焦也不滚, 直接裁掉 (同 Compose 版). */
    private fun updateMarquee() {
        val focusLook = isFocused
        val run = focusLook && style.marqueeRepeat != 0
        name.marqueeRepeatLimit = style.marqueeRepeat
        name.ellipsize = when {
            run -> TextUtils.TruncateAt.MARQUEE
            focusLook -> null
            else -> TextUtils.TruncateAt.END
        }
        name.isSelected = run && !marqueePaused
    }

    // 卡片内容互不重叠地叠在底色上, 透明度逐绘制指令乘 (同 Compose 版的 ModulateAlpha), 不走离屏层
    override fun hasOverlappingRendering(): Boolean = false
}

private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

private fun atMost(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)

private val UNSPECIFIED = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

/**
 * 选集卡的剧照: 中心裁剪铺满, 有剧照的卡在图上叠底部 scrim ([scrim] 非 null 时画, 从卡高 [TvNativeEpisodeStyle.scrimStart] 处的全透明
 * 渐变到卡底的黑). 尺寸由卡定死, 换图不往上冒泡重排 (同海报卡的封面).
 */
@SuppressLint("AppCompatCustomView")
private class TvNativeEpisodeStillView(context: Context) : ImageView(context) {
    private val scrimPaint = Paint()
    private var shaderHeight = -1

    var scrim: TvNativeEpisodeStyle? = null
        set(value) {
            if (field == value) return
            field = value
            shaderHeight = -1
            invalidate()
        }

    init {
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = scrim ?: return
        val top = height * s.scrimStart
        if (shaderHeight != height) {
            shaderHeight = height
            scrimPaint.shader = LinearGradient(0f, top, 0f, height.toFloat(), Color.TRANSPARENT, s.scrimColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), scrimPaint)
    }

    override fun requestLayout() {
        if (!isLaidOut) super.requestLayout()
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/**
 * 原生选集行的适配器. 稳定 id = 集 id: 同一串集只换内容 (看过状态、进度、剧照晚到) 时只通知变了的那几张, 持焦的卡原地重绑, 焦点不丢.
 */
@SuppressLint("NotifyDataSetChanged")
class TvNativeEpisodeAdapter(
    style: TvNativeEpisodeStyle,
    private val sketch: Sketch,
) : RecyclerView.Adapter<TvNativeEpisodeAdapter.Holder>() {
    var style: TvNativeEpisodeStyle = style
        private set

    var listener: TvNativeEpisodeRowListener? = null

    /** 卡片支持长按确认键; false = 按住也只算点击, 也没有按压反馈. 在绑定之前定. */
    var longPressEnabled: Boolean = true

    /** 长按的触发闸门 (见 [TvNativeConfirmKey.readyToFire]), 绑定时交给每张卡. */
    var readyToFire: (() -> Boolean)? = null

    /** 卡的按压态变化 (见 [TvNativeEpisodeCardView.onPressedChanged]). */
    var onPressedChanged: ((TvNativeEpisodeCardView, Boolean) -> Unit)? = null

    var cards: List<TvNativeEpisodeCard> = emptyList()
        private set

    /** 压暗分界 (下标小于它的卡压暗) 与每张卡额外的不透明度, 见 [dimOf]; 改用 [TvNativeEpisodeRowLayout] 上的同名方法. */
    internal var dimPivot: Int = -1
    internal var cardAlpha: ((Int) -> Float)? = null

    internal var marqueePaused: Boolean = false

    init {
        setHasStableIds(true)
    }

    /** 第 [index] 张此刻该有的不透明度: 压暗分界左边的压到 [TvNativeEpisodeStyle.pastDimAlpha], 再乘调用方给的额外不透明度. */
    fun dimOf(index: Int): Float =
        (if (index < dimPivot) style.pastDimAlpha else 1f) * (cardAlpha?.invoke(index) ?: 1f)

    /**
     * 换数据. 内容没变就不动; 集的先后没变时只通知变了的卡 (没变的卡不重绑, 剧照不再走一遍加载入口), 否则整排刷新 (稳定 id 让持焦的卡留着).
     */
    fun submit(new: List<TvNativeEpisodeCard>) {
        val old = cards
        if (new == old) return
        cards = new
        if (old.size != new.size || old.indices.any { old[it].id != new[it].id }) {
            notifyDataSetChanged()
            return
        }
        var i = 0
        while (i < new.size) {
            if (old[i] == new[i]) {
                i++
                continue
            }
            val start = i
            while (i < new.size && old[i] != new[i]) i++
            notifyItemRangeChanged(start, i - start)
        }
    }

    /** 换配色 (主题变了): 屏上的卡当场按新配色重绑. */
    fun applyStyle(recycler: RecyclerView, style: TvNativeEpisodeStyle) {
        if (this.style == style) return
        this.style = style
        forEachCard(recycler) { _, card -> card.applyStyle(style) }
        if (cards.isNotEmpty()) notifyItemRangeChanged(0, cards.size)
    }

    /** 按 [dimOf] 重算屏上每张卡的不透明度. */
    fun refreshDims(recycler: RecyclerView, animate: Boolean) {
        forEachCard(recycler) { index, card -> card.setDim(dimOf(index), animate) }
    }

    fun setMarqueePaused(recycler: RecyclerView, paused: Boolean) {
        marqueePaused = paused
        for (i in 0 until recycler.childCount) (recycler.getChildAt(i) as? TvNativeEpisodeCardView)?.marqueePaused = paused
    }

    override fun getItemCount(): Int = cards.size

    override fun getItemId(position: Int): Long = cards[position].id.toLong()

    override fun getItemViewType(position: Int): Int = TV_NATIVE_EPISODE_VIEW_TYPE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(TvNativeEpisodeCardView(parent.context, style))
        holder.card.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) {
                if (hasFocus) listener?.onFocused(index) else listener?.onFocusLost(index)
            }
        }
        holder.card.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) listener?.onClick(index)
        }
        holder.card.onPressedChanged = { card, pressed -> onPressedChanged?.invoke(card, pressed) }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        // 回收池里的卡可能还是换配色之前建的
        holder.card.applyStyle(style)
        holder.card.bind(cards[position], sketch)
        holder.card.longPressHandler = if (longPressEnabled) {
            {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) listener?.onLongPress(index)
            }
        } else {
            null
        }
        holder.card.readyToFire = readyToFire
        holder.card.marqueePaused = marqueePaused
        holder.card.setDim(dimOf(position), animate = false)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        // 回收缓存里原样拿回的卡不重绑: 离屏期间改的压暗分界 / 跑马灯暂停在重新上屏时补上
        val index = holder.bindingAdapterPosition
        if (index != RecyclerView.NO_POSITION) holder.card.setDim(dimOf(index), animate = false)
        holder.card.marqueePaused = marqueePaused
    }

    private inline fun forEachCard(recycler: RecyclerView, action: (Int, TvNativeEpisodeCardView) -> Unit) {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i) as? TvNativeEpisodeCardView ?: continue
            val index = recycler.getChildAdapterPosition(child)
            if (index != RecyclerView.NO_POSITION) action(index, child)
        }
    }

    class Holder(val card: TvNativeEpisodeCardView) : RecyclerView.ViewHolder(card)
}

/**
 * 原生选集行 (详情页选集轮播 / 播放器选集条), 行为见 [TvNativeStripView]; 与海报行不同的是**固定锚点** (Prime Video 式, 同 Compose 版
 * FocusEpisodeCarousel): 聚焦卡恒停在行首停靠线 (leanback 的对齐滚动, 停靠线 = 行首留白), 左右键时只有卡片在钉住的聚焦框下滑过;
 * 行尾留一整行空白, 末集也停得进框里. 行首按左与行尾按右都吞掉 (两头没有目标, 放出去会被系统按屏幕位置挑进同页别的原生行).
 *
 * 行外进来落到 [entryIndex] 那张 (展示中的集): 排出来了就给它, 由对齐滚动把它滑进框里; 没排出来先选中它、行自己接住焦点, 布局完交给它.
 * 行里有焦点之后才往两边多排 [TV_NATIVE_STRIP_HOLD_LEAD_CARDS] 张 (见 TvNativeStripView 的 aheadLayoutPx): 长按连发时平滑滚动落后焦点
 * 两张多, 往回走时左边那几张也得排着.
 */
@SuppressLint("ViewConstructor")
class TvNativeEpisodeRowView(
    context: Context,
    style: TvNativeEpisodeStyle,
    sketch: Sketch,
    startPx: Int,
    topPx: Int,
    bottomPx: Int,
) : TvNativeStripView(
    context,
    stepPx = style.cardWidthPx + style.spacingPx,
    spacingPx = style.spacingPx,
    extraLayoutPx = 0,
    prefetchItems = 0,
    startPx = startPx,
    endPx = 0,
    topPx = topPx,
    bottomPx = bottomPx,
    aheadLayoutPx = (style.cardWidthPx + style.spacingPx) * TV_NATIVE_STRIP_HOLD_LEAD_CARDS,
) {
    val cards = TvNativeEpisodeAdapter(style, sketch)

    init {
        startLeftExits = false
        @SuppressLint("RestrictedApi")
        val strategy = BaseGridView.FOCUS_SCROLL_ALIGNED
        setFocusScrollStrategy(strategy)
        windowAlignment = BaseGridView.WINDOW_ALIGN_NO_EDGE
        windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        windowAlignmentOffset = startPx
        itemAlignmentOffset = 0
        itemAlignmentOffsetPercent = 0f
        adapter = cards
    }

    /**
     * 停靠线 (= 行首留白) 与行尾留白: 行尾留白取 "行宽 − 停靠线 − 卡宽", 于是"完整露出" 的只有框里那一张 (行外进来的判据见
     * [acceptsEntry]).
     */
    fun setEdges(startPx: Int, endPx: Int) {
        if (paddingLeft != startPx || paddingRight != endPx) setPadding(startPx, paddingTop, endPx, paddingBottom)
        if (windowAlignmentOffset != startPx) windowAlignmentOffset = startPx
    }

    // 落点那张排出来了就给它: 对齐滚动会把它滑进框里 (不要求此刻已经在框里)
    override fun acceptsEntry(view: View): Boolean = true

    /** 停稳时选中的那张没停在停靠线上 (平滑滚动半路被打断) 就挪过去. */
    override fun alignWhenIdle() {
        if (!isAttachedToWindow || scrollState != SCROLL_STATE_IDLE) return
        val view = findViewHolderForAdapterPosition(selectedPosition)?.itemView ?: return
        val delta = view.left - paddingLeft
        if (delta != 0) scrollBy(delta, 0)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val index = entryIndex()
        val count = cards.itemCount
        if (childCount > 0 && index in 0 until count && findViewHolderForAdapterPosition(index) == null) {
            // 落点那张不在排好的卡里 (远处): 选中它 (下一次布局排在停靠线上), 行自己先接住焦点, 布局完交给它 (见基类的 onLayout)
            selectedPosition = index
            isFocusableInTouchMode = true
            return false
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    /** 滚到第 [index] 张停在停靠线上; [animated] = false 时一步到位 (下一次布局). 行里有焦点时焦点跟着过去 (leanback 的选中即聚焦). */
    fun scrollToCard(index: Int, animated: Boolean) {
        val count = cards.itemCount
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        if (animated) setSelectedPositionSmooth(target) else selectedPosition = target
    }

    /** 第 [index] 张此刻在不在屏上 (露出一截也算). */
    fun isCardOnScreen(index: Int): Boolean {
        val view = findViewHolderForAdapterPosition(index)?.itemView ?: return false
        return view.right > 0 && view.left < width
    }

    /** 此刻有没有一张卡持焦 (行自己等布局时接住的焦点不算). */
    fun hasFocusedCard(): Boolean {
        for (i in 0 until childCount) if (getChildAt(i).isFocused) return true
        return false
    }
}

/**
 * 固定锚位聚焦框 (同 Compose 版 FocusEpisodeAnchorRing): 钉在停靠线上不随卡片动, 比卡大一圈 ([TvNativeEpisodeStyle.ringOutsetPx]),
 * 描边画在框内侧 (几何同 TvFocusRing 的 tvFocusRingBorder). [countdownActive] 时画成倒计时环 (同 tvFocusRingCountdownBorder: 整圈压暗的底轨,
 * 走过的一段从顶边左端顺时针画满, 圆头); 进度 ≤ 0 时是普通的整圈.
 */
@SuppressLint("ViewConstructor")
class TvNativeEpisodeRingView(context: Context, style: TvNativeEpisodeStyle) : View(context) {
    private var style: TvNativeEpisodeStyle = style
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE }
    private val walkedPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private var outline: Path? = null
    private var measure: PathMeasure? = null
    private var outlineLength = 0f
    private val walked = Path()
    private var builtWidth = -1
    private var builtHeight = -1

    var countdownActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var countdownProgress: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            if (countdownActive) invalidate()
        }

    fun applyStyle(style: TvNativeEpisodeStyle) {
        if (this.style == style) return
        this.style = style
        builtWidth = -1
        invalidate()
    }

    private fun build(w: Int, h: Int) {
        builtWidth = w
        builtHeight = h
        val s = style
        val stroke = s.ringStrokePx
        paint.strokeWidth = stroke
        walkedPaint.strokeWidth = stroke
        val shader = if (s.ringStartColor == s.ringEndColor) {
            null
        } else {
            LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), s.ringStartColor, s.ringEndColor, Shader.TileMode.CLAMP)
        }
        for (p in arrayOf(paint, walkedPaint)) {
            p.shader = shader
            p.color = s.ringStartColor
        }
        val path = tvNativeRingPathFromTopLeft(w.toFloat(), h.toFloat(), stroke, s.ringCornerPx)
        outline = path
        measure = PathMeasure(path, false).also { outlineLength = it.length }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (w != builtWidth || h != builtHeight) build(w, h)
        val s = style
        val fraction = countdownProgress.coerceIn(0f, 1f)
        if (!countdownActive || fraction <= 0f) {
            val half = s.ringStrokePx / 2f
            rect.set(half, half, w - half, h - half)
            val r = (s.ringCornerPx - half).coerceAtLeast(0f)
            paint.alpha = 255
            canvas.drawRoundRect(rect, r, r, paint)
            return
        }
        paint.alpha = (255 * s.ringCountdownTrackAlpha).toInt()
        outline?.let { canvas.drawPath(it, paint) }
        paint.alpha = 255
        walked.rewind()
        measure?.getSegment(0f, outlineLength * fraction, walked, true)
        canvas.drawPath(walked, walkedPaint)
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 圆角矩形描边的路径: 起点在顶边左端 (左上圆角结束处)、顺时针闭合; 内缩半个线宽, 圆角同步减半个线宽 (同 TvFocusRing 的那一份). */
private fun tvNativeRingPathFromTopLeft(width: Float, height: Float, stroke: Float, radius: Float): Path {
    val half = stroke / 2f
    val left = half
    val top = half
    val right = width - half
    val bottom = height - half
    val r = (radius - half).coerceIn(0f, min(right - left, bottom - top) / 2f)
    val arc = RectF()
    return Path().apply {
        moveTo(left + r, top)
        lineTo(right - r, top)
        arc.set(right - 2 * r, top, right, top + 2 * r)
        arcTo(arc, -90f, 90f, false)
        lineTo(right, bottom - r)
        arc.set(right - 2 * r, bottom - 2 * r, right, bottom)
        arcTo(arc, 0f, 90f, false)
        lineTo(left + r, bottom)
        arc.set(left, bottom - 2 * r, left + 2 * r, bottom)
        arcTo(arc, 90f, 90f, false)
        lineTo(left, top + r)
        arc.set(left, top, left + 2 * r, top + 2 * r)
        arcTo(arc, 180f, 90f, false)
        close()
    }
}

/**
 * 原生选集行的整块 (装在 TvNativeRowHost 里的就是它): 卡片行 [row] + 钉在停靠线上的聚焦框. 框在行里有卡持焦时画 (当场出现, 不淡入),
 * 按住确认键时与聚焦卡一起缩 ([TvNativeEpisodeStyle.pressScale], 同一条曲线, 框与卡同心). 压暗分界随聚焦卡当场挪 (轮播的状态随后跟上,
 * 同值不再动画). 尺寸由装它的那层定死, 行里的重排不往上冒泡 ([TvNativeBoundaryLayout]).
 *
 * @param bleedPx 上下各多出的一截 (框伸出卡外的部分画在里面), 卡片从 [bleedPx] 处排起.
 */
@SuppressLint("ViewConstructor")
class TvNativeEpisodeRowLayout(
    context: Context,
    style: TvNativeEpisodeStyle,
    sketch: Sketch,
    startPx: Int,
    private val bleedPx: Int,
) : TvNativeBoundaryLayout(context), TvNativeAmbientAnimations {
    private var style: TvNativeEpisodeStyle = style
    private var startPx: Int = startPx
    val row = TvNativeEpisodeRowView(context, style, sketch, startPx, topPx = bleedPx, bottomPx = bleedPx)
    private val ring = TvNativeEpisodeRingView(context, style)
    private val scrollTracker = TvNativeScrollTracker { listener?.onScrollingChanged(it) }

    var listener: TvNativeEpisodeRowListener? = null

    private var pressedCard: TvNativeEpisodeCardView? = null
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ring, LayoutParams(style.ringWidthPx, style.ringHeightPx))
        ring.visibility = INVISIBLE
        ring.isFocusable = false
        row.cards.readyToFire = { row.scrollState == RecyclerView.SCROLL_STATE_IDLE }
        row.cards.onPressedChanged = { card, pressed -> onCardPressed(card, pressed) }
        row.cards.listener = object : TvNativeEpisodeRowListener {
            override fun onFocused(index: Int) {
                ring.visibility = VISIBLE
                setDimPivot(index, animate = true)
                listener?.onFocused(index)
            }

            override fun onFocusLost(index: Int) {
                // 行内移动时新卡此刻已经是聚焦态 (View 先标新卡再撤旧卡)
                if (!row.hasFocusedCard()) ring.visibility = INVISIBLE
                listener?.onFocusLost(index)
            }

            override fun onClick(index: Int) {
                listener?.onClick(index)
            }

            override fun onLongPress(index: Int) {
                listener?.onLongPress(index)
            }

            override fun onScrollingChanged(scrolling: Boolean) = Unit
        }
        row.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                scrollTracker.onStateChanged(this@TvNativeEpisodeRowLayout, newState)
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scrollTracker.onMoved(dx)
            }
        })
    }

    /** 换配色 (主题变了): 尺寸不变. */
    fun applyStyle(style: TvNativeEpisodeStyle) {
        if (this.style == style) return
        this.style = style
        ring.applyStyle(style)
        row.cards.applyStyle(row, style)
    }

    /** 停靠线 (= 聚焦框左缘让开的那一圈之内) 改了. */
    fun setStart(startPx: Int) {
        if (this.startPx == startPx) return
        this.startPx = startPx
        requestLayout()
    }

    /** 压暗分界 (见 [TvNativeEpisodeAdapter.dimOf]); [animate] = false 时屏上的卡当场换 (首次绑定、恢复的分界). */
    fun setDimPivot(index: Int, animate: Boolean) {
        val cards = row.cards
        if (cards.dimPivot == index) return
        cards.dimPivot = index
        cards.refreshDims(row, animate)
    }

    /** 每张卡额外的不透明度 (倒计时态落点之后的卡渐隐); 换了就渐变过去. */
    fun setCardAlpha(alpha: ((Int) -> Float)?, animate: Boolean) {
        val cards = row.cards
        if (cards.cardAlpha === alpha) return
        cards.cardAlpha = alpha
        cards.refreshDims(row, animate)
    }

    fun setCountdown(active: Boolean) {
        ring.countdownActive = active
    }

    fun setCountdownProgress(progress: Float) {
        ring.countdownProgress = progress
    }

    override fun setAmbientAnimationsPaused(paused: Boolean) {
        row.cards.setMarqueePaused(row, paused)
    }

    private fun onCardPressed(card: TvNativeEpisodeCardView, pressed: Boolean) {
        if (!row.cards.longPressEnabled) return
        if (pressed) {
            if (pressedCard !== card) pressedCard?.let { it.scaleX = 1f; it.scaleY = 1f }
            pressedCard = card
        } else if (pressedCard !== card) {
            return
        }
        animatePress(if (pressed) style.pressScale else 1f)
    }

    private fun animatePress(target: Float) {
        // 先摘再取消: 取消会回调结束, 那一下不能把刚换上的按压卡清掉
        val running = pressAnimator
        pressAnimator = null
        running?.cancel()
        if (pressScale == target) {
            if (target == 1f) pressedCard = null
            return
        }
        pressAnimator = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = TV_NATIVE_EPISODE_PRESS_MILLIS
            interpolator = TV_NATIVE_EPISODE_PRESS_INTERPOLATOR
            addUpdateListener { applyPress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (pressAnimator !== animation) return
                    pressAnimator = null
                    // 弹回到位: 这张卡不再归按压管
                    if (target == 1f) pressedCard = null
                }
            })
            start()
        }
    }

    private fun applyPress(scale: Float) {
        pressScale = scale
        pressedCard?.let {
            it.scaleX = scale
            it.scaleY = scale
        }
        ring.scaleX = scale
        ring.scaleY = scale
    }

    override fun measureContent(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        // 行尾留一整行空白: 末集也能停进框里; 同时"完整露出"的只有框里那一张
        row.setEdges(startPx, (w - startPx - style.cardWidthPx).coerceAtLeast(0))
        row.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        ring.measure(
            MeasureSpec.makeMeasureSpec(style.ringWidthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(style.ringHeightPx, MeasureSpec.EXACTLY),
        )
    }

    override fun layoutContent(width: Int, height: Int) {
        row.layout(0, 0, width, height)
        val left = startPx - style.ringOutsetPx
        val top = bleedPx - style.ringOutsetPx
        ring.layout(left, top, left + ring.measuredWidth, top + ring.measuredHeight)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scrollTracker.stop()
        val running = pressAnimator
        pressAnimator = null
        running?.cancel()
        applyPress(1f)
        pressedCard = null
    }
}

private const val TV_NATIVE_EPISODE_VIEW_TYPE = 0x7a13

/** 剧照加载失败后最多重试几次、每次多等多久 (同 rememberAsyncImageRetryState). */
private const val TV_NATIVE_STILL_MAX_ATTEMPTS = 3
private const val TV_NATIVE_STILL_RETRY_BACKOFF_MILLIS = 500L

/**
 * 按住确认键时卡与框的缩放: 照 Compose 版 animateFloatAsState 的默认弹簧 (刚度 1500, 临界阻尼, 0.94 ↔ 1 这段约 70ms 落进可见阈值),
 * 用同一条临界阻尼曲线在 [TV_NATIVE_EPISODE_PRESS_MILLIS] 内走完.
 */
private const val TV_NATIVE_EPISODE_PRESS_MILLIS = 100L

private val TV_NATIVE_EPISODE_PRESS_INTERPOLATOR: Interpolator = run {
    // ω · 时长 = √1500 × 0.1s
    val a = 3.873f
    val end = 1f - (1f + a) * exp(-a)
    Interpolator { u -> if (u >= 1f) 1f else (1f - (1f + a * u) * exp(-a * u)) / end }
}
