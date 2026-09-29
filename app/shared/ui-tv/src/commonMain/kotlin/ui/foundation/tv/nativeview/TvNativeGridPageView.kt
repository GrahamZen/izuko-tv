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

/**
 * 网格页 (追番 / 搜索) 原生海报墙的几何 (px, 页面坐标; 页面 = 主壳里让开侧边栏之后的那块, 搜索页是整页减去侧边栏).
 *
 * @param gridTopPx 网格顶线离页面顶 (顶栏 / 标签行 / 筛选条之下).
 * @param heroLeftPx / [heroTopPx] / [heroWidthPx] / [heroHeightPx] hero 文字块 (不占布局, 画在网格底下).
 * @param bleedLeftPx 原生视图比页面往左多画的一截 (收起的侧边栏宽, 同探索页): 网格从侧边栏底下画过, 最左一列的放大与投影、换标签时滑出的
 *   网格不在页面左缘被裁掉. 横坐标 (网格起点、hero 文字) 仍按页面坐标给, 本视图自己加上这一截.
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
 * 新番时间表 (没有 hero 态) 在最底下另铺一层整屏背景 ([enableWallBackdrop]): 聚焦那部的模糊版, 点按 / 长按时对焦 (见「整屏背景的对焦」一节).
 */
@SuppressLint("ViewConstructor")
class TvNativeGridPageView(
    context: Context,
    private val sketch: Sketch,
    private val scope: CoroutineScope,
    style: TvNativeWallStyle,
    metrics: TvNativeGridPageMetrics,
    heroTextStyle: TvNativeHeroTextStyle,
) : FrameLayout(context), TvNativeAmbientAnimations {
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

    /** 深色主题: hero 态整屏压黑, 背景图等黑透才露面. */
    var dark: Boolean = true
        set(value) {
            field = value
            timeline.dark = value
            applyHero()
        }

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
        timeline.dark = dark
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

    /** 网格的几何: 起点加上往左出血的那一截 (见 [TvNativeGridPageMetrics.bleedLeftPx]). */
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
        next.pullFade = cardsFade
        next.pullKeepIndex = wallKeep
        savedPosition[key]?.let { next.selectedPosition = it }
        current = next
        val width = gridBox.width.toFloat()
        if (cur == null || direction == 0 || !animated || !transitions || width <= 0f) {
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
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    slideAnimator = null
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
                if (recyclerView === current) scrollTracker.onMoved(dy)
            }
        })
        grids.add(grid)
        gridBox.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return grid
    }

    private inner class GridListener(private val grid: TvNativeGridView) : TvNativeGridListener {
        override fun onFocused(index: Int) {
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
                // 有整屏背景: 先对焦 (背景变清晰、卡片淡没) 再进, 见 openWithWallFocus
                if (!openWithWallFocus(grid, index)) listener?.onClick(index)
            }
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            if (grid !== current) return
            // 先进对焦再交给页面: 页面弹不出菜单时当场调 endWallPeek
            peekWallFocus(grid, index)
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
        // 整屏背景点开途中 (对焦还没到位): 方向键与确认键吞掉 (返回键交给页面, 见 cancelWallOpen)
        if (wallOpening >= 0 && (direction || tvNativeIsConfirmKey(code))) return true
        // 按下确认键: 整屏背景当场解清晰图 (点开 / 长按都要它), 不等停留; 按住到抬起 / 长按阈值的这段正好用来解
        if (tvNativeIsConfirmKey(code) && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) wallBackdrop?.prepareSharp()
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
        // 背景图与文字只在 hero 态画 (底色黑透之后淡入); 离开 hero 态淡完就撤. 退出途中内容定格, 不跟着导航换 (正在淡出的图与字换一张
        // 只会晃一下)
        if (!timeline.visible || !timeline.active) return
        if (enteringPending) {
            showEnteringContent()
        } else {
            backdrop.show(src.backdrop)
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
        heroText.setText(src.text, TvNativeTextTransition.Reset)
    }

    private fun onTimeline() {
        applyHero()
        listener?.onToneChanged(timeline.tone)
    }

    private fun applyHero() {
        val visible = timeline.visible
        if (visible && !enteringPending && backdrop.currentTarget == null && heroText.shownSubjectId == null) {
            // 进 hero 态: 换上聚焦那张的内容 (见 showEnteringContent)
            enteringPending = true
            showEnteringContent()
        } else if (!visible && (enteringPending || backdrop.currentTarget != null || heroText.shownSubjectId != null)) {
            enteringPending = false
            backdrop.rebuild()
            heroText.setText(null, TvNativeTextTransition.Reset)
        }
        val contentGate = if (dark) tvPosterWallToneGate(timeline.tone) else 1f
        backdrop.alpha = timeline.content * contentGate
        heroBox.alpha = timeline.text
        backdrop.publishZoom()
        current?.let { g ->
            g.heroAbove = timeline.above
            g.heroTransitioning = timeline.content < 1f && timeline.above > 0f
            g.cards.setTitleVisibility(g, 1f - timeline.above)
        }
    }

    // ------------------------------------------------------------------
    // 整屏背景的对焦 (新番时间表)
    // ------------------------------------------------------------------

    /**
     * 整屏背景的对焦. 长按卡片 ([WallFocus.Peek]): 别的卡淡没、被长按那张留着, 背景变清晰 (收藏菜单弹在旁边), 菜单关了倒放 ([endWallPeek]).
     * 点开 ([WallFocus.Open]): 卡片全部淡没 (顶栏跟着淡, 见 [TvNativeGridPageListener.onWallFade])、背景变清晰, 到位后才进详情页 —— 放大转场
     * 从这张整屏清晰图起 (全屏对全屏, 图原地不动); 从详情页回来倒放 ([endWallOpen]). 卡片淡没 [TV_WALL_BACKDROP_CARDS_MILLIS], 背景变清晰更慢
     * ([TV_WALL_BACKDROP_SHARPEN_MILLIS], 两头慢的缓动): 卡片先让开, 图再慢慢对上焦; 点开时背景走完才进.
     *
     * 点开时背景还不是这张卡的 (停下来之前就按了) / 没有清晰图 (竖版封面) 就当场进, 不对焦. 清晰图还没解好: 卡片照样当场开始淡 (按下的反馈),
     * 背景等它最多 [TV_WALL_BACKDROP_OPEN_WAIT_MILLIS], 等不到就不对焦直接进 (状态照旧停在点开, 回来倒放). 流畅档 ([transitions] = false)
     * 各段当场到位.
     */
    private enum class WallFocus { None, Peek, Open }

    private var wallFocus = WallFocus.None

    /** 对焦时留着不淡的那张 (长按的那张); -1 = 全部淡没. */
    private var wallKeep = -1

    /** 点开途中 (对焦还没到位, 还没进详情页) 的那张; -1 = 没有. */
    private var wallOpening = -1
    private var wallOpenStarted = false
    private var wallWait: Runnable? = null

    /** 这一次对焦是点开 (顶栏跟着卡片淡, 倒放完才撤). */
    private var wallChrome = false
    private var cardsFade = 0f
    private var cardsTween: TvNativeFrameTween? = null
    private var sharpGoal = 0f
    private var sharpEnd: (() -> Unit)? = null
    private var sharpTween: TvNativeFrameTween? = null

    /** 建出整屏背景 (页面给了才建, 画在最底下). */
    fun enableWallBackdrop(): TvNativeWallBackdropView =
        wallBackdrop ?: TvNativeWallBackdropView(context, sketch, scope).also { view ->
            view.composeRoot = composeRoot
            view.onSharpReady = { onWallSharpReady() }
            addView(view, 0)
            wallBackdrop = view
        }

    /**
     * 整屏背景换图 (页面按停稳后的聚焦条目给). 对焦期间要清晰图就当场解 (恢复点开的状态时, 目标在视图建好之后才到); 长按时背景还没换到
     * 那张卡, 换到了就接着变清晰.
     */
    fun setWallTarget(target: TvNativeWallBackdropTarget?) {
        val wb = wallBackdrop ?: return
        wb.show(target)
        if (wallFocus == WallFocus.None) return
        wb.prepareSharp()
        val grid = current
        if (wallFocus == WallFocus.Peek && sharpGoal == 0f && grid != null && wallMatches(grid, wallKeep)) animateSharp(1f)
    }

    /** 背景此刻是 [grid] 第 [index] 张卡的、能变清晰. */
    private fun wallMatches(grid: TvNativeGridView, index: Int): Boolean {
        val t = wallBackdrop?.currentTarget ?: return false
        val subject = grid.cards.subjectIdAt(index) ?: return false
        return t.sharp && t.subjectId == subject
    }

    /** 点开第 [index] 张: 能对焦就开始对焦, 到位后再进 (返回 true); 否则 false, 调用方当场进. */
    private fun openWithWallFocus(grid: TvNativeGridView, index: Int): Boolean {
        val wb = wallBackdrop ?: return false
        if (wallFocus != WallFocus.None || !wallMatches(grid, index)) return false
        wallFocus = WallFocus.Open
        wallKeep = -1
        wallChrome = true
        wallOpening = index
        wallOpenStarted = false
        applyWallKeep()
        listener?.onWallOpeningChanged(true)
        // 卡片与顶栏当场开始淡 (按下的反馈), 背景等清晰图就位再对焦
        animateCardsFade(1f)
        wb.prepareSharp()
        if (wallOpenStarted) return true
        if (wb.sharpReady) {
            startWallOpen()
        } else {
            val wait = Runnable { if (wallOpening >= 0 && !wallOpenStarted) giveUpWallOpen() }
            wallWait = wait
            postDelayed(wait, TV_WALL_BACKDROP_OPEN_WAIT_MILLIS)
        }
        return true
    }

    private fun startWallOpen() {
        wallOpenStarted = true
        cancelWallWait()
        animateSharp(1f) { finishWallOpen() }
    }

    /** 对焦到位: 进详情页. 状态停在点开 (卡片淡没、背景清晰), 回来时页面调 [endWallOpen] 倒放. */
    private fun finishWallOpen() {
        val index = wallOpening
        if (index < 0) return
        wallOpening = -1
        listener?.onWallOpeningChanged(false)
        listener?.onWallOpened(index)
        listener?.onClick(index)
    }

    /** 清晰图等不到: 不对焦, 直接进. 卡片已经淡了 —— 状态照旧停在点开 (背景留在模糊版), 回来时页面调 [endWallOpen] 倒放. */
    private fun giveUpWallOpen() {
        val index = wallOpening
        if (index < 0) return
        wallOpening = -1
        listener?.onWallOpeningChanged(false)
        listener?.onWallOpened(index)
        listener?.onClick(index)
    }

    private fun cancelWallWait() {
        wallWait?.let { removeCallbacks(it) }
        wallWait = null
    }

    /** 点开途中按了返回: 不进了, 倒放. */
    fun cancelWallOpen() {
        if (wallOpening < 0) return
        wallOpening = -1
        cancelWallWait()
        listener?.onWallOpeningChanged(false)
        releaseWallFocus()
    }

    /** 从点开进去的详情页回来了 (页面在本页回到前台、缩回层撤掉之后调): 倒放回模糊与卡片墙. */
    fun endWallOpen() {
        if (wallFocus == WallFocus.Open && wallOpening < 0) releaseWallFocus()
    }

    /** 恢复点开的状态 (返回时页面重建): 卡片淡没、背景清晰 (清晰图解好就直接出现), 等页面调 [endWallOpen]. */
    fun restoreWallOpen() {
        val wb = wallBackdrop ?: return
        if (wallFocus != WallFocus.None) return
        wallFocus = WallFocus.Open
        wallKeep = -1
        wallChrome = true
        applyWallKeep()
        cardsTween?.cancel()
        setCardsFade(1f)
        sharpTween?.cancel()
        sharpGoal = 1f
        sharpEnd = null
        wb.sharpness = 1f
        wb.prepareSharp()
    }

    /** 长按第 [index] 张: 别的卡淡没, 背景是这张卡的就变清晰. */
    private fun peekWallFocus(grid: TvNativeGridView, index: Int) {
        val wb = wallBackdrop ?: return
        if (wallFocus != WallFocus.None) return
        wallFocus = WallFocus.Peek
        wallKeep = index
        applyWallKeep()
        animateCardsFade(1f)
        if (wallMatches(grid, index)) {
            wb.prepareSharp()
            animateSharp(1f)
        }
    }

    /** 长按的菜单关了 (或没弹出来): 倒放. */
    fun endWallPeek() {
        if (wallFocus == WallFocus.Peek) releaseWallFocus()
    }

    private fun releaseWallFocus() {
        wallFocus = WallFocus.None
        animateCardsFade(0f) {
            wallKeep = -1
            applyWallKeep()
            wallChrome = false
        }
        animateSharp(0f)
    }

    private fun applyWallKeep() {
        current?.pullKeepIndex = wallKeep
    }

    private fun setCardsFade(value: Float) {
        cardsFade = value
        current?.pullFade = value
        if (wallChrome) listener?.onWallFade(value)
    }

    /** 两段过渡都按帧推进 ([TvNativeFrameTween]): 长按时收藏菜单那个新窗口一出来主线程会卡一下, 按墙钟算进度的话背景直接跳到清晰. */
    private fun animateCardsFade(to: Float, onEnd: (() -> Unit)? = null) {
        cardsTween?.cancel()
        if (!transitions || cardsFade == to) {
            setCardsFade(to)
            onEnd?.invoke()
            return
        }
        val from = cardsFade
        cardsTween = TvNativeFrameTween(
            TV_WALL_BACKDROP_CARDS_MILLIS, TV_NATIVE_FAST_OUT_SLOW_IN,
            onUpdate = { f -> setCardsFade(from + (to - from) * f) },
            onEnd = onEnd,
        ).also { it.start() }
    }

    /** 背景清晰层的透明度走到 [to]. 清晰图还没解好就先记下, 解好时 ([onWallSharpReady]) 接着走. */
    private fun animateSharp(to: Float, onEnd: (() -> Unit)? = null) {
        val wb = wallBackdrop ?: return
        sharpGoal = to
        sharpEnd = onEnd
        sharpTween?.cancel()
        if (!wb.sharpReady) {
            // 显示不出来: 从 0 起 (解好时从 0 走过去)
            wb.sharpness = 0f
            return
        }
        runSharpAnimation(wb)
    }

    private fun runSharpAnimation(wb: TvNativeWallBackdropView) {
        val to = sharpGoal
        val onEnd = sharpEnd
        sharpEnd = null
        if (!transitions || wb.sharpness == to) {
            wb.sharpness = to
            onEnd?.invoke()
            return
        }
        val from = wb.sharpness
        sharpTween = TvNativeFrameTween(
            TV_WALL_BACKDROP_SHARPEN_MILLIS, TV_WALL_BACKDROP_SHARPEN_EASING,
            onUpdate = { f -> wb.sharpness = from + (to - from) * f },
            onEnd = onEnd,
        ).also { it.start() }
    }

    private fun onWallSharpReady() {
        val wb = wallBackdrop ?: return
        if (wallOpening >= 0 && !wallOpenStarted) {
            startWallOpen()
        } else if (wb.sharpness != sharpGoal && sharpTween?.running != true) {
            runSharpAnimation(wb)
        }
    }

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
        super.onDetachedFromWindow()
        scrollTracker.stop()
        cancelWallWait()
        cardsTween?.cancel()
        sharpTween?.cancel()
        // 停放中离开页面: 收回停放标记, 不然页面的焦点域一直当焦点驻留着, 之后的按键再也取消不了在途送焦
        if (parkedAt >= 0) {
            parkedAt = -1L
            listener?.onFocusParkedChanged(false)
        }
    }
}
