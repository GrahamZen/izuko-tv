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
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.GridLayoutManager
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PAST_DIM_ALPHA
import kotlin.math.abs

/**
 * 原生海报墙的一条横滑行 (探索页的每一行、详情页的关联条目): leanback HorizontalGridView, 卡片从行首停靠线 [startPx] 排起, 行本身
 * 铺满宽度 (行尾第 N+1 张露一截). **按需挪** ([tvStripLeftIndex]): FOCUS_SCROLL_ITEM 下焦点在完整露出的卡之间走不滚,
 * 走到伸进行尾留白 [endPx] 的那张才把它挪到留白线上 —— 行尾留白取 "屏宽 − 行首 − 整数张卡" 时恰好整行挪一格; 往左同理贴回行首.
 * leanback 不回收持焦的那张, 平滑滚向远处的卡途中焦点不会丢.
 *
 * 左右键行自己走 (同一行上一张 / 下一张), 不交给系统找焦点: 行首按左 (只认新按下的那一下) 放出去, 由外面接 (详情页交给页面, 探索页的
 * 按键在 TvNativeExploreView 里就接住了, 到不了这里); 行尾按右吞掉. 上下键不管. 行本身不可聚焦, 焦点只落在卡上.
 *
 * 滑过行首的卡压暗着出屏 (越线 [fadeDistancePx] 内压到 [TV_CARD_PAST_DIM_ALPHA], 同原 hero 页的横滑行). 长按方向键的
 * 连发限速同 tvFocusMoveRateLimit (横向 8 次 / 秒).
 */
@SuppressLint("ViewConstructor")
class TvNativeRowView(
    context: Context,
    val style: TvNativeWallStyle,
    sketch: Sketch,
    pool: RecyclerView.RecycledViewPool?,
    startPx: Int,
    endPx: Int,
    bottomPx: Int = 0,
    private val fadeDistancePx: Float,
    topPx: Int = 0,
) : HorizontalGridView(context) {
    val cards = TvNativeCardAdapter(style, sketch)
    private val scroll = TvNativeSpringScroll()
    private var lastRepeatMove = 0L

    /** 动画滚动 (视觉效果流畅档关: 一步到位). */
    var animatedScroll: Boolean
        get() = scroll.animated
        set(value) {
            scroll.animated = value
        }

    /** 整行的淡化 (hero 态越线淡没, 0..1), 与越过行首的压暗相乘. */
    var rowAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            cards.refreshDim(this)
        }

    init {
        clipChildren = false
        clipToPadding = false
        // 行本身不可聚焦 (RecyclerView 默认连触摸模式都可聚焦): 进行时卡给不出去就不进, 焦点不会停在没有聚焦效果的行上
        isFocusable = false
        setPadding(startPx, topPx, endPx, bottomPx)
        horizontalSpacing = style.columnSpacingPx
        // 受限 API (leanback-grid 1.0.0 仍实现, 之后的版本标成不支持): 按需挪
        @SuppressLint("RestrictedApi")
        val strategy = BaseGridView.FOCUS_SCROLL_ITEM
        setFocusScrollStrategy(strategy)
        pool?.let { setRecycledViewPool(it) }
        initialPrefetchItemCount = style.prefetchItems
        // leanback 默认关掉 GapWorker 预取 (注释说低端芯片上回退); 这里的卡在空闲帧里先建好, 换行那一帧不现建
        layoutManager?.isItemPrefetchEnabled = true
        (layoutManager as? GridLayoutManager)?.setFocusOutAllowed(true, false)
        setSmoothScrollByBehavior(scroll)
        itemAnimator = null
        cards.dimOf = { _, view -> pastStartDim(view) * rowAlpha }
        adapter = cards
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0) cards.refreshDim(this@TvNativeRowView)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_IDLE) {
                    snapToColumns()
                    fireSettled()
                }
            }
        })
        addOnLayoutCompletedListener { cards.refreshDim(this) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 回收缓存里拿回来的行: 离屏那一刻 (RecyclerView 摘下时停掉滚动) 可能停在半路
        post { snapToColumns() }
    }

    /**
     * 停稳时贴齐卡片列: 合法的停位都是某张卡正好贴在行首停靠线上 (行尾留白按整数张卡取, 挪到底也一样). 平滑滚动半路被打断 (整行滚出屏时
     * RecyclerView 当场停住) 会停在两列之间, 按需挪之后不会自己对回 —— 卡片歪一截, 按行首算的落点也跟着错.
     */
    private fun snapToColumns() {
        if (!isAttachedToWindow || scrollState != SCROLL_STATE_IDLE || childCount == 0) return
        var delta = Int.MAX_VALUE
        for (i in 0 until childCount) {
            val d = getChildAt(i).left - paddingLeft
            if (abs(d) < abs(delta)) delta = d
        }
        if (delta != 0 && abs(delta) < (style.cardWidthPx + style.columnSpacingPx) / 2) scrollBy(delta, 0)
    }

    /** 行首在第几张 (第一张完整露在行首停靠线右边的卡), 回收重绑时用来恢复. */
    fun leftIndex(): Int {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.left >= paddingLeft - 1) return getChildAdapterPosition(child).coerceAtLeast(0)
        }
        return 0
    }

    /**
     * 换一行的数据 ([leftIndex] = 回收前 / 跨导航记下的行首, [columns] = 屏上完整放得下几张, [focusIndex] = 选中哪张, 落在屏上那几张里;
     * -1 = 行首那张). 按需挪下 leanback 的布局不滚到选中的那张 (第一次布局从第 0 张排起), 行首由这一次布局完成时按定宽挪到位 (见 [onLayout]).
     */
    fun bind(cards: List<TvNativeCard?>, keyOf: (Int) -> Long, leftIndex: Int, columns: Int, focusIndex: Int = -1) {
        this.cards.submit(cards, keyOf)
        val count = cards.size
        if (count == 0) {
            pendingLeft = -1
            return
        }
        // 挪到底时最后一张贴在行尾留白线上: 行首最多到 count - columns
        val left = leftIndex.coerceIn(0, (count - columns).coerceAtLeast(0))
        val lastOnScreen = (left + columns - 1).coerceAtMost(count - 1)
        selectedPosition = if (focusIndex >= 0) focusIndex.coerceIn(left, lastOnScreen) else left
        pendingLeft = left
        // 内容没变时适配器不刷新、不会重排: 保证这一次就挪到位, 不拖到以后某次布局才跳
        requestLayout()
    }

    /** [bind] 之后这一次布局完成时要挪到的行首, -1 = 没有. */
    private var pendingLeft = -1

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val left = pendingLeft
        if (left < 0 || childCount == 0) return
        pendingLeft = -1
        // 各卡定宽: 由排出来的第一张推出行首那张此刻离停靠线多远, 一次挪到位 (还在这一次布局里, 第一帧就是对的)
        var first: View? = null
        var firstPos = Int.MAX_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val pos = getChildAdapterPosition(child)
            if (pos in 0 until firstPos) {
                first = child
                firstPos = pos
            }
        }
        if (first == null) return
        val dx = first.left - paddingLeft + (left - firstPos) * (style.cardWidthPx + style.columnSpacingPx)
        if (dx != 0) scrollBy(dx, 0)
    }

    /**
     * 从行外进来时的落点 (-1 = 交给 leanback: 按需挪下是第一张完整露出的). 单独成行时 (详情页关联条目) 由调用方给上次聚焦的那张.
     */
    var entryIndex: () -> Int = { -1 }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val index = entryIndex()
        if (index >= 0) {
            val view = findViewHolderForAdapterPosition(index)?.itemView
            // 还完整露在行首停靠线与行尾留白之间才给它 (滚出去了就按 leanback 的, 进来不滚)
            if (view != null && view.left >= paddingLeft - 1 && view.right <= width - paddingRight + 1 &&
                view.requestFocus(direction, previouslyFocusedRect)
            ) {
                return true
            }
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    /** 第 [index] 张已经排出来就送焦 (在行首停靠线外露一截的也算: 拿到焦点时按需挪把它滚进来), 返回是否送上. */
    fun focusCardIfLaidOut(index: Int): Boolean = findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() == true

    /**
     * 行已排好、第 [index] 张在屏外 (没排出来) 时平滑滚过去, 停下后回调 [onSettled] (由调用方再送焦; 行有焦点时 leanback 到位也会
     * 自己送). 不能直接改选中位置: leanback 会按此刻持焦的那张重排, 行只挪一截、焦点留在原处.
     */
    fun scrollToCardSmooth(index: Int, onSettled: () -> Unit) {
        val count = cards.itemCount
        if (count == 0) return
        settledCallback = onSettled
        setSelectedPositionSmooth(index.coerceIn(0, count - 1))
    }

    /** 行刚绑定、还没排过时选中第 [index] 张: 第一次布局就排在那里. */
    fun selectCard(index: Int) {
        val count = cards.itemCount
        if (count == 0) return
        selectedPosition = index.coerceIn(0, count - 1)
    }

    private var settledCallback: (() -> Unit)? = null

    private fun fireSettled() {
        val callback = settledCallback ?: return
        settledCallback = null
        callback()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN || (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT)) {
            return super.dispatchKeyEvent(event)
        }
        val focused = focusedChild ?: return super.dispatchKeyEvent(event)
        val index = getChildAdapterPosition(focused)
        // 焦点所在的卡正被换掉 (数据刷新途中): 这一下吞掉, 不交给系统找焦点
        if (index == NO_POSITION) return true
        if (event.repeatCount > 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatMove < TV_NATIVE_HORIZONTAL_REPEAT_MILLIS) return true
            lastRepeatMove = now
        }
        val target = index + if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        // 行首按左: 新按下的那一下放出去 (按住的连发不出行), 行尾按右吞掉
        if (target < 0) return event.repeatCount > 0
        if (target >= cards.itemCount) return true
        val view = findViewHolderForAdapterPosition(target)?.itemView
        // 没排出来 (连按超前): 平滑滚过去, 到位时 leanback 把焦点给它 (行有焦点)
        if (view == null || !view.requestFocus()) setSelectedPositionSmooth(target)
        return true
    }

    private fun pastStartDim(view: View): Float {
        val x = view.left - paddingLeft
        if (x >= 0) return 1f
        val t = (-x / fadeDistancePx).coerceIn(0f, 1f)
        return 1f - t * (1f - TV_CARD_PAST_DIM_ALPHA)
    }
}

/** 长按方向键时横向最快多久挪一次 (同 Compose 版 tvFocusMoveRateLimit: 8 次 / 秒). */
internal const val TV_NATIVE_HORIZONTAL_REPEAT_MILLIS = 125L

/** 纵向 (同上: 6 次 / 秒). */
internal const val TV_NATIVE_VERTICAL_REPEAT_MILLIS = 166L

/**
 * 横滑行按需挪: 焦点走到第 [target] 张 (整行 [count] 张, 屏上完整放得下 [columns] 张) 后, 行首该是第几张. 在屏上完整露出的
 * 几张之间走时不动; 走到右边露一截的那张, 整行往左挪到它刚好完整露出 (贴右), 往左同理贴左. 聚焦卡落在屏上第
 * `target - 行首` 列.
 *
 * @param current 现在的行首.
 */
internal fun tvStripLeftIndex(current: Int, target: Int, count: Int, columns: Int): Int {
    val maxLeft = (count - columns).coerceAtLeast(0)
    var left = current.coerceIn(0, maxLeft)
    if (target < left) left = target
    if (target > left + columns - 1) left = target - columns + 1
    return left.coerceIn(0, maxLeft)
}
