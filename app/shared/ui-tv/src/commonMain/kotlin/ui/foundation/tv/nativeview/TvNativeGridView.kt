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
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PAST_DIM_ALPHA
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 网格海报墙 (追番 / 搜索页) 的几何 (px). 视图本身往上下出血 [topBleedPx] / [bottomBleedPx] (同 Compose 版 tvGridBleed), 网格顶线在视图里
 * [topBleedPx] 处, 视口 = 视图高 − 上下出血. [endMarginPx] = 滚到底时末行底边离视口底边的空 (tvPosterWallEndMargin).
 * [heroLinePx] = hero 态聚焦行行顶停在网格顶线下方多远 (TV_POSTER_WALL_HERO_ROW_TOP 换算到网格坐标).
 */
data class TvNativeGridMetrics(
    val columns: Int,
    val startPx: Int,
    val endPx: Int,
    val topBleedPx: Int,
    val bottomBleedPx: Int,
    val endMarginPx: Int,
    val heroLinePx: Int,
    /** 越过网格顶线的卡在这段距离内压暗到 [TV_CARD_PAST_DIM_ALPHA] 并保持 (TV_CARD_FADE_DISTANCE). */
    val fadeDistancePx: Float,
)

/**
 * 网格海报墙的纵向停位 (px, 网格内容坐标: 首行行顶贴网格顶线时为 0). 各行定高, 停位按行号直接算, 不看网格现在停在哪 —— 从详情页返回、
 * 分页重载、程序送焦各条路径算出来的是同一个位置, 不会先被拉到别处再滚回来.
 *
 * @param rowCount 行数 (末行不满也算一行).
 * @param pitchPx 行高 + 行距.
 * @param rowHeightPx 行高 (海报 + 番名块).
 * @param viewportPx 视口高 (去掉上下出血).
 * @param endMarginPx 滚到底时末行底边离视口底边留的空.
 * @param heroLinePx hero 态聚焦行行顶停在网格顶线下方多远.
 */
internal data class TvNativeGridStops(
    val rowCount: Int,
    val pitchPx: Int,
    val rowHeightPx: Int,
    val viewportPx: Int,
    val endMarginPx: Int,
    val heroLinePx: Int,
) {
    /** 能滚到的最大量: 末行底边离视口底 [endMarginPx]; 内容不满一屏时为 0. */
    val maxScroll: Int
        get() = ((rowCount - 1).coerceAtLeast(0) * pitchPx + rowHeightPx + endMarginPx - viewportPx).coerceAtLeast(0)

    /**
     * 聚焦第 [row] 行时该滚到哪: 卡片墙这一行的中线对准视口中线 (Apple TV 的网格就是这样滚, 焦点尽量在屏幕中间); hero 态 ([hero]) 行顶对准
     * [heroLinePx]. 两头放不到就贴边: 开头几行不滚, 末尾停在 [maxScroll].
     */
    fun scrollFor(row: Int, hero: Boolean): Int = desiredScroll(row, hero).coerceIn(0, maxScroll)

    /** hero 态第 [row] 行滚不到 hero 线的那截 (px, 正 = 往下推): 首行往下推, 末行往上提, 由整片平移补. */
    fun heroShiftFor(row: Int): Int = scrollFor(row, hero = true) - desiredScroll(row, hero = true)

    private fun desiredScroll(row: Int, hero: Boolean): Int =
        if (hero) row * pitchPx - heroLinePx else row * pitchPx + rowHeightPx / 2 - viewportPx / 2
}

/** 网格的事件. */
interface TvNativeGridListener : TvNativeCardListener {
    /** 首行按上 (交给顶栏: 追番页的标签行 / 搜索页的关键词). 返回是否已处理. */
    fun onTopRowUp(): Boolean

    /**
     * 行缘按左 / 右 ([direction] −1 / +1, [row] 为所在行): 追番页在这里换标签页, 行首按左出页面. 返回 false = 不处理 (按右吞掉,
     * 按左放出去, 由外面的焦点搜索接).
     */
    fun onRowEdge(direction: Int, row: Int): Boolean = false
}

/**
 * 网格海报墙: leanback VerticalGridView ([TvNativeGridMetrics.columns] 列), 卡片定宽定高. 停位 ([TvNativeGridStops]):
 * - 卡片墙: 聚焦行 (海报 + 番名) 的中线对准视口正中, 开头几行贴网格顶线、末尾末行底边离视口底 [TvNativeGridMetrics.endMarginPx]
 *   (leanback 的两头贴边窗口对齐, 视口按去掉出血算);
 * - hero 态: 聚焦行行顶对准 hero 线; 滚不到的那截 (首行往下推 / 末行往上提) 由整片平移补. 进出 hero 态时 leanback 按新基线当场重排,
 *   这一跳先用反向平移抵掉, 再让平移按 [TvScrollSpring.Far] 走到位 (聚焦卡一路跟着走, 不闪); hero 态里换行, 平移跟着滚动同一档 spring 走.
 * 方向键网格自己走 (见 [navigate]): 上下落在同一列, 下面没有同列的卡时落到最后一张, 末行按下吞掉; 长按连发限速同 tvFocusMoveRateLimit.
 * 网格本身不可聚焦, 焦点只落在卡上.
 * 越过网格顶线的卡压暗到 [TV_CARD_PAST_DIM_ALPHA] (顶栏画在网格上面, 卡压暗着从它底下滑过); hero 态里越过 hero 线的行淡没 (见 [heroDim]).
 * 上下各多排一屏 (setExtraLayoutSpace): 平移、连按时露出来的行都已经排好.
 */
@SuppressLint("ViewConstructor", "RestrictedApi")
class TvNativeGridView(
    context: Context,
    val style: TvNativeWallStyle,
    sketch: Sketch,
    metrics: TvNativeGridMetrics,
) : VerticalGridView(context) {
    val cards = TvNativeCardAdapter(style, sketch)
    var listener: TvNativeGridListener? = null
        set(value) {
            field = value
            cards.listener = value
        }

    var metrics: TvNativeGridMetrics = metrics
        set(value) {
            if (field == value) return
            field = value
            applyMetrics()
        }

    private val scroll = TvNativeSpringScroll()

    /** 动画滚动 (视觉效果流畅档关: 一步到位). */
    var animatedScroll: Boolean
        get() = scroll.animated
        set(value) {
            scroll.animated = value
        }

    private var lastVerticalRepeat = 0L
    private var lastHorizontalRepeat = 0L
    private var pendingFocus = -1
    private var farJumpTarget = -1

    /** hero 态: 停位换成 hero 线. */
    var heroActive: Boolean = false
        private set

    /** 进出 hero 态的淡化进度 (hero 态时间线的 above, 见 TvNativeHeroTimeline), 乘进越线行的淡没. */
    var heroAbove: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            cards.refreshDim(this)
        }

    /** hero 态还在进出途中 (content 没走满): 越线的行不按位置淡 (见 [heroDim]). */
    var heroTransitioning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            cards.refreshDim(this)
        }

    /** hero 态整片平移 (px, 正 = 往下推). */
    private var shiftY = 0f
    private var shiftAnimator: ValueAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        // 网格本身不可聚焦 (RecyclerView 默认连触摸模式都可聚焦): 焦点只落在卡上
        isFocusable = false
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        itemAlignmentOffsetPercent = BaseGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED
        horizontalSpacing = style.columnSpacingPx
        verticalSpacing = style.rowSpacingPx
        initialPrefetchItemCount = style.prefetchItems
        layoutManager?.isItemPrefetchEnabled = true
        setSmoothScrollByBehavior(scroll)
        itemAnimator = null
        cards.dimOf = { index, view -> topLineDim(index, view) }
        adapter = cards
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) cards.refreshDim(this@TvNativeGridView)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) finishFarJump()
            }
        })
        addOnLayoutCompletedListener {
            cards.refreshDim(this)
            val pending = pendingFocus
            if (pending >= 0 && focusItemNow(pending)) pendingFocus = -1
        }
        applyMetrics()
    }

    private fun applyMetrics() {
        val m = metrics
        setNumColumns(m.columns)
        setPadding(m.startPx, m.topBleedPx, m.endPx, m.bottomBleedPx + m.endMarginPx)
        applyAlignment()
    }

    private val viewportPx: Int get() = (height - metrics.topBleedPx - metrics.bottomBleedPx).coerceAtLeast(0)

    private val pitchPx: Int get() = style.cardBlockHeightPx + style.rowSpacingPx

    /** 按当前是否 hero 态设窗口对齐的基线 (leanback 在下一次布局里按它当场对齐). */
    private fun applyAlignment() {
        val m = metrics
        if (heroActive) {
            // 聚焦行行顶落在 hero 线: 对齐点用行顶
            itemAlignmentOffset = 0
            windowAlignmentOffset = m.topBleedPx + m.heroLinePx
        } else {
            itemAlignmentOffset = style.cardBlockHeightPx / 2
            windowAlignmentOffset = m.topBleedPx + viewportPx / 2
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) {
            applyAlignment()
            // 上下各多排一屏
            setExtraLayoutSpace(viewportPx)
        }
    }

    /** 网格的行数 (末行不满也算一行). */
    private fun rowCount(): Int {
        val cols = metrics.columns.coerceAtLeast(1)
        return (cards.itemCount + cols - 1) / cols
    }

    private fun stops(): TvNativeGridStops =
        TvNativeGridStops(rowCount(), pitchPx, style.cardBlockHeightPx, viewportPx, metrics.endMarginPx, metrics.heroLinePx)

    /** 聚焦第 [index] 张时列表该滚到哪 (网格内容坐标, 已夹在两头): 卡片墙居中 / hero 态行顶对 hero 线. */
    private fun stopScrollFor(index: Int, hero: Boolean): Int =
        stops().scrollFor(index / metrics.columns.coerceAtLeast(1), hero)

    /** 第 [index] 张停稳时行顶在视图里的位置 (不含整片平移). */
    private fun stopTopFor(index: Int, hero: Boolean): Int {
        val row = index / metrics.columns.coerceAtLeast(1)
        return metrics.topBleedPx + row * pitchPx - stopScrollFor(index, hero)
    }

    /** hero 态下这一格所在行滚不到 hero 线的那截 (px, 正 = 往下推): 首行往下推, 末行往上提. */
    private fun heroShiftFor(index: Int): Float {
        if (index < 0) return 0f
        return stops().heroShiftFor(index / metrics.columns.coerceAtLeast(1)).toFloat()
    }

    /**
     * 进 / 出 hero 态: 停位换成 hero 线 / 视口正中, 滚不到的那截由整片平移补 ([TvNativeGridStops.heroShiftFor]). [animated] = false 时直接到位
     * (页面重建时恢复在 hero 态).
     */
    fun setHeroActive(active: Boolean, animated: Boolean) {
        if (heroActive == active) return
        val index = selectedPosition
        val before = findViewHolderForAdapterPosition(index)?.itemView?.top
        heroActive = active
        applyAlignment()
        val target = if (active) heroShiftFor(index) else 0f
        if (animated && before != null && index >= 0) {
            // leanback 下一次布局按新基线当场对齐, 这一跳先用平移抵掉, 平移再走到位: 聚焦行一路连续
            val after = stopTopFor(index, active)
            shiftAnimator?.cancel()
            shiftY += (before - after).toFloat()
            translationY = shiftY
            animateShift(target, TvScrollSpring.Far, animated = true)
        } else {
            animateShift(target, TvScrollSpring.Far, animated = false)
        }
        cards.refreshDim(this)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        // hero 态里换行: 平移跟着新行的停位走 (首行 / 末行滚不到 hero 线的那截), 与滚动同一档 spring
        if (heroActive && child != null) {
            val index = getChildAdapterPosition(child)
            if (index >= 0) animateShift(heroShiftFor(index), TvScrollSpring.Step, animated = scroll.animated)
        }
    }

    private fun animateShift(target: Float, pace: TvScrollSpring, animated: Boolean) {
        shiftAnimator?.cancel()
        if (!animated || abs(target - shiftY) < 0.5f) {
            shiftY = target
            translationY = target
            cards.refreshDim(this)
            return
        }
        val from = shiftY
        val distance = abs(target - from).roundToInt()
        val millis = tvSpringDurationMillis(pace, distance)
        shiftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = millis.toLong()
            interpolator = tvSpringInterpolator(pace, millis)
            addUpdateListener {
                shiftY = from + (target - from) * (it.animatedValue as Float)
                translationY = shiftY
                cards.refreshDim(this@TvNativeGridView)
            }
            start()
        }
    }

    /**
     * 网格顶线以下第一张 (中线不高于顶线; 出血区里正在淡出的上一行不算), 同 Compose 版 firstItemBelowTopLine: 顶栏按下时落到它所在的那一行.
     * 还没排出来时 null.
     */
    fun firstIndexBelowTopLine(): Int? {
        var best: Int? = null
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val index = getChildAdapterPosition(child)
            if (index < 0) continue
            val center = child.top + translationY - metrics.topBleedPx + child.height / 2f
            if (center >= 0f && (best == null || index < best)) best = index
        }
        return best
    }

    /** 送焦到第 [index] 张 (进页恢复 / 程序化落点): 已经排出来就当场给, 否则先选中 (直接到位) 等布局完成再给. */
    fun focusItem(index: Int) {
        val count = cards.itemCount
        if (count == 0) {
            pendingFocus = index.coerceAtLeast(0)
            return
        }
        val target = index.coerceIn(0, count - 1)
        if (!focusItemNow(target)) {
            pendingFocus = target
            selectedPosition = target
        }
    }

    private fun focusItemNow(index: Int): Boolean {
        val count = cards.itemCount
        if (count == 0) return false
        val view = findViewHolderForAdapterPosition(index.coerceIn(0, count - 1))?.itemView ?: return false
        return view.requestFocus()
    }

    /**
     * 远跳到第 [index] 张 (返回键回首卡): 按像素距离走 [TvScrollSpring.Far] (Apple TV 页面滚动那一组), 途中不显示聚焦效果, 到位再把焦点
     * 给目标 (同 Apple TV 返回回顶: 一路滚上去, 不瞬移; 一路滚过的卡不挨个放大).
     */
    fun farJumpTo(index: Int) {
        val count = cards.itemCount
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        val first = (0 until childCount).map { getChildAt(it) }
            .filter { getChildAdapterPosition(it) >= 0 }
            .minByOrNull { getChildAdapterPosition(it) }
        if (first == null) {
            focusItem(target)
            return
        }
        val cols = metrics.columns.coerceAtLeast(1)
        // 各行定高: 目标行此刻的顶 (视图坐标) 由第一张排出来的卡的顶按行数推出来
        val targetTop = first.top + (target / cols - getChildAdapterPosition(first) / cols) * pitchPx
        val dy = targetTop - stopTopFor(target, heroActive)
        if (dy == 0 || !scroll.animated) {
            if (dy != 0) scrollBy(0, dy)
            focusItem(target)
            return
        }
        farJumpTarget = target
        setFocusEffectSuppressed(true)
        scroll.pace = TvScrollSpring.Far
        smoothScrollBy(0, dy)
        scroll.pace = TvScrollSpring.Step
    }

    private fun finishFarJump() {
        val target = farJumpTarget
        if (target < 0) return
        farJumpTarget = -1
        focusItem(target)
        setFocusEffectSuppressed(false)
    }

    /** 远跳途中所有卡都画成未聚焦 (一路滚过的卡不挨个放大), 到位再按焦点恢复. */
    private fun setFocusEffectSuppressed(suppressed: Boolean) = cards.setFocusEffectSuppressed(this, suppressed)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                // 按了新键: 挂着的程序化落点 (含远跳) 作废, 从此刻的焦点接着走
                pendingFocus = -1
                if (farJumpTarget >= 0) {
                    farJumpTarget = -1
                    setFocusEffectSuppressed(false)
                }
            }
            val code = event.keyCode
            val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            val horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            val focused = focusedChild
            if ((vertical || horizontal) && focused != null) return navigate(code, getChildAdapterPosition(focused), event.repeatCount)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 方向键网格自己走, 不交给系统找焦点 (焦点搜索落到 Compose 那边时按整个宿主里的几何挑目标, 不可预测): 上下同一列, 左右同一行;
     * 下面没有同列的卡时落到最后一张, 末行按下吞掉 (同 Compose 版 tvGridKeyNavigation); 首行按上交给 [TvNativeGridListener.onTopRowUp],
     * 行缘按左 / 右交给 [TvNativeGridListener.onRowEdge] (不处理时按右吞掉、按左放出去). 返回是否吞掉这一下.
     */
    private fun navigate(code: Int, index: Int, repeatCount: Int): Boolean {
        // 焦点所在的卡正被换掉 (分页刷新途中): 这一下吞掉
        if (index == NO_POSITION) return true
        val vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (repeatCount > 0) {
            val now = SystemClock.uptimeMillis()
            if (vertical) {
                if (now - lastVerticalRepeat < TV_NATIVE_VERTICAL_REPEAT_MILLIS) return true
                lastVerticalRepeat = now
            } else {
                if (now - lastHorizontalRepeat < TV_NATIVE_HORIZONTAL_REPEAT_MILLIS) return true
                lastHorizontalRepeat = now
            }
        }
        val cols = metrics.columns.coerceAtLeast(1)
        val count = cards.itemCount
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (index < cols) return listener?.onTopRowUp() ?: false
                moveFocusTo(index - cols)
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                index + cols < count -> moveFocusTo(index + cols)
                index / cols < (count - 1) / cols -> moveFocusTo(count - 1)
            }

            else -> {
                val left = code == KeyEvent.KEYCODE_DPAD_LEFT
                val atEdge = if (left) index % cols == 0 else index % cols == cols - 1 || index == count - 1
                if (atEdge) return listener?.onRowEdge(if (left) -1 else 1, index / cols) == true || !left
                moveFocusTo(if (left) index - 1 else index + 1)
            }
        }
        return true
    }

    /** 用户导航的落点: 排出来了当场给 (leanback 按窗口对齐滚过去), 没排出来 (连按超前) 平滑滚过去, 到位时 leanback 把焦点给它. */
    private fun moveFocusTo(index: Int) {
        val view = findViewHolderForAdapterPosition(index)?.itemView
        if (view == null || !view.requestFocus()) setSelectedPositionSmooth(index)
    }

    /**
     * 卡的压暗: 越过网格顶线往上走时在 [TvNativeGridMetrics.fadeDistancePx] 内压到 [TV_CARD_PAST_DIM_ALPHA] 并保持 —— 不淡没: 聚焦行停在
     * 正中, 上一行大半还在屏上, 淡没会让它整行消失; hero 态里再按
     * [heroDim] 淡没越过 hero 线的行.
     */
    private fun topLineDim(index: Int, view: View): Float {
        val m = metrics
        val top = view.top + translationY - m.topBleedPx
        val wall = if (top >= 0) 1f else 1f - ((-top) / m.fadeDistancePx).coerceIn(0f, 1f) * (1f - TV_CARD_PAST_DIM_ALPHA)
        val p = heroAbove
        if (p <= 0f) return wall
        val hero = heroDim(index, view)
        return wall + (hero - wall) * p
    }

    /**
     * hero 态里一张卡的淡化: 进出途中聚焦行及以下不淡 (它们的终点在 hero 线上 / 线下, 途中却要从线上方经过, 按位置淡就是整行先暗一下再亮回来)、
     * 上面的整行跟 above 淡 (退场时它们随网格往下挪、从 hero 线上方经过, 按位置淡就在背景图还没淡完时先露出来);
     * 停稳后按位置, 越过 hero 线 [TvNativeGridMetrics.fadeDistancePx] 内淡没.
     *
     * [index] 由适配器给, 不从 [view] 反查: 绑定时新建的卡还没加进网格 (布局参数还是卡自己的), getChildAdapterPosition 会抛异常.
     */
    private fun heroDim(index: Int, view: View): Float {
        val m = metrics
        val cols = m.columns.coerceAtLeast(1)
        val focused = selectedPosition
        return if (heroTransitioning && focused >= 0 && index >= 0) {
            if (index / cols >= focused / cols) 1f else 0f
        } else {
            val aboveLine = m.heroLinePx - (view.top + translationY - m.topBleedPx)
            if (aboveLine <= 0f) 1f else 1f - (aboveLine / m.fadeDistancePx).coerceIn(0f, 1f)
        }
    }
}
