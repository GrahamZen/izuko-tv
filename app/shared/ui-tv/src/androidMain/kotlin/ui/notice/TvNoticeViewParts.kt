/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.lan.QrCodeMatrix
import me.him188.ani.app.ui.foundation.lan.encodeQrCode
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeSpringScroll
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextView
import me.him188.ani.app.ui.foundation.tv.nativeview.tvNativeWithAlpha
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// 通知弹窗 (原生, 见 TvNoticeView) 的各个小视图: 签、刷新、列表行、按钮、右栏与正文块. 尺寸与配色都从 TvNoticeStyle 取, 自己不记数值.

private val UNSPECIFIED: Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

internal fun tvNoticeAtMost(px: Int): Int = View.MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), View.MeasureSpec.AT_MOST)

internal fun tvNoticeExactly(px: Int): Int = View.MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), View.MeasureSpec.EXACTLY)

internal fun View.tvNoticeLayoutAt(x: Int, y: Int) = layout(x, y, x + measuredWidth, y + measuredHeight)

/** 新建一个字视图: 按内容定高 (换字时重新测量, 不按上一段字的高), 套上 [style]. */
internal fun tvNoticeText(context: Context, style: TvNativeTextStyle, maxLines: Int = 1): TvNativeTextView =
    TvNativeTextView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        style.applyTo(this)
    }

/**
 * 按内容宽量, 最宽 [room]: 先不限宽量出要多宽, 放得下就多给一点按定宽排 —— 按 AT_MOST 量出的宽度有时比排版要的少零点几像素
 * (回落字体的字), 最后一个字被挤到下一行; 放不下的按 [room] 折行或截断. 同设置页行尾的字.
 */
internal fun TvNativeTextView.measureWithin(room: Int) {
    measure(UNSPECIFIED, UNSPECIFIED)
    if (measuredWidth <= room) {
        measure(tvNoticeExactly(min(measuredWidth + TEXT_SLACK_PX, room)), UNSPECIFIED)
    } else {
        measure(tvNoticeAtMost(room), UNSPECIFIED)
    }
}

/** [measureWithin] 多给的宽 (px). */
private const val TEXT_SLACK_PX = 2

/** 字变了才换 (换字会让它重新排版). 带样式的字 (粗体) 每次都换. */
internal fun TvNativeTextView.setTextIfChanged(value: CharSequence) {
    if (value !is Spanned && text.toString() == value.toString()) return
    text = value
}

/**
 * 字体 (中文的回落字体) 比固定行高高出的那一截 (px): Compose 的 Text 在首行上方与末行下方各补这么多 (见 TvNativeTextView.fontOverflowTopPx),
 * 大字号的中文标题要照样留出来, 与 Compose 版一样高. 量过之后才有值.
 */
internal val TvNativeTextView.overflowPad: Int get() = fontOverflowTopPx

/** Compose 的带样式字换成原生的: 粗体 (字重 ≥ 600) 与颜色, 其余照纯文字. */
internal fun AnnotatedString.toTvNoticeText(): CharSequence {
    if (spanStyles.isEmpty()) return text
    val spannable = SpannableString(text)
    for (range in spanStyles) {
        val weight = range.item.fontWeight?.weight
        if (weight != null && weight >= 600) {
            spannable.setSpan(StyleSpan(Typeface.BOLD), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val color = range.item.color
        if (color.isSpecified) {
            spannable.setSpan(ForegroundColorSpan(color.toArgb()), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return spannable
}

/** 圆角实底 (与按下时淡淡一层): 行、按钮、卡片共用. */
private fun Canvas.drawTvNoticePlate(rect: RectF, width: Int, height: Int, corner: Float, color: Int, paint: Paint) {
    if (android.graphics.Color.alpha(color) == 0) return
    rect.set(0f, 0f, width.toFloat(), height.toFloat())
    paint.color = color
    drawRoundRect(rect, corner, corner, paint)
}

/** 位图图标按 [color] 着色画在 ([left], [top]) 起 [size] 见方的格子里. */
private fun Canvas.drawTvNoticeIcon(bitmap: Bitmap?, left: Float, top: Float, size: Int, color: Int, paint: Paint, dst: RectF) {
    if (bitmap == null || size <= 0) return
    paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    dst.set(left, top, left + size, top + size)
    drawBitmap(bitmap, null, dst, paint)
}

// ---- 顶栏 ----

/**
 * 顶上的一个签: 字 (与红点) 与下面一条短横线. 没选中的字小、淡; 选中的字大、加粗, 下面一条主色横线. 只显示选中的是哪个, 焦点不落上来
 * (电视上在列表里左右键换签); 触屏上点它换签.
 */
@SuppressLint("ViewConstructor")
internal class TvNoticeTabView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context) {
    var tabId: String = ""
        private set
    private val label = tvNoticeText(context, style.tab)
    private var fresh = false
    private var selectedTab = false
    private var emphasized: Boolean? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        isFocusable = false
        isClickable = true
        addView(label)
    }

    fun bind(id: String, text: String, fresh: Boolean, selected: Boolean, style: TvNoticeStyle) {
        if (this.style != style) {
            this.style = style
            emphasized = null
        }
        tabId = id
        label.setTextIfChanged(text)
        this.fresh = fresh
        selectedTab = selected
        if (emphasized != selected) {
            emphasized = selected
            (if (selected) style.tabSelected else style.tab).applyTo(label)
            requestLayout()
        }
        label.setTextColor(if (selected) style.onSurface else style.onSurfaceVariant)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        label.measure(UNSPECIFIED, UNSPECIFIED)
        val width = max(label.measuredWidth, s.tabUnderlineWidthPx) + s.tabPaddingPx * 2
        val height = s.tabPaddingPx * 2 + label.measuredHeight + label.overflowPad * 2 + s.tabUnderlineGapPx + s.tabUnderlineHeightPx
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        label.tvNoticeLayoutAt((r - l - label.measuredWidth) / 2, style.tabPaddingPx + label.overflowPad)
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        if (selectedTab) {
            val top = (label.bottom + label.overflowPad + s.tabUnderlineGapPx).toFloat()
            val left = (width - s.tabUnderlineWidthPx) / 2f
            rect.set(left, top, left + s.tabUnderlineWidthPx, top + s.tabUnderlineHeightPx)
            paint.color = s.primary
            canvas.drawRoundRect(rect, s.tabUnderlineCornerPx, s.tabUnderlineCornerPx, paint)
        }
        if (fresh && !selectedTab) {
            // 字的右上角往右挪出去一点 (同 Compose 版的 offset)
            val radius = s.tabDotPx / 2f
            paint.color = s.error
            canvas.drawCircle(label.right - s.tabDotPx + s.tabDotOffsetPx + radius, label.top + radius, radius, paint)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/**
 * 顶栏右边的刷新: 图标 + 字, 不铺底, 聚焦时图标与字一下变主色 (触屏上不画), 冷却中变淡, 正在刷新时图标转圈.
 */
@SuppressLint("ViewConstructor")
internal class TvNoticeRefreshView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context) {
    private val label = tvNoticeText(context, style.refreshLabel)
    private var icon: Bitmap? = null
    private var ui: TvNoticeRefreshUi? = null
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconRect = RectF()
    private var spin = 0f
    private var spinner: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        addView(label)
    }

    fun bind(ui: TvNoticeRefreshUi, icon: Bitmap?, style: TvNoticeStyle) {
        if (this.style != style) {
            this.style = style
            style.refreshLabel.applyTo(label)
        }
        this.ui = ui
        this.icon = icon
        label.setTextIfChanged(ui.label)
        applyLook()
        updateSpinner()
    }

    /** 窗口被叠在上面的小窗拿走了焦点: 不画聚焦的样子 (见 TvNoticeView.covered). */
    var covered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyLook()
        }

    private fun applyLook() {
        val s = style
        val cooling = ui?.cooling == true
        val base = if (isFocused && !s.touch && !covered) s.primary else s.onSurfaceVariant
        val color = if (cooling) tvNativeWithAlpha(base, s.refreshCoolingAlpha) else base
        label.setTextColor(color)
        iconPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        invalidate()
    }

    private fun updateSpinner() {
        val busy = ui?.busy == true && isAttachedToWindow
        if (busy && spinner == null) {
            spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = style.refreshSpinMillis
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    spin = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!busy && spinner != null) {
            spinner?.cancel()
            spinner = null
            spin = 0f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpinner()
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        spinner = null
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        applyLook()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        label.measure(UNSPECIFIED, UNSPECIFIED)
        val width = s.refreshPaddingPx * 2 + s.refreshIconPx + s.refreshIconGapPx + label.measuredWidth
        val height = s.refreshPaddingPx * 2 + max(s.refreshIconPx, label.measuredHeight)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        label.tvNoticeLayoutAt(s.refreshPaddingPx + s.refreshIconPx + s.refreshIconGapPx, (b - t - label.measuredHeight) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = icon ?: return
        val s = style
        val left = s.refreshPaddingPx.toFloat()
        val top = (height - s.refreshIconPx) / 2f
        val save = canvas.save()
        canvas.rotate(spin, left + s.refreshIconPx / 2f, top + s.refreshIconPx / 2f)
        iconRect.set(left, top, left + s.refreshIconPx, top + s.refreshIconPx)
        canvas.drawBitmap(bitmap, null, iconRect, iconPaint)
        canvas.restoreToCount(save)
    }
}

// ---- 列表 ----

/** 列表里的一项: 段的小标题或一行. */
internal sealed class TvNoticeListEntry(val id: String)

internal class TvNoticeSectionEntry(val text: String, val first: Boolean) : TvNoticeListEntry("section:$text")

/** 一行; [index] 是它在这个签的行里的位置 (交给操作, 见 TvNoticeViewListener.onAction). */
internal class TvNoticeRowEntry(val row: TvNoticeViewRow, val index: Int) : TvNoticeListEntry(row.item.key)

/** [rows] 排成列表项: 与上一行不同的段在行前面写一行小标题. */
internal fun tvNoticeListEntries(rows: List<TvNoticeViewRow>): List<TvNoticeListEntry> {
    val entries = ArrayList<TvNoticeListEntry>(rows.size + 2)
    rows.forEachIndexed { index, row ->
        val section = row.item.section
        if (section != null && (index == 0 || rows[index - 1].item.section != section)) {
            entries += TvNoticeSectionEntry(section, first = entries.isEmpty())
        }
        entries += TvNoticeRowEntry(row, index)
    }
    return entries
}

/**
 * 列表的一行: 类别图标 (没看过的右上角一个红点)、标题与时间、下面一行摘要. 自己画圆角底与图标, 不嵌套布局.
 * 底色: 聚焦是实底 (触屏上不画), 右栏说的是它 ([selectedLook]) 是 secondaryContainer, 其余是面板里的淡底.
 */
@SuppressLint("ViewConstructor")
internal class TvNoticeRowView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context) {
    var entry: TvNoticeRowEntry? = null
        private set
    private val title = tvNoticeText(context, style.rowTitle)
    private val time = tvNoticeText(context, style.rowTime)
    private val summary = tvNoticeText(context, style.rowSummary)
    private var icon: Bitmap? = null
    private var fresh = false
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val iconRect = RectF()
    private var contentColor = 0
    private var bgColor = 0

    var selectedLook: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyColors()
        }

    /** 窗口被叠在上面的小窗拿走了焦点: 聚焦的这一行画成「右栏说的是它」的浅底, 不画聚焦的实底. */
    var covered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyColors()
        }

    init {
        setWillNotDraw(false)
        clipChildren = false
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        addView(title)
        addView(time)
        addView(summary)
    }

    fun bind(entry: TvNoticeRowEntry, icon: Bitmap?, style: TvNoticeStyle) {
        if (this.style != style) {
            this.style = style
            style.rowTitle.applyTo(title)
            style.rowTime.applyTo(time)
            style.rowSummary.applyTo(summary)
        }
        this.entry = entry
        val item = entry.row.item
        this.icon = icon
        fresh = item.fresh
        contentDescription = item.iconLabel
        title.setTextIfChanged(item.title)
        val timeText = entry.row.time.orEmpty()
        time.setTextIfChanged(timeText)
        time.visibility = if (timeText.isEmpty()) GONE else VISIBLE
        summary.setTextIfChanged(item.summary.orEmpty())
        summary.visibility = if (item.summary.isNullOrEmpty()) GONE else VISIBLE
        applyColors()
        requestLayout()
    }

    private fun applyColors() {
        val s = style
        when {
            isFocused && !s.touch && !covered -> {
                bgColor = s.focusBg
                contentColor = s.focusFg
            }

            selectedLook || (isFocused && !s.touch) -> {
                bgColor = s.selectedBg
                contentColor = s.selectedFg
            }

            else -> {
                bgColor = s.itemBg
                contentColor = s.onSurface
            }
        }
        val secondary = tvNativeWithAlpha(contentColor, s.rowSecondaryAlpha)
        title.setTextColor(contentColor)
        time.setTextColor(secondary)
        summary.setTextColor(secondary)
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // 字常在列表布局途中换 (差异更新时重新绑定): 每次都重新量, 不按旧字的宽度排
        title.forceLayout()
        time.forceLayout()
        summary.forceLayout()
        val textWidth = (width - s.rowPaddingHPx * 2 - s.rowIconPx - s.rowIconGapPx).coerceAtLeast(0)
        var timeWidth = 0
        if (time.visibility != GONE) {
            time.measure(UNSPECIFIED, UNSPECIFIED)
            timeWidth = time.measuredWidth + s.rowTimeGapPx
        }
        title.measureWithin(textWidth - timeWidth)
        if (summary.visibility != GONE) summary.measureWithin(textWidth)
        val height = max(textBlockHeight(), s.rowIconPx) + s.rowPaddingVPx * 2
        setMeasuredDimension(width, height)
    }

    private fun firstLineHeight() = max(title.measuredHeight, if (time.visibility != GONE) time.measuredHeight else 0)

    private fun textBlockHeight(): Int {
        val summaryHeight = if (summary.visibility != GONE) style.rowLineGapPx + summary.measuredHeight else 0
        return firstLineHeight() + summaryHeight
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        val w = r - l
        val x = s.rowPaddingHPx + s.rowIconPx + s.rowIconGapPx
        val top = (b - t - textBlockHeight()) / 2
        val line = firstLineHeight()
        title.tvNoticeLayoutAt(x, top + (line - title.measuredHeight) / 2)
        if (time.visibility != GONE) time.tvNoticeLayoutAt(w - s.rowPaddingHPx - time.measuredWidth, top + (line - time.measuredHeight) / 2)
        if (summary.visibility != GONE) summary.tvNoticeLayoutAt(x, top + line + s.rowLineGapPx)
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        canvas.drawTvNoticePlate(rect, width, height, s.rowCornerPx, bgColor, bgPaint)
        if (isPressed && s.touch) canvas.drawTvNoticePlate(rect, width, height, s.rowCornerPx, s.pressedOverlay, bgPaint)
        val iconTop = (height - s.rowIconPx) / 2f
        canvas.drawTvNoticeIcon(icon, s.rowPaddingHPx.toFloat(), iconTop, s.rowIconPx, contentColor, iconPaint, iconRect)
        if (fresh) {
            val radius = s.dotPx / 2f
            bgPaint.color = s.error
            canvas.drawCircle(
                s.rowPaddingHPx + s.rowIconPx - s.dotPx + s.dotOffsetPx + radius,
                iconTop - s.dotOffsetPx + radius,
                radius,
                bgPaint,
            )
        }
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/** 段的小标题 (「新通知」「之前的」「处理中」): 不能聚焦; 不是第一项时上面多留一截. */
@SuppressLint("ViewConstructor")
internal class TvNoticeSectionView(context: Context, private var style: TvNoticeStyle) : TvNativeTextView(context) {
    init {
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        maxLines = 1
        isFocusable = false
        style.section.applyTo(this)
    }

    fun bind(entry: TvNoticeSectionEntry, style: TvNoticeStyle) {
        if (this.style != style) {
            this.style = style
            style.section.applyTo(this)
        }
        setTextIfChanged(entry.text)
        setPadding(style.sectionStartPx, if (entry.first) 0 else style.sectionTopPx, 0, 0)
    }
}

/** 一个签的列表适配器: 按行的键做差异更新 (后台线程), 键换成稳定的长整数 id; 持焦的行不因内容变化丢焦点. */
internal class TvNoticeRowAdapter(var style: TvNoticeStyle) : ListAdapter<TvNoticeListEntry, RecyclerView.ViewHolder>(Diff) {
    var icons: Map<ImageVector, Bitmap> = emptyMap()
    var onRowFocused: ((TvNoticeRowEntry) -> Unit)? = null
    var onRowClicked: ((TvNoticeRowEntry) -> Unit)? = null

    /** 画成「右栏说的是它」的那一行. */
    var selectedKey: String? = null
        set(value) {
            if (field == value) return
            field = value
            syncSelected()
        }

    /** 窗口被叠在上面的小窗拿走了焦点 (见 [TvNoticeRowView.covered]). */
    var covered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncSelected()
        }

    private var recycler: RecyclerView? = null
    private val stableIds = HashMap<String, Long>()
    private var nextStableId = 1L

    init {
        setHasStableIds(true)
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
    }

    override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).id) { nextStableId++ }

    override fun getItemViewType(position: Int): Int = if (getItem(position) is TvNoticeSectionEntry) TYPE_SECTION else TYPE_ROW

    /** 列表里键为 [key] 的那一行的位置; 没有为 -1. */
    fun positionOf(key: String?): Int = if (key == null) -1 else currentList.indexOfFirst { it is TvNoticeRowEntry && it.id == key }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_SECTION) return Holder(TvNoticeSectionView(parent.context, style))
        val view = TvNoticeRowView(parent.context, style)
        view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        val holder = Holder(view)
        // 回调里现取 (视图会被复用到别的位置)
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) view.entry?.let { onRowFocused?.invoke(it) }
        }
        view.setOnClickListener { view.entry?.let { onRowClicked?.invoke(it) } }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = getItem(position)) {
            is TvNoticeSectionEntry -> (holder.itemView as TvNoticeSectionView).bind(entry, style)
            is TvNoticeRowEntry -> {
                val view = holder.itemView as TvNoticeRowView
                view.bind(entry, icons[entry.row.item.icon], style)
                view.selectedLook = entry.id == selectedKey
                view.covered = covered
            }
        }
    }

    /** 从缓存里拿回来的行不重新绑定: 挂回列表时按此刻的标记对一次. */
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView as? TvNoticeRowView ?: return
        view.selectedLook = view.entry?.id == selectedKey
        view.covered = covered
    }

    private fun syncSelected() {
        val recycler = recycler ?: return
        for (i in 0 until recycler.childCount) {
            val view = recycler.getChildAt(i) as? TvNoticeRowView ?: continue
            view.selectedLook = view.entry?.id == selectedKey
            view.covered = covered
        }
    }

    private object Diff : DiffUtil.ItemCallback<TvNoticeListEntry>() {
        override fun areItemsTheSame(oldItem: TvNoticeListEntry, newItem: TvNoticeListEntry) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TvNoticeListEntry, newItem: TvNoticeListEntry): Boolean = when {
            oldItem is TvNoticeSectionEntry && newItem is TvNoticeSectionEntry -> oldItem.first == newItem.first
            oldItem is TvNoticeRowEntry && newItem is TvNoticeRowEntry -> {
                val a = oldItem.row
                val b = newItem.row
                a.time == b.time && a.item.title == b.item.title && a.item.summary == b.item.summary &&
                    a.item.fresh == b.item.fresh && a.item.icon === b.item.icon && a.item.iconLabel == b.item.iconLabel
            }

            else -> false
        }
    }

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_ROW = 1
    }
}

/**
 * 一个签的列表: 聚焦行停在离顶 1/3 处, 两头贴边 (短的不滚). 比两栏的高长时被截住的那一头渐隐 —— leanback 的竖列表没有渐隐,
 * 借 View 自带的渐隐边, 强度按头尾那一行露没露全自己算 (同选源面板).
 */
internal class TvNoticeGrid(context: Context) : VerticalGridView(context) {
    init {
        clipToPadding = false
        isFocusable = false
        itemAnimator = null
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = 33f
        itemAlignmentOffsetPercent = 50f
        setSmoothScrollByBehavior(TvNativeSpringScroll())
        isVerticalFadingEdgeEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun getTopFadingEdgeStrength(): Float {
        if (childCount == 0) return 0f
        val first = findViewHolderForAdapterPosition(0)?.itemView ?: return 1f
        return if (first.top + first.translationY < paddingTop - 1) 1f else 0f
    }

    override fun getBottomFadingEdgeStrength(): Float {
        val count = adapter?.itemCount ?: 0
        if (count == 0 || childCount == 0) return 0f
        val last = findViewHolderForAdapterPosition(count - 1)?.itemView ?: return 1f
        return if (last.bottom + last.translationY > height - paddingBottom + 1) 1f else 0f
    }
}

// ---- 右栏 ----

/** 右栏的一颗操作按钮: 圆角底 (聚焦实底, 触屏上不画聚焦), 一行字, 横排时居中、竖排时靠左. */
@SuppressLint("ViewConstructor")
internal class TvNoticeButtonView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context) {
    private val label = tvNoticeText(context, style.action)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
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

    /** 窗口被叠在上面的小窗拿走了焦点: 不画聚焦的样子 (见 TvNoticeView.covered). */
    var covered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyColors()
        }

    fun bind(text: String, style: TvNoticeStyle) {
        if (this.style != style) {
            this.style = style
            style.action.applyTo(label)
        }
        label.setTextIfChanged(text)
        applyColors()
    }

    private fun focusLook() = isFocused && !style.touch && !covered

    private fun applyColors() {
        label.setTextColor(if (focusLook()) style.focusFg else style.onSurface)
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

    private fun paddingH() = if (center) style.actionPaddingCenterPx else style.actionPaddingStartPx

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
        val height = max(s.actionHeightPx, label.measuredHeight + s.actionPaddingVPx * 2)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val x = if (center) (w - label.measuredWidth) / 2 else paddingH()
        label.tvNoticeLayoutAt(x, (b - t - label.measuredHeight) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        canvas.drawTvNoticePlate(rect, width, height, s.actionCornerPx, if (focusLook()) s.focusBg else s.itemBg, paint)
        if (isPressed && s.touch) canvas.drawTvNoticePlate(rect, width, height, s.actionCornerPx, s.pressedOverlay, paint)
    }

    override fun hasOverlappingRendering(): Boolean = false
}

/**
 * 右栏的操作: 至多 [TvNoticeStyle.rowActions] 颗横排 (各占自己的宽, 最多平分), 更多的一颗一行竖排 (定宽).
 * 按钮在内容变化时复用 (个数不变就只换字), 持焦的那颗不丢焦点.
 */
@SuppressLint("ViewConstructor")
internal class TvNoticeActionsView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context) {
    val buttons = ArrayList<TvNoticeButtonView>()
    var onClick: ((Int) -> Unit)? = null
    var onFocus: ((Int, Boolean) -> Unit)? = null

    val inRow: Boolean get() = buttons.size <= style.rowActions

    /** 窗口被叠在上面的小窗拿走了焦点 (见 [TvNoticeButtonView.covered]). */
    var covered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            buttons.forEach { it.covered = value }
        }

    fun bind(labels: List<String>, style: TvNoticeStyle) {
        this.style = style
        while (buttons.size > labels.size) {
            val removed = buttons.removeAt(buttons.lastIndex)
            removeView(removed)
        }
        while (buttons.size < labels.size) {
            val button = TvNoticeButtonView(context, style)
            button.index = buttons.size
            button.covered = covered
            button.setOnClickListener { onClick?.invoke(button.index) }
            button.setOnFocusChangeListener { _, hasFocus -> onFocus?.invoke(button.index, hasFocus) }
            buttons += button
            addView(button)
        }
        val center = labels.size <= style.rowActions
        labels.forEachIndexed { i, label ->
            buttons[i].center = center
            buttons[i].bind(label, style)
        }
        requestLayout()
    }

    fun focusedIndex(): Int = buttons.indexOfFirst { it.isFocused }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (buttons.isEmpty()) {
            setMeasuredDimension(width, 0)
            return
        }
        if (inRow) {
            val gaps = s.actionRowGapPx * (buttons.size - 1)
            val share = (width - gaps) / buttons.size
            var height = 0
            buttons.forEach {
                it.measure(tvNoticeAtMost(share), UNSPECIFIED)
                height = max(height, it.measuredHeight)
            }
            setMeasuredDimension(width, height)
        } else {
            val columnWidth = min(s.actionColumnWidthPx, width)
            var height = 0
            buttons.forEachIndexed { i, button ->
                button.measure(tvNoticeExactly(columnWidth), UNSPECIFIED)
                height += button.measuredHeight + if (i > 0) s.actionColumnGapPx else 0
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
                x += it.measuredWidth + s.actionRowGapPx
            }
        } else {
            var y = 0
            buttons.forEach {
                it.tvNoticeLayoutAt(0, y)
                y += it.measuredHeight + s.actionColumnGapPx
            }
        }
    }
}

/**
 * 右栏正文那一栏: 放不下时自己滚, 截住的那一头渐隐. [startAtEnd] 时下一次排完停在末尾 (排版之后、绘制之前, 第一帧就在末尾).
 * 能拿焦点 (没有操作的行正文放不下时, 进右栏停在这里上下翻, 见 TvNoticeView); 不画聚焦的样子, 列表里那一行铺浅底表示在这里.
 */
internal class TvNoticeBodyScroll(context: Context) : ScrollView(context) {
    var startAtEnd = false
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        isVerticalFadingEdgeEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun maxScroll(): Int = (content.height - (height - paddingTop - paddingBottom)).coerceAtLeast(0)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (startAtEnd) {
            startAtEnd = false
            scrollTo(0, maxScroll())
        }
    }

    /** 焦点 (只有触屏上的链接按钮) 不带着正文滚. */
    override fun requestChildFocus(child: View?, focused: View?) {
        if (parent != null) parent.requestChildFocus(this, focused)
    }
}

/** 正文里一块的视图 (见 [TvNoticeBlock]): 同一种块换数据时原地换, 不重建. */
internal interface TvNoticeBlockView {
    fun bind(block: TvNoticeBlock, env: TvNoticeBlockEnv)
}

/** 画正文块要的东西: 样式、图标、二维码还没有时那句话、触屏上打开外部链接. */
internal class TvNoticeBlockEnv(
    val style: TvNoticeStyle,
    val icons: Map<ImageVector, Bitmap>,
    val qrUnavailable: String,
    val openUrl: (String) -> Unit,
)

/** 一块的视图: 按块的种类建. */
internal fun createTvNoticeBlockView(context: Context, block: TvNoticeBlock, style: TvNoticeStyle): View = when (block) {
    is TvNoticeParagraph -> TvNoticeParagraphView(context, style)
    is TvNoticeMessage -> TvNoticeBubbleView(context, style)
    is TvNoticeCard -> TvNoticeCardView(context, style)
    is TvNoticeQr -> TvNoticeQrBlockView(context, style)
}

/** 这个视图画得了这块 (同一种). */
internal fun View.canShow(block: TvNoticeBlock): Boolean = when (block) {
    is TvNoticeParagraph -> this is TvNoticeParagraphView
    is TvNoticeMessage -> this is TvNoticeBubbleView
    is TvNoticeCard -> this is TvNoticeCardView
    is TvNoticeQr -> this is TvNoticeQrBlockView
}

/** 一段 ([TvNoticeParagraph]): 有小标题的小标题在上、说明在下 (要点前面画一个点), 没有小标题的就是一段话. */
@SuppressLint("ViewConstructor")
internal class TvNoticeParagraphView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context), TvNoticeBlockView {
    private val title = tvNoticeText(context, style.paragraphTitle, maxLines = 2)
    private val text = tvNoticeText(context, style.paragraph, maxLines = Int.MAX_VALUE)
    private var bullet = false
    private var plain: Boolean? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        addView(title)
        addView(text)
    }

    override fun bind(block: TvNoticeBlock, env: TvNoticeBlockEnv) {
        val p = block as TvNoticeParagraph
        val s = env.style
        if (style != s) {
            style = s
            s.paragraphTitle.applyTo(title)
            plain = null
        }
        val isPlain = p.title == null && !p.bullet
        if (plain != isPlain) {
            plain = isPlain
            (if (isPlain) s.paragraph else s.paragraphSmall).applyTo(text)
        }
        bullet = p.bullet
        title.visibility = if (p.title == null) GONE else VISIBLE
        title.setTextIfChanged(p.title.orEmpty())
        title.setTextColor(s.onSurface)
        // 触屏上操作画成按钮, 资源名这类长串写全会把按钮挤出右栏: 截到几行; 电视上只有一行按键提示, 照常写全
        val nameLines = if (s.touch && p.longName) s.touchNameLines else Int.MAX_VALUE
        val lines = min(nameLines, p.maxLines)
        if (text.maxLines != lines) text.maxLines = lines
        text.setTextIfChanged(p.text.toTvNoticeText())
        text.setTextColor(
            when (p.tone) {
                TvNoticeTone.Normal -> if (isPlain) s.onSurface else s.onSurfaceVariant
                else -> s.toneColor(p.tone)
            },
        )
        requestLayout()
        invalidate()
    }

    private fun textLeft() = if (bullet) style.bulletPx + style.bulletGapPx else 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val room = width - textLeft()
        title.forceLayout()
        text.forceLayout()
        var height = 0
        if (title.visibility != GONE) {
            title.measureWithin(room)
            height += title.measuredHeight + style.paragraphTitleGapPx
        }
        text.measureWithin(room)
        height += text.measuredHeight
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = textLeft()
        var y = 0
        if (title.visibility != GONE) {
            title.tvNoticeLayoutAt(x, 0)
            y = title.measuredHeight + style.paragraphTitleGapPx
        }
        text.tvNoticeLayoutAt(x, y)
    }

    override fun onDraw(canvas: Canvas) {
        if (!bullet) return
        val radius = style.bulletPx / 2f
        paint.color = style.primary
        canvas.drawCircle(radius, style.bulletTopPx + radius, radius, paint)
    }
}

/** 来往记录里的一条 ([TvNoticeMessage]): 自己的靠右、主色淡底, 别人的靠左、中性淡底; 最宽占栏宽的 [TvNoticeStyle.bubbleMaxFraction]. */
@SuppressLint("ViewConstructor")
internal class TvNoticeBubbleView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context), TvNoticeBlockView {
    private val meta = tvNoticeText(context, style.bubbleMeta)
    private val text = tvNoticeText(context, style.bubbleText, maxLines = Int.MAX_VALUE)
    private var mine = false
    private var fresh = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        addView(meta)
        addView(text)
    }

    override fun bind(block: TvNoticeBlock, env: TvNoticeBlockEnv) {
        val m = block as TvNoticeMessage
        val s = env.style
        if (style != s) {
            style = s
            s.bubbleMeta.applyTo(meta)
            s.bubbleText.applyTo(text)
        }
        mine = m.mine
        fresh = m.fresh
        meta.setTextIfChanged(m.meta)
        text.visibility = if (m.text == null) GONE else VISIBLE
        text.setTextIfChanged(m.text.orEmpty())
        (layoutParams as? LinearLayout.LayoutParams)?.let {
            val gravity = if (mine) Gravity.END else Gravity.START
            if (it.gravity != gravity) {
                it.gravity = gravity
                layoutParams = it
            }
        }
        requestLayout()
        invalidate()
    }

    private fun dotTakes() = if (fresh) style.dotPx + style.bubbleDotGapPx else 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val outer = (MeasureSpec.getSize(widthMeasureSpec) * s.bubbleMaxFraction).toInt()
        val inner = outer - s.bubblePaddingHPx * 2
        meta.forceLayout()
        text.forceLayout()
        meta.measureWithin(inner - dotTakes())
        var contentWidth = meta.measuredWidth + dotTakes()
        var height = meta.measuredHeight
        if (text.visibility != GONE) {
            text.measureWithin(inner)
            contentWidth = max(contentWidth, text.measuredWidth)
            height += s.bubbleLineGapPx + text.measuredHeight
        }
        setMeasuredDimension(contentWidth + s.bubblePaddingHPx * 2, height + s.bubblePaddingVPx * 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        meta.tvNoticeLayoutAt(s.bubblePaddingHPx, s.bubblePaddingVPx)
        if (text.visibility != GONE) text.tvNoticeLayoutAt(s.bubblePaddingHPx, meta.bottom + s.bubbleLineGapPx)
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        canvas.drawTvNoticePlate(rect, width, height, s.bubbleCornerPx, if (mine) s.mineBubbleBg else s.otherBubbleBg, paint)
        if (fresh) {
            val radius = s.dotPx / 2f
            paint.color = s.error
            canvas.drawCircle(meta.right + s.bubbleDotGapPx + radius, (meta.top + meta.bottom) / 2f, radius, paint)
        }
    }
}

/** 一张卡片 ([TvNoticeCard]): 图标、标题 (长的截到两行)、状态一行, 有进度时标题行最右写百分比、下面一条进度. */
@SuppressLint("ViewConstructor")
internal class TvNoticeCardView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context), TvNoticeBlockView {
    private val title = tvNoticeText(context, style.cardTitle, maxLines = 2)
    private val status = tvNoticeText(context, style.cardStatus, maxLines = 2)
    private val percent = tvNoticeText(context, style.cardPercent)
    private val progress = TvNoticeProgressView(context)
    private var icon: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val iconRect = RectF()

    init {
        setWillNotDraw(false)
        addView(title)
        addView(status)
        addView(percent)
        addView(progress)
    }

    override fun bind(block: TvNoticeBlock, env: TvNoticeBlockEnv) {
        val c = block as TvNoticeCard
        val s = env.style
        if (style != s) {
            style = s
            s.cardTitle.applyTo(title)
            s.cardStatus.applyTo(status)
            s.cardPercent.applyTo(percent)
        }
        icon = env.icons[c.icon]
        title.setTextIfChanged(c.title)
        title.setTextColor(s.onSurface)
        status.visibility = if (c.status == null) GONE else VISIBLE
        status.setTextIfChanged(c.status.orEmpty())
        status.setTextColor(s.toneColor(c.statusTone))
        val p = c.progress?.coerceIn(0f, 1f)
        percent.visibility = if (p == null) GONE else VISIBLE
        if (p != null) percent.setTextIfChanged(noticePercentText(p))
        percent.setTextColor(s.primary)
        progress.visibility = if (p != null || c.indeterminate) VISIBLE else GONE
        progress.set(p, c.indeterminate, s.primary, s.progressTrack)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - s.cardPaddingHPx * 2
        title.forceLayout()
        status.forceLayout()
        percent.forceLayout()
        var percentTakes = 0
        if (percent.visibility != GONE) {
            percent.measure(UNSPECIFIED, UNSPECIFIED)
            percentTakes = percent.measuredWidth + s.cardIconGapPx
        }
        val textRoom = inner - s.cardIconPx - s.cardIconGapPx - percentTakes
        title.measureWithin(textRoom)
        if (status.visibility != GONE) status.measureWithin(textRoom)
        var height = rowHeight()
        if (progress.visibility != GONE) {
            progress.measure(tvNoticeExactly(inner), tvNoticeExactly(s.progressHeightPx))
            height += s.cardGapPx + s.progressHeightPx
        }
        setMeasuredDimension(width, height + s.cardPaddingVPx * 2)
    }

    private fun textHeight() = title.measuredHeight + if (status.visibility != GONE) status.measuredHeight else 0

    private fun rowHeight() = maxOf(style.cardIconPx, textHeight(), if (percent.visibility != GONE) percent.measuredHeight else 0)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        val w = r - l
        val row = rowHeight()
        val top = s.cardPaddingVPx
        val x = s.cardPaddingHPx + s.cardIconPx + s.cardIconGapPx
        var y = top + (row - textHeight()) / 2
        title.tvNoticeLayoutAt(x, y)
        y += title.measuredHeight
        if (status.visibility != GONE) status.tvNoticeLayoutAt(x, y)
        if (percent.visibility != GONE) {
            percent.tvNoticeLayoutAt(w - s.cardPaddingHPx - percent.measuredWidth, top + (row - percent.measuredHeight) / 2)
        }
        if (progress.visibility != GONE) progress.tvNoticeLayoutAt(s.cardPaddingHPx, top + row + s.cardGapPx)
    }

    override fun onDraw(canvas: Canvas) {
        val s = style
        canvas.drawTvNoticePlate(rect, width, height, s.cardCornerPx, s.cardBg, paint)
        val row = rowHeight()
        canvas.drawTvNoticeIcon(icon, s.cardPaddingHPx.toFloat(), s.cardPaddingVPx + (row - s.cardIconPx) / 2f, s.cardIconPx, s.primary, iconPaint, iconRect)
    }
}

/** 一条进度: 圆头的轨道与进度; 不定式的是一截在轨道上来回走. */
internal class TvNoticeProgressView(context: Context) : View(context) {
    private var progress: Float? = null
    private var indeterminate = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var color = 0
    private var track = 0
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun set(progress: Float?, indeterminate: Boolean, color: Int, track: Int) {
        this.progress = progress
        this.indeterminate = indeterminate && progress == null
        this.color = color
        this.track = track
        updateAnimator()
        invalidate()
    }

    private fun updateAnimator() {
        val run = indeterminate && isAttachedToWindow && visibility == VISIBLE
        if (run && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = INDETERMINATE_MILLIS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!run && animator != null) {
            animator?.cancel()
            animator = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimator()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2
        paint.color = track
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = color
        val p = progress
        when {
            p != null -> if (p > 0f) {
                rect.set(0f, 0f, max(h, w * p), h)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }

            indeterminate -> {
                val segment = w * INDETERMINATE_SEGMENT
                val start = (w + segment) * phase - segment
                rect.set(start.coerceAtLeast(0f), 0f, (start + segment).coerceAtMost(w), h)
                if (rect.width() > 0f) canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    private companion object {
        const val INDETERMINATE_MILLIS = 1200L
        const val INDETERMINATE_SEGMENT = 0.35f
    }
}

/** 二维码: 圆角底上画模块, 四周留 [quietPx] 的空白; 模块边长取整像素 (相邻模块之间不出灰缝). 同 Compose 的 QrCodeImage. */
internal class TvNoticeQrView(context: Context) : View(context) {
    private var content: String? = null
    private var matrix: QrCodeMatrix? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modulePaint = Paint()
    private val rect = RectF()
    private var quietPx = 0
    private var cornerPx = 0f
    private var placeholder = false

    /** 此刻画的内容 (测试用). */
    val shownContent: String? get() = content

    fun set(content: String?, quietPx: Int, cornerPx: Float, bg: Int, fg: Int, placeholderBorder: Int) {
        if (content != this.content) {
            this.content = content
            matrix = content?.let { text -> matrices[text] ?: encodeQrCode(text)?.also { matrices.put(text, it) } }
        }
        this.quietPx = quietPx
        this.cornerPx = cornerPx
        placeholder = content == null
        if (placeholder) {
            bgPaint.style = Paint.Style.STROKE
            bgPaint.strokeWidth = resources.displayMetrics.density
            bgPaint.color = placeholderBorder
        } else {
            bgPaint.style = Paint.Style.FILL
            bgPaint.color = bg
        }
        modulePaint.color = fg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        if (placeholder) {
            val half = bgPaint.strokeWidth / 2
            rect.set(half, half, side - half, side - half)
            canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)
            return
        }
        val m = matrix ?: return
        rect.set(0f, 0f, side, side)
        canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)
        val inner = side - quietPx * 2
        val cell = floor(inner / m.size)
        if (cell <= 0f) return
        val offset = quietPx + (inner - cell * m.size) / 2f
        for (y in 0 until m.size) {
            for (x in 0 until m.size) {
                if (!m[x, y]) continue
                val left = offset + x * cell
                val top = offset + y * cell
                canvas.drawRect(left, top, left + cell, top + cell, modulePaint)
            }
        }
    }

    private companion object {
        /** 编过的码 (内容 → 模块): 每次打开弹窗、换一行都要画的那几个码 (控制台的码、外部链接) 不重新编. 只在主线程上用. */
        val matrices = LruCache<String, QrCodeMatrix>(8)
    }
}

/**
 * 正文里的二维码 ([TvNoticeQr]): 有标题的码在左、右边竖排标题、说明与状态 (点 + 字), 整块与码垂直居中; 没标题的码下面写一行字与状态.
 * 还没有码 (url 为 null) 时有标题的画一个空框 (框里写为什么没有), 没标题的只写字. 外部链接在触屏上是一颗在浏览器里打开的按钮.
 */
@SuppressLint("ViewConstructor")
internal class TvNoticeQrBlockView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context), TvNoticeBlockView {
    private val qr = TvNoticeQrView(context)
    private val unavailable = tvNoticeText(context, style.qrUnavailable, maxLines = 3).apply { gravity = Gravity.CENTER }
    private val title = tvNoticeText(context, style.qrTitle, maxLines = 2)
    private val caption = tvNoticeText(context, style.qrCaption, maxLines = 3)
    private val status = tvNoticeText(context, style.status, maxLines = 2)
    private val link = TvNoticeButtonView(context, style)
    private var titled = false
    private var linkMode = false
    private var hasQr = false
    private var statusDot = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 此刻码里的内容 (测试用). */
    val shownContent: String? get() = qr.shownContent?.takeIf { hasQr }

    init {
        setWillNotDraw(false)
        addView(qr)
        addView(unavailable)
        addView(title)
        addView(caption)
        addView(status)
        addView(link)
        link.center = false
    }

    override fun bind(block: TvNoticeBlock, env: TvNoticeBlockEnv) {
        val q = block as TvNoticeQr
        val s = env.style
        if (style != s) {
            style = s
            s.qrUnavailable.applyTo(unavailable)
            s.qrTitle.applyTo(title)
            s.status.applyTo(status)
        }
        val url = q.url
        linkMode = s.touch && q.openOnTouch && url != null
        titled = q.title != null
        hasQr = url != null || titled
        if (linkMode && url != null) {
            link.bind(q.caption ?: url, s)
            link.setOnClickListener { env.openUrl(url) }
        }
        (if (titled) s.qrCaption else s.qrCaptionSmall).applyTo(caption)
        caption.maxLines = if (titled) 3 else 2
        qr.set(
            url,
            if (titled) s.qrQuietPx else s.linkQrQuietPx,
            s.qrCornerPx, s.qrBg, s.qrFg, s.outlineVariant,
        )
        unavailable.setTextIfChanged(env.qrUnavailable)
        title.setTextIfChanged(q.title.orEmpty())
        title.setTextColor(s.onSurface)
        caption.setTextIfChanged(q.caption.orEmpty())
        caption.setTextColor(if (titled) s.onSurface else s.onSurfaceVariant)
        status.setTextIfChanged(q.status.orEmpty())
        status.setTextColor(s.toneColor(q.statusTone))
        statusDot = when (q.statusTone) {
            TvNoticeTone.Normal, TvNoticeTone.Secondary -> s.outline
            else -> s.toneColor(q.statusTone)
        }
        link.visibility = if (linkMode) VISIBLE else GONE
        qr.visibility = if (!linkMode && hasQr) VISIBLE else GONE
        unavailable.visibility = if (!linkMode && titled && url == null) VISIBLE else GONE
        title.visibility = if (!linkMode && titled) VISIBLE else GONE
        caption.visibility = if (!linkMode && !q.caption.isNullOrEmpty()) VISIBLE else GONE
        status.visibility = if (!linkMode && !q.status.isNullOrEmpty()) VISIBLE else GONE
        requestLayout()
        invalidate()
    }

    private fun qrSide() = if (titled) style.qrSizePx + style.qrQuietPx * 2 else style.linkQrSizePx + style.linkQrQuietPx * 2

    private fun statusIndent() = style.statusDotPx + style.statusDotGapPx

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val width = MeasureSpec.getSize(widthMeasureSpec)
        for (v in arrayOf(unavailable, title, caption, status)) v.forceLayout()
        if (linkMode) {
            link.measure(tvNoticeExactly(width), UNSPECIFIED)
            setMeasuredDimension(width, link.measuredHeight)
            return
        }
        val side = qrSide()
        if (qr.visibility != GONE) qr.measure(tvNoticeExactly(side), tvNoticeExactly(side))
        if (unavailable.visibility != GONE) {
            val pad = s.qrUnavailablePaddingPx
            unavailable.measureWithin(side - pad * 2)
        }
        if (titled) {
            val room = width - side - s.qrGapPx
            title.measureWithin(room)
            if (caption.visibility != GONE) caption.measureWithin(room)
            if (status.visibility != GONE) status.measureWithin(room - statusIndent())
            setMeasuredDimension(width, max(side, columnHeight()))
        } else {
            var height = s.qrTopPx
            if (qr.visibility != GONE) height += side
            if (caption.visibility != GONE) {
                caption.measureWithin(width)
                height += s.qrCaptionGapPx + caption.measuredHeight
            }
            if (status.visibility != GONE) {
                status.measureWithin(width - statusIndent())
                height += s.qrCaptionGapPx + status.measuredHeight
            }
            setMeasuredDimension(width, height)
        }
    }

    /** 有标题时码右边那一栏的高: 标题、说明、状态. */
    private fun columnHeight(): Int {
        val s = style
        var height = title.measuredHeight
        if (caption.visibility != GONE) height += s.qrTitleGapPx + caption.measuredHeight
        if (status.visibility != GONE) height += s.qrStatusGapPx + status.measuredHeight
        return height
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        if (linkMode) {
            link.tvNoticeLayoutAt(0, 0)
            return
        }
        val side = qrSide()
        if (titled) {
            val h = b - t
            val qrTop = (h - side) / 2
            qr.tvNoticeLayoutAt(0, qrTop)
            if (unavailable.visibility != GONE) {
                unavailable.tvNoticeLayoutAt((side - unavailable.measuredWidth) / 2, qrTop + (side - unavailable.measuredHeight) / 2)
            }
            val x = side + s.qrGapPx
            var y = (h - columnHeight()) / 2
            title.tvNoticeLayoutAt(x, y)
            y += title.measuredHeight
            if (caption.visibility != GONE) {
                caption.tvNoticeLayoutAt(x, y + s.qrTitleGapPx)
                y = caption.bottom
            }
            if (status.visibility != GONE) status.tvNoticeLayoutAt(x + statusIndent(), y + s.qrStatusGapPx)
        } else {
            var y = s.qrTopPx
            if (qr.visibility != GONE) {
                qr.tvNoticeLayoutAt(0, y)
                y += side
            }
            if (caption.visibility != GONE) {
                caption.tvNoticeLayoutAt(0, y + s.qrCaptionGapPx)
                y = caption.bottom
            }
            if (status.visibility != GONE) status.tvNoticeLayoutAt(statusIndent(), y + s.qrCaptionGapPx)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (linkMode || status.visibility == GONE) return
        val radius = style.statusDotPx / 2f
        paint.color = statusDot
        // 点与状态的第一行居中
        val firstLine = status.layout?.let { it.getLineBottom(0) - it.getLineTop(0) } ?: status.measuredHeight
        canvas.drawCircle(status.left - style.statusDotGapPx - radius, status.top + firstLine / 2f, radius, paint)
    }
}

/** 右栏: 签与时间 (或 meta)、大标题、哪一个、正文 (放不下时自己滚)、操作. 两栏一样高, 高由外面定. */
@SuppressLint("ViewConstructor")
internal class TvNoticePaneView(context: Context, private var style: TvNoticeStyle) : ViewGroup(context) {
    private val chips = ArrayList<TvNativeTextView>()
    private val meta = tvNoticeText(context, style.meta)
    private val title = tvNoticeText(context, style.paneTitle, maxLines = 2)
    private val subject = tvNoticeText(context, style.subject, maxLines = 2)
    val body = TvNoticeBodyScroll(context)
    val actions = TvNoticeActionsView(context, style)

    /** 此刻画的是哪一行的键 (没有为 null). */
    var key: String? = null
        private set
    private var blocks: List<TvNoticeBlock>? = null

    init {
        addView(meta)
        addView(title)
        addView(subject)
        addView(body)
        addView(actions)
        body.setFadingEdgeLength(style.fadePx)
    }

    /**
     * 画 [row] 这一行. 同一行 ([sameRow]) 换内容时原地换: 正文的滚动位置不动, 块的种类与个数没变就逐块换字; 换了一行时正文回到开头
     * ([startAtEnd] 时停在末尾).
     */
    fun bind(row: TvNoticeViewRow, buttons: Boolean, startAtEnd: Boolean, sameRow: Boolean, env: TvNoticeBlockEnv) {
        val s = env.style
        if (style != s) applyStyle(s)
        val item = row.item
        key = item.key
        bindChips(item.chips)
        val metaText = item.meta ?: row.time
        meta.visibility = if (metaText.isNullOrEmpty()) GONE else VISIBLE
        meta.setTextIfChanged(metaText.orEmpty())
        title.setTextIfChanged(item.title)
        subject.visibility = if (item.subject == null) GONE else VISIBLE
        subject.setTextIfChanged(item.subject.orEmpty())
        bindBlocks(item.blocks, env)
        if (!sameRow) {
            body.scrollTo(0, 0)
            body.startAtEnd = startAtEnd
        }
        val labels = if (buttons) item.actions.map { it.label } else emptyList()
        actions.bind(labels, s)
        actions.visibility = if (labels.isEmpty()) GONE else VISIBLE
        requestLayout()
    }

    fun clear() {
        key = null
        blocks = null
        bindChips(emptyList())
        meta.visibility = GONE
        title.text = ""
        subject.visibility = GONE
        body.content.removeAllViews()
        actions.bind(emptyList(), style)
        actions.visibility = GONE
        requestLayout()
    }

    private fun applyStyle(s: TvNoticeStyle) {
        style = s
        s.meta.applyTo(meta)
        s.paneTitle.applyTo(title)
        s.subject.applyTo(subject)
        chips.forEach { s.chip.applyTo(it) }
        body.setFadingEdgeLength(s.fadePx)
        blocks = null
    }

    private fun bindChips(list: List<TvNoticeChip>) {
        while (chips.size > list.size) removeView(chips.removeAt(chips.lastIndex))
        while (chips.size < list.size) {
            val chip = tvNoticeText(context, style.chip)
            chip.setPadding(style.chipPaddingHPx, style.chipPaddingVPx, style.chipPaddingHPx, style.chipPaddingVPx)
            chips += chip
            addView(chip)
        }
        list.forEachIndexed { i, c ->
            val view = chips[i]
            view.setTextIfChanged(c.text)
            view.setTextColor(style.onSurface)
            view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = style.chipCornerPx
                setColor(if (c.accent) style.accentChipBg else style.chipBg)
            }
        }
    }

    private fun bindBlocks(list: List<TvNoticeBlock>, env: TvNoticeBlockEnv) {
        if (list == blocks) return
        blocks = list
        val container = body.content
        val reusable = container.childCount == list.size && list.indices.all { container.getChildAt(it).canShow(list[it]) }
        if (!reusable) {
            container.removeAllViews()
            list.forEachIndexed { i, block ->
                val view = createTvNoticeBlockView(context, block, style)
                val bubble = block is TvNoticeMessage
                val params = LinearLayout.LayoutParams(
                    if (bubble) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (i > 0) params.topMargin = style.blockGapPx
                container.addView(view, params)
            }
        }
        list.forEachIndexed { i, block -> (container.getChildAt(i) as TvNoticeBlockView).bind(block, env) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = style
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        for (v in arrayOf(meta, title, subject)) v.forceLayout()
        var used = 0
        var x = 0
        var chipRow = 0
        chips.forEach {
            it.measure(UNSPECIFIED, UNSPECIFIED)
            chipRow = max(chipRow, it.measuredHeight)
            x += it.measuredWidth + s.chipGapPx
        }
        if (meta.visibility != GONE) {
            meta.measureWithin(width - x - s.metaStartPx)
            chipRow = max(chipRow, meta.measuredHeight)
        }
        used += chipRow
        title.measureWithin(width)
        used += s.paneTitleTopPx + title.measuredHeight + title.overflowPad * 2
        if (subject.visibility != GONE) {
            subject.measureWithin(width)
            used += s.subjectTopPx + subject.measuredHeight
        }
        if (actions.visibility != GONE) {
            actions.measure(tvNoticeExactly(width), UNSPECIFIED)
            used += s.actionsTopPx + actions.measuredHeight
        }
        val bodyHeight = (height - used - s.bodyTopPx).coerceAtLeast(0)
        body.measure(tvNoticeExactly(width), tvNoticeExactly(bodyHeight))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = style
        val h = b - t
        var chipRow = 0
        chips.forEach { chipRow = max(chipRow, it.measuredHeight) }
        if (meta.visibility != GONE) chipRow = max(chipRow, meta.measuredHeight)
        var x = 0
        chips.forEach {
            it.tvNoticeLayoutAt(x, (chipRow - it.measuredHeight) / 2)
            x += it.measuredWidth + s.chipGapPx
        }
        if (meta.visibility != GONE) {
            meta.tvNoticeLayoutAt(x + s.metaStartPx, (chipRow - meta.measuredHeight) / 2)
        }
        var y = chipRow + s.paneTitleTopPx
        title.tvNoticeLayoutAt(0, y + title.overflowPad)
        y += title.measuredHeight + title.overflowPad * 2
        if (subject.visibility != GONE) {
            subject.tvNoticeLayoutAt(0, y + s.subjectTopPx)
            y = subject.bottom
        }
        y += s.bodyTopPx
        body.tvNoticeLayoutAt(0, y)
        if (actions.visibility != GONE) actions.tvNoticeLayoutAt(0, h - actions.measuredHeight)
    }
}
