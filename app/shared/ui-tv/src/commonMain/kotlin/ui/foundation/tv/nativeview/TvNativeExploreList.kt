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
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Immutable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring

/*
 * 探索页海报墙: 顶上是热门轮播; 焦点一进卡片区, 整页往上滚, 第一行也停在屏幕正中, 轮播跟着上移、
 * 还露着一截 (照 Apple TV 首页, 见 [tvNativeCarouselShift]) —— 卡片区是按组分段的「海报 + 番名」行: 继续观看一行, 每组推荐一行 (12 张; 没登录时
 * 那一组「推荐」两百条按屏上完整放得下的张数切成几行, 不横滑), 组间一行标题. 行横滑不循环, 按需挪 ([tvStripLeftIndex]): 焦点在屏上完整露出的
 * 几张之间走时不动, 走到右边露一截的那张才整行往左挪. 纵向聚焦行尽量停在视口垂直正中 ([centeredScroll]).
 *
 * 列表结构: [hero 占位] + [继续观看标题, 继续观看行] + [各组: 标题, 行]. hero 占位是一段透明的空白, 轮播态列表停在顶上时它正好垫在轮播下面,
 * 第一组标题落在 hero 文字块 (连同轮播按钮) 下面; 进卡片区时它随列表滚上去, 轮播的文字与背景图跟着上移 (不变透明、不模糊、不压暗; 背景图多挪一截,
 * 见 [tvNativeCarouselShift]) —— 轮播的进退就是一次列表滚动, 与卡片同一条 spring.
 *
 * hero 态 (卡片上按确定): 背景与轮播文字换成聚焦卡、回到 hero 的位置, 聚焦行的组标题落到 hero 简介块下沿那条线上 ([heroScroll]),
 * 上面的项越线淡出; 再按确定放大进详情页.
 */

/**
 * 探索页海报墙纵向列表的一项: 轮播下面垫的 hero 占位、组标题、一条横滑行. 各项定高 (见 [TvNativeExploreMetrics]), 停位直接按高度算.
 */
@Immutable
sealed interface TvNativeExploreItem {
    val key: String

    @Immutable
    data class Spacer(override val key: String) : TvNativeExploreItem

    @Immutable
    data class Header(override val key: String, val title: String) : TvNativeExploreItem

    /**
     * 一条横滑行. [cards] 里的 null 是分页还没到的占位. 卡的稳定 id 按下标: 推荐重算落库时持焦的卡原地换内容,
     * 焦点不被带走 (按条目 id 的话持焦的那张被换掉, 焦点跟着丢).
     */
    @Immutable
    data class Row(override val key: String, val cards: List<TvNativeCard?>) : TvNativeExploreItem
}

/** [items] 里 [rowKey] 那一行的卡; 没有这一行时 null. */
internal fun tvNativeRowCards(items: List<TvNativeExploreItem>, rowKey: String): List<TvNativeCard?>? =
    (items.firstOrNull { it.key == rowKey } as? TvNativeExploreItem.Row)?.cards

/**
 * 聚焦的 [rowKey] 那一行在换数据 ([oldItems] → [newItems]) 后没了, 焦点交给哪一行: 旧列表里它下面第一条还在的行 (顶上来占它位置的那条),
 * 下面都没了就上面最近的一条; 一行都不剩时 null.
 */
internal fun tvNativeReplacementRow(oldItems: List<TvNativeExploreItem>, newItems: List<TvNativeExploreItem>, rowKey: String): String? {
    val at = oldItems.indexOfFirst { it.key == rowKey }
    if (at < 0) return null
    val remaining = newItems.mapTo(HashSet()) { it.key }
    val below = oldItems.subList(at + 1, oldItems.size).asSequence()
    val above = oldItems.subList(0, at).asReversed().asSequence()
    return (below + above).firstOrNull { it is TvNativeExploreItem.Row && it.key in remaining }?.key
}

/** 纵向列表每一项的高度 (px): 行 = 海报 + 两行番名 + 行距, 组标题定高, hero 占位 = 轮播态下卡片区顶线到首组标题的距离. */
internal fun TvNativeExploreMetrics.heightOf(item: TvNativeExploreItem): Int = when (item) {
    is TvNativeExploreItem.Spacer -> spacerPx
    is TvNativeExploreItem.Header -> headerPx
    is TvNativeExploreItem.Row -> rowPx
}

/** 第 [index] 项的顶离列表内容顶多远 (px). */
internal fun TvNativeExploreMetrics.itemTop(items: List<TvNativeExploreItem>, index: Int): Int {
    var top = 0
    for (i in 0 until index.coerceAtMost(items.size)) top += heightOf(items[i])
    return top
}

/**
 * 聚焦第 [index] 行时列表该滚到哪 (px, 从列表内容顶算起): 行停在视口垂直正中 —— 同 Apple TV 首页, 焦点尽量在屏幕中间, 第一行也一样
 * (上面的热门轮播还露着一截, 见 [tvNativeCarouselShift]). 两头放不到正中就贴边: 不少于 0 (列表顶), 不多于"末行番名底边离视口底
 * [TvNativeExploreMetrics.endMarginPx]" 那一处.
 *
 * 停位只由聚焦行决定, 不看列表现在停在哪: 从详情页返回整页重建、分页迟到往头部插项、连按时目标跑在滚动前面,
 * 各条路径算出来的都是同一个位置, 不会先被拉到别处再滚回来.
 */
internal fun TvNativeExploreMetrics.centeredScroll(items: List<TvNativeExploreItem>, index: Int): Int {
    val rowHeight = rowPx - rowGapPx
    val centered = itemTop(items, index) + rowHeight / 2 - viewportPx / 2
    val contentEnd = itemTop(items, items.size) - rowGapPx
    val max = contentEnd + endMarginPx - viewportPx
    return centered.coerceAtMost(max).coerceAtLeast(0)
}

/**
 * hero 态聚焦第 [index] 行时列表该滚到哪: 这一行的组标题位置落在 hero 线 [TvNativeExploreMetrics.heroHeaderTopPx] 上 (hero 简介块下沿),
 * 行紧跟在下面; 没有组标题的行 (同组第二行起) 上一行越线淡出. 开头滚不到 (负数) 就停在顶.
 */
internal fun TvNativeExploreMetrics.heroScroll(items: List<TvNativeExploreItem>, index: Int): Int =
    (itemTop(items, index) - headerPx - heroHeaderTopPx).coerceAtLeast(0)

/**
 * 热门轮播的背景图此刻往上挪了多少 (px), 照 Apple TV 首页的 hero: 跟着列表一起滚, 再按进度额外上移 [overhangPx] —— 进度 = 列表滚过的量 /
 * 第一行居中时的量 [firstRowStopPx], 到 1 为止. [overhangPx] 是背景图比 hero 占位长出去的那一截 (压在第一组标题与海报上面): 第一行居中时
 * 正好挪完, 图的下缘 (羽化到底色) 停在第一组标题上沿, 第一行整个落在底色上; 再往下翻就与列表同步滚出屏.
 * 轮播的文字不多挪这一截, 与列表 1:1.
 */
internal fun tvNativeCarouselShift(scrolledPx: Float, firstRowStopPx: Int, overhangPx: Int): Float {
    val progress = if (firstRowStopPx > 0) (scrolledPx / firstRowStopPx).coerceIn(0f, 1f) else 1f
    return scrolledPx + overhangPx * progress
}

/**
 * 探索页海报墙的纵向列表: RecyclerView (纵向 LinearLayoutManager), 项为 [TvNativeExploreItem]. 焦点换行时**不让框架自动滚**
 * (requestChildRectangleOnScreen 一律不滚), 停位按定高公式算好 ([centeredScroll] / [heroScroll]), 用 [TvNativeSpringScroll] 按像素距离滚
 * ([scrollToStop]), 与页面其余滚动同一组 spring.
 *
 * 视口上下各多排一屏 ([TvNativeExploreMetrics.viewportPx]): 连按上下键时目标行已经排好, 焦点当场给得出去.
 * 横滑行共用一个卡片回收池.
 */
@SuppressLint("ViewConstructor", "NotifyDataSetChanged")
class TvNativeExploreList(
    context: Context,
    private val sketch: Sketch,
    style: TvNativeWallStyle,
    metrics: TvNativeExploreMetrics,
    private val headerStyle: TvNativeTextStyle,
) : RecyclerView(context) {
    var style: TvNativeWallStyle = style
        private set
    var metrics: TvNativeExploreMetrics = metrics
        private set

    private val cardPool = RecycledViewPool().apply { setMaxRecycledViews(TV_NATIVE_CARD_VIEW_TYPE, TV_NATIVE_EXPLORE_CARD_POOL) }
    private val lm = ListLayoutManager(context)
    val scroll = TvNativeSpringScroll()

    var items: List<TvNativeExploreItem> = emptyList()
        private set

    /** 各行记住的行首 (回收重绑时恢复横向位置), 按行键. */
    val rowLeftIndex = HashMap<String, Int>()

    /** 各行的卡片事件 (页面实现, 带上行键). */
    var cardListener: ((row: String) -> TvNativeCardListener)? = null

    /** 卡片被绑定 (分页的访问提示: 页面据此触发继续观看分页往后取). */
    var onBindCard: ((row: String, index: Int) -> Unit)? = null

    /** 行绑定 / 滚动 / 布局后调, 页面据此重算各项的淡化 (hero 态越线淡没). */
    var onItemsMoved: (() -> Unit)? = null

    /**
     * 远跳途中压住所有卡的聚焦效果. 屏上的行当场改; 回收缓存 / 回收池里的行 (远跳一路滚出去的那些) 重新上屏或重绑时照此补上 ——
     * 只改屏上的行的话, 按返回那一刻屏上的行放开时已经离屏, 回来时一直画成未聚焦.
     */
    var focusEffectSuppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            for (i in 0 until childCount) (getChildAt(i) as? TvNativeRowView)?.let { it.cards.setFocusEffectSuppressed(it, value) }
        }

    /**
     * 没有焦点也画成聚焦态的那张 (行键, 下标; 见 [TvNativeCardAdapter.setFocusLookHeld]), null = 没有. 屏上的行当场改, 之后排出来 / 重新上屏的
     * 行绑定时补上.
     */
    var heldFocus: Pair<String, Int>? = null
        set(value) {
            if (field == value) return
            field = value
            for (i in 0 until childCount) (getChildAt(i) as? TvNativeRowView)?.let { applyHeldFocus(it) }
        }

    private fun applyHeldFocus(row: TvNativeRowView) {
        val held = heldFocus
        row.cards.setFocusLookHeld(row, if (held != null && held.first == row.tag) held.second else -1)
    }

    /** 焦点不在那一组时组标题的不透明度 (见 [TvNativeExploreHeaderView]); 1 = 不淡. */
    var headerIdleAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            for (i in 0 until childCount) (getChildAt(i) as? TvNativeExploreHeaderView)?.idleAlpha = value
        }

    /** 全亮的那个组标题 (项键; null = 没有), 见 [setEmphasizedHeader]. */
    var emphasizedHeaderKey: String? = null
        private set

    /**
     * 焦点所在那一组的标题 [key] 全亮, 其余组标题淡一档 (null = 都淡). 屏上的标题 [animate] 时按卡片聚焦的节奏过渡;
     * 之后排出来 / 重新上屏的标题绑定时直接到位.
     */
    fun setEmphasizedHeader(key: String?, animate: Boolean) {
        if (emphasizedHeaderKey == key) return
        emphasizedHeaderKey = key
        val millis = if (animate) style.focusMillis else 0L
        for (i in 0 until childCount) {
            val header = getChildAt(i) as? TvNativeExploreHeaderView ?: continue
            header.setEmphasized(header.tag == key, millis)
        }
    }

    /** 回收缓存里原样拿回、或换绑到别的组的标题: 淡化档位与是否全亮当场对上. */
    private fun syncHeader(header: TvNativeExploreHeaderView) {
        header.idleAlpha = headerIdleAlpha
        header.setEmphasized(header.tag == emphasizedHeaderKey, 0L)
    }

    private val adapterImpl = Adapter()

    init {
        clipChildren = false
        clipToPadding = false
        itemAnimator = null
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        layoutManager = lm
        adapter = adapterImpl
        setItemViewCacheSize(2)
        applyMetrics()
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onItemsMoved?.invoke()
            }
        })
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onItemsMoved?.invoke() }
    }

    fun update(style: TvNativeWallStyle, metrics: TvNativeExploreMetrics) {
        val styleChanged = this.style != style
        val metricsChanged = this.metrics != metrics
        this.style = style
        this.metrics = metrics
        if (metricsChanged) applyMetrics()
        if (styleChanged || metricsChanged) adapterImpl.notifyDataSetChanged()
    }

    private fun applyMetrics() {
        val m = metrics
        // 顶上出血一截 (离场行越过顶线继续上移), 末尾补一整屏: 任何一行的停位都滚得到
        setPadding(0, m.listTopBleedPx, 0, m.viewportPx + m.listBottomBleedPx)
        lm.extraPx = m.viewportPx
    }

    fun submit(items: List<TvNativeExploreItem>) {
        if (items == this.items) return
        val keysChanged = items.size != this.items.size || items.indices.any { items[it].key != this.items[it].key }
        this.items = items
        if (keysChanged) {
            adapterImpl.notifyDataSetChanged()
        } else {
            // 结构没变只换内容 (推荐重算 / 分页到货): 按位置刷新, 已排好的行原地重绑
            for (i in items.indices) {
                val holder = findViewHolderForAdapterPosition(i)
                if (holder != null) adapterImpl.bindItem(holder, i) else adapterImpl.notifyItemChanged(i)
            }
        }
    }

    /** 行键 → 下标. */
    fun indexOfKey(key: String): Int = items.indexOfFirst { it.key == key }

    /** 此刻滚过了多少像素 (各项定高, 由第一个排出来的项推出). */
    fun scrolledPx(): Int {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val pos = getChildAdapterPosition(child)
            if (pos == NO_POSITION) continue
            return metrics.itemTop(items, pos) - (child.top - paddingTop)
        }
        return 0
    }

    /** 已排出来的第 [index] 项. */
    fun viewAt(index: Int): View? = findViewHolderForAdapterPosition(index)?.itemView

    /** 第 [index] 项的横滑行 (已排出来时). */
    fun rowAt(index: Int): TvNativeRowView? = viewAt(index) as? TvNativeRowView

    /**
     * 滚到停位 [targetPx] (列表内容坐标). [pace] = 这一段的 spring (远跳 / 进出 hero 态用 Far); [animated] = false 时当场到位.
     */
    fun scrollToStop(targetPx: Int, pace: TvScrollSpring, animated: Boolean) {
        // 本列表正在布局 (窗口没焦点时, 行在这一趟里第一次排卡, leanback 经 ViewRootImpl.focusableViewAvailable 当场把焦点给卡, 卡的聚焦回调
        // 一路调到这里): 这时同步 scrollBy 会重入布局, RecyclerView 的布局步骤断言当场失败. 挪到这一趟布局之后
        if (isComputingLayout) {
            post { scrollToStop(targetPx, pace, animated) }
            return
        }
        val dy = targetPx - scrolledPx()
        if (dy == 0) return
        if (!animated || !scroll.animated) {
            stopScroll()
            scroll.reset()
            scrollBy(0, dy)
            return
        }
        scroll.pace = pace
        val plan = scroll.plan(dy.toFloat())
        smoothScrollBy(0, dy, plan.interpolator, plan.durationMillis)
    }

    /** 当场跳到 [targetPx] (进页恢复). */
    fun jumpTo(targetPx: Int) {
        stopScroll()
        scroll.reset()
        val m = metrics
        // 按定高换算成 (项, 项内偏移), 交给 LinearLayoutManager 一步定位
        var remaining = targetPx.coerceAtLeast(0)
        var index = 0
        while (index < items.size) {
            val h = m.heightOf(items[index])
            if (remaining < h) break
            remaining -= h
            index++
        }
        if (index >= items.size) {
            index = (items.size - 1).coerceAtLeast(0)
            remaining = 0
        }
        lm.scrollToPositionWithOffset(index, -remaining)
    }

    private inner class ListLayoutManager(context: Context) : LinearLayoutManager(context, VERTICAL, false) {
        var extraPx = 0

        override fun calculateExtraLayoutSpace(state: State, extraLayoutSpace: IntArray) {
            extraLayoutSpace[0] = extraPx
            extraLayoutSpace[1] = extraPx
        }

        // 焦点换行不自动滚: 停位由页面按公式算 (见类说明)
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView,
            child: View,
            rect: Rect,
            immediate: Boolean,
            focusedChildVisible: Boolean,
        ): Boolean = false

        override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean): Boolean = false

        override fun supportsPredictiveItemAnimations(): Boolean = false
    }

    private inner class Adapter : RecyclerView.Adapter<ViewHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is TvNativeExploreItem.Spacer -> TYPE_SPACER
            is TvNativeExploreItem.Header -> TYPE_HEADER
            is TvNativeExploreItem.Row -> TYPE_ROW
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val m = metrics
            val view = when (viewType) {
                TYPE_SPACER -> View(parent.context).apply { isFocusable = false }
                TYPE_HEADER -> TvNativeExploreHeaderView(parent.context).apply {
                    headerStyle.applyTo(this)
                    maxLines = 1
                    setPadding(m.rowStartPx, 0, m.endPadPx, 0)
                    isFocusable = false
                }

                else -> TvNativeRowView(
                    parent.context, style, sketch, cardPool,
                    startPx = m.rowStartPx, endPx = m.endPadPx, bottomPx = m.rowGapPx,
                    fadeDistancePx = m.fadeDistancePx,
                )
            }
            return object : ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = bindItem(holder, position)

        fun bindItem(holder: ViewHolder, position: Int) {
            val m = metrics
            val item = items[position]
            val view = holder.itemView
            val height = m.heightOf(item)
            val lp = view.layoutParams as? LayoutParams
            if (lp == null || lp.height != height || lp.width != LayoutParams.MATCH_PARENT) {
                view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height)
            }
            when (item) {
                is TvNativeExploreItem.Spacer -> Unit
                is TvNativeExploreItem.Header -> {
                    val header = view as TvNativeExploreHeaderView
                    header.text = item.title
                    // 同一组原地刷新内容时在走的过渡照走; 换绑到别的组才当场到位
                    if (header.tag != item.key) {
                        header.tag = item.key
                        syncHeader(header)
                    }
                }
                is TvNativeExploreItem.Row -> {
                    val row = view as TvNativeRowView
                    val previousKey = row.tag as? String
                    if (previousKey != null && previousKey != item.key) rowLeftIndex[previousKey] = row.leftIndex()
                    row.tag = item.key
                    row.animatedScroll = scroll.animated
                    row.cards.setFocusEffectSuppressed(row, focusEffectSuppressed)
                    applyHeldFocus(row)
                    row.cards.listener = cardListener?.invoke(item.key)
                    row.cards.onBind = { index -> onBindCard?.invoke(item.key, index) }
                    if (previousKey != item.key) {
                        row.bind(item.cards, { it.toLong() }, rowLeftIndex[item.key] ?: 0, m.columns)
                    } else if (row.cards.items != item.cards) {
                        // 同一行换内容 (推荐重算落库、行尾「更多」接上新卡): 适配器整体刷新后 leanback 从第 0 张重新排起, 行首与选中的
                        // 那张照原样放回 —— 不然走到行后面时换内容, 行滚回开头, 持焦的卡被甩出屏, 要再按一下方向键才回来
                        row.bind(item.cards, { it.toLong() }, row.leftIndex(), m.columns, focusIndex = row.selectedPosition)
                    }
                }
            }
            onItemsMoved?.invoke()
        }

        override fun onViewRecycled(holder: ViewHolder) {
            val row = holder.itemView as? TvNativeRowView ?: return
            (row.tag as? String)?.let { rowLeftIndex[it] = row.leftIndex() }
        }

        override fun onViewAttachedToWindow(holder: ViewHolder) {
            // 回收缓存里原样拿回同一位置的项不重绑 (见 focusEffectSuppressed)
            when (val view = holder.itemView) {
                is TvNativeRowView -> {
                    view.cards.setFocusEffectSuppressed(view, focusEffectSuppressed)
                    applyHeldFocus(view)
                }

                is TvNativeExploreHeaderView -> syncHeader(view)
            }
        }
    }

    private companion object {
        const val TYPE_SPACER = 0
        const val TYPE_HEADER = 1
        const val TYPE_ROW = 2
    }
}

/**
 * 探索页海报墙的组标题: 一行字. 焦点在它这一组里时全亮, 否则淡到 [idleAlpha] —— 照 tvOS 的货架标题 (焦点所在那一行的标题 100%, 其余 50%);
 * 换组时按卡片聚焦的节奏过渡. hero 态越线的淡化 ([fade]) 与它相乘. 两者都只动视图的透明度 (逐绘制指令乘, 文字不重录).
 */
internal class TvNativeExploreHeaderView(context: Context) : TvNativeTextView(context) {
    var idleAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyAlpha()
        }

    /** hero 态里越过 hero 线的淡化 (0..1, 1 = 不淡), 页面逐帧给. */
    var fade: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyAlpha()
        }

    private var emphasized = false

    /** 全亮的程度 (0 = 淡到 [idleAlpha], 1 = 全亮). */
    private var emphasis = 0f
    private var animator: ValueAnimator? = null

    /** 焦点在不在这一组. [millis] > 0 时过渡这么久; 0 = 当场到位 (在走的过渡也收掉). */
    fun setEmphasized(on: Boolean, millis: Long) {
        if (emphasized == on && (millis > 0L || animator == null)) return
        emphasized = on
        animator?.cancel()
        animator = null
        val target = if (on) 1f else 0f
        if (millis <= 0L) {
            emphasis = target
            applyAlpha()
            return
        }
        animator = ValueAnimator.ofFloat(emphasis, target).apply {
            duration = millis
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                emphasis = it.animatedValue as Float
                applyAlpha()
            }
            start()
        }
    }

    private fun applyAlpha() {
        alpha = (idleAlpha + (1f - idleAlpha) * emphasis) * fade
    }
}

/** 探索页各行共用的卡片回收池容量: 屏上约三行、每行放得下的张数加露一截的那张, 外加预取的一行. */
private const val TV_NATIVE_EXPLORE_CARD_POOL = 40
