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
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import kotlin.math.abs

/** 货架页 ([TvNativeShelfView]) 的事件 (页面实现). 行键是页面给的 [TvNativeExploreItem.Row.key]. */
interface TvNativeShelfListener {
    fun onCardFocused(rowKey: String, index: Int)

    fun onCardClick(rowKey: String, index: Int)

    fun onCardLongPress(rowKey: String, index: Int, anchor: Rect)

    /** 首行按上: 返回 true = 页面接走了焦点 (送回顶上的标签行). */
    fun onTopRowUp(): Boolean

    /** 行首按左出页面 (进侧边栏). */
    fun onExitLeft()

    /** 焦点进出卡片区. */
    fun onCardAreaFocusChanged(hasFocus: Boolean)

    /** 列表在不在滚 (见 TvScrollActivity). */
    fun onScrollingChanged(scrolling: Boolean)
}

/**
 * 货架页: 一组一行的「海报 + 番名」横滑行, 组间一行标题 (追番页的「追更」). 列表、横滑行、组标题与探索页海报墙同一套 ([TvNativeExploreList]),
 * 几何也同一份 ([TvNativeExploreMetrics], 轮播与 hero 那几项不用); 没有轮播、没有 hero 态, 卡片上按确定直接交给页面.
 *
 * 焦点规则同探索页: 上下键落到上一行 / 下一行屏上同一列那张 (行短了落最后一张), 首行按上交给页面 ([TvNativeShelfListener.onTopRowUp]),
 * 末行按下吞掉; 行内按需挪, 行首按左出页面, 行尾按右吞掉; 聚焦行停在视口垂直正中. 长按方向键的连发限速同探索页.
 * 程序化落点 ([focusCard]) 目标没排出来时挂着, 排出来再送; 远跳 (返回键回首卡) 途中焦点停放在本视图上, 方向键当场落地, 确认键排队到落地.
 */
@SuppressLint("ViewConstructor")
class TvNativeShelfView(
    context: Context,
    sketch: Sketch,
    style: TvNativeWallStyle,
    metrics: TvNativeExploreMetrics,
    headerStyle: TvNativeTextStyle,
) : FrameLayout(context) {
    var listener: TvNativeShelfListener? = null

    var metrics: TvNativeExploreMetrics = metrics
        private set

    /** 视觉效果: 动画滚动 (流畅档一步到位) 与组标题亮度的过渡. */
    var animatedScroll: Boolean = true
        set(value) {
            field = value
            list.scroll.animated = value
        }
    var transitions: Boolean = true

    /** 焦点不在那一组时组标题的不透明度. */
    var headerIdleAlpha: Float
        get() = list.headerIdleAlpha
        set(value) {
            list.headerIdleAlpha = value
        }

    private val list = TvNativeExploreList(context, sketch, style, metrics, headerStyle)
    private val scrollTracker = TvNativeScrollTracker { listener?.onScrollingChanged(it) }

    // ---- 焦点簿记 ----
    /** 上次聚焦的行 (null = 从未进过卡片区). 焦点去别处时不清. */
    var focusedRowKey: String? = null
        private set
    var focusedCardIndex: Int = 0
        private set
    private var focusedColumn = 0

    /** 焦点此刻在卡片区. */
    var cardAreaHasFocus: Boolean = false
        private set

    /** 还没送出去的落点 (目标行 / 卡还没排出来): 行键, 下标 (-1 = 屏上第 [pendingColumn] 列; 两者都没有 = 这一行上次停的那张). */
    private var pendingRow: String? = null
    private var pendingIndex = -1
    private var pendingColumn = -1
    private var pendingSmooth = false
    private var pendingInstant = false
    private var pendingScrollPx = -1

    /** 远跳 (见 [focusCard]) 的目标行; null = 没在远跳. */
    private var farJumpRow: String? = null
    private var farJumpAlongRow = false
    private var farJumpConfirmQueued = false
    private var clickOnLandingRow: String? = null
    private var landingSince = 0L
    private var lastVerticalRepeat = 0L
    private var lastHorizontalRepeat = 0L

    /** 各行上次停的那张 (从行外进来时落在它上面), 按行键. */
    private val rowFocusedIndex = HashMap<String, Int>()

    init {
        clipChildren = false
        clipToPadding = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isFocusable = false
        addView(list)
        list.cardListener = { rowKey -> RowListener(rowKey) }
        list.onItemsMoved = { onListMoved() }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                scrollTracker.onStateChanged(this@TvNativeShelfView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) finishFarJump()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scrollTracker.onMoved(dy)
            }
        })
    }

    // ------------------------------------------------------------------
    // 页面给的数据
    // ------------------------------------------------------------------

    fun update(style: TvNativeWallStyle, metrics: TvNativeExploreMetrics) {
        val changed = this.metrics != metrics
        this.metrics = metrics
        list.update(style, metrics)
        if (changed) requestLayout()
    }

    /**
     * 换数据. 聚焦那一行整个没了 (组里最后一部走了) 时焦点交给顶上来的下一行同一列, 下面没有就上一行; 行还在而聚焦那一格换成了别的作品
     * (卡按下标绑定, 持焦的卡原地重绑、不会再报聚焦) 时补报一次 —— 同探索页 (见 TvNativeExploreView.setItems).
     */
    fun setItems(items: List<TvNativeExploreItem>) {
        val oldItems = list.items
        val structural = items.size != oldItems.size || items.indices.any { items[it].key != oldItems[it].key }
        val focusedRow = focusedRowKey?.takeIf { cardAreaHasFocus && farJumpRow == null }
        val focusedSubject = focusedRow?.let { tvNativeRowCards(oldItems, it)?.getOrNull(focusedCardIndex)?.subjectId }
        list.submit(items)
        if (pendingScrollPx >= 0 && items.isNotEmpty()) {
            val px = pendingScrollPx
            pendingScrollPx = -1
            list.jumpTo(px)
        } else if (structural) {
            realignFocusedRow()
        }
        if (focusedRow != null) followFocusAfterItemsChanged(oldItems, focusedRow, focusedSubject)
        post { resolvePending(scrollIfMissing = true) }
    }

    private fun followFocusAfterItemsChanged(oldItems: List<TvNativeExploreItem>, rowKey: String, subjectId: Int?) {
        if (list.indexOfKey(rowKey) < 0) {
            // 等这一趟布局排完再送 (排版前各项位置无效), 由 setItems 末尾的 resolvePending 送; 一行都不剩时交给页面 (回标签行)
            val target = tvNativeReplacementRow(oldItems, list.items, rowKey)
            if (target != null) {
                holdCardAtColumn(target, focusedColumn)
            } else {
                post { if (focusedRowKey == rowKey && !cardAreaHasFocus) listener?.onTopRowUp() }
            }
            return
        }
        val now = tvNativeRowCards(list.items, rowKey)?.getOrNull(focusedCardIndex)?.subjectId
        if (now == null || now == subjectId) return
        val index = focusedCardIndex
        post {
            if (cardAreaHasFocus && focusedRowKey == rowKey && focusedCardIndex == index) listener?.onCardFocused(rowKey, index)
        }
    }

    /** 进页恢复: 列表停位、各行行首与上次停的那张、焦点簿记. [holdLook] = 返回本页时焦点要回那一张: 从排出来的第一帧起按住它的聚焦态. */
    fun restore(
        scrollPx: Int,
        rowLeftIndex: Map<String, Int>,
        rowFocusedIndex: Map<String, Int>,
        focusedRowKey: String?,
        focusedCardIndex: Int,
        holdLook: Boolean,
    ) {
        list.rowLeftIndex.putAll(rowLeftIndex)
        this.rowFocusedIndex.putAll(rowFocusedIndex)
        this.focusedRowKey = focusedRowKey
        this.focusedCardIndex = focusedCardIndex
        if (holdLook && focusedRowKey != null) {
            list.heldFocus = focusedRowKey to rememberedIndex(focusedRowKey, list.rowLeftIndex[focusedRowKey] ?: 0)
        }
        if (list.items.isNotEmpty()) list.jumpTo(scrollPx) else pendingScrollPx = scrollPx
    }

    val scrolledPx: Int get() = list.scrolledPx()
    val rowLeftIndex: Map<String, Int> get() = list.rowLeftIndex
    val rowFocusedIndexMap: Map<String, Int> get() = rowFocusedIndex

    /** 第一行的行键; 没有行时 null. */
    val firstRowKey: String? get() = list.items.firstOrNull { it is TvNativeExploreItem.Row }?.key

    /** 程序化落点还挂着 (目标没排出来 / 远跳途中). */
    val focusPending: Boolean get() = pendingRow != null || farJumpRow != null

    // ------------------------------------------------------------------
    // 焦点命令 (页面发)
    // ------------------------------------------------------------------

    /**
     * 送焦到 [rowKey] 行的第 [index] 张 (-1 = 这一行上次停的那张). [far] = 远跳 (返回键回首卡): 目标没排出来时列表按 [TvScrollSpring.Far]
     * 滚过去, 途中焦点停放在本视图上, 按方向键当场落地、按确认排队; 目标已排出来时同按上下键当场送焦. 目标行还没排出来就记下, 排出来时再送.
     */
    fun focusCard(rowKey: String, index: Int, far: Boolean = false): Boolean {
        farJumpConfirmQueued = false
        clickOnLandingRow = null
        farJumpAlongRow = false
        pendingInstant = false
        val item = list.indexOfKey(rowKey)
        pendingRow = rowKey
        pendingIndex = index
        pendingColumn = -1
        pendingSmooth = false
        if (!far && !cardAreaHasFocus) holdLandingLook(rowKey, index)
        if (item < 0 || !isLaidOut) return false
        if (far && !targetLaidOut(rowKey, item)) {
            farJumpRow = rowKey
            list.focusEffectSuppressed = true
            parkFocus()
            val stop = metrics.centeredScroll(list.items, item)
            val near = abs(stop - list.scrolledPx()) <= TV_NATIVE_SHELF_NEAR_JUMP_ROWS * (metrics.rowPx + metrics.headerPx)
            list.scrollToStop(stop, if (near) TvScrollSpring.Step else TvScrollSpring.Far, animatedScroll)
            if (list.scrollState == RecyclerView.SCROLL_STATE_IDLE) finishFarJump()
            return true
        }
        return resolvePending(scrollIfMissing = true)
    }

    /** 远跳还在滚时又按了返回: 当场落到目标 (返回 true); 没在远跳返回 false. */
    fun landFarJump(): Boolean {
        if (farJumpRow == null) return false
        landFarJumpNow()
        return true
    }

    private fun targetLaidOut(rowKey: String, item: Int): Boolean {
        val row = list.rowAt(item) ?: return false
        val count = row.cards.itemCount
        if (count == 0) return false
        val left = list.rowLeftIndex[rowKey] ?: row.leftIndex()
        return row.findViewHolderForAdapterPosition(pendingTarget(rowKey, left, count)) != null
    }

    /** 焦点从本页以外送进来而目标卡还没排出来: 先按住它的聚焦态, 排出来的第一帧就是放大的 (见 [TvNativeExploreList.heldFocus]). */
    private fun holdLandingLook(rowKey: String, index: Int) {
        val item = list.indexOfKey(rowKey)
        val row = if (item >= 0) list.rowAt(item) else null
        val left = list.rowLeftIndex[rowKey] ?: row?.leftIndex() ?: 0
        val target = if (index >= 0) index else rememberedIndex(rowKey, left)
        if (row?.findViewHolderForAdapterPosition(target) != null) return
        list.heldFocus = rowKey to target
    }

    private fun cancelPending() {
        pendingRow = null
        pendingInstant = false
        farJumpConfirmQueued = false
        clickOnLandingRow = null
        farJumpAlongRow = false
        if (farJumpRow != null) {
            farJumpRow = null
            list.focusEffectSuppressed = false
        }
    }

    /** 远跳途中把焦点停在本视图上: 出发那张卡随整行滚出屏被回收, 焦点就丢了 (见 TvNativeExploreView.parkFocus). */
    private fun parkFocus() {
        val focused = findFocus() ?: return
        if (focused === this) return
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        isFocusableInTouchMode = true
        if (!requestFocus()) unparkFocus()
    }

    private fun unparkFocus() {
        if (!isFocusable) return
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        if (!isFocused) isFocusable = false
    }

    private fun landFarJumpNow() {
        val rowKey = farJumpRow ?: return
        farJumpRow = null
        farJumpAlongRow = false
        farJumpConfirmQueued = false
        list.focusEffectSuppressed = false
        val item = list.indexOfKey(rowKey)
        if (item >= 0) list.jumpTo(metrics.centeredScroll(list.items, item))
        pendingRow = rowKey
        pendingSmooth = false
        pendingInstant = true
        landingSince = SystemClock.uptimeMillis()
        list.post { resolvePending(scrollIfMissing = false) }
    }

    /** 把挂着的落点送出去; 送出去了返回 true. 目标行还没排出来时 [scrollIfMissing] = true 就滚到它的停位让它排出来. */
    private fun resolvePending(scrollIfMissing: Boolean): Boolean {
        val rowKey = pendingRow ?: return false
        if (farJumpRow != null) return false
        val item = list.indexOfKey(rowKey)
        if (item < 0) return false
        val row = list.rowAt(item)
        if (row == null) {
            if (scrollIfMissing) list.scrollToStop(metrics.centeredScroll(list.items, item), TvScrollSpring.Step, animatedScroll)
            return false
        }
        val count = row.cards.itemCount
        if (count == 0) return false
        val left = list.rowLeftIndex[rowKey] ?: row.leftIndex()
        val target = pendingTarget(rowKey, left, count)
        pendingRow = null
        if (row.findViewHolderForAdapterPosition(target)?.itemView?.isFocused == true) {
            onCardFocused(rowKey, target)
            return true
        }
        if (row.focusCardIfLaidOut(target)) return true
        pendingRow = rowKey
        pendingIndex = target
        pendingColumn = -1
        when {
            pendingSmooth -> Unit
            pendingInstant && row.childCount > 0 -> {
                pendingSmooth = true
                row.jumpToCard(target, metrics.columns.coerceAtLeast(1)) { if (pendingRow == rowKey) resolvePending(scrollIfMissing = false) }
            }

            row.childCount > 0 -> {
                pendingSmooth = true
                row.scrollToCardSmooth(target) { if (pendingRow == rowKey) resolvePending(scrollIfMissing = false) }
            }

            else -> {
                row.selectCard(target)
                row.post { resolvePending(scrollIfMissing = false) }
            }
        }
        return false
    }

    private fun rememberedIndex(rowKey: String, left: Int): Int = rowFocusedIndex[rowKey] ?: left

    private fun pendingTarget(rowKey: String, left: Int, count: Int): Int = when {
        pendingIndex >= 0 -> pendingIndex
        pendingColumn >= 0 -> left + pendingColumn
        else -> rememberedIndex(rowKey, left)
    }.coerceIn(0, count - 1)

    private fun holdCardAtColumn(rowKey: String, column: Int) {
        pendingRow = rowKey
        pendingIndex = -1
        pendingColumn = column
        pendingSmooth = false
    }

    /** 远跳的列表停下了: 目标卡还没排出来就让那一行接着平滑滚过去 (仍算远跳), 行停下再落地. */
    private fun finishFarJump() {
        val rowKey = farJumpRow ?: return
        if (farJumpAlongRow) return
        val item = list.indexOfKey(rowKey)
        val row = if (item >= 0) list.rowAt(item) else null
        val count = row?.cards?.itemCount ?: 0
        if (row != null && row.childCount > 0 && count > 0) {
            val left = list.rowLeftIndex[rowKey] ?: row.leftIndex()
            val target = pendingTarget(rowKey, left, count)
            if (row.findViewHolderForAdapterPosition(target) == null) {
                farJumpAlongRow = true
                row.scrollToCardSmooth(target) { if (farJumpRow == rowKey) landAfterFarJump() }
                return
            }
        }
        landAfterFarJump()
    }

    private fun landAfterFarJump() {
        val rowKey = farJumpRow ?: return
        farJumpRow = null
        farJumpAlongRow = false
        if (farJumpConfirmQueued) {
            farJumpConfirmQueued = false
            clickOnLandingRow = rowKey
        }
        list.focusEffectSuppressed = false
        pendingRow = rowKey
        pendingSmooth = false
        resolvePending(scrollIfMissing = true)
    }

    // ------------------------------------------------------------------
    // 按键
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        TvNativeFocusCues.onKey(this, event)
        // 远跳途中的确认键作用在目标上: 记下 (按下与抬起都吞掉), 落到目标时点它
        if (farJumpRow != null && tvNativeIsConfirmKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) farJumpConfirmQueued = true
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && handleKeyDown(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleKeyDown(event: KeyEvent): Boolean {
        val code = event.keyCode
        val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        val horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!vertical && !horizontal) return false
        if (farJumpRow != null) {
            landFarJumpNow()
            return true
        }
        if (isFocused && descendantFocusability == FOCUS_BEFORE_DESCENDANTS) {
            // 焦点还停放在本视图上 (远跳刚当场落地、落点要等这一趟布局): 紧跟着的按键吞掉, 等太久没落地就按挂着的落点重送
            val pending = pendingRow
            if (pending != null) {
                if (SystemClock.uptimeMillis() - landingSince > TV_NATIVE_SHELF_LANDING_STALE_MILLIS) focusCard(pending, pendingIndex)
                return true
            }
            val row = focusedRowKey
            if (row != null) {
                focusCard(row, -1)
                return true
            }
            unparkFocus()
        }
        if (event.repeatCount == 0) cancelPending()
        val rowKey = focusedRowKey
        if (!cardAreaHasFocus || rowKey == null) return false
        if (vertical) {
            if (event.repeatCount > 0) {
                // 按住连发时上一格还没落地就先不往下排 (见 TvNativeExploreView.handleKeyDown)
                if (pendingRow != null) return true
                val now = SystemClock.uptimeMillis()
                if (now - lastVerticalRepeat < TV_NATIVE_VERTICAL_REPEAT_MILLIS) return true
                lastVerticalRepeat = now
            }
            navigateVertical(rowKey, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            return true
        }
        if (event.repeatCount > 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastHorizontalRepeat < TV_NATIVE_HORIZONTAL_REPEAT_MILLIS) return true
            lastHorizontalRepeat = now
        }
        navigateHorizontal(rowKey, if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
        return true
    }

    /** 左右键: 同一行的上一张 / 下一张; 行首按左出页面 (焦点真落到行首那张之后才出), 行尾按右吞掉. */
    private fun navigateHorizontal(rowKey: String, direction: Int) {
        val pending = pendingRow?.takeIf { pendingIndex >= 0 }
        val fromKey = pending ?: rowKey
        val from = if (pending != null) pendingIndex else focusedCardIndex
        val item = list.indexOfKey(fromKey)
        val row = (if (item >= 0) list.rowAt(item) else null) ?: return
        val target = from + direction
        if (target < 0) {
            if (pending == null) listener?.onExitLeft()
            return
        }
        if (target >= row.cards.itemCount) return
        pendingRow = fromKey
        pendingIndex = target
        pendingColumn = -1
        pendingSmooth = false
        resolvePending(scrollIfMissing = false)
    }

    /** 上下键: 落到上一行 / 下一行屏上同一列那张; 首行按上交给页面; 末行按下吞掉. 上一次的落点还在路上时从它接着算. */
    private fun navigateVertical(rowKey: String, direction: Int) {
        val keys = list.items.filterIsInstance<TvNativeExploreItem.Row>().map { it.key }
        val fromKey = pendingRow ?: rowKey
        val row = keys.indexOf(fromKey)
        if (row < 0) return
        val column = if (pendingRow != null && pendingColumn >= 0) pendingColumn else focusedColumn
        if (direction < 0 && row == 0) {
            if (listener?.onTopRowUp() == true) cancelPending()
            return
        }
        val target = row + direction
        if (target !in keys.indices) return
        holdCardAtColumn(keys[target], column)
        resolvePending(scrollIfMissing = true)
    }

    /**
     * 卡片排出来时不往上报"有可聚焦的视图了": 窗口没焦点时系统会把焦点直接塞给第一个报上来的卡 (ViewRootImpl.focusableViewAvailable),
     * 抢在页面自己的落点之前 (见 TvNativeExploreView 同名方法).
     */
    override fun focusableViewAvailable(v: View?) = Unit

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        // 有挂着的落点 (换数据时聚焦那一行没了, 持焦的卡随之被拆, 系统从窗口根上重新送焦走到这里): 照原定的落点送, 不改成上次那张
        val pending = pendingRow
        if (pending != null && list.indexOfKey(pending) >= 0) {
            resolvePending(scrollIfMissing = true)
            return findFocus() != null || pendingRow != null
        }
        // 从页面外进来 (标签行按下 / 侧边栏按右 / 返回本页): 回上次那张卡, 没进过就是第一行
        val rowKey = focusedRowKey?.takeIf { list.indexOfKey(it) >= 0 } ?: firstRowKey ?: return false
        focusCard(rowKey, -1)
        return findFocus() != null || pendingRow != null
    }

    // ------------------------------------------------------------------
    // 焦点回调
    // ------------------------------------------------------------------

    private inner class RowListener(private val rowKey: String) : TvNativeCardListener {
        override fun onFocused(index: Int) = onCardFocused(rowKey, index)

        override fun onClick(index: Int) {
            // 进播放页: 焦点交出去之后这张卡仍画成聚焦态, 返回后焦点交还前也不缩
            list.heldFocus = rowKey to index
            listener?.onCardClick(rowKey, index)
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            listener?.onCardLongPress(rowKey, index, anchor)
        }
    }

    private fun onCardFocused(rowKey: String, index: Int) {
        if (landedElsewhere(rowKey, index)) {
            // 焦点不是按挂着的落点来的 (换数据时持焦的卡被拆, 列表自己恢复焦点落到了顶上来的那一行的随便哪张): 挂着的落点照送
            post { resolvePending(scrollIfMissing = true) }
            return
        }
        list.heldFocus = null
        val item = list.indexOfKey(rowKey)
        val row = if (item >= 0) list.rowAt(item) else null
        val columns = metrics.columns.coerceAtLeast(1)
        val current = list.rowLeftIndex[rowKey] ?: row?.leftIndex() ?: 0
        val count = (list.items.getOrNull(item) as? TvNativeExploreItem.Row)?.cards?.size ?: (index + 1)
        val left = tvStripLeftIndex(current, index, count, columns)
        list.rowLeftIndex[rowKey] = left
        rowFocusedIndex[rowKey] = index
        focusedRowKey = rowKey
        focusedCardIndex = index
        focusedColumn = index - left
        if (pendingRow == rowKey || farJumpRow == null) pendingRow = null
        pendingInstant = false
        setCardAreaFocus(true)
        listener?.onCardFocused(rowKey, index)
        if (clickOnLandingRow != null) {
            val card = if (clickOnLandingRow == rowKey) row?.findViewHolderForAdapterPosition(index)?.itemView else null
            clickOnLandingRow = null
            card?.let { post { if (it.isFocused) it.performClick() } }
        }
        updateStop()
        applyHeaderEmphasis()
    }

    /**
     * [rowKey] 行第 [index] 张拿到了焦点, 而挂着的落点 (不在远跳途中) 指的是别处: 只有系统 / 列表自己送的焦点会这样 —— 按键与页面的落点
     * 都是先挂落点再送到那一张. 落点那一行还没排出来时不算 (那时也算不出它指哪张).
     */
    private fun landedElsewhere(rowKey: String, index: Int): Boolean {
        val pending = pendingRow ?: return false
        if (farJumpRow != null) return false
        val item = list.indexOfKey(pending)
        if (item < 0) return false
        if (pending != rowKey) return true
        val row = list.rowAt(item) ?: return false
        val count = row.cards.itemCount
        if (count == 0) return false
        val left = list.rowLeftIndex[pending] ?: row.leftIndex()
        return pendingTarget(pending, left, count) != index
    }

    private fun setCardAreaFocus(has: Boolean) {
        if (cardAreaHasFocus == has) return
        cardAreaHasFocus = has
        applyHeaderEmphasis()
        listener?.onCardAreaFocusChanged(has)
    }

    /** 焦点要落 / 已落的那一组的标题全亮, 其余淡一档 (照 tvOS 的货架标题). */
    private fun applyHeaderEmphasis() {
        val rowKey = farJumpRow ?: pendingRow ?: list.heldFocus?.first ?: focusedRowKey?.takeIf { cardAreaHasFocus }
        list.setEmphasizedHeader(rowKey?.let { headerKeyOf(it) }, animate = transitions)
    }

    private fun headerKeyOf(rowKey: String): String? {
        val items = list.items
        var i = list.indexOfKey(rowKey)
        while (i > 0) {
            i--
            val item = items[i]
            if (item is TvNativeExploreItem.Header) return item.key
        }
        return null
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        // 远跳停放的焦点落地了
        if (isFocusable) {
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            isFocusable = false
        }
        setCardAreaFocus(child === list)
    }

    override fun clearChildFocus(child: View?) {
        super.clearChildFocus(child)
        setCardAreaFocus(false)
    }

    /** 焦点换到本页以外 (标签行 / 侧边栏): 这条路上祖先只经 unFocus 清掉旧焦点, 不回调 [clearChildFocus], 按窗口的焦点变化判. */
    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (!tvNativeIsInside(newFocus, this)) setCardAreaFocus(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
        TvNativeFocusCues.attachPage(this)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        TvNativeFocusCues.detachPage(this)
        super.onDetachedFromWindow()
        scrollTracker.stop()
    }

    /** 行结构变了: 聚焦行跟着自己的新位置瞬时对回停位 (RecyclerView 整体刷新时按下标保位置). */
    private fun realignFocusedRow() {
        val rowKey = focusedRowKey ?: return
        if (!cardAreaHasFocus || farJumpRow != null) return
        val item = list.indexOfKey(rowKey)
        if (item < 0) return
        list.jumpTo(metrics.centeredScroll(list.items, item))
    }

    private fun updateStop() {
        val rowKey = focusedRowKey ?: return
        if (!cardAreaHasFocus) return
        val item = list.indexOfKey(rowKey)
        if (item < 0) return
        list.scrollToStop(metrics.centeredScroll(list.items, item), TvScrollSpring.Step, animatedScroll)
    }

    private fun onListMoved() {
        if (pendingRow != null && farJumpRow == null) resolvePending(scrollIfMissing = false)
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = metrics
        val width = m.bleedLeftPx + m.pageWidthPx
        val height = m.pageHeightPx
        list.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((height - m.listTopPx + m.listTopBleedPx).coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val m = metrics
        val ly = m.listTopPx - m.listTopBleedPx
        list.layout(0, ly, list.measuredWidth, ly + list.measuredHeight)
        // 建出来之前就发过来的落点
        if (pendingRow != null) post { resolvePending(scrollIfMissing = true) }
    }
}

/** 远跳途中按方向键当场落地, 超过这么久焦点还停放着 (那一趟布局没来) 就当落地失败, 下一次按键按挂着的落点重送. */
private const val TV_NATIVE_SHELF_LANDING_STALE_MILLIS = 400L

/** 远跳的纵向距离在这么多行 (含组标题) 以内按逐格的 spring 滚. */
private const val TV_NATIVE_SHELF_NEAR_JUMP_ROWS = 2
