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
import kotlin.math.abs

/**
 * 原生横滑行的公共部分 (海报行 [TvNativeRowView]、演职人员的圆头像行 [TvNativeMonogramRowView]; 选集行 [TvNativeEpisodeRowView] 换成固定锚点,
 * 见它的说明): leanback HorizontalGridView, 卡片从行首停靠线
 * [startPx] 排起, 行本身铺满宽度 (行尾露一截下一张). **按需挪** ([tvStripLeftIndex]): FOCUS_SCROLL_ITEM 下焦点在完整露出的卡之间走不滚,
 * 走到伸进行尾留白 [endPx] 的那张才把它挪到留白线上 —— 行尾留白取 "行宽 − 行首 − 整数张卡" 时恰好整行挪一格; 往左同理贴回行首.
 * leanback 不回收持焦的那张, 平滑滚向远处的卡途中焦点不会丢.
 *
 * 左右键行自己走 (同一行上一张 / 下一张), 不交给系统找焦点: 行首按左时 [startLeftExits] 就把新按下的那一下放出去 (按住的连发不出行),
 * 由外面接, 否则吞掉; 行尾按右吞掉; [canMoveTo] 不让去的卡也吞掉. 上下键不管. 行本身不可聚焦, 焦点只落在卡上 (有卡却一张都还没排出来
 * 时被送焦是例外, 见 [onRequestFocusInDescendants]).
 *
 * 长按方向键的连发上限 [repeatMillis] (默认同 tvFocusMoveRateLimit 的横向上限, 高于系统连发: 按住时每一发都走一格).
 *
 * @param stepPx 相邻两张卡左缘的距离 (卡宽 + 列距): 停稳时贴齐卡片列、恢复行首都按它算 (各卡定宽).
 * @param extraLayoutPx 行外左右各多排多宽: 长按连发时下一张总是已经排好, 每一步都经 requestFocus 走行自己的 spring 并接上一段的速度.
 *   没排出来就只能走 leanback 的 setSelectedPositionSmooth —— 它先掐掉在走的 spring, 再按每英寸 25ms 找过去、到了急刹, 长按时看着是
 *   一下平滑一下瞬移. 要多宽看连发多快: 平滑滚动跟不上焦点的那段落差 (临界阻尼 spring 追匀速目标, 落后 2v / ω) 都得排着.
 * @param prefetchItems 被外层列表预取时一起预取几张.
 * @param aheadLayoutPx 行里有焦点之后才在 [extraLayoutPx] 之外再多排的宽度 (单独成行的详情页各行: 建行那一帧只排屏上那几张).
 *   拿到焦点那一下常常是详情页换页的第一帧, 多排的建卡、绑定等 [TV_NATIVE_AHEAD_LAYOUT_DELAY_MILLIS] 再做 (焦点那时还在行里才做);
 *   等不到就在第一次按左右键时当场排, 长按连发总在这之后.
 */
@SuppressLint("ViewConstructor")
abstract class TvNativeStripView(
    context: Context,
    private val stepPx: Int,
    spacingPx: Int,
    private val extraLayoutPx: Int,
    prefetchItems: Int,
    startPx: Int,
    endPx: Int,
    topPx: Int,
    bottomPx: Int,
    private val aheadLayoutPx: Int = 0,
) : HorizontalGridView(context) {
    private val scroll = TvNativeSpringScroll()
    private var lastRepeatMove = 0L
    private var aheadLaidOut = aheadLayoutPx <= 0
    private val layoutAheadWhileFocused = Runnable { if (hasFocus()) layoutAhead() }

    /** 动画滚动 (视觉效果流畅档关: 一步到位). */
    var animatedScroll: Boolean
        get() = scroll.animated
        set(value) {
            scroll.animated = value
        }

    /** 长按方向键时横向最快多久挪一次 (ms). */
    var repeatMillis: Long = TV_NATIVE_HORIZONTAL_REPEAT_MILLIS

    /** 行首按左 (新按下的那一下) 放出去由外面接; false = 吞掉 (行首左边没有目标). */
    var startLeftExits: Boolean = true

    private val itemCount: Int get() = adapter?.itemCount ?: 0

    init {
        clipChildren = false
        clipToPadding = false
        // 行本身不可聚焦 (RecyclerView 默认连触摸模式都可聚焦): 进行时卡给不出去就不进, 焦点不会停在没有聚焦效果的行上
        isFocusable = false
        setPadding(startPx, topPx, endPx, bottomPx)
        horizontalSpacing = spacingPx
        // 受限 API (leanback-grid 1.0.0 仍实现, 之后的版本标成不支持): 按需挪
        @SuppressLint("RestrictedApi")
        val strategy = BaseGridView.FOCUS_SCROLL_ITEM
        setFocusScrollStrategy(strategy)
        setExtraLayoutSpace(extraLayoutPx)
        initialPrefetchItemCount = prefetchItems
        // leanback 默认关掉 GapWorker 预取 (注释说低端芯片上回退); 这里的卡在空闲帧里先建好, 换行那一帧不现建
        layoutManager?.isItemPrefetchEnabled = true
        (layoutManager as? GridLayoutManager)?.setFocusOutAllowed(true, false)
        setSmoothScrollByBehavior(scroll)
        itemAnimator = null
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0) onRowMoved()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_IDLE) {
                    alignWhenIdle()
                    fireSettled()
                }
            }
        })
        addOnLayoutCompletedListener { onRowMoved() }
    }

    /** 停稳时 (以及从回收缓存里拿回来重新上屏时) 把行对齐到合法的停位; 默认贴齐卡片列 ([snapToColumns]). */
    protected open fun alignWhenIdle() = snapToColumns()

    /**
     * 从行外进来时, 已经排出来的落点那张 ([entryIndex]) 能不能直接接焦点. 默认要完整露在行首停靠线与行尾留白之间 (进来不滚); 不行就按
     * leanback 的来.
     */
    protected open fun acceptsEntry(view: View): Boolean = isFullyVisible(view)

    /** 卡片在行里的位置变了 (滚动中每帧、每次布局完成): 按位置算的外观 (越过行首的压暗) 在这里刷新. */
    protected open fun onRowMoved() {}

    /** 焦点能不能挪到第 [index] 张 (左右键). 不能就吞掉这一下. */
    protected open fun canMoveTo(index: Int): Boolean = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 回收缓存里拿回来的行: 离屏那一刻 (RecyclerView 摘下时停掉滚动) 可能停在半路
        post { alignWhenIdle() }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(layoutAheadWhileFocused)
        super.onDetachedFromWindow()
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (!aheadLaidOut) {
            removeCallbacks(layoutAheadWhileFocused)
            postDelayed(layoutAheadWhileFocused, TV_NATIVE_AHEAD_LAYOUT_DELAY_MILLIS)
        }
    }

    /** 两侧多排 [aheadLayoutPx] (见类说明). */
    private fun layoutAhead() {
        if (aheadLaidOut) return
        aheadLaidOut = true
        removeCallbacks(layoutAheadWhileFocused)
        setExtraLayoutSpace(extraLayoutPx + aheadLayoutPx)
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
        if (delta != 0 && abs(delta) < stepPx / 2) scrollBy(delta, 0)
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
     * 适配器换了数据之后定行首与选中 ([count] = 新的张数, [leftIndex] = 回收前 / 跨导航记下的行首, [columns] = 屏上完整放得下几张,
     * [focusIndex] = 选中哪张, 落在屏上那几张里; -1 = 行首那张). 按需挪下 leanback 的布局不滚到选中的那张 (第一次布局从第 0 张排起),
     * 行首由这一次布局完成时按定宽挪到位 (见 [onLayout]).
     */
    protected fun placeAfterSubmit(count: Int, leftIndex: Int, columns: Int, focusIndex: Int) {
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

    /** [placeAfterSubmit] 之后这一次布局完成时要挪到的行首, -1 = 没有. */
    private var pendingLeft = -1

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        restorePendingLeft()
        // 焦点先停在行上等布局 (见 onRequestFocusInDescendants): 排出来了交给选中的那张 (恢复的上次那张), 没排到它就交给行首那张 ——
        // 不让焦点停在没有聚焦效果的行上
        if (isFocused && childCount > 0 && !focusCardIfLaidOut(selectedPosition)) focusCardIfLaidOut(leftIndex())
    }

    /** [placeAfterSubmit] 之后这一次布局完成时挪到记下的行首. */
    private fun restorePendingLeft() {
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
        val dx = first.left - paddingLeft + (left - firstPos) * stepPx
        if (dx != 0) scrollBy(dx, 0)
    }

    /**
     * 从行外进来时的落点 (-1 = 交给 leanback: 按来处的位置挑完整露出的卡里最近的那张). 单独成行时 (详情页、人物预览弹窗) 由调用方给
     * 上次聚焦的那张, 头一回是行首那张 —— 同 Compose 的 TvAnchoredStrip, 落点不随来处的位置变. 两条进行的路都认它:
     * 直接 requestFocus ([onRequestFocusInDescendants]) 与按方向找焦点 ([addFocusables]).
     */
    var entryIndex: () -> Int = { -1 }

    /** 完整露在行首停靠线与行尾留白之间. */
    private fun isFullyVisible(view: View): Boolean =
        view.left >= paddingLeft - 1 && view.right <= width - paddingRight + 1

    /**
     * 按方向找焦点时交出哪些候选: Compose 把焦点按方向送进这个互操作视图时, 在里面用系统 FocusFinder 挑离来处最近的那张 (系统自己的
     * 查找也是这样), 而 leanback 按需挪 (FOCUS_SCROLL_ITEM) 下行里没焦点时会把屏上完整露出的卡全交出去 —— 从右上角的按钮往下按就落到
     * 第四张. 行里还没有焦点时只交出落点那张 ([entryIndex]); 落点给不出 (-1 / 不在屏上) 时照 leanback 的.
     */
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (!hasFocus()) {
            val index = entryIndex()
            val view = if (index >= 0) findViewHolderForAdapterPosition(index)?.itemView else null
            if (view != null && acceptsEntry(view)) {
                view.addFocusables(views, direction, focusableMode)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (childCount == 0 && itemCount > 0) {
            // 有卡但一张都还没排出来 (刚建出来 / 刚换了数据, 如详情页重建时关联条目这一行): 让行自己先接住焦点 (返回 false 后由
            // ViewGroup.requestFocus 落到行本身), 布局完交给卡 (见 onLayout), 卡一拿到焦点行就撤回可聚焦 (见 onFocusChanged).
            // 接不住的话 Compose 那边的送焦当场失败, 焦点被全局兜底塞到页面别处 (详情页: 先跳回首屏, 布局完再被挂着的请求拉回来)
            isFocusableInTouchMode = true
            return false
        }
        val index = entryIndex()
        if (index >= 0) {
            val view = findViewHolderForAdapterPosition(index)?.itemView
            // 还完整露在行首停靠线与行尾留白之间才给它 (滚出去了就按 leanback 的, 进来不滚; 见 acceptsEntry)
            if (view != null && acceptsEntry(view) && view.requestFocus(direction, previouslyFocusedRect)) {
                return true
            }
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // 行自己接住的焦点走了 (交到卡上 / 被按到别处): 撤回可聚焦, 行只在"等布局"那一段可聚焦 (见 onRequestFocusInDescendants)
        if (!gainFocus && isFocusable) isFocusable = false
    }

    /** 第 [index] 张已经排出来就送焦 (在行首停靠线外露一截的也算: 拿到焦点时按需挪把它滚进来), 返回是否送上. */
    fun focusCardIfLaidOut(index: Int): Boolean = findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() == true

    /**
     * 行已排好、第 [index] 张在屏外 (没排出来) 时平滑滚过去, 停下后回调 [onSettled] (由调用方再送焦; 行有焦点时 leanback 到位也会
     * 自己送). 不能直接改选中位置: leanback 会按此刻持焦的那张重排, 行只挪一截、焦点留在原处.
     */
    fun scrollToCardSmooth(index: Int, onSettled: () -> Unit) {
        val count = itemCount
        if (count == 0) return
        settledCallback = onSettled
        setSelectedPositionSmooth(index.coerceIn(0, count - 1))
    }

    /** 行刚绑定、还没排过时选中第 [index] 张: 第一次布局就排在那里. */
    fun selectCard(index: Int) {
        val count = itemCount
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
        // 长按连发要两侧排好 (见 aheadLayoutPx): 还没到延时就在第一次按左右键时排
        layoutAhead()
        val index = getChildAdapterPosition(focused)
        // 焦点所在的卡正被换掉 (数据刷新途中): 这一下吞掉, 不交给系统找焦点
        if (index == NO_POSITION) return true
        if (event.repeatCount > 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatMove < repeatMillis) return true
            lastRepeatMove = now
        }
        val target = index + if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        // 行首按左: 新按下的那一下放出去 (按住的连发不出行), 行尾按右吞掉
        if (target < 0) return !startLeftExits || event.repeatCount > 0
        if (target >= itemCount || !canMoveTo(target)) return true
        val view = findViewHolderForAdapterPosition(target)?.itemView
        // 没排出来 (连按超前): 平滑滚过去, 到位时 leanback 把焦点给它 (行有焦点)
        if (view == null || !view.requestFocus()) setSelectedPositionSmooth(target)
        return true
    }
}

/** 行里有焦点之后等这么久再两侧多排 (见 [TvNativeStripView] 的 aheadLayoutPx): 比详情页换页过渡 (360ms) 略长, 建卡、绑定落在过渡之后. */
internal const val TV_NATIVE_AHEAD_LAYOUT_DELAY_MILLIS = 450L

/**
 * 长按左右键时焦点最多领先平滑滚动几张 (向上取整): 横滑行在行外 (或行里有焦点之后) 多排这么多张, 每一发连发的目标卡才总是已经排好
 * (见 [TvNativeStripView] 的 extraLayoutPx). 滚动 spring (刚度 TV_SCROLL_STIFFNESS = 260, 临界阻尼, ω ≈ 16 / 秒) 追匀速前进的焦点
 * 落后 2v / ω, 系统连发约 20 张 / 秒时约两张半.
 */
internal const val TV_NATIVE_STRIP_HOLD_LEAD_CARDS = 3
