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
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.util.FloatProperty
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.runtime.Immutable
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.TvNativeImages

/** 演职人员圆头像行 ([TvNativeMonogramRowView]) 的一格. */
@Immutable
sealed interface TvNativeMonogram {
    /**
     * 一个人: 圆里是照片 ([imageUrl] 为空 = 写 [initials]), 圆下姓名 + 一行副标题 (角色是声优, 制作人员是职位).
     * 副标题空了也占一行 (同一排格子一样高).
     */
    data class Person(val imageUrl: String?, val name: String, val subtitle: String, val initials: String) : TvNativeMonogram

    /** 行末的「查看全部」: 圆里一个箭头, 副标题写还有几个没露出来 (null = 数不出, 留空). */
    data class ViewAll(val remaining: Int?) : TvNativeMonogram
}

/**
 * 圆头像行的尺寸 (px) 与配色, 由 Compose 那侧按主题与界面缩放算好 (见 rememberTvNativeMonogramStyle), 原生视图只认像素.
 * 几何同 Compose 的圆头像格 (SubjectPeopleSections.kt 的 PersonMonogramCell 与占位 TvPeopleStripPlaceholder).
 */
@Immutable
data class TvNativeMonogramStyle(
    /** 圆的直径 = 格宽. */
    val sizePx: Int,
    val spacingPx: Int,
    /** 圆下缘到姓名 / 姓名到副标题. */
    val textGapPx: Int,
    val lineGapPx: Int,
    val name: TvNativeTextStyle,
    val subtitle: TvNativeTextStyle,
    /** 没聚焦时副标题的不透明度 (聚焦提亮成 1). */
    val subtitleIdleAlpha: Float,
    val initials: TvNativeTextStyle,
    val focusScale: Float,
    val ringWidthPx: Float,
    val ringColor: Int,
    /** 首字格与「查看全部」的圆底. */
    val filledColor: Int,
    /** 数据没到时的占位 (圆与两条字位), 照片没到时的圆底也是它: 占位换成真数据时圆不变色. */
    val placeholderColor: Int,
    val placeholderNameWidthPx: Int,
    val placeholderSubtitleWidthPx: Int,
    /** 占位字条在一行里上下各让出多少、圆角. */
    val placeholderBarInsetPx: Int,
    val placeholderBarCornerPx: Float,
    /** 「查看全部」圆里的箭头 (白色位图, 按 [arrowColor] 着色) 与它下面那行字. */
    val arrow: Bitmap,
    val arrowColor: Int,
    val viewAllLabel: String,
    val focusMillis: Long,
    val crossfade: Boolean,
) {
    /** 一格的高: 圆 + 间距 + 两行字. */
    val cellHeightPx: Int get() = sizePx + textGapPx + name.lineHeightPx + lineGapPx + subtitle.lineHeightPx

    /** 相邻两格左缘的距离. */
    val stepPx: Int get() = sizePx + spacingPx
}

/**
 * 演职人员的圆头像格 (Apple 管这个形态叫 Monogram): 圆 + 姓名 + 一行副标题, 居中竖排, 没有卡片底板. 聚焦时**只放大圆** (绕圆心,
 * 不占布局, 邻居不动) 并描一圈主题色细环, 两行字不放大、跟着圆的下缘下移; 副标题聚焦时提亮. 取值的出处见 Compose 版
 * (PersonMonogramCell / TvMonogramLockup).
 *
 * 聚焦效果由状态动画推一个聚焦程度 [focusProgress] (0..1), 只动 RenderNode 属性 (缩放、位移、环的透明度), 不重录内容.
 * 绑定 null = 数据没到的占位格: 灰圆 + 两条字位, 照样可聚焦 (数据在路上时焦点停在这里, 到了原地换成真数据).
 */
@SuppressLint("ViewConstructor")
class TvNativeMonogramCardView(context: Context, private val style: TvNativeMonogramStyle) : FrameLayout(context) {
    private val circle = TvNativeMonogramCircleView(context, style)
    private val ring = TvNativeMonogramRingView(context, style)
    private val label = TvNativeMonogramLabelView(context)
    private val name = TvNativeTextView(context)
    private val subtitle = TvNativeTextView(context)
    private val confirmKey = TvNativeConfirmKey()
    private val nameBar = placeholderBar(style.placeholderNameWidthPx)
    private val subtitleBar = placeholderBar(style.placeholderSubtitleWidthPx)
    private var placeholder = false

    /** 确定键长按的回调 (放大看照片); null = 没有长按, 按住也只算点击. */
    var longPressHandler: (() -> Unit)? = null

    /** 聚焦程度 (0..1), 由状态动画推 (拿到 / 失去焦点时动画到 1 / 0). */
    var focusProgress: Float = 0f
        set(value) {
            field = value
            applyFocus()
        }

    /**
     * 没有焦点也画成聚焦态 (同海报卡的 [TvNativeCardView.focusLookHeld]): 返回本页 (页面重建) 时焦点要回的那格, 排出来的第一帧就是放大的,
     * 焦点到了画面不变. 改用 [setFocusLookHeld].
     */
    var focusLookHeld: Boolean = false
        private set
    private var releaseAnimator: Animator? = null

    /** 按住 / 放开聚焦态 ([focusLookHeld]), 同 [TvNativeCardView.setFocusLookHeld]. */
    fun setFocusLookHeld(held: Boolean, animate: Boolean) {
        if (focusLookHeld == held) return
        focusLookHeld = held
        releaseAnimator?.cancel()
        releaseAnimator = null
        if (!held) {
            // 放开常发生在拿到焦点的回调里 (适配器的焦点监听): View 在 onFocusChanged 之后才按新焦点 refreshDrawableState, 这时
            // 状态动画还停在失焦态, 跳到的终点是缩回的样子. 先按真实焦点刷新一次, 跳到的就是聚焦态
            refreshDrawableState()
            stateListAnimator?.jumpToCurrentState()
            if (animate && !isFocused) {
                releaseAnimator = ObjectAnimator.ofFloat(this, TV_NATIVE_MONOGRAM_FOCUS_PROGRESS, 1f, 0f).apply {
                    duration = style.focusMillis
                    interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
                    start()
                }
            }
        }
        applyFocus()
        applySubtitleAlpha()
    }

    init {
        layoutParams = ViewGroup.LayoutParams(style.sizePx, style.cellHeightPx)
        // 触摸模式下也可聚焦 (同海报卡)
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        clipChildren = false
        clipToPadding = false
        addView(circle, LayoutParams(style.sizePx, style.sizePx))
        addView(ring, LayoutParams(style.sizePx, style.sizePx))
        label.orientation = LinearLayout.VERTICAL
        label.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, style.name.lineHeightPx))
        label.addView(
            subtitle,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, style.subtitle.lineHeightPx).apply {
                topMargin = style.lineGapPx
            },
        )
        addView(
            label,
            LayoutParams(style.sizePx, style.cellHeightPx - style.sizePx - style.textGapPx).apply {
                topMargin = style.sizePx + style.textGapPx
            },
        )
        style.name.applyTo(name)
        style.subtitle.applyTo(subtitle)
        for (text in arrayOf(name, subtitle)) {
            text.maxLines = 1
            text.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusAnimator(1f))
            addState(intArrayOf(), focusAnimator(0f))
        }
        applyFocus()
        applySubtitleAlpha()
    }

    /** 绑定一格; null = 占位. */
    fun bind(item: TvNativeMonogram?, sketch: Sketch) {
        placeholder = item == null
        when (item) {
            null -> {
                contentDescription = null
                name.text = ""
                subtitle.text = ""
                circle.showPlaceholder()
            }

            is TvNativeMonogram.Person -> {
                contentDescription = item.name
                name.text = item.name
                subtitle.text = item.subtitle
                circle.showPerson(item, sketch)
            }

            is TvNativeMonogram.ViewAll -> {
                contentDescription = style.viewAllLabel
                name.text = style.viewAllLabel
                subtitle.text = item.remaining?.takeIf { it > 0 }?.let { "+$it" }.orEmpty()
                circle.showArrow()
            }
        }
        // 占位的字条画成背景. 设背景时 View 会把 InsetDrawable 的内缩当成自己的内边距, 换回 null 也不清 —— 字区缩成字条那么窄、
        // 往下挪, 数据到了之后姓名被截断、下半截被裁掉. 每次设完都把内边距清零 (字条照样按自己的内缩画)
        name.background = if (placeholder) nameBar else null
        subtitle.background = if (placeholder) subtitleBar else null
        name.setPadding(0, 0, 0, 0)
        subtitle.setPadding(0, 0, 0, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, longPressHandler) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        confirmKey.onKey(this, event, null) || super.onKeyUp(keyCode, event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // 放开时的缩回动画还在走又拿到焦点: 交给状态动画从当前值接着走
        releaseAnimator?.cancel()
        releaseAnimator = null
        applySubtitleAlpha()
    }

    /** 副标题聚焦时当场提亮 (同 Compose 版: 按焦点翻, 不跟放大的进度走). */
    private fun applySubtitleAlpha() {
        subtitle.alpha = if (isFocused || focusLookHeld) 1f else style.subtitleIdleAlpha
    }

    private fun applyFocus() {
        val p = if (focusLookHeld) 1f else focusProgress
        val s = 1f + (style.focusScale - 1f) * p
        circle.scaleX = s
        circle.scaleY = s
        ring.scaleX = s
        ring.scaleY = s
        ring.alpha = p
        // 圆绕圆心放大, 下缘位移 = 半径 × (倍数 − 1); 字号不变
        label.translationY = style.sizePx / 2f * (s - 1f)
    }

    private fun placeholderBar(widthPx: Int): InsetDrawable {
        val bar = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = style.placeholderBarCornerPx
            setColor(style.placeholderColor)
        }
        val side = ((style.sizePx - widthPx) / 2).coerceAtLeast(0)
        return InsetDrawable(bar, side, style.placeholderBarInsetPx, side, style.placeholderBarInsetPx)
    }

    private fun focusAnimator(target: Float) = AnimatorSet().apply {
        play(ObjectAnimator.ofFloat<TvNativeMonogramCardView>(null, TV_NATIVE_MONOGRAM_FOCUS_PROGRESS, target))
        duration = style.focusMillis
        interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
    }
}

/**
 * 圆: 按 outline 裁成正圆, 底色垫在下面 (照片没到时露出, 与占位同色). 照片按顶部对齐裁成正方形再解 (立绘顶部是脸, 同 Compose 版
 * AvatarImage 的 TopCenter; 裁剪在解码时做, 见 TvNativeImages.loadAvatar); 没照片写首字, 「查看全部」画箭头.
 * 尺寸由布局参数定死, 换图不往上冒泡重排 (同海报卡的封面).
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
private class TvNativeMonogramCircleView(context: Context, private val style: TvNativeMonogramStyle) : ImageView(context) {
    private var initials: String? = null
    private var arrow = false
    // 不在 Paint 的 apply 里读 style: Paint 自己也有 style 属性, 会被它遮住
    private val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textSize = style.initials.sizePx
        it.typeface = tvNativeTypeface(style.initials.weight)
        it.color = style.initials.color
        it.textAlign = Paint.Align.CENTER
        it.letterSpacing = style.initials.letterSpacingEm
    }
    private val arrowPaint = Paint(Paint.FILTER_BITMAP_FLAG).also {
        it.colorFilter = PorterDuffColorFilter(style.arrowColor, PorterDuff.Mode.SRC_IN)
    }

    init {
        scaleType = ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }

    fun showPlaceholder() {
        TvNativeImages.clear(this)
        initials = null
        arrow = false
        setBackgroundColor(style.placeholderColor)
        invalidate()
    }

    fun showPerson(person: TvNativeMonogram.Person, sketch: Sketch) {
        arrow = false
        val url = person.imageUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            // 没有照片就写姓名首字, 不显示占位图 (Bangumi 对没照片的人给的是一张 "TEXT ONLY" 图, 放成大圆很难看)
            TvNativeImages.clear(this)
            initials = person.initials
            setBackgroundColor(style.filledColor)
        } else {
            initials = null
            setBackgroundColor(style.placeholderColor)
            TvNativeImages.loadAvatar(sketch, this, url, style.sizePx, style.crossfade)
        }
        invalidate()
    }

    fun showArrow() {
        TvNativeImages.clear(this)
        initials = null
        arrow = true
        setBackgroundColor(style.filledColor)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text = initials
        if (!text.isNullOrEmpty()) {
            val fm = initialsPaint.fontMetrics
            canvas.drawText(text, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, initialsPaint)
        }
        if (arrow) {
            val b = style.arrow
            canvas.drawBitmap(b, (width - b.width) / 2f, (height - b.height) / 2f, arrowPaint)
        }
    }

    override fun requestLayout() {
        if (!isLaidOut) super.requestLayout()
    }
}

/** 聚焦时圆上那圈细环: 画在圆的边缘以内, 透明度由卡按聚焦程度设 (图层属性, 不重录). */
@SuppressLint("ViewConstructor")
private class TvNativeMonogramRingView(context: Context, private val style: TvNativeMonogramStyle) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE
        it.strokeWidth = style.ringWidthPx
        it.color = style.ringColor
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val half = style.ringWidthPx / 2f
        oval.set(half, half, width - half, height - half)
        canvas.drawOval(oval, paint)
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 两行字竖排; 透明度逐绘制指令乘 (两行不重叠, 不走离屏层). */
private class TvNativeMonogramLabelView(context: Context) : LinearLayout(context) {
    override fun hasOverlappingRendering(): Boolean = false
}

/**
 * 圆头像行的适配器. [submit] null = 数据没到, 放 [placeholderCount] 个占位格 (照样可聚焦); 列表里的 null 是分页还没到的那一格, 也画成占位.
 * 稳定 id 按下标: 占位换成真数据时持焦的那格原样留着、原地重绑 —— 焦点不会因为换数据被系统改派到页面别处.
 */
@SuppressLint("NotifyDataSetChanged")
class TvNativeMonogramAdapter(
    private val style: TvNativeMonogramStyle,
    private val sketch: Sketch,
) : RecyclerView.Adapter<TvNativeMonogramAdapter.Holder>() {
    /** 事件; 占位格不派发点击与长按. 长按的 anchor 是格在窗口里的框. */
    var listener: TvNativeCardListener? = null

    /** 第几格被绑定 (分页的访问提示). 占位不报. */
    var onBind: ((index: Int) -> Unit)? = null

    /** 人物格按住确定键算长按 (派发 [TvNativeCardListener.onLongPress]); false = 按住也只算点击. 在绑定之前定. */
    var longPressEnabled: Boolean = true

    private var items: List<TvNativeMonogram?>? = null

    /** 数据没到时放几个占位格. */
    var placeholderCount: Int = 0
        set(value) {
            if (field == value) return
            field = value
            if (items == null) notifyDataSetChanged()
        }

    /**
     * 没有焦点也画成聚焦态的那格 (见 [TvNativeMonogramCardView.setFocusLookHeld]), -1 = 没有; 改用 [setFocusLookHeld]. 这一行任何一格拿到真焦点
     * 就放开 (落在它自己身上画面不变, 落在别处它照失焦缩回).
     */
    var heldFocusIndex: Int = -1
        private set

    init {
        setHasStableIds(true)
    }

    /** 让第 [index] 格 (-1 = 没有) 没有焦点也画成聚焦态, 屏上的当场改 (放开的那格照失焦缩回), 之后绑定 / 重新上屏的补上. */
    fun setFocusLookHeld(recycler: RecyclerView, index: Int) {
        if (heldFocusIndex == index) return
        heldFocusIndex = index
        for (i in 0 until recycler.childCount) {
            val cell = recycler.getChildAt(i) as? TvNativeMonogramCardView ?: continue
            val position = recycler.getChildAdapterPosition(cell)
            if (position != RecyclerView.NO_POSITION) cell.setFocusLookHeld(position == index, animate = true)
        }
    }

    /** 数据还没到 (放着占位). */
    val loading: Boolean get() = items == null

    fun itemAt(index: Int): TvNativeMonogram? = items?.getOrNull(index)

    /**
     * 换数据 (null = 占位). 内容没变就不动. 占位与数据互换整排刷新 (稳定 id 按下标, 持焦的那格原地重绑); 数据之间只通知变了的格
     * (分页追加、个别格晚到), 没变的格不重绑, 头像不再走一遍加载入口.
     */
    fun submit(items: List<TvNativeMonogram?>?) {
        val old = this.items
        if (items == old) return
        this.items = items
        if (old == null || items == null) {
            notifyDataSetChanged()
            return
        }
        val common = minOf(old.size, items.size)
        var i = 0
        while (i < common) {
            if (old[i] == items[i]) {
                i++
                continue
            }
            val start = i
            while (i < common && old[i] != items[i]) i++
            notifyItemRangeChanged(start, i - start)
        }
        if (items.size > common) notifyItemRangeInserted(common, items.size - common)
        if (old.size > common) notifyItemRangeRemoved(common, old.size - common)
    }

    override fun getItemCount(): Int = items?.size ?: placeholderCount

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getItemViewType(position: Int): Int = TV_NATIVE_MONOGRAM_VIEW_TYPE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(TvNativeMonogramCardView(parent.context, style))
        holder.card.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            val index = holder.bindingAdapterPosition
            if (hasFocus && index != RecyclerView.NO_POSITION) {
                // 真焦点到了: 按住的聚焦态放开
                (holder.card.parent as? RecyclerView)?.let { setFocusLookHeld(it, -1) }
                listener?.onFocused(index)
            }
        }
        holder.card.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION && itemAt(index) != null) listener?.onClick(index)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items?.getOrNull(position)
        holder.card.bind(item, sketch)
        holder.card.longPressHandler = if (item is TvNativeMonogram.Person && longPressEnabled) {
            {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) listener?.onLongPress(index, holder.card.rectInWindow())
            }
        } else {
            null
        }
        holder.card.setFocusLookHeld(position == heldFocusIndex, animate = false)
        if (item != null) onBind?.invoke(position)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        // 回收缓存里原样拿回同一位置的格不重绑: 按住的聚焦态在重新上屏时补上
        holder.card.setFocusLookHeld(heldFocusIndex >= 0 && holder.bindingAdapterPosition == heldFocusIndex, animate = false)
    }

    class Holder(val card: TvNativeMonogramCardView) : RecyclerView.ViewHolder(card)
}

/**
 * 演职人员的圆头像横滑行 (详情页角色 / 制作人员), 行为见 [TvNativeStripView]: 按需挪, 行首按左与行尾按右都吞掉 (左右两头都没有目标),
 * 上下键不管. 占位时 (数据没到) 左右键也吞掉: 焦点停在第一格等数据.
 *
 * 长按连发按 [repeatMillis] (Compose 版同一个上限, 调用方给). 行外左右各多排三格: 圆头像格步距小、连发快, 平滑滚动落后焦点两格多
 * (临界阻尼 spring 追匀速目标落后 2v / ω: 1080p 上每秒 20 格 × 300px, ω = √260), 这段都得排着. 行里第一次有焦点时才多排:
 * 详情页建人物页那一帧只排屏上那几格 (建卡、绑定都在那一帧里), 连发总在拿到焦点之后.
 */
@SuppressLint("ViewConstructor")
class TvNativeMonogramRowView(
    context: Context,
    val style: TvNativeMonogramStyle,
    sketch: Sketch,
    startPx: Int,
    endPx: Int,
    topPx: Int,
    bottomPx: Int,
) : TvNativeStripView(
    context,
    stepPx = style.stepPx,
    spacingPx = style.spacingPx,
    extraLayoutPx = 0,
    prefetchItems = 0,
    startPx = startPx,
    endPx = endPx,
    topPx = topPx,
    bottomPx = bottomPx,
) {
    val cells = TvNativeMonogramAdapter(style, sketch)
    private var aheadLaidOut = false

    init {
        startLeftExits = false
        adapter = cells
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (!aheadLaidOut) {
            aheadLaidOut = true
            setExtraLayoutSpace(style.stepPx * 3)
        }
    }

    override fun canMoveTo(index: Int): Boolean = !cells.loading

    /**
     * 换数据 (null = 占位, 见 [TvNativeMonogramAdapter.submit]) 并定行首与选中 (参数同 [TvNativeRowView.bind]).
     */
    fun bind(items: List<TvNativeMonogram?>?, leftIndex: Int, columns: Int, focusIndex: Int = -1) {
        cells.submit(items)
        placeAfterSubmit(cells.itemCount, leftIndex, columns, focusIndex)
    }
}

/** 格在窗口里的框 (未放大时的位置). */
private fun View.rectInWindow(): Rect {
    val xy = IntArray(2)
    getLocationInWindow(xy)
    return Rect(xy[0], xy[1], xy[0] + width, xy[1] + height)
}

private const val TV_NATIVE_MONOGRAM_VIEW_TYPE = 0x7a12

/** 圆头像格的聚焦程度属性 (Property 版, 省掉 ObjectAnimator 按名字反射). */
private val TV_NATIVE_MONOGRAM_FOCUS_PROGRESS = object : FloatProperty<TvNativeMonogramCardView>("focusProgress") {
    override fun setValue(view: TvNativeMonogramCardView, value: Float) {
        view.focusProgress = value
    }

    override fun get(view: TvNativeMonogramCardView): Float = view.focusProgress
}
