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
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.Immutable
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.ui.foundation.focus.TV_TRANSIT_PARK_KEY_GRACE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_TAB_CONTENT_SLIDE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.tvPosterWallToneGate
import kotlin.math.roundToInt

/**
 * 网格页 (追番 / 搜索) 原生海报墙的几何 (px; 横坐标按让开侧边栏之后的内容区给, 纵坐标 = 页面坐标).
 *
 * @param gridTopPx 网格顶线离页面顶 (顶栏 / 标签行 / 筛选条之下).
 * @param heroLeftPx / [heroTopPx] / [heroWidthPx] / [heroHeightPx] hero 文字块 (不占布局, 画在网格底下).
 * @param bleedLeftPx 内容区左边、侧边栏底下那一截 (收起的侧边栏宽, 同探索页; 没有侧边栏的页为 0): 视图从屏幕左缘铺起 (页面铺满, 侧边栏
 *   盖在上面), 网格起点、hero 文字这些横坐标由本视图加上这一截; 网格画进这一截, 最左一列的放大与投影、换标签时滑出的网格从侧边栏底下画过.
 */
@Immutable
data class TvNativeGridPageMetrics(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val gridTopPx: Int,
    val grid: TvNativeGridMetrics,
    val backdropWidthPx: Int,
    val backdropHeightPx: Int,
    val heroLeftPx: Int,
    val heroTopPx: Int,
    val heroWidthPx: Int,
    val heroHeightPx: Int,
    val titleWidthPx: Int,
    val summaryWidthPx: Int,
    val bleedLeftPx: Int = 0,
)

/** 网格页原生海报墙的事件 (页面实现). */
interface TvNativeGridPageListener : TvNativeGridListener {
    /** 进 / 出 hero 态 (页面存起来, 返回本页时恢复). */
    fun onHeroActiveChanged(active: Boolean)

    /** 整屏黑度 (hero 态进度) 变了: 交给画整屏底色的那一层. */
    fun onToneChanged(tone: Float)

    /** 网格滚动在不在挪 (hero 文字滚动期间藏起来). */
    fun onScrollingChanged(scrolling: Boolean)

    /** 焦点进出网格. */
    fun onGridFocusChanged(hasFocus: Boolean)

    /** 焦点停放在本视图上 / 解除 (换标签时新那份网格的卡还没到, 见 TvNativeGridPageView 类说明): 停放期间的按键不算用户接管. */
    fun onFocusParkedChanged(parked: Boolean)

    /** 整屏背景点开途中 (对焦还没到位、还没进详情页, 见 TvNativeGridPageView 的对焦一节): 这期间返回键取消点开. */
    fun onWallOpeningChanged(opening: Boolean) {}

    /** 点开对焦到位, 这就进详情页 (紧接着是 [onClick]): 回到本页时页面调 [TvNativeGridPageView.endWallOpen] 倒放. */
    fun onWallOpened(index: Int) {}

    /** 点开时顶栏等 Compose 部件跟着卡片淡没的程度 (0..1). */
    fun onWallFade(fade: Float) {}

    /** 网格内容从页顶往上滚了多少 (px, 见 [TvNativeGridPageView.contentScrollLimitPx]): 页面让顶栏跟着一起滚走. */
    fun onContentScrolled(offsetPx: Int) {}
}

/**
 * 网格页 (追番 / 搜索) 的原生海报墙: 背景图 (hero 态, 贴右上角) < hero 文字 (hero 态, 在网格底下) < 网格 ([TvNativeGridView]).
 * 顶栏 (标签行 / 搜索栏 / 筛选条) 留在 Compose 里, 画在本视图上面 (越过网格顶线的卡照常从它底下滑过, 顶栏控件自带玻璃底).
 *
 * 追番页的换标签: 网格按标签分几份 ([showGrid] 的 key), 换的时候新旧两份整体水平滑过 ([TV_TAB_CONTENT_SLIDE_MILLIS]), 各自保留自己的
 * 滚动位置; 视觉效果流畅档直接换. hero 态时间线 ([TvNativeHeroTimeline]) 驱动背景图 / 文字 / 整屏黑度 / 越线行淡没, 与探索页
 * 同一套时长.
 *
 * 换标签那一刻焦点还在换下去的那份网格上 (行末按右 / 行首按左跨标签, 新那份的卡要等数据): 焦点先停放在本视图自己身上, 新那份的卡
 * 拿到焦点就解除 (见 [parkFocus]). 不停放的话焦点留在滑出去的网格里 —— 长按的连发在它的行末卡上接着报行缘, 一路连跨标签;
 * 那份网格被收起 / 复用时焦点随之丢掉, 全局兜底把它塞给首个标签, 连发接着在标签行上往右走.
 *
 * 新番时间表 (没有 hero 态) 在最底下另铺一层整屏背景 ([enableWallBackdrop]): 聚焦那部的模糊版, 点按 / 长按时对焦 (见 [TvNativeWallFocus]).
 * 开了 [heroBlur] 的追番 / 搜索页, hero 态也在底下铺这一层 (背景图与 hero 文字照旧画在它上面; 按确定对焦变清晰再进详情页).
 */
@SuppressLint("ViewConstructor")
class TvNativeGridPageView(
    context: Context,
    private val sketch: Sketch,
    private val scope: CoroutineScope,
    style: TvNativeWallStyle,
    metrics: TvNativeGridPageMetrics,
    heroTextStyle: TvNativeHeroTextStyle,
) : FrameLayout(context), TvNativeAmbientAnimations, TvNativeWallOpenable {
    var listener: TvNativeGridPageListener? = null

    /** 第几张卡被绑定 (此刻显示的那份网格; 分页的访问提示). */
    var onBindCard: ((index: Int) -> Unit)? = null

    var style: TvNativeWallStyle = style
        private set
    var metrics: TvNativeGridPageMetrics = metrics
        private set

    /** 有 hero 态: 卡片墙上按确定先切到 hero 态, hero 态里才进详情页. false = 卡片墙上按确定直接进 (新番时间表). */
    var heroEnabled: Boolean = true

    var transitions: Boolean = true
    var animatedScroll: Boolean = true
        set(value) {
            field = value
            grids.forEach { it.animatedScroll = value }
        }

    var composeRoot: View? = null
        set(value) {
            field = value
            backdrop.composeRoot = value
            heroText.composeRoot = value
            wallBackdrop?.composeRoot = value
        }

    /** 深色主题: hero 态整屏压黑, 背景图等黑透才露面 (铺着模糊背景时不压黑, 见 [heroDarkens]). */
    var dark: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyHeroTone()
        }

    /**
     * hero 态把整屏底色压黑: 深色主题、且没铺模糊背景 —— 模糊背景 ([heroBlur]) 盖满整屏, 压黑只会在进出 hero 态时先闪一下黑; 浅色主题 hero
     * 底与卡片墙同色. 不压黑时 hero 时间线按不压黑的时长走, 背景图不等底色, 报给页面的黑度恒为 0.
     */
    private val heroDarkens: Boolean get() = dark && !heroBlur

    /** 压不压黑变了 (换主题 / 开关模糊背景): 时间线的时长、背景图的放行与报给页面的黑度跟着换. */
    private fun applyHeroTone() {
        timeline.darkens = heroDarkens
        applyHero()
        listener?.onToneChanged(heroTone())
    }

    /** 报给页面画整屏底色的黑度 (不压黑时恒 0). */
    private fun heroTone(): Float = if (heroDarkens) timeline.tone else 0f

    var fadeColor: Int
        get() = backdrop.fadeColor
        set(value) {
            backdrop.fadeColor = value
        }

    var treatment: TvBackdropTreatment?
        get() = backdrop.treatment
        set(value) {
            backdrop.treatment = value
        }

    private val backdrop = TvNativeBackdropView(context, sketch, scope)

    /** 此刻在不在导航: 背景图的剧照升档等它为 false 才去取原图 (见 [TvNativeBackdropView.navigating]). */
    var backdropNavigating: () -> Boolean
        get() = backdrop.navigating
        set(value) {
            backdrop.navigating = value
        }

    val heroText = TvNativeHeroTextView(context, heroTextStyle)

    /** 最底下的整屏背景 (新番时间表, 见 [enableWallBackdrop]); null = 没有 (追番 / 搜索). */
    var wallBackdrop: TvNativeWallBackdropView? = null
        private set

    /**
     * 装 hero 文字的一层, hero 时间线的显隐 ([TvNativeHeroTimeline.text]) 调它的透明度: 文字块换字时自己也调自己的透明度 (旧字淡出、新字进场),
     * 两边写同一个属性就是谁后写谁算 —— 进 hero 态途中内容晚到、退出途中换了条目, 字都会以满透明度露出来.
     */
    private val heroBox = object : FrameLayout(context) {
        override fun hasOverlappingRendering(): Boolean = false
    }
    private val gridBox = FrameLayout(context)

    /** 网格框的裁剪框 (框自己的坐标, 见 init). */
    private val gridClip = Rect()
    private val grids = ArrayList<TvNativeGridView>(2)
    private val gridKeys = HashMap<TvNativeGridView, Any>()
    private var current: TvNativeGridView? = null
    private var slideAnimator: ValueAnimator? = null
    private val savedPosition = HashMap<Any, Int>()

    /** 返回本页时焦点要回的那张 (见 [holdLandingLook]), -1 = 没有. */
    private var landingHeld = -1
    private val timeline = TvNativeHeroTimeline { onTimeline() }
    private val scrollTracker = TvNativeScrollTracker { listener?.onScrollingChanged(it) }
    private var source: TvNativeHeroSource? = null
    private var lastDimSubject: Int? = null
    private var gridHasFocus = false

    /** 进了 hero 态、背景图与文字还没换上: 等页面给的内容跟上聚焦的那张 (见 [showEnteringContent]). */
    private var enteringPending = false

    /** 焦点停放在本视图上的那一刻 (见 [parkFocus]), -1 = 没停放. */
    private var parkedAt = -1L

    init {
        clipChildren = false
        clipToPadding = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(backdrop)
        heroBox.clipChildren = false
        heroBox.clipToPadding = false
        heroBox.addView(heroText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(heroBox)
        // 网格只在出血后的框里画 (换标签滑动时滑出框的部分裁掉): 裁的是框本身 (clipBounds, 见 onLayout). 不能用 clipChildren —— 它按
        // 每份网格自己的边界裁, 边界跟着网格平移走: 进出 hero 态时网格整片平移 (见 TvNativeGridView.setHeroActive), 往上抬的那一截
        // 会把下面几排卡从半截裁掉
        gridBox.clipChildren = false
        gridBox.clipToPadding = false
        addView(gridBox)
        backdrop.alpha = 0f
        heroBox.alpha = 0f
        timeline.darkens = heroDarkens
        applyHero()
    }

    override fun setAmbientAnimationsPaused(paused: Boolean) {
        heroText.marqueePaused = paused
    }

    fun update(style: TvNativeWallStyle, metrics: TvNativeGridPageMetrics, heroTextStyle: TvNativeHeroTextStyle) {
        val changed = this.metrics != metrics || this.style != style
        this.style = style
        this.metrics = metrics
        heroText.style = heroTextStyle
        heroText.titleWidthPx = metrics.titleWidthPx
        heroText.summaryWidthPx = metrics.summaryWidthPx
        if (changed) {
            grids.forEach { it.metrics = gridMetrics() }
            requestLayout()
        }
    }

    // ------------------------------------------------------------------
    // 网格
    // ------------------------------------------------------------------

    /** 此刻显示的那份网格. */
    val grid: TvNativeGridView? get() = current

    /** 网格的几何: 起点加上侧边栏底下那一截 (见 [TvNativeGridPageMetrics.bleedLeftPx]). */
    private fun gridMetrics(): TvNativeGridMetrics = metrics.grid.let { it.copy(startPx = it.startPx + metrics.bleedLeftPx) }

    /** 此刻显示的那份网格的 key. */
    val currentKey: Any? get() = current?.let { gridKeys[it] }

    /**
     * 换成 [key] 那份网格 (追番页的标签; 搜索页只有一份). [direction] = 新网格从哪边滑进来 (+1 右边, −1 左边, 0 不滑);
     * 流畅档 / [animated] = false 直接换. 新网格停在这个 key 上次的位置.
     */
    fun showGrid(key: Any, direction: Int, animated: Boolean) {
        val cur = current
        if (cur != null && gridKeys[cur] == key) return
        // 焦点还在要换下去的网格上: 先停放 (见类说明)
        if (gridBox.hasFocus()) parkFocus()
        cur?.let { savedPosition[gridKeys[it] ?: return@let] = it.selectedPosition }
        slideAnimator?.end()
        val next = grids.firstOrNull { it !== cur } ?: newGrid()
        gridKeys[next] = key
        next.cards.submit(emptyList()) { it.toLong() }
        next.translationX = 0f
        next.visibility = VISIBLE
        next.setHeroActive(timeline.active, animated = false)
        next.heroAbove = timeline.above
        next.heroTransitioning = timeline.content < 1f && timeline.above > 0f
        next.cards.setTitleVisibility(next, 1f - timeline.above)
        next.pullFade = wallFocus.fade
        next.pullKeepIndex = wallFocus.keep
        // 没记过位置的 (头一回显示 / 被 forgetPosition 忘掉的) 从第一张排起: 复用的那份网格还停在上一个 key 的位置上
        next.selectedPosition = savedPosition[key] ?: 0
        current = next
        val width = gridBox.width.toFloat()
        val slide = cur != null && direction != 0 && animated && transitions && width > 0f
        // 顶栏跟着滚走的量 (见 emitContentScroll): 滑动的话从换之前报出去的值起步, 跟着滑动进度滑到新那份网格的; 不滑动当场换
        contentScrollFrom = reportedContentScroll
        contentScrollProgress = 0f
        contentScrollSliding = slide
        reportContentScroll()
        // 进页恢复的那一张只属于建视图后的第一份网格; 换了标签就作废
        if (cur == null && landingHeld >= 0) next.cards.setFocusLookHeld(next, landingHeld) else landingHeld = -1
        if (cur == null || !slide) {
            cur?.let { retire(it) }
            return
        }
        next.translationX = width * direction
        slideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TV_TAB_CONTENT_SLIDE_MILLIS.toLong()
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                val f = it.animatedValue as Float
                next.translationX = width * direction * (1f - f)
                cur.translationX = -width * direction * f
                contentScrollProgress = f
                emitContentScroll()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slideAnimator = null
                    contentScrollSliding = false
                    emitContentScroll()
                    if (current !== cur) retire(cur)
                }
            })
            start()
        }
    }

    /** 退场的网格: 不画、不收焦点, 下次换标签时拿来复用. */
    private fun retire(grid: TvNativeGridView) {
        // 还持着焦点就先停放: 收起时系统会清掉焦点, 全局兜底把它塞给首个标签
        if (grid.hasFocus()) parkFocus()
        grid.visibility = INVISIBLE
        grid.translationX = 0f
    }

    /** 给 [key] 那份网格换数据 (分页快照; null 是还没到的占位). 不是此刻显示的那份就只记下, 显示时再给. */
    fun setCards(key: Any, cards: List<TvNativeCard?>) {
        val grid = grids.firstOrNull { gridKeys[it] == key } ?: return
        grid.cards.submit(cards) { it.toLong() }
    }

    private fun newGrid(): TvNativeGridView {
        val grid = TvNativeGridView(context, style, sketch, gridMetrics())
        grid.animatedScroll = animatedScroll
        grid.listener = GridListener(grid)
        grid.cards.onBind = { index -> if (grid === current) onBindCard?.invoke(index) }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (recyclerView === current) scrollTracker.onStateChanged(this@TvNativeGridPageView, newState)
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (recyclerView === current) {
                    scrollTracker.onMoved(dy)
                    reportContentScroll()
                }
            }
        })
        grids.add(grid)
        gridBox.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return grid
    }

    private inner class GridListener(private val grid: TvNativeGridView) : TvNativeGridListener {
        override fun onFocused(index: Int) {
            landingHeld = -1
            if (grid === current) listener?.onFocused(index)
        }

        override fun onClick(index: Int) {
            if (grid !== current) return
            // 卡片墙上先切到 hero 态, hero 态里才进详情页 (这时有 hero 背景图, 走放大转场)
            if (heroEnabled && !timeline.active) {
                setHeroActive(true)
            } else {
                // 进详情页: 焦点交出去之后这张卡仍画成聚焦态, 返回后焦点交还前也不缩 (见 TvNativeGridView.focusItem)
                grid.cards.setFocusLookHeld(grid, index)
                // 有整屏背景 (新番时间表 / hero 态的模糊背景): 先对焦 (背景变清晰、卡片淡没) 再进, 见 TvNativeWallFocus
                if (!wallFocus.open(index, wallMatches(grid, index))) listener?.onClick(index)
            }
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            if (grid !== current) return
            // 新番时间表: 先进对焦再交给页面, 页面弹不出菜单时当场调 endWallPeek. hero 态的模糊背景不对焦, 只弹菜单
            if (!heroEnabled) wallFocus.peek(index, wallMatches(grid, index))
            listener?.onLongPress(index, anchor)
        }

        override fun onTopRowUp(): Boolean = listener?.onTopRowUp() ?: false

        // 换下去的那份网格不再报行缘 (焦点还没交出去时的连发): 否则一路连跨标签
        override fun onRowEdge(direction: Int, row: Int): Boolean =
            if (grid !== current) true else listener?.onRowEdge(direction, row) ?: false
    }

    /** 送焦到此刻那份网格的第 [index] 张 (排出来之前记下, 布局完成再送). */
    fun focusItem(index: Int) {
        current?.focusItem(index)
    }

    /**
     * 返回本页 (页面重建, 如从播放器回来) 时焦点会回到第 [index] 张 (页面的进页落点): 从排出来的第一帧起就按住它的聚焦态, 焦点到了画面不变.
     * 等落点请求送到再按住就晚了 —— 那时它多半已经按未聚焦画过一帧, 焦点到了再放大一遍. 建视图时网格还没建出来, 先记下, 第一份网格建出来时补上;
     * 任何一张卡拿到焦点 (适配器随之放开) 就作废.
     */
    fun holdLandingLook(index: Int) {
        landingHeld = index
        current?.let { if (!it.hasFocus()) it.cards.setFocusLookHeld(it, index) }
    }

    /** 此刻那份网格顶线以下第一张 (见 [TvNativeGridView.firstIndexBelowTopLine]). */
    fun firstIndexBelowTopLine(): Int? = current?.firstIndexBelowTopLine()

    /** 远跳到第 [index] 张 (返回键回首卡), 见 [TvNativeGridView.farJumpTo]. */
    fun farJumpTo(index: Int) {
        current?.farJumpTo(index)
    }

    /** 各份网格此刻的选中位置 (页面重建时恢复用). */
    fun savedPositions(): Map<Any, Int> {
        current?.let { g -> gridKeys[g]?.let { savedPosition[it] = g.selectedPosition } }
        return savedPosition
    }

    fun restorePositions(positions: Map<out Any, Int>) {
        savedPosition.putAll(positions)
    }

    /** 忘掉 [key] 那份网格的位置: 下次显示从第一行排起 (新番时间表在日期行上换天, 换过去的那天从头看). */
    fun forgetPosition(key: Any) {
        savedPosition.remove(key)
    }

    // ------------------------------------------------------------------
    // 内容滚动 (顶栏跟着滚走)
    // ------------------------------------------------------------------

    /**
     * 报告网格内容从页顶往上滚了多少 ([TvNativeGridPageListener.onContentScrolled]), 夹在 0..此值: 页面让顶栏跟着内容一起滚走 (新番时间表的
     * 日期行, 照 tvOS 标签栏的默认行为: 内容只有一个主视图时标签栏随内容滚出屏幕). 量的是第一行离开它停在页顶时的位置 (行顶 = 网格的上内边距);
     * 第一行已滚出排版范围 = 此值, 没有卡 = 0. 换网格 (时间表跨天) 时报的值跟着网格滑动一起滑到新那份的, 不在换的那一刻跳. 0 = 不报 (追番 / 搜索).
     */
    var contentScrollLimitPx: Int = 0

    private var reportedContentScroll = -1

    /** 此刻那份网格实际滚了多少 (换网格滑动途中是滑向的终点; 新那份网格排出来、自己滚动时跟着变). */
    private var contentScrollTarget = 0

    /** 换网格的滑动途中 (见 [showGrid]): 报的值从换之前报出去的 [contentScrollFrom] 按滑动进度 [contentScrollProgress] 插到 [contentScrollTarget]. */
    private var contentScrollSliding = false
    private var contentScrollFrom = -1
    private var contentScrollProgress = 0f

    /** 排版完成 (换数据、换标签后的第一次排版) 也补报一次: 滚动回调只管挪动. */
    private val layoutWatcher = ViewTreeObserver.OnGlobalLayoutListener { reportContentScroll() }

    private fun reportContentScroll() {
        val limit = contentScrollLimitPx
        val grid = current
        if (limit <= 0 || grid == null) return
        contentScrollTarget = if (grid.cards.itemCount == 0) {
            0
        } else {
            val first = grid.findViewHolderForAdapterPosition(0)?.itemView
            if (first == null) limit else (grid.paddingTop - first.top).coerceIn(0, limit)
        }
        emitContentScroll()
    }

    /** 报出去: 平时就是网格实际滚的量 (跟着滚动 1:1); 换网格滑动途中按滑动进度插值, 顶栏与网格一起滑到位. */
    private fun emitContentScroll() {
        if (contentScrollLimitPx <= 0) return
        val target = contentScrollTarget
        val from = contentScrollFrom
        val value = if (contentScrollSliding && from >= 0) (from + (target - from) * contentScrollProgress).roundToInt() else target
        if (value == reportedContentScroll) return
        reportedContentScroll = value
        listener?.onContentScrolled(value)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val g = current ?: return false
        val count = g.cards.itemCount
        if (count == 0) return false
        g.focusItem(g.selectedPosition.coerceIn(0, count - 1))
        return g.hasFocus() || super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        // 停放的焦点落地了 (新那份网格的卡拿到焦点)
        unparkFocus()
        setGridFocus(child === gridBox)
    }

    override fun clearChildFocus(child: View?) {
        super.clearChildFocus(child)
        setGridFocus(false)
    }

    /**
     * 焦点换到网格以外 (标签行 / 搜索栏等 Compose 控件): 这条路上祖先只经 unFocus 清掉旧焦点, 不回调 [clearChildFocus], 按窗口的焦点变化判.
     * 停放在本视图自己身上仍算在网格里.
     */
    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus !== this && !tvNativeIsInside(newFocus, gridBox)) {
            unparkFocus()
            setGridFocus(false)
        }
    }

    /**
     * 把焦点停放在本视图自己身上 (见类说明): 停放期间只让自己先拿焦点 (FOCUS_BEFORE_DESCENDANTS), 子视图拿到焦点就解除 (见
     * [requestChildFocus]). 同探索页远跳的停放.
     */
    private fun parkFocus() {
        if (isFocused) return
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        // 连触摸模式一起 (同卡片): 停放不因窗口进了触摸模式而落空
        isFocusableInTouchMode = true
        if (!requestFocus()) {
            unparkFocus()
            return
        }
        parkedAt = SystemClock.uptimeMillis()
        listener?.onFocusParkedChanged(true)
    }

    private fun unparkFocus() {
        if (!isFocusable) return
        // 先让子视图能拿焦点再撤掉自己的可聚焦 (自己还持焦时撤掉会 clearFocus, 系统又去塞给第一个可聚焦的)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        if (!isFocused) isFocusable = false
        if (parkedAt >= 0) {
            parkedAt = -1L
            listener?.onFocusParkedChanged(false)
        }
    }

    /**
     * 停放期间 (焦点在本视图自己身上) 的方向键与确认键一律吞掉, 新那份网格的卡一到焦点就落过去 —— 交给系统找焦点的话会按宿主里的几何
     * 落到滑动中的网格或标签行上. 两个出口交给页面回标签行: 按上; 停放过了 [TV_TRANSIT_PARK_KEY_GRACE_MILLIS] 之后新按下的一下 (那时
     * 页面已把它当成用户接管、取消了在途送焦, 新标签是空的或数据迟迟不到, 焦点不会一直停在这里吞键). 连发不走出口: 在途送焦还在, 数据
     * 到了照常落地.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val direction = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        // 整屏背景: 点开途中吞掉方向键与确认键, 按下确认键当场解清晰图 (见 TvNativeWallFocus.handleKey)
        if (wallFocus.handleKey(event)) return true
        if (!isFocused || parkedAt < 0 || !(direction || tvNativeIsConfirmKey(code))) return super.dispatchKeyEvent(event)
        if (direction && event.action == KeyEvent.ACTION_DOWN) {
            val expired = SystemClock.uptimeMillis() - parkedAt >= TV_TRANSIT_PARK_KEY_GRACE_MILLIS
            if (code == KeyEvent.KEYCODE_DPAD_UP || (expired && event.repeatCount == 0)) listener?.onTopRowUp()
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
        viewTreeObserver.addOnGlobalLayoutListener(layoutWatcher)
    }

    private fun setGridFocus(has: Boolean) {
        if (gridHasFocus == has) return
        gridHasFocus = has
        listener?.onGridFocusChanged(has)
    }

    // ------------------------------------------------------------------
    // hero 态
    // ------------------------------------------------------------------

    val heroActive: Boolean get() = timeline.active

    /** 进 / 出 hero 态 (返回键在页面里, 调这里). [animated] = false 时直接到位 (返回本页时恢复). */
    fun setHeroActive(active: Boolean, animated: Boolean = transitions) {
        if (timeline.active == active) return
        timeline.setActive(active, animated)
        current?.setHeroActive(active, animated = animated && animatedScroll)
        listener?.onHeroActiveChanged(active)
        // 退出途中又进来 (内容还在屏上): 退出时内容定格没跟着导航换 (见 applySource), 这里对上此刻的
        if (active) applySource()
    }

    /** hero 的内容 (停稳后的背景图、文字; 页面按 rememberTvSettledHeroProvider / rememberTvScrollHiddenProvider 算好). */
    fun setSource(source: TvNativeHeroSource) {
        this.source = source
        applySource()
    }

    private fun applySource() {
        val src = source ?: return
        // 按下即压暗: 真实目标换了就压暗, 等展示目标跟上再放开
        if (lastDimSubject != null && lastDimSubject != src.rawSubjectId) backdrop.triggerPressDim()
        lastDimSubject = src.rawSubjectId
        backdrop.dimming = src.dimming
        // 背景图与文字只在 hero 态画 (压黑时底色黑透之后才淡入); 离开 hero 态淡完就撤. 退出途中内容定格, 不跟着导航换 (正在淡出的图与字换一张
        // 只会晃一下)
        if (!timeline.visible || !timeline.active) return
        if (enteringPending) {
            showEnteringContent()
        } else {
            backdrop.show(src.backdrop)
            if (heroBlur) setWallTarget(src.wall)
            heroText.setText(src.text, TvNativeTextTransition.Key)
        }
    }

    /**
     * 进 hero 态时换上内容 (不交叉淡入, 图层这时还是透明的). 只认聚焦那张卡的: 页面的内容在 Compose 里算, 比原生晚一两帧 ——
     * 远跳途中按的确认在落到首卡时当场进 hero 态, 那一刻手里的还是出发那张的背景图与文字, 换上就是先露出它、再换成首卡. 真实目标
     * 不是聚焦的那张、或展示还没跟上 (按下即压暗的等待里) 就先空着, 页面的内容一跟上 ([setSource]) 再换.
     */
    private fun showEnteringContent() {
        val src = source ?: return
        val grid = current
        val focusedSubject = grid?.cards?.subjectIdAt(grid.selectedPosition)
        if (focusedSubject != null && (src.rawSubjectId != focusedSubject || src.dimming)) return
        enteringPending = false
        backdrop.show(src.backdrop, crossfade = false)
        if (heroBlur) setWallTarget(src.wall)
        heroText.setText(src.text, TvNativeTextTransition.Reset)
    }

    private fun onTimeline() {
        applyHero()
        listener?.onToneChanged(heroTone())
    }

    private fun applyHero() {
        val visible = timeline.visible
        val wallShown = heroBlur && wallBackdrop?.currentTarget != null
        if (visible && !enteringPending && backdrop.currentTarget == null && heroText.shownSubjectId == null) {
            // 进 hero 态: 换上聚焦那张的内容 (见 showEnteringContent)
            enteringPending = true
            showEnteringContent()
        } else if (!visible && (enteringPending || backdrop.currentTarget != null || wallShown || heroText.shownSubjectId != null)) {
            enteringPending = false
            backdrop.rebuild()
            if (heroBlur) setWallTarget(null)
            heroText.setText(null, TvNativeTextTransition.Reset)
        }
        applyHeroAlpha()
        heroBox.alpha = timeline.text
        backdrop.publishZoom()
        current?.let { g ->
            g.heroAbove = timeline.above
            g.heroTransitioning = timeline.content < 1f && timeline.above > 0f
            g.cards.setTitleVisibility(g, 1f - timeline.above)
        }
    }

    /**
     * 背景图与 hero 态模糊背景的透明度: 都跟着 hero 时间线 (压黑时等底色黑透才淡入); 点开时 ([wallOpenFade]) 背景图随卡片淡没, 底下的模糊背景
     * 变清晰接上. 新番时间表的整屏背景常在, 不归时间线管.
     */
    private fun applyHeroAlpha() {
        val contentGate = if (heroDarkens) tvPosterWallToneGate(timeline.tone) else 1f
        val shown = timeline.content * contentGate
        backdrop.alpha = shown * (1f - wallOpenFade)
        if (heroBlur) wallBackdrop?.alpha = shown
    }

    // ------------------------------------------------------------------
    // 整屏背景 (新番时间表的卡片墙 / 开了 heroBlur 的 hero 态)
    // ------------------------------------------------------------------

    /**
     * hero 态在整页底下铺模糊背景 (设置里的「hero 态铺模糊背景」, 同新番时间表): 进 hero 态时整屏背景 ([wallBackdrop]) 随 hero 时间线淡入,
     * 铺的是停稳后聚焦那部的横版背景图的模糊版 ([TvNativeHeroSource.wall]: 整部的那张, 不是单集剧照); 贴右上角的背景图 ([backdrop]) 与
     * hero 文字照旧画在它上面, 背景图的边缘擦成透明露出模糊背景 ([TvNativeBackdropView.feather]); 整屏底色不压黑 (深色主题也是, 见
     * [heroDarkens]). hero 态里按确定: 卡片、背景图、简介淡没, 模糊背景对焦变清晰 (就是详情页的背景), 到位再进详情页 (见
     * [TvNativeWallFocus]), 代替从背景图放大; 回来倒放. 卡片墙上 (不在 hero 态) 不铺; 长按不对焦, 照旧只弹菜单. 新番时间表
     * ([heroEnabled] = false) 不用它.
     */
    var heroBlur: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                // 连着换卡时模糊层同时只有一张在淡入 (单次按键当场换)
                enableWallBackdrop().coalesceSwaps = true
            } else {
                wallFocus.reset()
                wallBackdrop?.let { removeView(it) }
                wallBackdrop = null
            }
            // 进详情页从整屏背景起, 背景图不登记成放大转场的来源; 叠在模糊背景上时边缘擦成透明
            backdrop.feather = value
            backdrop.zoomSource = !value
            applySource()
            applyHeroTone()
        }

    /** 点开时背景图随卡片淡没的程度 (0..1, 见 [applyHeroAlpha]); 长按的对焦不算. */
    private var wallOpenFade = 0f

    private val wallFocus = TvNativeWallFocus(
        this,
        object : TvNativeWallFocus.Host {
            override val wall: TvNativeWallBackdropView? get() = wallBackdrop
            override val transitions: Boolean get() = this@TvNativeGridPageView.transitions

            override fun applyFade(fade: Float, keep: Int, chrome: Boolean) {
                current?.let { g ->
                    g.pullKeepIndex = keep
                    g.pullFade = fade
                }
                // 点开: 背景图与 hero 文字里标题以外的几行跟着卡片淡没 (标题留着, 交给详情页的标题接着画), 顶栏由页面跟着淡
                wallOpenFade = if (chrome) fade else 0f
                heroText.detailAlpha = 1f - wallOpenFade
                applyHeroAlpha()
                if (chrome) listener?.onWallFade(fade)
            }

            override fun onOpeningChanged(opening: Boolean) {
                listener?.onWallOpeningChanged(opening)
            }

            override fun onOpened(index: Int) {
                listener?.onWallOpened(index)
                listener?.onClick(index)
            }
        },
    )

    /** 建出整屏背景 (页面给了才建, 画在最底下). */
    fun enableWallBackdrop(): TvNativeWallBackdropView =
        wallBackdrop ?: TvNativeWallBackdropView(context, sketch, scope).also { view ->
            view.composeRoot = composeRoot
            view.onSharpReady = { wallFocus.onSharpReady() }
            addView(view, 0)
            wallBackdrop = view
        }

    /**
     * 整屏背景换图 (新番时间表: 页面按停稳后的聚焦条目给; hero 态的模糊背景: 本视图按 hero 内容的 [TvNativeHeroSource.wall] 给). 对焦期间
     * 要清晰图就当场解, 长按时背景还没换到那张卡, 换到了就接着变清晰 (见 [TvNativeWallFocus.onTargetChanged]).
     */
    fun setWallTarget(target: TvNativeWallBackdropTarget?) {
        val wb = wallBackdrop ?: return
        wb.show(target)
        val grid = current
        wallFocus.onTargetChanged(keepMatches = grid != null && wallMatches(grid, wallFocus.keep))
    }

    /** 背景此刻是 [grid] 第 [index] 张卡的、能变清晰. */
    private fun wallMatches(grid: TvNativeGridView, index: Int): Boolean {
        val t = wallBackdrop?.currentTarget ?: return false
        val subject = grid.cards.subjectIdAt(index) ?: return false
        return t.sharp && t.subjectId == subject
    }

    /** 点开途中按了返回: 不进了, 倒放. */
    override fun cancelWallOpen() = wallFocus.cancelOpen()

    /** 从点开进去的详情页回来了 (页面在本页回到前台、缩回层撤掉之后调): 倒放回模糊与卡片墙. */
    override fun endWallOpen() = wallFocus.endOpen()

    /** 恢复点开的状态 (返回时页面重建): 卡片淡没、背景清晰 (清晰图解好就直接出现), 等页面调 [endWallOpen]. */
    override fun restoreWallOpen() = wallFocus.restoreOpen()

    /** 长按的菜单关了 (或没弹出来): 倒放. */
    fun endWallPeek() = wallFocus.endPeek()

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = metrics
        fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.EXACTLY)
        wallBackdrop?.measure(exactly(m.bleedLeftPx + m.pageWidthPx), exactly(m.pageHeightPx))
        backdrop.measure(exactly(m.backdropWidthPx), exactly(m.backdropHeightPx))
        heroBox.measure(exactly(m.heroWidthPx), exactly(m.heroHeightPx))
        val g = m.grid
        val gridHeight = m.pageHeightPx - m.gridTopPx + g.topBleedPx + g.bottomBleedPx
        gridBox.measure(exactly(m.bleedLeftPx + m.pageWidthPx), exactly(gridHeight))
        setMeasuredDimension(m.bleedLeftPx + m.pageWidthPx, m.pageHeightPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val m = metrics
        wallBackdrop?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        val bx = m.bleedLeftPx + m.pageWidthPx - backdrop.measuredWidth
        backdrop.layout(bx, 0, bx + backdrop.measuredWidth, backdrop.measuredHeight)
        val hx = m.bleedLeftPx + m.heroLeftPx
        heroBox.layout(hx, m.heroTopPx, hx + heroBox.measuredWidth, m.heroTopPx + heroBox.measuredHeight)
        val gy = m.gridTopPx - m.grid.topBleedPx
        gridBox.layout(0, gy, gridBox.measuredWidth, gy + gridBox.measuredHeight)
        gridClip.set(0, 0, gridBox.measuredWidth, gridBox.measuredHeight)
        gridBox.clipBounds = gridClip
        backdrop.publishZoom()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutWatcher)
        super.onDetachedFromWindow()
        scrollTracker.stop()
        wallFocus.detach()
        // 停放中离开页面: 收回停放标记, 不然页面的焦点域一直当焦点驻留着, 之后的按键再也取消不了在途送焦
        if (parkedAt >= 0) {
            parkedAt = -1L
            listener?.onFocusParkedChanged(false)
        }
    }
}
