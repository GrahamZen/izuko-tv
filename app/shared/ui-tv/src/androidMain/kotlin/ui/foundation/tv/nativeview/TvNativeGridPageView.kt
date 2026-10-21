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
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.Immutable
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.ui.foundation.focus.TV_TRANSIT_PARK_KEY_GRACE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_ASPECT_RATIO
import me.him188.ani.app.ui.foundation.tv.TV_TAB_CONTENT_SLIDE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvWallConfirm
import me.him188.ani.app.ui.foundation.tv.TvWallTouchTap
import me.him188.ani.app.ui.foundation.tv.tvWallConfirm
import me.him188.ani.app.ui.foundation.tv.tvWallTapConfirmsWhileFocusing
import me.him188.ani.app.ui.foundation.tv.tvWallTouchTap
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_SUMMARY_WIDTH_FRACTION
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_TEXT_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_TITLE_WIDTH_FRACTION
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_WALL_SUMMARY_LINES
import kotlin.math.abs
import kotlin.math.roundToInt

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
 *
 * 除了网格, 还能放一份一组一行的内容 ([rows], 追番页「追更」, 行式海报墙的宾客模式): 占一个 key, 与各份网格同样水平滑着换; hero 态、
 * 背景图与文字、点开对焦都是本页这一份, 跨标签时 hero 态留着, 进详情页同网格.
 */
@SuppressLint("ViewConstructor")
class TvNativeGridPageView(
    context: Context,
    private val sketch: Sketch,
    private val scope: CoroutineScope,
    style: TvNativeWallStyle,
    metrics: TvNativeGridPageMetrics,
    heroTextStyle: TvNativeHeroTextStyle,
) : FrameLayout(context), TvNativeAmbientAnimations, TvNativeWallOpenable, TvGridPageSurface {
    var listener: TvNativeGridPageListener? = null

    /** 第几张卡被绑定 (此刻显示的那份网格; 分页的访问提示). */
    var onBindCard: ((index: Int) -> Unit)? = null

    var style: TvNativeWallStyle = style
        private set
    var metrics: TvNativeGridPageMetrics = metrics
        private set

    /**
     * 有 hero 态: 卡片墙上按确定先切到 hero 态, hero 态里才进详情页. false = 卡片墙上按确定直接交给页面 (新番时间表; 「海报上按确定」不是先看简介时
     * 的追番 / 搜索页). 停在 hero 态时关掉: 当场回卡片墙.
     */
    var heroEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value && timeline.active) setHeroActive(false, animated = false)
        }

    /** 按确定时整屏背景先对焦再交给页面 (新番时间表 / hero 态的模糊背景); false = 当场交给页面 (「海报上按确定」选直接播放). */
    var confirmFocusesWall: Boolean = true

    /**
     * 触屏设备上海报墙左右滑 (左滑 +1, 右滑 −1; 追番页换标签、新番时间表换天), 宿主在触摸开关打开且页面要时给; 电视上为 null, 不装.
     * hero 态里照样认 (同遥控器在行缘按左右跨标签, 新那份网格接着在 hero 态, 见 [showGrid]); 点开途中 (对焦进详情页) 不认.
     */
    var onTouchSwipe: ((delta: Int) -> Unit)? = null
        set(value) {
            if ((field == null) != (value == null)) {
                touchSwipe = value?.let {
                    TvNativeHorizontalSwipe(context, this, { _, _ -> !wallFocus.opening }) { delta -> onTouchSwipe?.invoke(delta) }
                }
            }
            field = value
        }
    private var touchSwipe: TvNativeHorizontalSwipe? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        touchSwipe?.onIntercept(ev) == true || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        // 按在 hero 区 / 空白处而子视图都不接的这一下由本视图接住, 抬起才派发得到 (见 TvNativeHeroBlankTap)
        touchSwipe?.onTouch(event) == true || heroBlankTap?.tracking == true || super.onTouchEvent(event)

    /**
     * 触屏设备 (平板装了 TV 包) 才为 true, 宿主按 LocalTvTouchInputEnabled 给; 电视上恒 false. 手指点卡片照触屏的规矩 (见 [touchMode]);
     * hero 态里点卡片之间的空白处退回卡片墙、点 hero 区打开聚焦那张 (见 [TvNativeHeroBlankTap]).
     */
    var touchInput: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            heroBlankTap = if (value) {
                TvNativeHeroBlankTap(this, { timeline.active && !wallFocus.opening }, ::heroAreaBottomInWindow, ::openFocusedCard) {
                    setHeroActive(false)
                }
            } else {
                null
            }
            applyTouchLook()
        }
    private var heroBlankTap: TvNativeHeroBlankTap? = null

    /**
     * 此刻按触屏的规矩走 (同 iPad 的 Compose 海报墙): 触屏设备上最近一次输入是手指 (见 [lastInputTouch]). 手指点卡片按
     * tvWallTouchTap 判 (按下不动焦点), 卡片墙上不画聚焦效果 (hero 态里照画).
     */
    private val touchMode: Boolean get() = touchInput && lastInputTouch

    /** 本页最近一次收到的输入是手指 (同 TvNativeRowWallView.lastInputTouch). */
    internal var lastInputTouch: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyTouchLook()
        }

    /**
     * 按触屏的规矩走时网格的卡片墙上不画聚焦效果、焦点不带着滚动 (见 [touchMode]; 一组一行的内容自己管). 进出 hero 态、换网格与换输入方式时
     * 对一次.
     */
    private fun applyTouchLook() {
        current?.let {
            it.cards.setTouchLookHidden(it, touchMode && !timeline.active)
            it.touchFree = touchMode
        }
    }

    /** 按触屏的规矩把焦点停在了本视图自己身上 (见 [touchPark]). */
    private var touchParked = false

    /**
     * 按触屏的规矩走时手指拖动网格: 焦点停在本视图自己身上 (持焦的卡随手指滚出屏被回收也不丢焦点、不被系统塞给别处), 之后按方向键落到屏上
     * 离视口中线最近的那一行 (见 [dispatchKeyEvent]). 与换标签的停放 ([parkFocus]) 不同, 不报给页面: 焦点仍算在网格里.
     */
    private fun touchPark() {
        if (isFocused) return
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        isFocusableInTouchMode = true
        touchParked = requestFocus()
        if (!touchParked) unparkFocus()
    }

    /** 按触屏的规矩走时网格被手指拖起来: hero 态里原地退回卡片墙, 焦点停放. */
    private fun onGridDragStart(grid: TvNativeGridView) {
        if (grid !== current || !touchMode) return
        if (timeline.active) setHeroActive(false, animated = true) else touchPark()
    }

    /** 原地退回卡片墙 (按触屏的规矩走时): hero 背景与文字照常淡出, 网格 / 一组一行的内容停在原地. */
    private fun exitHeroInPlace(animated: Boolean) {
        timeline.setActive(false, animated)
        applyTouchLook()
        TvNativeFocusCues.setSpotlight(this, false, animated)
        current?.exitHeroInPlace(animated && animatedScroll)
        rowsShown()?.onHostHeroChanged(animated)
        listener?.onHeroActiveChanged(false)
    }

    /** hero 区 (背景图与文字, hero 态聚焦行行顶以上) 下缘在窗口里的纵坐标. */
    private fun heroAreaBottomInWindow(): Int {
        rowsShown()?.let { return it.heroAreaBottomInWindow() }
        getLocationInWindow(windowXY)
        return windowXY[1] + metrics.gridTopPx + metrics.grid.heroLinePx
    }

    /** 触屏上 hero 态里点 hero 区 (背景图、文字, 及淡没在那里的上面几行): 同点聚焦的那张. */
    private fun openFocusedCard() {
        rowsShown()?.let {
            it.openFocusedCard()
            return
        }
        val grid = current ?: return
        if (timeline.active && grid.selectedPosition >= 0) GridListener(grid).onClick(grid.selectedPosition)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) lastInputTouch = true
        heroBlankTap?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    var transitions: Boolean = true
        set(value) {
            field = value
            rows?.transitions = value
        }
    var animatedScroll: Boolean = true
        set(value) {
            field = value
            grids.forEach { it.animatedScroll = value }
            rows?.animatedScroll = value
        }

    /**
     * hero 舞台 (背景图、hero 文字、整屏背景、hero 时间线、点开的对焦, 见 [TvNativeHeroStage]): 内容怎么换、各层跟着时间线透明到什么程度在那里,
     * 摆在哪里、网格怎么跟着进出 hero 态在本类.
     */
    private val stage: TvNativeHeroStage = TvNativeHeroStage(
        this, sketch, scope, heroTextStyle,
        object : TvNativeHeroStage.Host {
            override val transitions: Boolean get() = this@TvNativeGridPageView.transitions

            override fun onHeroFrame() {
                applyHero()
                listener?.onToneChanged(stage.tone)
            }

            override fun onHeroBlurChanged(enabled: Boolean) {
                // 进详情页从整屏背景起, 背景图不登记成放大转场的来源; 叠在模糊背景上时边缘擦成透明
                backdrop.feather = enabled
                backdrop.zoomSource = !enabled
                applySource()
            }

            override fun applyOpenFade(fade: Float, keep: Int, chrome: Boolean) {
                current?.let { g ->
                    g.pullKeepIndex = keep
                    g.pullFade = fade
                }
                // 一组一行的内容: 点开时各行跟着淡没 (见 TvNativeRowWallView.applyItemFades)
                rowsShown()?.onHostHeroFrame()
                // 点开: 背景图与 hero 文字里标题以外的几行跟着卡片淡没 (标题留着, 交给详情页的标题接着画), 顶栏由页面跟着淡
                applyHeroAlpha()
                if (chrome) listener?.onWallFade(fade)
            }

            override fun onOpeningChanged(opening: Boolean) {
                listener?.onWallOpeningChanged(opening)
            }

            override fun onOpened(index: Int) {
                listener?.onWallOpened(index)
                val rows = rowsShown()
                if (rows != null) rows.onHostWallOpened(index) else listener?.onClick(index)
            }
        },
    )
    private val backdrop: TvNativeBackdropView get() = stage.backdrop
    private val timeline: TvNativeHeroTimeline get() = stage.timeline
    private val wallFocus: TvNativeWallFocus get() = stage.wallFocus

    var composeRoot: View?
        get() = stage.composeRoot
        set(value) {
            stage.composeRoot = value
        }

    /** 深色主题 (见 [TvNativeHeroStage.dark]). */
    var dark: Boolean
        get() = stage.dark
        set(value) {
            stage.dark = value
        }

    var fadeColor: Int
        get() = stage.fadeColor
        set(value) {
            stage.fadeColor = value
        }

    var treatment: TvBackdropTreatment?
        get() = backdrop.treatment
        set(value) {
            backdrop.treatment = value
        }

    /** 此刻在不在导航: 背景图的剧照升档等它为 false 才去取原图 (见 [TvNativeBackdropView.navigating]). */
    var backdropNavigating: () -> Boolean
        get() = stage.backdropNavigating
        set(value) {
            stage.backdropNavigating = value
        }

    val heroText: TvNativeHeroTextView get() = stage.heroText

    /** 最底下的整屏背景 (新番时间表, 见 [enableWallBackdrop]; 开了 [heroBlur] 的 hero 态); null = 没有. */
    val wallBackdrop: TvNativeWallBackdropView? get() = stage.wallBackdrop

    /**
     * 装 hero 文字的一层, hero 时间线的显隐 ([TvNativeHeroTimeline.text]) 调它的透明度: 文字块换字时自己也调自己的透明度 (旧字淡出、新字进场),
     * 两边写同一个属性就是谁后写谁算 —— 进 hero 态途中内容晚到、退出途中换了条目, 字都会以满透明度露出来.
     */
    private val heroBox = object : FrameLayout(context) {
        override fun hasOverlappingRendering(): Boolean = false
    }
    private val gridBox = object : FrameLayout(context) {
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            // 一组一行的内容按整页的坐标排 (它的几何从页顶算, 见 TvNativeExploreMetrics): 往上挪到页顶, 同样只在框里画
            rows?.let { it.layout(0, -top, it.measuredWidth, -top + it.measuredHeight) }
        }
    }

    /** 网格框的裁剪框 (框自己的坐标, 见 init). */
    private val gridClip = Rect()
    private val grids = ArrayList<TvNativeGridView>(2)
    private val gridKeys = HashMap<TvNativeGridView, Any>()
    private var current: TvNativeGridView? = null

    /** 此刻显示的那份内容: 网格 ([current]) 或一组一行的内容 ([rows]). */
    private var shown: View? = null

    /** 一组一行的内容 (见类说明), 页面要了才建 ([ensureRows]); 宾客模式的行式海报墙, 焦点与列表在它那里. */
    var rows: TvNativeRowWallView? = null
        private set
    private var rowsKey: Any? = null
    private var slideAnimator: ValueAnimator? = null
    private val savedPosition = HashMap<Any, Int>()

    /** 返回本页时焦点要回的那张 (见 [holdLandingLook]), -1 = 没有. */
    private var landingHeld = -1
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
        applyHeroTextWidths()
        if (changed) {
            grids.forEach { it.metrics = gridMetrics() }
            requestLayout()
        }
    }

    /**
     * 平板版 hero 的摆法 (宿主在触屏设备上给, 调试开关 tvHeroOnTouch 改回电视的对照; 几何见 TvPadHeroGeometry): hero 区铺到聚焦行行顶为止,
     * 背景图高度铺满它、贴右上角, 文字贴它左下往上长 (简介压成一段、最多 [TV_PAD_HERO_WALL_SUMMARY_LINES] 行), 宽度按页面宽的比例.
     */
    var padHero: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            heroText.padLayout = value
            heroText.summaryCapLines = if (value) TV_PAD_HERO_WALL_SUMMARY_LINES else 0
            applyHeroTextWidths()
            requestLayout()
        }

    /** 平板版: hero 区高 —— 页顶到 hero 态聚焦行的行顶. */
    private val padRegionPx: Int get() = metrics.gridTopPx + metrics.grid.heroLinePx

    private fun applyHeroTextWidths() {
        val m = metrics
        if (padHero) {
            heroText.titleWidthPx = (m.pageWidthPx * TV_PAD_HERO_TITLE_WIDTH_FRACTION).roundToInt()
            heroText.summaryWidthPx = (m.pageWidthPx * TV_PAD_HERO_SUMMARY_WIDTH_FRACTION).roundToInt()
        } else {
            heroText.titleWidthPx = m.titleWidthPx
            heroText.summaryWidthPx = m.summaryWidthPx
        }
    }

    // ------------------------------------------------------------------
    // 网格
    // ------------------------------------------------------------------

    /** 此刻显示的那份网格. */
    val grid: TvNativeGridView? get() = current

    override val columns: Int get() = grid?.metrics?.columns ?: 1

    /** 网格的几何: 起点加上侧边栏底下那一截 (见 [TvNativeGridPageMetrics.bleedLeftPx]). */
    private fun gridMetrics(): TvNativeGridMetrics = metrics.grid.let { it.copy(startPx = it.startPx + metrics.bleedLeftPx) }

    /** 此刻显示的那份内容的 key (网格的, 或一组一行的内容的). */
    override val currentKey: Any? get() = shown?.let { if (it === rows) rowsKey else gridKeys[it] }

    /**
     * 换成 [key] 那份网格 (追番页的标签; 搜索页只有一份). [direction] = 新网格从哪边滑进来 (+1 右边, −1 左边, 0 不滑);
     * 流畅档 / [animated] = false 直接换. 新网格停在这个 key 上次的位置.
     */
    fun showGrid(key: Any, direction: Int, animated: Boolean) {
        val cur = shown
        if (cur != null && cur === current && gridKeys[cur] == key) return
        beginSwitch()
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
        applyTouchLook()
        // 进页恢复的那一张只属于建视图后的第一份网格; 换了标签就作废
        if (cur == null && landingHeld >= 0) next.cards.setFocusLookHeld(next, landingHeld) else landingHeld = -1
        slideIn(cur, next, direction, animated)
    }

    /**
     * 建出一组一行的内容 (已建过就换样式与几何): 宾客模式的行式海报墙, 占 [showRows] 给的那个 key. 列表与焦点的接线 (数据、事件) 由页面对
     * 返回的视图做.
     */
    fun ensureRows(style: TvNativeWallStyle, metrics: TvNativeExploreMetrics, headerStyle: TvNativeTextStyle): TvNativeRowWallView {
        rows?.let {
            it.updateContent(style, metrics)
            return it
        }
        val view = TvNativeRowWallView(context, sketch, scope, style, metrics, headerStyle, RowsGuest())
        view.transitions = transitions
        view.animatedScroll = animatedScroll
        view.visibility = INVISIBLE
        gridBox.addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        rows = view
        return view
    }

    /** 换成一组一行的内容 ([ensureRows] 建的), 占 [key]; 滑动同 [showGrid]. 没建过时不换. */
    fun showRows(key: Any, direction: Int, animated: Boolean) {
        val next = rows ?: return
        val cur = shown
        if (cur === next && rowsKey == key) return
        beginSwitch()
        rowsKey = key
        next.translationX = 0f
        next.visibility = VISIBLE
        // 跟上此刻的 hero 态 (换过来时当场到位, 同网格)
        next.onHostHeroChanged(animated = false)
        current = null
        landingHeld = -1
        slideIn(cur, next, direction, animated)
    }

    /** 换内容之前: 焦点还在要换下去的那份上就先停放 (见类说明), 记下网格的位置, 上一次的滑动收尾. */
    private fun beginSwitch() {
        if (gridBox.hasFocus()) parkFocus()
        current?.let { g -> gridKeys[g]?.let { savedPosition[it] = g.selectedPosition } }
        slideAnimator?.end()
    }

    private fun rowsShown(): TvNativeRowWallView? = rows?.takeIf { shown === it }

    /** 新那份 [next] 从 [direction] 那边滑进来、旧的 [cur] 滑出去 (不滑时当场换). */
    private fun slideIn(cur: View?, next: View, direction: Int, animated: Boolean) {
        shown = next
        val width = gridBox.width.toFloat()
        val slide = cur != null && direction != 0 && animated && transitions && width > 0f
        // 顶栏跟着滚走的量 (见 emitContentScroll): 滑动的话从换之前报出去的值起步, 跟着滑动进度滑到新那份网格的; 不滑动当场换
        contentScrollFrom = reportedContentScroll
        contentScrollProgress = 0f
        contentScrollSliding = slide
        reportContentScroll()
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
                    if (shown !== cur) retire(cur)
                }
            })
            start()
        }
    }

    /** 退场的那份 (网格 / 一组一行的内容): 不画、不收焦点, 网格下次换标签时拿来复用. */
    private fun retire(view: View) {
        // 还持着焦点就先停放: 收起时系统会清掉焦点, 全局兜底把它塞给首个标签
        if (view.hasFocus()) parkFocus()
        view.visibility = INVISIBLE
        view.translationX = 0f
    }

    /**
     * 给 [key] 那份网格换数据 (分页快照; null 是还没到的占位). 不是此刻显示的那份就只记下, 显示时再给.
     *
     * 持焦那一格换成了别的作品 (列表刷新拿掉了聚焦的那部, 后面的往前挪一格): 卡按下标绑定, 持焦的卡原地重绑、焦点没动,
     * 系统不会再报一次聚焦, 页面的 hero 还指着已经没了的那部 ⇒ 这里补报. 末尾那张走了不用管: 卡被摘掉时 RecyclerView
     * 把焦点交给前一张, 照常回调聚焦 (同 TvNativeExploreView 的 followFocusAfterItemsChanged).
     */
    fun setCards(key: Any, cards: List<TvNativeCard?>) {
        val grid = grids.firstOrNull { gridKeys[it] == key } ?: return
        val focused = grid.focusedCardIndex()
        val before = focused?.let { grid.cards.subjectIdAt(it) }
        grid.cards.submit(cards) { it.toLong() }
        val after = focused?.let { grid.cards.subjectIdAt(it) } ?: return
        if (after == before) return
        post { if (grid === current && grid.focusedCardIndex() == focused) grid.listener?.onFocused(focused) }
    }

    private fun TvNativeGridView.focusedCardIndex(): Int? =
        focusedChild?.let { getChildAdapterPosition(it) }?.takeIf { it >= 0 }

    private fun newGrid(): TvNativeGridView {
        val grid = TvNativeGridView(context, style, sketch, gridMetrics())
        grid.animatedScroll = animatedScroll
        grid.listener = GridListener(grid)
        grid.onTouchDragStart = { onGridDragStart(grid) }
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
            when (tvWallConfirm(isMore = false, heroEnabled, timeline.active)) {
                TvWallConfirm.EnterHero -> setHeroActive(true)
                TvWallConfirm.More, TvWallConfirm.Open -> {
                    // 进详情页: 焦点交出去之后这张卡仍画成聚焦态, 返回后焦点交还前也不缩 (见 TvNativeGridView.focusItem)
                    grid.cards.setFocusLookHeld(grid, index)
                    // 有整屏背景 (新番时间表 / hero 态的模糊背景): 先对焦 (背景变清晰、卡片淡没) 再进, 见 TvNativeWallFocus
                    if (!confirmFocusesWall || !wallFocus.open(index, wallMatches(grid, index))) listener?.onClick(index)
                }
            }
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            if (grid !== current) return
            // 新番时间表: 先进对焦再交给页面, 页面弹不出菜单时当场调 endWallPeek. hero 态的模糊背景不对焦, 只弹菜单
            if (!heroEnabled) wallFocus.peek(index, wallMatches(grid, index))
            listener?.onLongPress(index, anchor)
        }

        // hero 态里 / 没有 hero 态 (确认就进详情页或播放) 点一下只换焦点
        override fun tapConfirmsWhileFocusing(index: Int): Boolean =
            grid === current && tvWallTapConfirmsWhileFocusing(isMore = false, heroEnabled, timeline.active)

        override val touchTaps: Boolean get() = touchInput

        override fun onTouchTap(index: Int, card: View) {
            if (grid !== current) return
            val focused = grid.selectedPosition
            val columns = metrics.grid.columns.coerceAtLeast(1)
            val above = focused >= 0 && index / columns < focused / columns
            when (tvWallTouchTap(isMore = false, heroEnabled, timeline.active, card.isFocused, above)) {
                TvWallTouchTap.OpenFocused -> openFocusedCard()
                TvWallTouchTap.FocusOnly -> card.requestFocus()
                TvWallTouchTap.FocusAndConfirm -> {
                    card.requestFocus()
                    onClick(index)
                }

                // 没有 hero 态的页面上点一下就进: 焦点同时送到这张, 回来时落在它上面
                TvWallTouchTap.Confirm -> {
                    if (!card.isFocused) card.requestFocus()
                    // 整屏背景跟着聚焦卡的页面 (新番时间表) 上背景还不是这张的: 当场进, 照点开过记账 —— 回到本页 (页面重建) 按点开的样子恢复,
                    // 这张的清晰图淡回它的模糊底 (同 iPad 的 Compose 海报墙)
                    if (!timeline.active && confirmFocusesWall && wallBackdrop != null && !wallMatches(grid, index)) listener?.onWallOpened(index)
                    onClick(index)
                }
            }
        }

        override fun onTopRowUp(): Boolean = listener?.onTopRowUp() ?: false

        // 换下去的那份网格不再报行缘 (焦点还没交出去时的连发): 否则一路连跨标签
        override fun onRowEdge(direction: Int, row: Int): Boolean =
            if (grid !== current) true else listener?.onRowEdge(direction, row) ?: false
    }

    /** 送焦到此刻那份网格的第 [index] 张 (排出来之前记下, 布局完成再送). */
    override fun focusItem(index: Int) {
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
    override fun firstIndexBelowTopLine(): Int? = current?.firstIndexBelowTopLine()

    private val windowXY = IntArray(2)

    /** 此刻那份网格里聚焦那张卡的中线在窗口里的纵坐标 (换到别的标签时就近找行用); 焦点不在网格的卡上时 null. */
    override fun focusedCardCenterYInWindow(): Int? {
        rowsShown()?.let { return it.focusedCardCenterYInWindow() }
        val grid = current ?: return null
        val card = grid.findFocus()?.takeIf { it !== grid } ?: return null
        card.getLocationInWindow(windowXY)
        return windowXY[1] + card.height / 2
    }

    /**
     * 此刻那份网格已排出来的卡里, 中线离 [windowY] (窗口坐标) 最近的那一行 (行号); 一张都没排出来时 null. 从别的版式的标签 (追番页「追更」的
     * 一组一行) 换过来时按屏上的高度就近接住.
     */
    override fun rowNearestWindowY(windowY: Int): Int? {
        val grid = current ?: return null
        val columns = metrics.grid.columns.coerceAtLeast(1)
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val position = grid.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            child.getLocationInWindow(windowXY)
            val distance = abs(windowXY[1] + child.height / 2 - windowY)
            if (distance < bestDistance) {
                bestDistance = distance
                best = position / columns
            }
        }
        return best
    }

    /** 远跳到第 [index] 张 (返回键回首卡), 见 [TvNativeGridView.farJumpTo]. */
    fun farJumpTo(index: Int) {
        current?.farJumpTo(index)
    }

    /** 此刻那份网格的远跳还在滚就当场落地, 返回是否有远跳在滚. 见 [TvNativeGridView.landFarJump]. */
    override fun landFarJump(): Boolean = current?.landFarJump() == true

    /** 各份网格此刻的选中位置 (页面重建时恢复用). */
    override fun savedPositions(): Map<Any, Int> {
        current?.let { g -> gridKeys[g]?.let { savedPosition[it] = g.selectedPosition } }
        return savedPosition
    }

    fun restorePositions(positions: Map<out Any, Int>) {
        savedPosition.putAll(positions)
    }

    /** 忘掉 [key] 那份网格的位置: 下次显示从第一行排起 (新番时间表在日期行上换天, 换过去的那天从头看). */
    override fun forgetPosition(key: Any) {
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
        rowsShown()?.let { return it.requestFocus(direction, previouslyFocusedRect) }
        val g = current ?: return false
        val count = g.cards.itemCount
        if (count == 0) return false
        g.focusItem(g.selectedPosition.coerceIn(0, count - 1))
        return g.hasFocus() || super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        // 停放的焦点落地了 (新那份网格的卡拿到焦点)
        touchParked = false
        unparkFocus()
        // 一组一行的内容拿到焦点不算 (它自己报卡片区的焦点)
        setGridFocus(child === gridBox && current?.hasFocus() == true)
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
        if (event.action == KeyEvent.ACTION_DOWN && tvNativeIsWallInputKey(event.keyCode)) lastInputTouch = false
        TvNativeFocusCues.onKey(this, event)
        val code = event.keyCode
        val direction = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        // 整屏背景: 点开途中吞掉方向键与确认键, 按下确认键当场解清晰图 (见 TvNativeWallFocus.handleKey)
        if (wallFocus.handleKey(event)) return true
        if (isFocused && touchParked && direction && event.action == KeyEvent.ACTION_DOWN) {
            // 按触屏的规矩停放后按了方向键 (回到电视的规矩): 落到屏上离中线最近的那一行、上次那一列
            touchParked = false
            val grid = current
            val columns = metrics.grid.columns.coerceAtLeast(1)
            val target = grid?.indexNearestViewportCenter(grid.selectedPosition.coerceAtLeast(0) % columns)
            unparkFocus()
            if (grid != null && target != null) grid.focusItem(target) else rowsShown()?.requestCardsFocus()
            return true
        }
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
        applyTouchLook()
        TvNativeFocusCues.attachPage(this)
    }

    private fun setGridFocus(has: Boolean) {
        if (gridHasFocus == has) return
        gridHasFocus = has
        listener?.onGridFocusChanged(has)
    }

    // ------------------------------------------------------------------
    // hero 态
    // ------------------------------------------------------------------

    override val heroActive: Boolean get() = timeline.active

    /**
     * 进 / 出 hero 态 (返回键在页面里, 调这里). [animated] = false 时直接到位 (返回本页时恢复). 淡入淡出 (hero 时间线) 三档都有 ——
     * 流畅档也走, 聚焦行照旧直接跳到位 ([animatedScroll]): 贵的是行的滚动动画, 淡入淡出只改几层的透明度.
     */
    override fun setHeroActive(active: Boolean, animated: Boolean) {
        if (timeline.active == active || active && !heroEnabled) return
        // 按触屏的规矩走时退出 (按返回、点空白处、拖动): 停在原地; 网格上的焦点停放 (一组一行的内容自己停放)
        if (!active && touchMode) {
            exitHeroInPlace(animated)
            if (rowsShown() == null) touchPark()
            return
        }
        timeline.setActive(active, animated)
        applyTouchLook()
        TvNativeFocusCues.setSpotlight(this, active, animated)
        current?.setHeroActive(active, animated = animated && animatedScroll)
        rowsShown()?.onHostHeroChanged(animated)
        listener?.onHeroActiveChanged(active)
        // 退出途中又进来 (内容还在屏上): 退出时内容定格没跟着导航换 (见 applySource), 这里对上此刻的
        if (active) applySource()
    }

    /** hero 的内容 (停稳后的背景图、文字; 页面按 rememberTvSettledHeroProvider / rememberTvScrollHiddenProvider 算好). */
    override fun setSource(source: TvNativeHeroSource) {
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
            heroText.setText(stage.textOf(src), TvNativeTextTransition.Key)
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
        val focusedSubject = if (grid != null) grid.cards.subjectIdAt(grid.selectedPosition) else rowsShown()?.focusedSubjectId
        if (focusedSubject != null && (src.rawSubjectId != focusedSubject || src.dimming)) return
        enteringPending = false
        backdrop.show(src.backdrop, crossfade = false)
        if (heroBlur) setWallTarget(src.wall)
        heroText.setText(stage.textOf(src), TvNativeTextTransition.Reset)
    }

    private fun applyHero() {
        applyTouchLook()
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
        rowsShown()?.onHostHeroFrame()
    }

    /**
     * 背景图与 hero 态模糊背景的透明度: 都跟着 hero 时间线 (压黑时等底色黑透才淡入, 见 [TvNativeHeroStage.contentAlpha]); 点开时
     * ([TvNativeHeroStage.openFade]) 背景图随卡片淡没, 底下的模糊背景变清晰接上. 新番时间表的整屏背景常在, 不归时间线管.
     */
    private fun applyHeroAlpha() {
        val shown = stage.contentAlpha
        backdrop.alpha = shown * (1f - stage.openFade)
        if (heroBlur) wallBackdrop?.alpha = shown
    }

    // ------------------------------------------------------------------
    // 整屏背景 (新番时间表的卡片墙 / 开了 heroBlur 的 hero 态)
    // ------------------------------------------------------------------

    /**
     * hero 态在整页底下铺模糊背景 (见 [TvNativeHeroStage.heroBlur]): 贴右上角的背景图与 hero 文字照旧画在它上面, 背景图的边缘擦成透明露出模糊背景
     * ([TvNativeBackdropView.feather]); hero 态里按确定由模糊背景对焦变清晰再进详情页, 代替从背景图放大. 卡片墙上 (不在 hero 态) 不铺;
     * 长按不对焦, 照旧只弹菜单. 新番时间表 ([heroEnabled] = false) 不用它.
     */
    var heroBlur: Boolean
        get() = stage.heroBlur
        set(value) {
            stage.heroBlur = value
        }

    /** 建出整屏背景 (页面给了才建, 画在最底下). */
    fun enableWallBackdrop(): TvNativeWallBackdropView = stage.enableWallBackdrop()

    /**
     * 整屏背景换图 (新番时间表: 页面按停稳后的聚焦条目给; hero 态的模糊背景: 本视图按 hero 内容的 [TvNativeHeroSource.wall] 给). 对焦期间
     * 要清晰图就当场解, 长按时背景还没换到那张卡, 换到了就接着变清晰 (见 [TvNativeWallFocus.onTargetChanged]).
     */
    fun setWallTarget(target: TvNativeWallBackdropTarget?) {
        stage.showWall(target) {
            val grid = current
            if (grid != null) {
                wallMatches(grid, wallFocus.keep)
            } else {
                rowsShown()?.openingSubjectId(wallFocus.keep)?.let { wallMatchesSubject(it) } == true
            }
        }
    }

    /** 背景此刻是 [grid] 第 [index] 张卡的、能变清晰. */
    private fun wallMatches(grid: TvNativeGridView, index: Int): Boolean {
        val subject = grid.cards.subjectIdAt(index) ?: return false
        return wallMatchesSubject(subject)
    }

    /** 背景此刻是 [subjectId] 那部的、能变清晰. */
    private fun wallMatchesSubject(subjectId: Int): Boolean {
        val t = wallBackdrop?.currentTarget ?: return false
        return t.sharp && t.subjectId == subjectId
    }

    /** 一组一行的内容 ([rows]) 用本页的 hero: 进出 hero 态、点开前核对模糊背景都交给本页. */
    private inner class RowsGuest : TvNativeRowWallGuest {
        override val stage: TvNativeHeroStage get() = this@TvNativeGridPageView.stage

        override fun requestHeroActive(active: Boolean) = setHeroActive(active)

        override fun wallMatches(subjectId: Int): Boolean = wallMatchesSubject(subjectId)
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
        if (padHero) {
            // 平板版: 图高铺满 hero 区 (16:9), 文字块从 hero 区顶上的那条线排到文字下沿, 贴底排 (见 TvNativeHeroTextView.padLayout)
            val region = padRegionPx
            backdrop.measure(exactly((region * TV_BACKDROP_ASPECT_RATIO).roundToInt()), exactly(region))
            val textBottom = region - (TV_PAD_HERO_TEXT_BOTTOM.value * resources.displayMetrics.density).roundToInt()
            heroBox.measure(exactly((m.pageWidthPx * TV_PAD_HERO_TITLE_WIDTH_FRACTION).roundToInt()), exactly(textBottom - m.heroTopPx))
        } else {
            backdrop.measure(exactly(m.backdropWidthPx), exactly(m.backdropHeightPx))
            heroBox.measure(exactly(m.heroWidthPx), exactly(m.heroHeightPx))
        }
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
        TvNativeFocusCues.detachPage(this)
        super.onDetachedFromWindow()
        scrollTracker.stop()
        stage.detach()
        // 停放中离开页面: 收回停放标记, 不然页面的焦点域一直当焦点驻留着, 之后的按键再也取消不了在途送焦
        if (parkedAt >= 0) {
            parkedAt = -1L
            listener?.onFocusParkedChanged(false)
        }
    }
}
