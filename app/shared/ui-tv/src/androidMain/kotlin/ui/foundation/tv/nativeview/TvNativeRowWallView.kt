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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.unit.Dp
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PAST_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_ASPECT_RATIO
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_STILL_FRAMES
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_STILL_PX
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvKeyRepeatGate
import me.him188.ani.app.ui.foundation.tv.TvWallConfirm
import me.him188.ani.app.ui.foundation.tv.TvWallTouchTap
import me.him188.ani.app.ui.foundation.tv.tvWallConfirm
import me.him188.ani.app.ui.foundation.tv.tvWallTapConfirmsWhileFocusing
import me.him188.ani.app.ui.foundation.tv.tvWallTouchTap
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_SUMMARY_LINES
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_SUMMARY_WIDTH_FRACTION
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_TEXT_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_TITLE_WIDTH_FRACTION
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_WALL_SUMMARY_LINES
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 行式海报墙作为别的页面里的一份内容 (追番页「追更」在网格页 [TvNativeGridPageView] 里, 与各分类的网格换着显示): hero (背景图、hero 文字、
 * 模糊背景、时间线、点开的对焦) 都是宿主页面的 [stage], 本视图只管列表与焦点, 跟着宿主的 hero 时间线挪停位、淡各行.
 */
internal interface TvNativeRowWallGuest {
    val stage: TvNativeHeroStage

    /** 进 / 出 hero 态 (卡片墙上按确定、页面按返回): 宿主切, 切完调 [TvNativeRowWallView.onHostHeroChanged]. */
    fun requestHeroActive(active: Boolean)

    /** 宿主的模糊背景此刻是 [subjectId] 那部的、能变清晰 (hero 态里按确定先对焦再进详情页). */
    fun wallMatches(subjectId: Int): Boolean
}

/**
 * 行式海报墙: 组标题 + 横滑行 ([TvNativeExploreList]), 卡片的 hero 态 (背景图 < hero 文字 < 卡片列表, 见 [TvNativeHeroStage]; 列表压在
 * hero 上面: 离场的行淡出时从 hero 上面滑过). 数据、hero 媒体流水线、换挡规则、返回键分层留在 Compose 页面里 ([TvNativeRowWallListener] 回调 +
 * 本类的 set / focus 方法); 焦点、滚动、hero 态时间线与逐帧的联动 (hero 态里聚焦行停在 hero 线、上面几行淡没) 在这里, 每帧只动 RenderNode 属性.
 *
 * 焦点规则: 上下键落到上一行 / 下一行屏上同一列那张 (行短了落到最后一张), 首行按上交给顶部区域 / 页面 ([onFirstRowUp]); 行首按左出页面
 * (侧边栏), 行尾按右交给页面 ([TvNativeRowWallListener.onExitRight]), 末行按下吞掉. 长按方向键每一发连发都走一格 (上限同 tvFocusMoveRateLimit,
 * 高于系统连发). 远跳 ([focusCard] far) 途中焦点停放在本视图上, 方向键当场落地, 确认键排队到目标.
 *
 * 顶部区域 (可选, 子类给): 列表顶上自己的一块 hero, 焦点在它上面时背景图与文字显示它的内容 (探索页的热门轮播与两颗按钮, 见 [TvNativeExploreView]).
 * 子类覆盖 [applyFrame] / [applySources] / [onShowsCardChanged] 处理两路内容的交接与背景图尺寸, 覆盖 [onFirstRowUp] 等几处决定焦点往哪去.
 * 本类自己只有卡片的 hero: 不在 hero 态时背景图与文字不画.
 *
 * 宾客模式 ([TvNativeRowWallGuest], 只给网格页用): 不建自己的 hero 舞台、不画背景图与文字, 用宿主的舞台 —— 进出 hero 态由宿主切
 * ([onHostHeroChanged]), 逐帧联动由宿主推 ([onHostHeroFrame]), 点开对焦到位由宿主转过来 ([onHostWallOpened]); 按键先经宿主
 * (聚焦提示、点开途中吞键). 换标签时与网格一起水平滑, hero 态跨标签留着, 同网格之间换标签.
 */
@SuppressLint("ViewConstructor")
open class TvNativeRowWallView private constructor(
    context: Context,
    protected val sketch: Sketch,
    protected val scope: CoroutineScope,
    style: TvNativeWallStyle,
    metrics: TvNativeExploreMetrics,
    heroTextStyle: TvNativeHeroTextStyle?,
    headerStyle: TvNativeTextStyle,
    private val guest: TvNativeRowWallGuest?,
) : FrameLayout(context), TvNativeAmbientAnimations, TvNativeWallOpenable, TvRowWallSurface {
    constructor(
        context: Context,
        sketch: Sketch,
        scope: CoroutineScope,
        style: TvNativeWallStyle,
        metrics: TvNativeExploreMetrics,
        heroTextStyle: TvNativeHeroTextStyle,
        headerStyle: TvNativeTextStyle,
    ) : this(context, sketch, scope, style, metrics, heroTextStyle, headerStyle, null)

    /** 宾客模式 (见类说明): hero 用宿主页面的舞台. */
    internal constructor(
        context: Context,
        sketch: Sketch,
        scope: CoroutineScope,
        style: TvNativeWallStyle,
        metrics: TvNativeExploreMetrics,
        headerStyle: TvNativeTextStyle,
        guest: TvNativeRowWallGuest,
    ) : this(context, sketch, scope, style, metrics, null, headerStyle, guest)

    /** 事件; 探索页由 [TvNativeExploreView.listener] 给. */
    override var rowListener: TvNativeRowWallListener? = null

    var style: TvNativeWallStyle = style
        private set
    var metrics: TvNativeExploreMetrics = metrics
        private set

    /** 视觉效果: 过渡 (轮播 ↔ 卡片的背景图尺寸动画等) 与动画滚动. */
    var transitions: Boolean = true
    var animatedScroll: Boolean = true
        set(value) {
            field = value
            list.scroll.animated = value
        }

    /** hero 舞台 (背景图、hero 文字、整屏模糊背景、hero 时间线、点开的对焦, 见 [TvNativeHeroStage]); 宾客模式下是宿主的. */
    internal val stage: TvNativeHeroStage = guest?.stage ?: TvNativeHeroStage(this, sketch, scope, checkNotNull(heroTextStyle), StageHost())
    protected val backdrop: TvNativeBackdropView get() = stage.backdrop
    protected val timeline: TvNativeHeroTimeline get() = stage.timeline
    internal val wallFocus: TvNativeWallFocus get() = stage.wallFocus
    val heroText: TvNativeHeroTextView get() = stage.heroText

    /** Compose 根视图 (放大转场的框按它的坐标登记). */
    var composeRoot: View?
        get() = stage.composeRoot
        set(value) {
            stage.composeRoot = value
        }

    /** 此刻在不在导航: 背景图的剧照升档等它为 false 才去取原图 (见 [TvNativeBackdropView.navigating]). */
    var backdropNavigating: () -> Boolean
        get() = stage.backdropNavigating
        set(value) {
            stage.backdropNavigating = value
        }

    /**
     * 有 hero 态: 卡片上按确定先切到 hero 态, hero 态里按确定才进详情页. false = 没有 hero 态, 卡片上按确定直接交给页面
     * ([TvNativeRowWallListener.onCardClick], 页面按设置播放或进详情页). 停在 hero 态时关掉: 当场回卡片墙.
     */
    override var heroEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value && timeline.active) setHeroActive(false)
        }

    /**
     * 钉死锚点版式 (探索页「固定焦点」风格, 见 TvExploreLayout): 横滑行钉死锚点 (聚焦卡恒在行首停靠线上, 见 TvNativeStripView 的 anchored),
     * 焦点一落到卡上就进 hero 态 (确定直接进详情页), 停靠线上一个固定的聚焦框 ([anchorRingStyle]), hero 态里聚焦行下面的行压暗、滑上 hero 线时
     * 提亮, 卡片下不显示番名, 不做聚光压暗. 在有行之前设, 之后不改 (宿主改了就整块重建). 循环行见 [TvNativeExploreItem.Row.loopPeriod].
     */
    var fixedAnchor: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            list.fixedAnchor = value
            if (value && anchorRing.parent == null) addView(anchorRing)
            applyItemFades()
        }

    /** 钉死锚点版式的固定聚焦框 (只在 [fixedAnchor] 时挂上): 停在 hero 线上聚焦行的行首停靠线处, 焦点在卡片区时显示. */
    private val anchorRing = TvNativeAnchorRingView(context).apply { alpha = 0f }

    /** 测试用: 固定聚焦框. */
    internal val anchorRingView: View get() = anchorRing

    /** 固定聚焦框的样子 (见 [rememberTvNativeAnchorRingStyle]). */
    var anchorRingStyle: TvNativeAnchorRingStyle?
        get() = anchorRing.ringStyle
        set(value) {
            anchorRing.ringStyle = value
        }

    /** 固定聚焦框的显示程度 (0..1), 由 [updateAnchorRing] 过渡; 点开时再乘各行的淡没 (见 [applyAnchorRingAlpha]). */
    private var anchorRingShown = 0f
    private var anchorRingAnimator: ValueAnimator? = null

    /**
     * hero 态里按确定打开时先对焦整屏背景 (铺着模糊背景时: 卡片淡没、背景变清晰, 到位再交给页面, 见 TvNativeWallFocus); false = 直接交给页面
     * (钉死锚点版式选了「直接播放」: 不进详情页, 用不着这段进详情页的过渡).
     */
    var openFocusesWall: Boolean = true

    /**
     * 平板版 hero 的摆法 (宿主在触屏设备上给; 几何见 TvPadHeroGeometry): 背景图高度铺满 hero 区、贴右上角,
     * 文字贴 hero 区左下往上长 (简介压成一段、限行数), 宽度按页面宽的比例. 宾客模式下 hero 归宿主, 这里不管.
     */
    var padHero: Boolean = false
        set(value) {
            if (field == value || guest != null) return
            field = value
            heroText.padLayout = value
            applyPadText(card = true)
            onPadHeroChanged(value)
            requestLayout()
            applyFrame()
        }

    /** 子类跟着 [padHero] 换顶部区域的摆法 (探索页的按钮与圆点). */
    protected open fun onPadHeroChanged(enabled: Boolean) {}

    /** 平板版: 聚焦卡的 hero 区高 —— 页顶到聚焦行的组标题. */
    protected val padCardRegionPx: Int get() = metrics.listTopPx + metrics.heroHeaderTopPx

    /** 平板版: 标题宽 (文字块宽). */
    protected val padTitleWidthPx: Int get() = (metrics.pageWidthPx * TV_PAD_HERO_TITLE_WIDTH_FRACTION).roundToInt()

    /** 平板版: 聚焦卡时文字块下沿 (从页顶算). */
    protected val padCardTextBottomPx: Int get() = padCardRegionPx - dpPx(TV_PAD_HERO_TEXT_BOTTOM)

    /** 平板版背景图的高 (宽按 16:9); 子类 (探索页) 按轮播区量, 聚焦卡时缩放. */
    protected open fun padBackdropHeightPx(): Int = padCardRegionPx

    /** 平板版文字块的下沿 (块的位置; 子类按两路内容各自的下沿平移). */
    protected open fun padTextBoxBottomPx(): Int = padCardTextBottomPx

    /** 平板版的简介宽度与行数: 聚焦卡 ([card]) / 轮播. */
    protected fun applyPadText(card: Boolean) {
        if (padHero) {
            heroText.summaryWidthPx = (metrics.pageWidthPx * TV_PAD_HERO_SUMMARY_WIDTH_FRACTION).roundToInt()
            heroText.summaryCapLines = if (card) TV_PAD_HERO_WALL_SUMMARY_LINES else TV_PAD_HERO_SUMMARY_LINES
        } else {
            heroText.summaryCapLines = 0
        }
    }

    protected fun dpPx(dp: Dp): Int = (dp.value * resources.displayMetrics.density).roundToInt()

    /** hero 态里组标题跟着上面几行淡没 (聚焦行的也藏, 同网格页的 hero 态只剩一排卡); false = 聚焦行的组标题留着 (探索页). */
    override var hideHeadersInHero: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyItemFades()
        }

    /**
     * 触屏设备 (平板装了 TV 包) 才为 true, 宿主按 LocalTvTouchInputEnabled 给; 电视上恒 false. 手指点卡片照触屏的规矩 (见 [touchMode]);
     * hero 态里点卡片之间的空白处退回卡片墙、点 hero 区打开聚焦那张 (见 [TvNativeHeroBlankTap]); 顶部区域另有的触摸处理见 [onTouchInputChanged].
     */
    override var touchInput: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // 宾客模式下由宿主装 (点在整页的空白处)
            heroBlankTap = if (value && guest == null) {
                TvNativeHeroBlankTap(this, { timeline.active && !wallFocus.opening }, ::heroAreaBottomInWindow, ::openFocusedCard) {
                    setHeroActive(false)
                }
            } else {
                null
            }
            list.freeTouchScroll = value
            applyTouchLook()
            onTouchInputChanged(value)
        }

    /**
     * 此刻按触屏的规矩走 (同 iPad 的 Compose 海报墙): 触屏设备上最近一次输入是手指 (见 [lastInputTouch]). 手指点卡片按
     * tvWallTouchTap 判 (按下不动焦点), 卡片墙上不画聚焦效果 (hero 态里照画); 列表由手指自由滚, 焦点不带着滚动 —— 卡片墙上焦点停在本视图
     * 自己身上 (见 [touchPark]), 只有进 hero 态、hero 态里换卡才滚到停位; 退出 hero 态停在原地.
     */
    protected val touchMode: Boolean get() = touchInput && lastInputTouch

    /**
     * 本页最近一次收到的输入是手指 (同 iPad 的 tvWallInputMode): 手指按下算触屏, 方向键与确认键算回到电视的规矩; 焦点在页外时按的键不算.
     * 不用系统的触摸模式: 页面里的焦点请求 (弹窗关掉后交还焦点等) 也会让窗口退出触摸模式, 手指还在用.
     */
    internal var lastInputTouch: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyTouchLook()
            onTouchModeChanged()
        }

    /** 按触屏的规矩把焦点停在了本视图自己身上 (见 [touchPark]). */
    private var touchParked = false

    /** 焦点此刻按触屏的规矩停放在本视图上 (见 [touchPark]). */
    protected val touchParkedNow: Boolean get() = touchParked && isFocused

    /** 停放时还在 hero 态: 等 hero 淡完再报给页面 (见 [touchPark]). */
    private var touchParkReportPending = false

    /**
     * 按触屏的规矩走时焦点不留在卡上: 停在本视图自己身上 (看不见、不画聚焦; 持焦的卡随手指滚出屏被回收也不丢焦点、不被系统塞给别的卡).
     * 还在 hero 态就原地退回卡片墙 (hero 背景与文字淡出, 列表不滚回卡片墙的停位); 焦点离开卡片区等 hero 淡完才报给页面 ([onTouchParked]),
     * 这之前聚焦行留着 (hero 还画着那张卡). 之后按方向键落到屏上离视口中线最近的那一行 (见 [landFromTouchPark]).
     * 同 iPad 的 Compose 海报墙 (TvExploreComposeSurface.parkFocus).
     */
    protected fun touchPark() {
        if (touchParkedNow && !timeline.active) return
        cancelPending()
        list.heldFocus = null
        touchParked = true
        if (!isFocused) {
            descendantFocusability = FOCUS_BEFORE_DESCENDANTS
            isFocusableInTouchMode = true
            if (!requestFocus()) unparkFocus()
        }
        if (timeline.active) {
            touchParkReportPending = true
            exitHeroInPlace()
        } else {
            reportTouchParked()
        }
    }

    /**
     * 按触屏的规矩走时从页面外进来、落在卡片墙上 (不在 hero 态): 同 [touchPark], 焦点停在本视图自己身上. 返回 true 时调用方的
     * onRequestFocusInDescendants 随即返回 false, 由 ViewGroup 接着把焦点给本视图.
     */
    protected fun touchParkOnEntry(): Boolean {
        if (!touchMode || timeline.active) return false
        cancelPending()
        list.heldFocus = null
        touchParked = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        isFocusableInTouchMode = true
        reportTouchParked()
        return true
    }

    private fun reportTouchParked() {
        touchParkReportPending = false
        setCardAreaFocus(false)
        onTouchParked()
    }

    /** 按触屏的规矩停放了焦点 (见 [touchPark]): 子类 (探索页) 据此回到轮播态. */
    protected open fun onTouchParked() {}

    /** hero 淡完了: 补报挂着的停放 (见 [touchPark]). */
    private fun flushTouchParkReport() {
        if (touchParkReportPending && !timeline.visible) reportTouchParked()
    }

    /**
     * 原地退回卡片墙 (按触屏的规矩走时): hero 背景与文字照常淡出, 列表不滚回卡片墙的停位, 只把 hero 态开头几行的整片下推撤掉.
     * 宾客模式下交给宿主 (宿主同样停在原地).
     */
    private fun exitHeroInPlace() {
        if (!timeline.active) return
        guest?.let {
            it.requestHeroActive(false)
            return
        }
        timeline.setActive(false, animated = true)
        applyTouchLook()
        TvNativeFocusCues.setSpotlight(this, false, animated = true)
        rowListener?.onHeroActiveChanged(false)
        shiftToStop(-1, TvScrollSpring.Far)
    }

    /**
     * 按触屏的规矩停放后按了方向键 (回到电视的规矩): 落到屏上离视口中线最近的那一行、上次那一列 (手指横着拖过的行按此刻的行首算);
     * 离顶部区域更近时交给 [settleTouchAtTop], 一行都没排出来时交给 [focusEntry].
     */
    private fun landFromTouchPark() {
        touchParked = false
        getLocationInWindow(windowXY)
        val rowKey = rowNearest(windowXY[1] + metrics.listTopPx + metrics.viewportPx / 2)
        val items = list.items
        val first = items.indexOfFirst { it is TvNativeExploreItem.Row }
        val nearTop = rowKey == null || first >= 0 && list.scrolledPx() < metrics.centeredScroll(items, first) / 2
        unparkFocus()
        if (settleTouchAtTop(nearTop)) return
        if (rowKey == null) {
            focusEntry()
            return
        }
        list.rowAt(list.indexOfKey(rowKey))?.let { list.rowLeftIndex[rowKey] = it.leftIndex() }
        holdCardAtColumn(rowKey, focusedColumn)
        resolvePending(scrollIfMissing = true)
    }

    /** 换了输入方式 (见 [lastInputTouch]): 子类跟着换 (探索页平板版画法在触屏上只留「新番时间表」). */
    protected open fun onTouchModeChanged() {}

    /** 按触屏的规矩走时卡片墙上不画聚焦效果 (见 [touchMode]); 进出 hero 态、hero 时间线每一帧与换输入方式时对一次. */
    private fun applyTouchLook() {
        list.touchLookHidden = touchMode && !timeline.active
    }

    /** hero 区 (背景图与文字, 聚焦行的组标题以上) 下缘在窗口里的纵坐标. */
    internal fun heroAreaBottomInWindow(): Int {
        getLocationInWindow(windowXY)
        return windowXY[1] + metrics.listTopPx + metrics.heroHeaderTopPx
    }

    /** 触屏上 hero 态里点 hero 区 (背景图、文字, 及淡没在那里的上面几行): 同点聚焦的那张. */
    internal fun openFocusedCard() {
        val rowKey = focusedRowKey ?: return
        if (!timeline.active) return
        RowListener(rowKey).onClick(focusedCardIndex)
    }

    private fun isAboveFocusedRow(rowKey: String): Boolean {
        val focused = focusedRowKey?.let { list.indexOfKey(it) } ?: -1
        return focused >= 0 && list.indexOfKey(rowKey) in 0 until focused
    }
    private var heroBlankTap: TvNativeHeroBlankTap? = null

    /** 子类的触摸处理跟着 [touchInput] 装卸. */
    protected open fun onTouchInputChanged(enabled: Boolean) {}

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) lastInputTouch = true
        heroBlankTap?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    // 按在 hero 区 / 空白处而子视图都不接的这一下由本视图接住, 抬起才派发得到 (见 TvNativeHeroBlankTap)
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = heroBlankTap?.tracking == true || super.onTouchEvent(event)

    /** hero 态 (聚焦卡) 在整页底下铺模糊背景 (见 [TvNativeHeroStage.heroBlur]); 背景图的边缘擦成透明露出模糊背景, 整屏底色不压黑. */
    var heroBlur: Boolean
        get() = stage.heroBlur
        set(value) {
            stage.heroBlur = value
        }

    /** hero 态的整屏模糊背景 (见 [heroBlur]); null = 没开. */
    val wallBackdrop: TvNativeWallBackdropView? get() = stage.wallBackdrop

    /**
     * 卡片墙的底色 (页面给, 开了 [heroBlur] 才用). hero 态不压黑时, 底下主壳若还画着近黑的分界带 (探索页轮播的, 见 TvPosterWallTone), 列表挪到
     * hero 停位时它会滚进屏顶: 聚焦卡的 hero 态在本视图最底下铺这个色 ([heroFloor]), 跟着上面几行淡入淡出盖住它, 整屏只见卡片墙的灰.
     */
    var wallColor: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            applyFrame()
        }

    /** 本视图的背景 (画在所有子视图底下), 见 [wallColor]. */
    protected val heroFloor = ColorDrawable(Color.TRANSPARENT)

    /** 点开的那一行 (对焦到位时交给 [onWallOpenedAt]); null = 没在点开. */
    protected var wallOpenRow: String? = null

    /** hero 文字块 (文字 + 顶部区域的附加块, 见 [HeroBox]). */
    protected val heroBox = HeroBox(context)
    protected val list = TvNativeExploreList(context, sketch, style, metrics, headerStyle)
    private val scrollTracker = TvNativeScrollTracker { rowListener?.onScrollingChanged(it) }

    override fun setAmbientAnimationsPaused(paused: Boolean) {
        if (guest == null) heroText.marqueePaused = paused
    }

    // ---- 焦点簿记 ----
    /** 上次聚焦的行 (null = 焦点在顶部区域上, 或从未进过卡片区). 焦点去侧边栏时不清. */
    var focusedRowKey: String? = null
        protected set
    var focusedCardIndex: Int = 0
        private set
    protected var focusedColumn = 0
        private set

    /** 焦点此刻在卡片区. */
    var cardAreaHasFocus: Boolean = false
        private set

    /** 还没送出去的落点 (目标行还没排出来): 行键, 下标 (-1 = 屏上第 [pendingColumn] 列), 远跳. */
    protected var pendingRow: String? = null
    private var pendingIndex = -1
    private var pendingColumn = 0

    /** 目标行已在平滑滚向挂着的落点: 重试只在那张排出来 / 滚动停下时送焦, 不再发起滚动 (见 [resolvePending]). */
    private var pendingSmooth = false

    /** 挂着的落点要一步挪过去 (远跳途中按了方向键), 不平滑滚 (见 [resolvePending]). */
    private var pendingInstant = false

    /** 远跳途中按方向键当场落地是什么时候发起的 (见 [landFarJumpNow]); 落地一直没成时按键据此重送. */
    private var landingSince = 0L
    protected var farJumpRow: String? = null
        private set

    /** 远跳纵向已到位, 目标那一行正平滑滚向目标卡 (见 [finishFarJump]); 这一段仍算远跳. */
    private var farJumpAlongRow = false

    /** 远跳途中按了确认: 落到目标时点它 (见 [dispatchKeyEvent]). */
    private var farJumpConfirmQueued = false

    /** 远跳已落地、在等目标那一行的卡拿到焦点再点 (见 [onCardFocused]); null = 没有. */
    private var clickOnLandingRow: String? = null
    private val verticalRepeat = TvKeyRepeatGate(TV_NATIVE_VERTICAL_REPEAT_MILLIS)
    private val horizontalRepeat = TvKeyRepeatGate(TV_NATIVE_HORIZONTAL_REPEAT_MILLIS)
    private var pendingScrollPx = -1

    /** 各行上次停的那张 (从行外进来 / 返回键分层回这一行时落在它上面), 按行键. */
    private val rowFocusedIndex = HashMap<String, Int>()

    /**
     * 下一次从页面外进来 ([onRequestFocusInDescendants]) 落在纵向离这个高度 (窗口坐标) 最近的那一行、屏上最右边那张, 不回上次那张 (见 [enterNearRow]);
     * null = 回上次那张. 用一次就复位.
     */
    private var enterNearY: Int? = null

    /** [enterNearY] 那一发来的时候还一行都没排出来: 排出来再送 (见 [onLayout]). */
    private var enterNearDeferred = false

    /** [restore] 恢复在 hero 态: 停位按聚焦行的 hero 停位重新对 (见 [applyRestoredScroll]). */
    private var restoreHeroStop = false

    /** 下一次从页面外进来落在第几行 (见 [enterRow]); null = 不按行号. */
    private var enterRowNumber: Int? = null

    /** [enterNearRow] / [enterRow] 那一发是从左边过来的: 落那一行屏上最左边那张 (否则最右边那张). */
    private var enterFromLeft = false

    private val windowXY = IntArray(2)

    // ---- hero ----
    /** 聚焦卡这一路的 hero 内容 (页面给). */
    protected var cardHero: TvNativeHeroSource? = null

    /** 背景图与文字此刻是聚焦卡的 (hero 态看得见); false = 顶部区域的 / 没有. 换的时刻见 [applyFrame]. */
    protected var showsCard = false
        private set
    protected var modeSettled = false
        private set
    protected var lastDimSubject: Int? = null
    protected var titleHeightPx = Int.MAX_VALUE / 2

    /** 遮罩 (按背景图尺寸插值 0 = 顶部区域 (轮播), 1 = 聚焦卡), 页面给. */
    var treatmentFor: ((mode: Float) -> TvBackdropTreatment)? = null
        set(value) {
            field = value
            lastTreatmentMode = Float.NaN
            applyFrame()
        }
    protected var lastTreatmentMode = Float.NaN

    /** 深色主题 (见 [TvNativeHeroStage.dark]). */
    var dark: Boolean
        get() = stage.dark
        set(value) {
            stage.dark = value
        }

    /** 焦点不在那一组时组标题的不透明度 (焦点所在那一组的标题全亮, 见 [applyHeaderEmphasis]); 1 = 都不淡. */
    override var headerIdleAlpha: Float
        get() = list.headerIdleAlpha
        set(value) {
            list.headerIdleAlpha = value
        }

    /** 背景图遮罩色 (hero 的底). */
    var fadeColor: Int
        get() = stage.fadeColor
        set(value) {
            stage.fadeColor = value
        }

    private inner class StageHost : TvNativeHeroStage.Host {
        override val transitions: Boolean get() = this@TvNativeRowWallView.transitions

        override fun onHeroFrame() {
            applyTouchLook()
            flushTouchParkReport()
            applyFrame()
            reportTone()
        }

        override fun onHeroBlurChanged(enabled: Boolean) {
            if (!enabled) wallOpenRow = null
            applySources(reset = false)
        }

        // 点开: 各行 (连同各组标题、背景图、hero 文字里标题以外的几行) 淡没, 见 applyFrame
        override fun applyOpenFade(fade: Float, keep: Int, chrome: Boolean) = applyFrame()

        override fun onOpeningChanged(opening: Boolean) {
            rowListener?.onWallOpeningChanged(opening)
        }

        override fun onOpened(index: Int) = onHostWallOpened(index)
    }

    init {
        clipChildren = false
        clipToPadding = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isFocusable = false
        // 宾客模式下背景图与 hero 文字是宿主的, 画在宿主里
        if (guest == null) {
            background = heroFloor
            addView(backdrop)
            heroBox.addView(heroText)
            addView(heroBox)
            heroText.summaryWidthPx = metrics.cardSummaryWidthPx
        }
        addView(list)
        list.cardListener = { rowKey -> RowListener(rowKey) }
        list.onBindCard = { rowKey, index -> rowListener?.onBindCard(rowKey, pageIndex(rowKey, index)) }
        list.onItemsMoved = { onListMoved() }
        list.onRowDragStart = { if (touchMode) touchPark() }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                scrollTracker.onStateChanged(this@TvNativeRowWallView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    touchDragged = true
                    // 按触屏的规矩走: 手指一拖, 焦点停放 (hero 态里原地退回卡片墙), 列表跟着手指走
                    if (touchMode) touchPark()
                }
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    finishFarJump()
                    if (touchDragged) settleAfterTouch()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scrollTracker.onMoved(dy)
            }
        })
        // 第一次摆 (applyFrame) 等布局: 构造期间子类的字段还没初始化, 这里不调可覆盖的方法
    }

    // ------------------------------------------------------------------
    // 页面给的数据
    // ------------------------------------------------------------------

    fun update(style: TvNativeWallStyle, metrics: TvNativeExploreMetrics, heroTextStyle: TvNativeHeroTextStyle) {
        heroText.style = heroTextStyle
        if (!showsCardSummaryOverridden()) heroText.summaryWidthPx = metrics.cardSummaryWidthPx
        updateContent(style, metrics)
    }

    /** 换卡片样式与几何 (宾客模式下 hero 文字的样式归宿主, 只调这个). */
    internal fun updateContent(style: TvNativeWallStyle, metrics: TvNativeExploreMetrics) {
        val changed = this.metrics != metrics
        this.style = style
        this.metrics = metrics
        list.update(style, metrics)
        if (changed) requestLayout()
        applyFrame()
    }

    /** 换内容滑动途中藏起来的卡 (见 [beginLane]) 与它们的淡出 / 淡回. */
    private val laneHidden = ArrayList<TvNativeCardView>()
    private var laneFade: ValueAnimator? = null

    /**
     * 宿主换内容时整片平移这一份 (宾客模式, 见 TvNativeGridPageView.slideIn): 途中只在自己那一屏宽 ([laneWidth]) 里画, 屏外预排的卡
     * 不进旁边那份. 滑动途中扫过屏幕的是 [edge] 那条边 (+1 右边: 往左滑出去 / 从左边滑进来; −1 左边: 反过来), 跨出这条边的卡
     * (停着时被屏幕边切掉一截的那张, 以及屏外预排的) 当场淡出, 边上没有被切开的卡; 停稳后 [endLane] 再淡回来.
     * 另一条边途中一直在屏幕外, 跨着它的卡照常画 (从右边滑进来时屏幕右缘那张不用藏).
     */
    internal fun beginLane(laneWidth: Int, edge: Int) {
        clipBounds = Rect(0, Short.MIN_VALUE.toInt(), laneWidth, Short.MAX_VALUE.toInt())
        laneFade?.cancel()
        val crossing = ArrayList<TvNativeCardView>()
        val rect = Rect()
        forEachCard(list) { card ->
            rect.set(0, 0, card.width, card.height)
            offsetDescendantRectToMyCoords(card, rect)
            if (if (edge > 0) rect.right > laneWidth else rect.left < 0) crossing += card
        }
        // 上一轮淡回途中又换了: 这一轮不跨出的照常显示
        laneHidden.forEach { if (it !in crossing) it.transitionAlpha = 1f }
        laneHidden.clear()
        laneHidden += crossing
        fadeLaneHidden(to = 0f, millis = TV_NATIVE_LANE_CARD_OUT_MILLIS)
    }

    /** 滑动停下: 撤掉裁剪, 藏起来的卡 [fadeIn] 时淡回来, 否则 (换走收起了) 当场复原. */
    internal fun endLane(fadeIn: Boolean) {
        clipBounds = null
        laneFade?.cancel()
        if (fadeIn) {
            fadeLaneHidden(to = 1f, millis = TV_SCROLL_HIDDEN_TEXT_IN_MILLIS.toLong())
        } else {
            laneHidden.forEach { it.transitionAlpha = 1f }
            laneHidden.clear()
        }
    }

    private fun fadeLaneHidden(to: Float, millis: Long) {
        val cards = laneHidden.toList()
        if (cards.isEmpty()) return
        val from = FloatArray(cards.size) { cards[it].transitionAlpha }
        laneFade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (transitions) millis else 0L
            addUpdateListener {
                val f = it.animatedValue as Float
                cards.forEachIndexed { i, card -> card.transitionAlpha = from[i] + (to - from[i]) * f }
            }
            start()
        }
    }

    private fun forEachCard(group: ViewGroup, action: (TvNativeCardView) -> Unit) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is TvNativeCardView -> action(child)
                is ViewGroup -> forEachCard(child, action)
            }
        }
    }

    /** 简介宽度由子类按两路内容切换 (见 [TvNativeExploreView]); 本类恒用聚焦卡那一档. */
    protected open fun showsCardSummaryOverridden(): Boolean = false

    override fun setItems(items: List<TvNativeExploreItem>) {
        val oldItems = list.items
        val structural = items.size != oldItems.size || items.indices.any { items[it].key != oldItems[it].key }
        // 持焦的那张卡在换数据前是哪一行、哪一部 (见 followFocusAfterItemsChanged)
        val focusedRow = focusedRowKey?.takeIf { cardAreaHasFocus && farJumpRow == null }
        val focusedSubject = focusedRow?.let { tvNativeRowCards(oldItems, it)?.getOrNull(focusedCardIndex)?.subjectId }
        list.submit(items)
        if (pendingScrollPx >= 0 && items.isNotEmpty()) {
            val px = pendingScrollPx
            pendingScrollPx = -1
            applyRestoredScroll(px)
        } else if (structural) {
            realignFocusedRow()
        }
        if (focusedRow != null) followFocusAfterItemsChanged(oldItems, focusedRow, focusedSubject)
        // 等的落点行到了
        post { resolvePending(scrollIfMissing = true) }
    }

    /**
     * 卡片区持焦时换了数据 (改收藏让条目离开某一行、推荐重组), 焦点跟着数据走:
     * - 聚焦那一行整个没了 (行里最后一部也走了): 持焦的卡随行一起被摘掉, 焦点凭空消失 —— 系统不改派, Compose 那边还当焦点在本视图里,
     *   全局兜底也不出手. 交给顶上来的下一行同一列; 下面没有行了交给上一行, 一行都不剩交给 [onAllRowsGone].
     * - 行还在、聚焦那一格换成了别的作品 (中间一部走了, 后面的往前挪一格): 卡按下标绑定, 持焦的卡原地重绑、不会再报一次聚焦,
     *   这里补报, hero 跟着换.
     * 行尾那张走了不用管: 卡被摘掉时 RecyclerView 自己把焦点交给前一张, 照常回调聚焦.
     */
    private fun followFocusAfterItemsChanged(oldItems: List<TvNativeExploreItem>, rowKey: String, subjectId: Int?) {
        if (list.indexOfKey(rowKey) < 0) {
            val target = tvNativeReplacementRow(oldItems, list.items, rowKey)
            // 等这一趟布局排完再送: 列表刚整体刷新, 排版前各项位置无效 (scrolledPx 算成 0), 当场送焦会按错的距离滚一段, 而排完后那一行
            // 已在停位上、落焦时不再滚, 没人叫停这一段. 落点先挂着, 由 setItems 末尾 (布局之后) 的 resolvePending 送
            if (target != null) {
                holdCardAtColumn(target, focusedColumn)
            } else {
                post { if (focusedRowKey == rowKey && !cardAreaHasFocus) onAllRowsGone() }
            }
            return
        }
        val now = tvNativeRowCards(list.items, rowKey)?.getOrNull(focusedCardIndex)?.subjectId
        if (now == null || now == subjectId) return
        val index = focusedCardIndex
        post {
            if (cardAreaHasFocus && focusedRowKey == rowKey && focusedCardIndex == index) {
                rowListener?.onCardFocused(rowKey, pageIndex(rowKey, index), focusedColumn)
            }
        }
    }

    /** 聚焦那一组的卡全没了 (一行都不剩): 焦点交给顶部区域 / 页面. */
    protected open fun onAllRowsGone() {
        rowListener?.onTopRowUp()
    }

    /** 聚焦卡这一路的 hero 内容. */
    fun setCardSource(card: TvNativeHeroSource) {
        cardHero = card
        applySources(reset = false)
    }

    /**
     * 进页恢复: 列表停位、各行行首与上次停的那张、焦点簿记、hero 态 (直接到位, 不走过渡; 宾客模式下按宿主此刻的, 不看 [heroActive]).
     * [holdLook] = 返回本页时焦点要回那一张: 从排出来的第一帧起按住它的聚焦态 (焦点回标签行等别处时不按住).
     */
    override fun restore(
        scrollPx: Int,
        rowLeftIndex: Map<String, Int>,
        rowFocusedIndex: Map<String, Int>,
        focusedRowKey: String?,
        focusedCardIndex: Int,
        heroActive: Boolean,
        holdLook: Boolean,
    ) {
        list.rowLeftIndex.putAll(rowLeftIndex)
        this.rowFocusedIndex.putAll(rowFocusedIndex)
        this.focusedRowKey = focusedRowKey
        // 页面给的是条目下标 (循环行折回过的): 这一行停的那张是本视图自己报的位置, 有就用它
        this.focusedCardIndex = focusedRowKey?.let { rowFocusedIndex[it] } ?: focusedCardIndex
        // 返回本页 (页面重建, 如从播放器回来) 时焦点会回这一行上次停的那张 (见页面的进页恢复): 从排出来的第一帧起就按住它的聚焦态.
        // 等落点请求送到再按住就晚了 —— 那时它多半已经按未聚焦画过一帧, 焦点到了再放大一遍
        if (holdLook && focusedRowKey != null) {
            list.heldFocus = focusedRowKey to rememberedIndex(focusedRowKey, list.rowLeftIndex[focusedRowKey] ?: 0)
        }
        // 宾客模式下 hero 态是宿主的: 按它此刻的
        if (heroActive && guest == null) {
            timeline.setActive(true, animated = false)
            if (!fixedAnchor) TvNativeFocusCues.setSpotlight(this, on = true, animated = false)
        }
        // 列表停位等数据到了再定 (空列表定不了位)
        restoreHeroStop = if (guest != null) timeline.active else heroActive
        if (list.items.isNotEmpty()) applyRestoredScroll(scrollPx) else pendingScrollPx = scrollPx
        applyFrame()
    }

    /**
     * 恢复的停位: 恢复在 hero 态时聚焦行对回它的 hero 停位、连同整片平移 ([shiftToStop]) —— 存下的停位是卡片墙上的 (从别的标签带着 hero 态过来),
     * 整片平移也不在里面.
     */
    private fun applyRestoredScroll(px: Int) {
        list.jumpTo(px)
        if (!restoreHeroStop) return
        restoreHeroStop = false
        val item = focusedRowKey?.let { list.indexOfKey(it) } ?: -1
        if (item < 0) return
        list.jumpTo(stopFor(item))
        shiftToStop(item, pace = null)
    }

    override val scrolledPx: Int get() = list.scrolledPx()
    override val rowLeftIndex: Map<String, Int> get() = list.rowLeftIndex
    override val rowFocusedIndexMap: Map<String, Int> get() = rowFocusedIndex
    override val heroActive: Boolean get() = timeline.active

    /** 程序化落点还挂着 (目标没排出来 / 远跳途中). */
    val focusPending: Boolean get() = pendingRow != null || farJumpRow != null

    // ------------------------------------------------------------------
    // 跨页就近接住 (从别的版式的页面换过来, 按屏上的高度找行)
    // ------------------------------------------------------------------

    /**
     * 下一次从页面外进来落在纵向离 [windowY] (窗口坐标) 最近的那一行、屏上最右边那张 ([fromLeft]: 最左边那张): 从旁边标签的网格行缘过来,
     * 就近接住 (同网格之间换标签「行首按左 → 左边那份同一行的行末」, 行末按右对称; 一组一行与网格的行号对不上, 按屏上的高度对). 用一次就复位.
     */
    override fun enterNearRow(windowY: Int, fromLeft: Boolean) {
        enterNearY = windowY
        enterRowNumber = null
        enterFromLeft = fromLeft
    }

    /**
     * 下一次从页面外进来落在第 [number] 行 (从 0 数, 只数行不数组标题; 超出落最后一行)、屏上最右边那张 ([fromLeft]: 最左边那张): hero 态里
     * 从旁边标签的网格行缘过来按行号对 (同网格之间换标签; hero 态里各行都停在 hero 线上, 屏上高度对不出行). 列表当场跳到那一行的停位,
     * 不滚过去. 用一次就复位.
     */
    override fun enterRow(number: Int, fromLeft: Boolean) {
        enterRowNumber = number
        enterNearY = null
        enterNearDeferred = false
        enterFromLeft = fromLeft
    }

    /** 聚焦那一行是第几行 (从 0 数, 只数行不数组标题, 同 [enterRow]); 没有聚焦行时 null. */
    override fun focusedRowNumber(): Int? {
        val item = focusedRowKey?.let { list.indexOfKey(it) }?.takeIf { it >= 0 } ?: return null
        return list.items.subList(0, item).count { it is TvNativeExploreItem.Row }
    }

    /** 按 [enterRowNumber] 落点. 一行都没有时返回 false (记着, 交给别的落点). */
    private fun enterRowNow(): Boolean {
        val number = enterRowNumber ?: return false
        val rows = list.items.indices.filter { list.items[it] is TvNativeExploreItem.Row }
        val item = rows.getOrNull(number.coerceIn(0, (rows.size - 1).coerceAtLeast(0))) ?: return false
        enterRowNumber = null
        holdCardAtColumn(list.items[item].key, if (enterFromLeft) 0 else metrics.columns.coerceAtLeast(1) - 1)
        list.jumpTo(stopFor(item))
        shiftToStop(item, pace = null)
        // 列表按新位置排完才有那一行: 布局 / 挪动时 onListMoved 会再试, 这里补一次
        list.post { resolvePending(scrollIfMissing = false) }
        return true
    }

    /** 聚焦那张卡的中线在窗口里的纵坐标 (换到别的标签时就近找行用); 焦点不在卡片区时 null. */
    override fun focusedCardCenterYInWindow(): Int? {
        if (!cardAreaHasFocus) return null
        val card = findFocus()?.takeIf { it !== this } ?: return null
        card.getLocationInWindow(windowXY)
        return windowXY[1] + card.height / 2
    }

    /** [rowKey] 那一行卡片块的中线此刻在窗口里的纵坐标; 那一行没排出来时 null. */
    fun rowCenterYInWindow(rowKey: String): Int? {
        val item = list.indexOfKey(rowKey)
        val row = (if (item >= 0) list.rowAt(item) else null) ?: return null
        return row.cardCenterYInWindow()
    }

    /** 行高里含底下的行距, 卡片块是上面那一截. */
    private fun TvNativeRowView.cardCenterYInWindow(): Int {
        getLocationInWindow(windowXY)
        return windowXY[1] + (height - metrics.rowGapPx) / 2
    }

    /** 已排出来的行里, 卡片块中线离 [windowY] (窗口坐标) 最近的那一行; 一行都没排出来时 null. */
    private fun rowNearest(windowY: Int): String? {
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? TvNativeRowView ?: continue
            val key = row.tag as? String ?: continue
            val distance = abs(row.cardCenterYInWindow() - windowY)
            if (distance < bestDistance) {
                bestDistance = distance
                best = key
            }
        }
        return best
    }

    /** 按 [enterNearY] 落点: 最近那一行屏上最右边那张 (行短了落最后一张). 返回送没送出去 (还没排出来时记着, 排出来再送). */
    private fun enterNearRowNow(): Boolean {
        val y = enterNearY ?: return false
        val rowKey = rowNearest(y)
        if (rowKey == null) {
            enterNearDeferred = list.items.any { it is TvNativeExploreItem.Row }
            if (!enterNearDeferred) enterNearY = null
            return enterNearDeferred
        }
        enterNearY = null
        enterNearDeferred = false
        holdCardAtColumn(rowKey, if (enterFromLeft) 0 else metrics.columns.coerceAtLeast(1) - 1)
        resolvePending(scrollIfMissing = true)
        return true
    }

    // ------------------------------------------------------------------
    // 焦点命令 (页面发: 进页恢复 / 返回键分层 / 回到主界面)
    // ------------------------------------------------------------------

    /**
     * 送焦到 [rowKey] 行的第 [index] 张 (-1 = 这一行上次停的那张). [far] = 远跳 (返回键回本行首卡 / 跳回组首行): 列表按
     * [TvScrollSpring.Far] 滚过去, 目标卡没排出来的话那一行接着平滑滚过去; 途中焦点停放在本视图上、不显示聚焦效果, 按方向键当场落到目标,
     * 按确认排队 (落地后点它), 到位再把焦点给目标. 目标卡已经排出来 (在屏上或刚出屏) 时不必远跳, 同按上下键当场送焦 (见 [targetLaidOut]);
     * 两行以内的远跳按逐格的 [TvScrollSpring.Step] 滚. 目标行还没排出来就记下, 排出来时再送.
     */
    override fun focusCard(rowKey: String, index: Int, far: Boolean): Boolean {
        onFocusCardRequested()
        // 按触屏的规矩走时卡片墙上不送焦 (焦点不带着滚动): 停放
        if (touchMode && !timeline.active) {
            touchPark()
            return true
        }
        // 新的落点请求: 远跳途中排队的确认作废
        farJumpConfirmQueued = false
        clickOnLandingRow = null
        farJumpAlongRow = false
        pendingInstant = false
        val item = list.indexOfKey(rowKey)
        val rowIndex = rowIndexOf(rowKey, index)
        pendingRow = rowKey
        pendingIndex = rowIndex
        pendingColumn = -1
        pendingSmooth = false
        if (!far && !cardAreaHasFocus) holdLandingLook(rowKey, rowIndex)
        if (item < 0 || !isLaidOut) return false
        if (far && !targetLaidOut(rowKey, item)) {
            farJumpRow = rowKey
            setFocusEffectSuppressed(true)
            parkFocus()
            val stop = stopFor(item)
            // 近的 (两行以内) 按逐格的节奏滚: 远跳那组 spring 是给一次跳十几行的, 起步慢、尾巴长, 跳一行也要一秒多才停稳落焦
            val near = abs(stop - list.scrolledPx()) <= TV_NATIVE_NEAR_JUMP_ROWS * (metrics.rowPx + metrics.headerPx)
            list.scrollToStop(stop, if (near) TvScrollSpring.Step else TvScrollSpring.Far, animatedScroll)
            shiftToStop(item, if (near) TvScrollSpring.Step else TvScrollSpring.Far)
            // 已经在停位上 / 流畅档一步到位: 不会再有滚动停下的回调, 当场落焦
            if (list.scrollState == RecyclerView.SCROLL_STATE_IDLE) finishFarJump()
            return true
        }
        return resolvePending(scrollIfMissing = true)
    }

    /** 页面发了卡片的落点请求 (子类据此作废顶部区域挂着的落点). */
    protected open fun onFocusCardRequested() {}

    /** 第 [item] 项那一行此刻 (hero 态 / 卡片墙) 的列表停位. */
    private fun stopFor(item: Int): Int =
        if (timeline.active) metrics.heroScroll(list.items, item) else metrics.centeredScroll(list.items, item)

    /**
     * [rowKey] 行 (列表第 [item] 项) 已经排出来、挂着的目标卡也排出来了 (在屏上或刚出屏: 组首行就在上面、行内回首卡而行首还排着): 不必远跳,
     * 同按上下键当场送焦, 列表与行按逐格的节奏跟过去. 远跳 (停放焦点、滚停才落焦) 是给目标还没排出来的: 那时出发那张会随滚动被回收.
     */
    private fun targetLaidOut(rowKey: String, item: Int): Boolean {
        val row = list.rowAt(item) ?: return false
        val count = row.cards.itemCount
        if (count == 0) return false
        val left = list.rowLeftIndex[rowKey] ?: row.leftIndex()
        return row.findViewHolderForAdapterPosition(pendingTarget(rowKey, left, count)) != null
    }

    /**
     * 焦点从本页以外送进来 (返回本页 / 回到前台) 而目标卡还没排出来: 先按住它的聚焦态, 排出来的第一帧就是放大的, 焦点到位时画面不变
     * (见 [TvNativeExploreList.heldFocus]). 目标已在屏上就不按住, 照常走聚焦的放大动画. 任何一张卡 / 顶部区域拿到焦点就放开.
     */
    private fun holdLandingLook(rowKey: String, index: Int) {
        val item = list.indexOfKey(rowKey)
        val row = if (item >= 0) list.rowAt(item) else null
        val left = list.rowLeftIndex[rowKey] ?: row?.leftIndex() ?: 0
        val target = if (index >= 0) index else rememberedIndex(rowKey, left)
        if (row?.findViewHolderForAdapterPosition(target) != null) return
        list.heldFocus = rowKey to target
    }

    /** 程序化落点还挂着 (目标行没排出来 / 远跳途中): 页面的返回键分层按它的目标算. */
    val pendingFocus: Pair<String, Int>? get() = pendingRow?.let { it to pendingIndex }

    protected fun cancelPending() {
        pendingRow = null
        pendingInstant = false
        farJumpConfirmQueued = false
        clickOnLandingRow = null
        farJumpAlongRow = false
        if (farJumpRow != null) {
            farJumpRow = null
            setFocusEffectSuppressed(false)
        }
    }

    /**
     * 远跳途中把焦点停在本视图上: 出发那张卡随整行滚出屏被回收, 焦点就丢了 —— 窗口没焦点时 leanback 排出来的第一张可聚焦卡会被系统
     * (ViewRootImpl.focusableViewAvailable) 塞上焦点, 焦点跳到半路经过的某一行, 列表转去滚向那一行 (纵向列表是普通
     * LinearLayoutManager, 持焦的行照样回收). 停放期间只让自己先拿焦点 (FOCUS_BEFORE_DESCENDANTS), 排出来的卡抢不走; 有子视图拿到焦点就解除
     * (见 [requestChildFocus]).
     */
    private fun parkFocus() {
        val focused = findFocus() ?: return
        if (focused === this) return
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        // 连触摸模式一起 (同卡片): 停放不因窗口进了触摸模式而落空
        isFocusableInTouchMode = true
        if (!requestFocus()) unparkFocus()
    }

    private fun unparkFocus() {
        if (!isFocusable) return
        // 先让子视图能拿焦点再撤掉自己的可聚焦 (自己还持焦时撤掉会 clearFocus, 系统又去塞给第一个可聚焦的)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        if (!isFocused) isFocusable = false
    }

    /**
     * 远跳途中按了方向键: 当场落到目标 (这一下吞掉, 排队的确认作废), 之后的按键从目标接着走 —— 出发那张多半已滚出屏被回收, 从它算不出
     * 下一步. 列表一步到那一行的停位, 目标卡没排出来的话那一行也一步挪过去 (见 [resolvePending]).
     */
    private fun landFarJumpNow() {
        val rowKey = farJumpRow ?: return
        farJumpRow = null
        farJumpAlongRow = false
        farJumpConfirmQueued = false
        setFocusEffectSuppressed(false)
        val item = list.indexOfKey(rowKey)
        if (item >= 0) {
            list.jumpTo(stopFor(item))
            shiftToStop(item, pace = null)
        }
        pendingRow = rowKey
        pendingSmooth = false
        pendingInstant = true
        landingSince = SystemClock.uptimeMillis()
        // 列表按新位置排完才有那一行: 布局 / 挪动时 onListMoved 会再试, 这里补一次
        list.post { resolvePending(scrollIfMissing = false) }
    }

    /** 远跳还在滚时又按了返回 (返回键回首卡的那一跳): 当场落到目标并送焦 (返回 true); 没在远跳返回 false. */
    override fun landFarJump(): Boolean {
        if (farJumpRow == null) return false
        landFarJumpNow()
        return true
    }

    /**
     * 页面的返回分层: 上一下返回的远跳还在滚时又按了返回 —— 这一步先当场落地, 页面紧接着按它的落点发下一层的落点请求 (同一下里做完两步).
     * 落地 = 列表一步到那一行的停位, 那一行一步挪到目标卡, 行首与上次停的那张照落在目标卡上记下 (之后回到这一行、行被回收重绑都按它);
     * 不送焦: 焦点仍停放在本视图上, 下一层马上把它送走. 返回是否有远跳在滚.
     */
    fun settleFarJump(): Boolean {
        val rowKey = farJumpRow ?: return false
        val item = list.indexOfKey(rowKey)
        val count = (list.items.getOrNull(item) as? TvNativeExploreItem.Row)?.cards?.size ?: 0
        val row = if (item >= 0) list.rowAt(item) else null
        val current = list.rowLeftIndex[rowKey] ?: row?.leftIndex() ?: 0
        val target = if (count > 0) pendingTarget(rowKey, current, count) else -1
        // 先摘掉远跳与挂着的落点再挪: 列表 / 行滚动停下的回调会去落地送焦
        farJumpRow = null
        farJumpAlongRow = false
        farJumpConfirmQueued = false
        clickOnLandingRow = null
        pendingRow = null
        pendingInstant = false
        setFocusEffectSuppressed(false)
        if (item < 0 || target < 0) return true
        val columns = metrics.columns.coerceAtLeast(1)
        list.rowLeftIndex[rowKey] = if (fixedAnchor) target else tvStripLeftIndex(current, target, count, columns)
        rowFocusedIndex[rowKey] = target
        list.jumpTo(stopFor(item))
        shiftToStop(item, pace = null)
        // 那一行还在就当场挪到目标卡; 已被回收的话重新排出来时按上面记下的行首排
        if (row != null && row.findViewHolderForAdapterPosition(target) == null) row.jumpToCard(target, columns) {}
        return true
    }

    /**
     * 把挂着的落点送出去; 送出去了返回 true. 目标行还没排出来时 [scrollIfMissing] = true 就滚到它的停位让它排出来 (列表每次挪动 /
     * 布局都会再来这里试一次, 那时不再发起滚动).
     */
    protected fun resolvePending(scrollIfMissing: Boolean): Boolean {
        val rowKey = pendingRow ?: return false
        if (farJumpRow != null) return false
        val item = list.indexOfKey(rowKey)
        if (item < 0) return false
        val row = list.rowAt(item)
        if (row == null) {
            if (scrollIfMissing) {
                list.scrollToStop(stopFor(item), TvScrollSpring.Step, animatedScroll)
                shiftToStop(item, TvScrollSpring.Step)
            }
            return false
        }
        val count = row.cards.itemCount
        if (count == 0) return false
        val left = list.rowLeftIndex[rowKey] ?: row.leftIndex()
        val target = pendingTarget(rowKey, left, count)
        pendingRow = null
        if (row.findViewHolderForAdapterPosition(target)?.itemView?.isFocused == true) {
            // 目标已经持着焦点: requestFocus 不会再回调聚焦, 当场按到位报上去 (页面据此清掉挂着的落点请求)
            onCardFocused(rowKey, target)
            return true
        }
        if (row.focusCardIfLaidOut(target)) return true
        // 目标卡没排出来: 记着落点, 让行滚过去
        pendingRow = rowKey
        pendingIndex = target
        pendingColumn = -1
        when {
            // 已经在平滑滚过去: 等它排出来 / 停下 (再发一遍会把行的平滑滚动一次次重启)
            pendingSmooth -> Unit

            // 远跳途中按了方向键 (见 landFarJumpNow): 一步挪过去, 这一趟布局完再送
            pendingInstant && row.childCount > 0 -> {
                pendingSmooth = true
                row.jumpToCard(target, metrics.columns.coerceAtLeast(1)) { if (pendingRow == rowKey) resolvePending(scrollIfMissing = false) }
            }

            // 行已排好、那张在屏外 (左右键连按超前): 平滑滚过去, 停下再送. 停下时还没排出来 (半路被停掉) 就重新来过, 不再干等;
            // 滚不起来 (见 scrollToCardSmooth) 就一步挪过去
            row.childCount > 0 -> {
                pendingSmooth = true
                val smooth = row.scrollToCardSmooth(target) {
                    if (pendingRow == rowKey) {
                        pendingSmooth = false
                        resolvePending(scrollIfMissing = false)
                    }
                }
                if (!smooth) {
                    row.jumpToCard(target, metrics.columns.coerceAtLeast(1)) { if (pendingRow == rowKey) resolvePending(scrollIfMissing = false) }
                }
            }

            // 行刚绑定还没排: 先选中 (第一次布局就排在那里), 布局完成后再送
            else -> {
                row.selectCard(target)
                row.post { resolvePending(scrollIfMissing = false) }
            }
        }
        return false
    }

    private fun rememberedIndex(rowKey: String, left: Int): Int = rowFocusedIndex[rowKey] ?: left

    /** 挂着的落点在 [rowKey] 行 (行首 [left], 共 [count] 张) 里指的是第几张. */
    private fun pendingTarget(rowKey: String, left: Int, count: Int): Int = when {
        pendingIndex >= 0 -> pendingIndex
        pendingColumn >= 0 -> left + pendingColumn
        else -> rememberedIndex(rowKey, left)
    }.coerceIn(0, count - 1)

    /**
     * 远跳的列表停下了 (纵向到位): 目标卡还没排出来 (行首左边好几张, 如行内回首卡) 就让那一行接着平滑滚过去 —— 这一段仍算远跳, 焦点
     * 仍停放在本视图上: leanback 平滑滚向远处时当场把选中位置换成目标, 出发那张滚出排布范围就被回收, 焦点留在它身上就丢了
     * (系统从窗口根上重新送焦, 落到别处). 行停下再落地 ([landAfterFarJump]); 滚不起来 (见 [TvNativeStripView.scrollToCardSmooth]) 就当场落地,
     * 由落地一步挪过去.
     *
     * 刚停放焦点时失焦的卡常常请求了布局, 那一刻 leanback 不平滑滚: 行有布局请求挂着就等下一帧布局完 ([deferred] = 已经等过) 再滚
     * (动画回调在那一帧的布局之前, 再 post 一次排到布局之后).
     */
    private fun finishFarJump(deferred: Boolean = false) {
        val rowKey = farJumpRow ?: return
        // 行在滚着: 列表这时的停下与它无关
        if (farJumpAlongRow) return
        val item = list.indexOfKey(rowKey)
        val row = if (item >= 0) list.rowAt(item) else null
        val count = row?.cards?.itemCount ?: 0
        if (row != null && row.childCount > 0 && count > 0) {
            val left = list.rowLeftIndex[rowKey] ?: row.leftIndex()
            val target = pendingTarget(rowKey, left, count)
            if (row.findViewHolderForAdapterPosition(target) == null) {
                if (!deferred && row.isLayoutRequested) {
                    row.postOnAnimation { row.post { if (farJumpRow == rowKey) finishFarJump(deferred = true) } }
                    return
                }
                farJumpAlongRow = true
                if (row.scrollToCardSmooth(target) { if (farJumpRow == rowKey) landAfterFarJump() }) return
                farJumpAlongRow = false
            }
        }
        landAfterFarJump()
    }

    /** 远跳落地: 途中按的确认留给目标 (拿到焦点时点它), 送焦. */
    private fun landAfterFarJump() {
        val rowKey = farJumpRow ?: return
        farJumpRow = null
        farJumpAlongRow = false
        if (farJumpConfirmQueued) {
            farJumpConfirmQueued = false
            clickOnLandingRow = rowKey
        }
        setFocusEffectSuppressed(false)
        pendingRow = rowKey
        pendingSmooth = false
        resolvePending(scrollIfMissing = true)
    }

    private fun setFocusEffectSuppressed(suppressed: Boolean) {
        list.focusEffectSuppressed = suppressed
    }

    // ------------------------------------------------------------------
    // hero 态
    // ------------------------------------------------------------------

    /**
     * 进 / 出 hero 态 (返回键在页面里, 调这里). 淡入淡出 (hero 时间线) 三档都有 —— 流畅档也走, 聚焦行照旧直接跳到位 ([animatedScroll]):
     * 贵的是行的滚动动画, 淡入淡出只改几层的透明度.
     */
    fun setHeroActive(active: Boolean) {
        if (timeline.active == active || active && !heroEnabled) return
        // 按触屏的规矩走时退出 (页面按返回、点空白处): 停在原地, 焦点停放
        if (!active && touchMode) {
            touchPark()
            return
        }
        guest?.let {
            it.requestHeroActive(active)
            return
        }
        timeline.setActive(active, animated = true)
        applyTouchLook()
        if (!fixedAnchor) TvNativeFocusCues.setSpotlight(this, active, animated = true)
        rowListener?.onHeroActiveChanged(active)
        // 焦点没换, 只是进出 hero 态: 停位换成 hero 线 / 视口正中, 走 Far
        updateStop(heroToggled = true)
    }

    /** 整屏黑度 (不压黑时恒 0) 与分界线报给页面; 宾客模式下由宿主报. */
    protected fun reportTone() {
        if (guest == null) rowListener?.onToneChanged(stage.tone, splitY())
    }

    /**
     * 宾客模式: 宿主进出了 hero 态 (或换到本视图时对上它此刻的 hero 态). 停位换成 hero 线 / 视口正中: [animated] 走 Far, 否则当场到位
     * (换标签滑进来、返回本页恢复).
     */
    internal fun onHostHeroChanged(animated: Boolean) {
        applyTouchLook()
        if (animated) {
            // 按触屏的规矩走时退出 hero 态停在原地 (见 exitHeroInPlace)
            if (touchMode && !timeline.active) shiftToStop(-1, TvScrollSpring.Far) else updateStop(heroToggled = true)
        } else {
            val item = focusedRowKey?.let { list.indexOfKey(it) } ?: -1
            if (item >= 0) {
                list.jumpTo(stopFor(item))
                shiftToStop(item, pace = null)
            }
        }
        applyFrame()
    }

    /** 宾客模式: 宿主的 hero 时间线 / 点开的淡化走了一帧. */
    internal fun onHostHeroFrame() {
        applyTouchLook()
        flushTouchParkReport()
        applyFrame()
    }

    /** 点开对焦到位: 交给页面进详情页 (宾客模式下由宿主转过来). */
    internal fun onHostWallOpened(index: Int) {
        val rowKey = wallOpenRow ?: return
        rowListener?.onWallOpened()
        onWallOpenedAt(rowKey, index)
    }

    /** 聚焦那张卡的条目 (宿主进 hero 态时核对页面给的内容是不是它的); 没有时 null. */
    internal val focusedSubjectId: Int?
        get() = focusedRowKey?.let { tvNativeRowCards(list.items, it)?.getOrNull(focusedCardIndex)?.subjectId }

    /** 点开的那一行第 [index] 张的条目 (宿主换模糊背景时核对还能不能接着变清晰); 没在点开时 null. */
    internal fun openingSubjectId(index: Int): Int? =
        wallOpenRow?.let { tvNativeRowCards(list.items, it)?.getOrNull(index)?.subjectId }

    /** 顶部区域的分界线 (px, 从页面顶算; 探索页轮播的下缘随列表上移). 没有顶部区域时 0. */
    protected open fun splitY(): Float = 0f

    // ------------------------------------------------------------------
    // 触摸 (只在触屏设备上, 见 touchInput)
    // ------------------------------------------------------------------

    /** 这一轮列表滚动是手指拖出来的 (进过 SCROLL_STATE_DRAGGING, 只有触摸会进; 电视上恒 false): 停稳时就近停 (见 [settleAfterTouch]). */
    private var touchDragged = false

    /**
     * 手指拖完 (松手或甩完) 停稳: 列表的停位都是「某一行停在中线 (hero 态: hero 线)」或顶上的顶部区域, 手指拖动停在哪是哪 —— 按松手速度预判
     * 照惯性会停在哪 (见 [TvNativeExploreList.fling]), 挑离那里最近的停位: 一行就把焦点交给那一行上次停的那张、列表滚到它的停位 (= 遥控器上下
     * 走到那一行); 离顶更近交给 [settleTouchAtTop].
     */
    private fun settleAfterTouch() {
        touchDragged = false
        // 按触屏的规矩走时停在哪是哪 (见 touchMode)
        if (touchInput) return
        val goal = list.scrolledPx() + tvNativeFlingDistance(context, list.takeTouchFlingVelocity())
        val items = list.items
        val hero = timeline.active
        var rowKey: String? = null
        var best = Int.MAX_VALUE
        items.forEachIndexed { i, item ->
            if (item !is TvNativeExploreItem.Row) return@forEachIndexed
            val stop = if (hero) metrics.heroScroll(items, i) else metrics.centeredScroll(items, i)
            val distance = abs(stop - goal)
            if (distance < best) {
                best = distance
                rowKey = item.key
            }
        }
        val key = rowKey
        if (settleTouchAtTop(key == null || !hero && abs(goal) < best)) return
        if (key == null) return
        if (key == focusedRowKey && cardAreaHasFocus) updateStop(heroToggled = false) else focusCard(key, -1, far = true)
    }

    /** 手指拖完停在离顶部区域更近处 ([nearTop]; 一行都没有时也算): 子类把焦点交给顶部区域, 返回 true. 本类没有顶部区域, 就近停一行. */
    protected open fun settleTouchAtTop(nearTop: Boolean): Boolean = false

    // ------------------------------------------------------------------
    // 按键
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && tvNativeIsWallInputKey(event.keyCode)) lastInputTouch = false
        // 宾客模式下这两样宿主已经过了
        if (guest == null) {
            TvNativeFocusCues.onKey(this, event)
            // hero 态的模糊背景: 点开途中吞掉方向键与确认键, 按下确认键当场解清晰图 (见 TvNativeWallFocus.handleKey)
            if (wallFocus.handleKey(event)) return true
        }
        // 远跳途中的确认键算作用在目标上 (同 Apple TV: 滚动途中的输入作用在目的地): 记下 (按下与抬起都吞掉), 落到目标时点它
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
        if (touchParkedNow) {
            landFromTouchPark()
            return true
        }
        if (isFocused && descendantFocusability == FOCUS_BEFORE_DESCENDANTS) {
            // 焦点还停放在本视图上 (见 parkFocus): 远跳刚当场落地、落点要等这一趟布局 (见 landFarJumpNow) —— 紧跟着的按键 (连按 / 连发)
            // 吞掉, 不能把落点作废; 作废了焦点就一直停在本视图上, 之后的按键由系统从整块视图里找方向, 落到随便一张卡上.
            // 落地等了太久还没成 (那一趟布局没来): 不再干等, 按挂着的落点重新送一次. 没有挂着的落点 (停放没解开) 就送回这一行上次停的那张
            val pending = pendingRow
            if (pending != null) {
                if (SystemClock.uptimeMillis() - landingSince > TV_NATIVE_LANDING_STALE_MILLIS) focusCard(pending, pendingIndex)
                return true
            }
            val row = focusedRowKey
            if (row != null) {
                focusCard(row, -1)
                return true
            }
            unparkFocus()
        }
        if (event.repeatCount == 0) {
            // 用户按了新方向: 挂着的程序化落点 (含远跳) 作废, 从此刻的焦点接着走 (同 Compose 页面根的按键预览)
            cancelPending()
        }
        if (handleTopAreaKey(findFocus(), code)) return true
        val rowKey = focusedRowKey
        if (!cardAreaHasFocus || rowKey == null) return false
        if (vertical) {
            if (event.repeatCount > 0) {
                // 按住连发时上一格还没落地 (目标行还没排出来) 就先不往下排, 落地了再接着走. 不等的话落点一路跑到前面去, 列表追着它滚,
                // 焦点还停着的那张卡滚出屏被回收, 焦点丢了; 外面兜底送回来时找不到那张卡, 落到别处 —— hero 态因此被退出,
                // 之后的连发从那里往下走, 看着像退回卡片墙继续往下滚. 慢的设备 (debug 包、没预编译) 才落不了地
                if (pendingRow != null) return true
            }
            if (!verticalRepeat.pass(event.repeatCount, SystemClock.uptimeMillis())) return true
            navigateVertical(rowKey, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            return true
        }
        // 同横滑行的横向连发限速 (按键在这里就吃掉了, 到不了行)
        if (!horizontalRepeat.pass(event.repeatCount, SystemClock.uptimeMillis())) return true
        navigateHorizontal(rowKey, if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, repeat = event.repeatCount > 0)
        return true
    }

    /** 焦点在顶部区域 ([focused]) 时的方向键 ([code]); 接了返回 true. 本类没有顶部区域. */
    protected open fun handleTopAreaKey(focused: View?, code: Int): Boolean = false

    /**
     * 左右键: 同一行的上一张 / 下一张 (按需挪由横滑行在卡拿到焦点时自己滚). 不交给系统找焦点: 方向键没被原生树吃掉时由 Compose 用
     * FocusFinder 在整个宿主里按布局位置挑目标 —— 不计平移 (随列表上移出屏的顶部区域照样按原位参与), 行首停靠线左边露一截的那张
     * 常被别处比下去. 行首再按左出页面 (侧边栏; 按住的连发也出去, 同搜索页的网格), 焦点真落到行首那张之后才出; 行尾按右交给页面.
     */
    private fun navigateHorizontal(rowKey: String, direction: Int, repeat: Boolean) {
        // 连按时上一下还没落地 (目标卡还没排出来): 从它接着算
        val pending = pendingRow?.takeIf { pendingIndex >= 0 }
        val fromKey = pending ?: rowKey
        val from = if (pending != null) pendingIndex else focusedCardIndex
        val item = list.indexOfKey(fromKey)
        val row = (if (item >= 0) list.rowAt(item) else null) ?: return
        val target = from + direction
        // 循环行每一圈的第一张同行首: 按左出页面 (不往回接上一圈的最后一张)
        val period = loopPeriodOf(fromKey)
        if (target < 0 || direction < 0 && period > 0 && from % period == 0) {
            // 上一下还没落地 (焦点还在往行首赶): 这一下不出页面. 钉死锚点时按住的连发还要等这一行真滑到行首: 滚动落后焦点两张多,
            // 焦点到了第一张时画面还在往回滑, 这一发出去看着像还没到头就闪到了侧边栏
            if (pending == null && !(fixedAnchor && repeat && !row.isDocked(from))) rowListener?.onExitLeft()
            return
        }
        if (target >= row.cards.itemCount) {
            // 上一下还没落地 (焦点还在往行尾赶): 这一下不出页面
            if (pending == null) rowListener?.onExitRight()
            return
        }
        pendingRow = fromKey
        pendingIndex = target
        pendingColumn = -1
        // 每一下都是新目标: 没排出来就重新平滑滚向它 (落点留着, 连按从它接着算); 送上了由卡的聚焦回调当场清掉落点
        pendingSmooth = false
        resolvePending(scrollIfMissing = false)
    }

    protected fun rowKeys(): List<String> = list.items.filterIsInstance<TvNativeExploreItem.Row>().map { it.key }

    /** 第一行的行键; 没有行时 null. */
    override fun requestCardsFocus(): Boolean = requestFocus()

    override fun firstRowKey(): String? = list.items.firstOrNull { it is TvNativeExploreItem.Row }?.key

    /** 上下键: 落到上一行 / 下一行屏上同一列那张; 首行按上交给 [onFirstRowUp]; 末行按下吞掉. 上一次的落点还在路上时从它接着算. */
    private fun navigateVertical(rowKey: String, direction: Int) {
        val keys = rowKeys()
        val fromKey = pendingRow ?: rowKey
        val row = keys.indexOf(fromKey)
        if (row < 0) return
        val column = if (pendingRow != null && pendingColumn >= 0) pendingColumn else focusedColumn
        if (direction < 0 && row == 0) {
            onFirstRowUp()
            return
        }
        val target = row + direction
        if (target !in keys.indices) return
        focusCardAtColumn(keys[target], column)
    }

    /** 首行按上: 交给顶部区域 / 页面 (页面接了 = 焦点送走了, 挂着的落点作废). */
    protected open fun onFirstRowUp() {
        if (rowListener?.onTopRowUp() == true) cancelPending()
    }

    protected fun focusCardAtColumn(rowKey: String, column: Int) {
        holdCardAtColumn(rowKey, column)
        resolvePending(scrollIfMissing = true)
    }

    /** 落点挂到 [rowKey] 行屏上第 [column] 列, 先不送 (见 [resolvePending]). */
    private fun holdCardAtColumn(rowKey: String, column: Int) {
        pendingRow = rowKey
        pendingIndex = -1
        pendingColumn = column
        pendingSmooth = false
    }

    /**
     * 卡片排出来时不往上报"有可聚焦的视图了": 窗口此刻没有焦点的话 (冷启动; 返回本页时页面刚重建, 旧页面的焦点节点已经没了),
     * 系统会把焦点直接塞给第一个报上来的视图 (ViewRootImpl.focusableViewAvailable), 抢在页面自己的落点 (离开前那张卡) 之前 ——
     * 卡片获焦的回调把它当成停下的位置记下, 页面随后按它"恢复", 于是回到视口里第一张卡上. 本页的焦点一律由页面送 ([focusCard] /
     * 从页面外进来的 [onRequestFocusInDescendants]) 或按键移动, 用不着系统代劳.
     */
    override fun focusableViewAvailable(v: View?) = Unit

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (touchParkOnEntry()) return false
        // 有挂着的落点 (换数据时聚焦那一行没了, 持焦的卡随之被拆, 系统从窗口根上重新送焦走到这里): 照原定的落点送, 不改成上次那张
        val pending = pendingRow
        if (pending != null && farJumpRow == null && list.indexOfKey(pending) >= 0) {
            resolvePending(scrollIfMissing = true)
            return findFocus() != null || pendingRow != null
        }
        if (enterRowNumber != null && enterRowNow()) return true
        if (enterNearY != null) return enterNearRowNow()
        // 从页面外进来 (标签行按下 / 侧边栏按右 / 返回本页): 回上次那张卡, 那一行没了或没进过交给 [focusEntry]
        val rowKey = focusedRowKey?.takeIf { list.indexOfKey(it) >= 0 }
        if (rowKey != null) {
            focusCard(rowKey, -1)
            // 那一张还没排出来: 落点挂着, 排出来再送
            if (findFocus() != null || pendingRow != null) return true
        }
        return focusEntry()
    }

    /** 从页面外进来、没有上次那张卡时的落点: 本类落首行上次停的那张 (没停过就是行首); 子类 (探索页) 回顶部区域. */
    protected open fun focusEntry(): Boolean {
        val first = firstRowKey() ?: return false
        focusCard(first, -1)
        return findFocus() != null || pendingRow != null
    }

    /**
     * 卡拿到焦点时, 挂着的落点 (不在远跳途中) 指的是别处就不认这一下, 照原落点再送: 只有系统 / 列表自己送的焦点会这样 —— 按键与页面的落点
     * 都是先挂落点再送到那一张 (换数据时持焦的卡被拆, 列表自己恢复焦点落到了顶上来的那一行的随便哪张). 子类 (探索页) 不要时覆盖成 false.
     */
    protected open val redirectStrayFocus: Boolean get() = true

    /** [rowKey] 行第 [index] 张拿到了焦点, 而挂着的落点 (不在远跳途中) 指的是别处. 落点那一行还没排出来时不算 (那时也算不出它指哪张). */
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

    // ------------------------------------------------------------------
    // 焦点回调
    // ------------------------------------------------------------------

    private inner class RowListener(private val rowKey: String) : TvNativeCardListener {
        override fun onFocused(index: Int) = onCardFocused(rowKey, index)

        override fun onClick(index: Int) {
            // 卡片墙上先切到 hero 态, hero 态里才进详情页 (这时有 hero 背景图, 走放大转场); 没有 hero 态时直接交给页面
            when (tvWallConfirm(isMoreCard(rowKey, index), heroEnabled, timeline.active)) {
                TvWallConfirm.More -> rowListener?.onMoreClick(rowKey)
                TvWallConfirm.EnterHero -> setHeroActive(true)
                TvWallConfirm.Open -> {
                    // 进详情页: 焦点交出去之后这张卡仍画成聚焦态, 返回后焦点交还前也不缩 (见 holdLandingLook)
                    list.heldFocus = rowKey to index
                    // hero 态的模糊背景: 先对焦 (背景变清晰、卡片淡没) 再进, 见 TvNativeWallFocus
                    if (openFocusesWall && openWithWallFocus(rowKey, index)) return
                    rowListener?.onCardClick(rowKey, pageIndex(rowKey, index))
                }
            }
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            if (isMoreCard(rowKey, index)) return
            rowListener?.onCardLongPress(rowKey, pageIndex(rowKey, index), anchor.toComposeRect())
        }

        override fun tapConfirmsWhileFocusing(index: Int): Boolean =
            tvWallTapConfirmsWhileFocusing(isMoreCard(rowKey, index), heroEnabled, timeline.active)

        override val touchTaps: Boolean get() = touchInput

        override fun onTouchTap(index: Int, card: View) {
            when (tvWallTouchTap(isMoreCard(rowKey, index), heroEnabled, timeline.active, card.isFocused, isAboveFocusedRow(rowKey))) {
                TvWallTouchTap.OpenFocused -> openFocusedCard()
                TvWallTouchTap.FocusOnly -> card.requestFocus()
                TvWallTouchTap.FocusAndConfirm -> {
                    card.requestFocus()
                    onClick(index)
                }

                TvWallTouchTap.Confirm -> onClick(index)
            }
        }
    }

    private fun isMoreCard(rowKey: String, index: Int): Boolean =
        (list.items.getOrNull(list.indexOfKey(rowKey)) as? TvNativeExploreItem.Row)?.cards?.getOrNull(index)?.more != null

    /** 点开 [rowKey] 行第 [index] 张 (聚焦的那张): 背景此刻是这张卡的、能变清晰就先对焦, 到位再进 (返回 true); 否则 false, 调用方当场进. */
    private fun openWithWallFocus(rowKey: String, index: Int): Boolean {
        val wall = wallBackdrop ?: return false
        val matches = if (guest != null) {
            val subject = tvNativeRowCards(list.items, rowKey)?.getOrNull(index)?.subjectId
            subject != null && guest.wallMatches(subject)
        } else {
            val src = cardHero
            val t = wall.currentTarget
            showsCard && src != null && !src.dimming && t != null && t.sharp && t.subjectId != null && t.subjectId == src.rawSubjectId
        }
        wallOpenRow = rowKey
        if (wallFocus.open(index, matches)) return true
        wallOpenRow = null
        return false
    }

    /** 点开对焦到位: 交给页面进详情页. 子类另有自己的点开 (探索页轮播的「立即观看」) 时覆盖. */
    protected open fun onWallOpenedAt(rowKey: String, index: Int) {
        rowListener?.onCardClick(rowKey, pageIndex(rowKey, index))
    }

    /** [rowKey] 那一行的循环周期 (见 [TvNativeExploreItem.Row.loopPeriod]); 不循环 0. */
    private fun loopPeriodOf(rowKey: String): Int =
        (list.items.getOrNull(list.indexOfKey(rowKey)) as? TvNativeExploreItem.Row)?.loopPeriod ?: 0

    /** 行里第 [index] 张报给页面的下标: 循环行按周期折回条目下标, 否则原样. */
    private fun pageIndex(rowKey: String, index: Int): Int {
        val period = loopPeriodOf(rowKey)
        return if (period > 0 && index >= 0) index % period else index
    }

    /** 页面给的条目下标 [index] (-1 = 这一行上次停的那张) 换成行里的位置: 循环行取这一行停的那一圈里的那张, 否则原样. */
    private fun rowIndexOf(rowKey: String, index: Int): Int {
        val period = loopPeriodOf(rowKey)
        if (period <= 0 || index < 0) return index
        val at = rowFocusedIndex[rowKey] ?: list.rowLeftIndex[rowKey] ?: 0
        return at / period * period + index % period
    }

    /** 点开途中按了返回: 不进了, 倒放. */
    override fun cancelWallOpen() = wallFocus.cancelOpen()

    /** 从点开进去的详情页回来了 (页面在本页回到前台、缩回层撤掉之后调): 倒放回模糊背景与卡片. */
    override fun endWallOpen() = wallFocus.endOpen()

    /** 恢复点开的状态 (返回时页面重建): 卡片淡没、背景清晰 (清晰图解好就直接出现), 等页面调 [endWallOpen]. */
    override fun restoreWallOpen() = wallFocus.restoreOpen()

    private fun onCardFocused(rowKey: String, index: Int) {
        if (redirectStrayFocus && landedElsewhere(rowKey, index)) {
            // 焦点不是按挂着的落点来的 (见 redirectStrayFocus): 挂着的落点照送
            post { resolvePending(scrollIfMissing = true) }
            return
        }
        // 真焦点到了: 按住的聚焦态放开 (落在它自己身上画面不变, 落在别处它照失焦缩回)
        list.heldFocus = null
        val item = list.indexOfKey(rowKey)
        val row = if (item >= 0) list.rowAt(item) else null
        // 按需挪之后的行首: 走到行尾留白里那张才挪, 挪到它刚好完整露出; 钉死锚点时行首就是这一张
        val columns = metrics.columns.coerceAtLeast(1)
        val current = list.rowLeftIndex[rowKey] ?: row?.leftIndex() ?: 0
        val count = (list.items.getOrNull(item) as? TvNativeExploreItem.Row)?.cards?.size ?: (index + 1)
        val left = if (fixedAnchor) index else tvStripLeftIndex(current, index, count, columns)
        list.rowLeftIndex[rowKey] = left
        rowFocusedIndex[rowKey] = index
        focusedRowKey = rowKey
        focusedCardIndex = index
        focusedColumn = index - left
        if (pendingRow == rowKey || farJumpRow == null) pendingRow = null
        pendingInstant = false
        setCardAreaFocus(true)
        rowListener?.onCardFocused(rowKey, pageIndex(rowKey, index), focusedColumn)
        if (clickOnLandingRow != null) {
            // 远跳落地: 途中按的确认点在目标上 (走卡自己的点击, 同按一下确认; 放到这次焦点回调之后)
            val card = if (clickOnLandingRow == rowKey) row?.findViewHolderForAdapterPosition(index)?.itemView else null
            clickOnLandingRow = null
            card?.let { post { if (it.isFocused) it.performClick() } }
        }
        if (fixedAnchor && heroEnabled && !timeline.active && guest == null) {
            // 钉死锚点: 焦点落到卡上就进 hero 态 (按 hero 线滚过去, 见 setHeroActive)
            setHeroActive(true)
        } else if (!touchMode || timeline.active) {
            // 按触屏的规矩走时卡片墙上焦点不带着滚动 (见 touchMode)
            updateStop(heroToggled = false)
        }
        applyFrame()
    }

    protected fun setCardAreaFocus(has: Boolean) {
        if (cardAreaHasFocus == has) return
        cardAreaHasFocus = has
        applyHeaderEmphasis()
        updateAnchorRing()
        rowListener?.onCardAreaFocusChanged(has)
    }

    /** 固定聚焦框跟着焦点在不在卡片区显隐 (钉死锚点版式; 按卡片聚焦的节奏, 流畅档当场到位). */
    private fun updateAnchorRing() {
        val target = if (fixedAnchor && cardAreaHasFocus) 1f else 0f
        if (anchorRingTarget == target) return
        anchorRingTarget = target
        anchorRingAnimator?.cancel()
        anchorRingAnimator = null
        if (!transitions) {
            anchorRingShown = target
            applyAnchorRingAlpha()
            return
        }
        anchorRingAnimator = ValueAnimator.ofFloat(anchorRingShown, target).apply {
            duration = style.focusMillis
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                anchorRingShown = it.animatedValue as Float
                applyAnchorRingAlpha()
            }
            start()
        }
    }

    /** 固定聚焦框要过渡到的显示程度. */
    private var anchorRingTarget = 0f

    /**
     * 固定聚焦框的透明度 (显示程度乘点开时各行的淡没) 与纵向位置: 摆在 hero 线上, 聚焦行滚不到 hero 线 (开头几行, 见 heroShortfall) 时跟着它停在上面
     * 差的那一截, 再加列表此刻的整片平移.
     */
    private fun applyAnchorRingAlpha() {
        anchorRing.alpha = anchorRingShown * (1f - stage.openFade)
        val item = focusedRowKey?.let { list.indexOfKey(it) } ?: -1
        val shortfall = if (item >= 0) metrics.heroShortfall(list.items, item) else 0
        anchorRing.translationY = list.translationY - shortfall
    }

    /**
     * 哪一组的标题全亮 (其余淡一档, 照 tvOS 的货架标题): 跟着焦点要落 / 已落的那张卡走 —— 远跳途中与落点还挂着时是落点那一行,
     * 没有真焦点而按住聚焦态 (进页恢复) 时是那一张所在的行, 否则焦点在卡片区时的聚焦行; 焦点在顶部区域 / 本页以外时没有.
     */
    protected fun applyHeaderEmphasis() {
        val rowKey = farJumpRow ?: pendingRow ?: list.heldFocus?.first ?: focusedRowKey?.takeIf { cardAreaHasFocus }
        list.setEmphasizedHeader(rowKey?.let { headerKeyOf(it) }, animate = transitions)
    }

    /** [rowKey] 那一行所在组的标题项 (往上最近的一个标题; 一组切成几行时后面几行也算它的). */
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
        touchParked = false
        touchParkReportPending = false
        // 远跳 / 触屏停放的焦点落地了 (目标卡 / 顶部区域拿到焦点)
        if (isFocusable) {
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            isFocusable = false
        }
        setCardAreaFocus(child === list)
    }

    override fun clearChildFocus(child: View?) {
        super.clearChildFocus(child)
        // 焦点离开本页 (去侧边栏): 卡片区失焦, 簿记 (上次那一行) 保留
        setCardAreaFocus(false)
    }

    /**
     * 焦点换到本页以外 (侧边栏等 Compose 控件): 这条路上祖先只经 unFocus 清掉旧焦点, 不回调 [clearChildFocus], 按窗口的焦点变化判.
     * 远跳停放时焦点在本视图自己身上, 仍算在卡片区.
     */
    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (!tvNativeIsInside(newFocus, this)) {
            touchParked = false
            setCardAreaFocus(false)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
        applyTouchLook()
        // 宾客模式下聚焦提示按宿主那一页算
        if (guest == null) TvNativeFocusCues.attachPage(this)
    }

    /**
     * 行结构变了 (分页晚到插在头部、推荐重组): 聚焦行跟着自己的新位置瞬时对回停位 —— RecyclerView 整体刷新时按下标保位置, 头部插了项聚焦行
     * 就被挤出停位. 数据到货不是用户动作, 不做动画. 焦点不在卡片区 (在顶部区域上, 列表停在顶上) 时不动.
     */
    private fun realignFocusedRow() {
        val rowKey = focusedRowKey ?: return
        if (!cardAreaHasFocus || farJumpRow != null) return
        val item = list.indexOfKey(rowKey)
        if (item < 0) return
        list.jumpTo(stopFor(item))
        shiftToStop(item, pace = null)
    }

    /** 按聚焦行 (与是否 hero 态) 滚到停位: 进出 hero 态用 Far, 远跳那一行用 Far, 逐格用 Step. */
    private fun updateStop(heroToggled: Boolean) {
        val rowKey = focusedRowKey ?: return
        if (!cardAreaHasFocus && !heroToggled) return
        val item = list.indexOfKey(rowKey)
        if (item < 0) return
        val far = heroToggled || rowKey == farJumpRow
        val pace = if (far) TvScrollSpring.Far else TvScrollSpring.Step
        list.scrollToStop(stopFor(item), pace, animatedScroll)
        shiftToStop(item, pace)
    }

    /** 列表此刻整片往下推了多少 (px, 见 [shiftToStop]). */
    private var heroShift = 0f
    private var heroShiftAnimator: ValueAnimator? = null

    /**
     * 整片平移跟到第 [item] 项那一行的停位: hero 态里开头几行滚不到 hero 线 (上面没有顶部区域的占位), 差的那截由列表整片往下推补上
     * ([heroShortfall], 同网格页); 卡片墙上 0. [pace] 同这一段滚动的 spring, null = 当场到位.
     */
    private fun shiftToStop(item: Int, pace: TvScrollSpring?) {
        val target = if (timeline.active && item >= 0) metrics.heroShortfall(list.items, item).toFloat() else 0f
        heroShiftAnimator?.cancel()
        heroShiftAnimator = null
        if (pace == null || !animatedScroll || abs(target - heroShift) < 0.5f) {
            setHeroShift(target)
            return
        }
        val from = heroShift
        val millis = tvSpringDurationMillis(pace, abs(target - from))
        heroShiftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = millis.toLong()
            interpolator = tvSpringInterpolator(pace, millis)
            addUpdateListener { setHeroShift(from + (target - from) * (it.animatedValue as Float)) }
            start()
        }
    }

    private fun setHeroShift(px: Float) {
        if (heroShift == px && list.translationY == px) return
        heroShift = px
        list.translationY = px
        applyFrame()
    }

    // ------------------------------------------------------------------
    // 逐帧联动
    // ------------------------------------------------------------------

    private fun onListMoved() {
        // 等着的落点行排出来了就送 (不再发起滚动)
        if (pendingRow != null && farJumpRow == null) resolvePending(scrollIfMissing = false)
        applyFrame()
        reportTone()
    }

    /**
     * 背景图与文字换成聚焦卡的那一刻: 进 hero 态时等上面几行淡没 (above 到 1), 出时等聚焦卡的内容淡完. 换的时候看不见, 直接到位、
     * 整个重建 (不交叉淡入).
     */
    protected fun cardShownNow(): Boolean =
        timeline.visible && focusedRowKey != null && if (timeline.active) timeline.above >= 1f else timeline.content > 0f

    /** 按此刻的 hero 态进度摆背景图 / 文字, 算各项的淡化. 只动 RenderNode 属性. 子类有顶部区域时覆盖 (两路内容、背景图尺寸插值). */
    protected open fun applyFrame() {
        if (guest != null) {
            applyHeaderEmphasis()
            applyItemFades()
            return
        }
        val m = metrics
        val card = cardShownNow()
        if (card != showsCard || !modeSettled) onShowsCardChanged(card)
        val heroShown = if (showsCard) stage.contentAlpha else 0f
        // 点开时 (openFade) 聚焦卡的背景图随各行淡没, 底下的模糊背景变清晰接上
        backdrop.alpha = heroShown * (1f - stage.openFade)
        // 铺着模糊背景: 最底下铺卡片墙的底色, 跟着上面几行淡入淡出 (见 wallColor)
        val floor = if (heroBlur) (timeline.above * (wallColor ushr 24)).roundToInt() else 0
        heroFloor.color = if (floor <= 0) Color.TRANSPARENT else (wallColor and 0xFFFFFF) or (floor shl 24)
        val wall = wallBackdrop
        if (wall != null) {
            // hero 态的模糊背景跟着 hero 时间线显隐
            wall.alpha = heroShown
            wall.translationY = 0f
            wall.setFloor(Float.NaN, 0f, Color.TRANSPARENT)
        }
        // 叠在模糊背景上、背景图看得见时边缘擦成透明 (看不见时不擦, 不走离屏层); 进详情页从整屏背景起, 背景图不登记成放大转场的来源
        backdrop.feather = wall != null && heroShown > 0f
        backdrop.zoomSource = !heroBlur
        backdrop.scaleX = m.cardBackdropScale
        backdrop.scaleY = m.cardBackdropScale
        backdrop.translationY = 0f
        if (lastTreatmentMode != 1f) {
            lastTreatmentMode = 1f
            backdrop.treatment = treatmentFor?.invoke(1f)
        }
        backdrop.publishZoom()
        heroBox.translationY = 0f
        heroBox.alpha = if (showsCard) timeline.text else 0f
        heroText.setTitleMarquee(showsCard)
        heroText.publishTitle()
        applyHeaderEmphasis()
        applyItemFades()
    }

    /**
     * hero 态里越过 hero 线的项在 [TvNativeExploreMetrics.fadeDistancePx] 内淡没, 卡片番名跟着 above 淡.
     * 卡片墙上不淡 (上面的行大半还在屏上). 进出途中 (hero 态还没停稳) 不按位置淡: 聚焦行连同它的组标题及以下不淡 —— 卡片墙上聚焦行停在正中,
     * 在 hero 线之上, 途中按位置淡就是整行先暗一下再亮回来; 上面的项整项跟着 above 淡 —— 退场时它们随列表往下挪、从 hero 线上方经过,
     * 按位置淡就在背景图还没淡完时先露出来. 点开 ([TvNativeHeroStage.openFade]) 时各行都跟着淡没.
     */
    protected fun applyItemFades() {
        val m = metrics
        val p = timeline.above
        val keepFrom = keepFromIndex()
        val items = list.items
        val openFade = stage.openFade
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos >= items.size) continue
            val a = (
                if (p <= 0f) {
                    1f
                } else if (timeline.content < 1f) {
                    if (pos < keepFrom) 1f - p else 1f
                } else {
                    val above = m.heroHeaderTopPx - (child.top - list.paddingTop + list.translationY)
                    if (above <= 0f) 1f else 1f - (above / m.fadeDistancePx).coerceIn(0f, 1f) * p
                }
                ) * (1f - openFade)
            when (child) {
                is TvNativeRowView -> {
                    child.rowAlpha = a * belowLineDim(child, p)
                    // 钉死锚点不显示番名 (标题在 hero 里)
                    child.cards.setTitleVisibility(child, if (fixedAnchor) 0f else 1f - p)
                }

                is TvNativeExploreHeaderView -> child.fade = if (hideHeadersInHero) a * (1f - p) else a
                else -> child.alpha = a
            }
        }
        // 固定聚焦框跟着点开淡没、跟着聚焦行的停位 (子类的逐帧也走这里)
        if (fixedAnchor) applyAnchorRingAlpha()
    }

    /**
     * 钉死锚点版式 hero 态里 hero 线下面的行压暗 (同行首左边滑过停靠线的卡, 压到 [TV_CARD_PAST_DIM_ALPHA]): 按这一行离 hero 线的距离, 往上滑到
     * hero 线的途中逐渐提亮, 越过 hero 线的照常越线淡没. 跟着 hero 态的进度 [p] 进出; 别的版式恒 1.
     */
    private fun belowLineDim(row: View, p: Float): Float {
        if (!fixedAnchor || p <= 0f) return 1f
        val m = metrics
        val below = row.top - (list.paddingTop + m.headerPx + m.heroHeaderTopPx)
        if (below <= 0) return 1f
        val t = (below.toFloat() / m.rowPx.coerceAtLeast(1)).coerceIn(0f, 1f)
        return 1f - t * (1f - TV_CARD_PAST_DIM_ALPHA) * p
    }

    /** 进出 hero 态途中不按位置淡的起点: 聚焦行的组标题 (紧挨在上面时), 否则聚焦行本身; 焦点在顶部区域上时 0. */
    private fun keepFromIndex(): Int {
        val rowKey = focusedRowKey ?: return 0
        val item = list.indexOfKey(rowKey)
        if (item < 0) return 0
        return if (item > 0 && list.items[item - 1] is TvNativeExploreItem.Header) item - 1 else item
    }

    /**
     * 背景图与文字换成 / 换回聚焦卡 ([card]): 换的那一刻看不见 (见 [cardShownNow]), 直接到位, 图与文字整个重建 (不交叉淡入, 否则没褪完的旧图 /
     * 旧字会跟着露出来). 子类有顶部区域时覆盖 (两路之间的交接).
     */
    protected open fun onShowsCardChanged(card: Boolean) {
        val settled = modeSettled
        markShowsCard(card)
        if (settled) backdrop.rebuild()
        applySources(reset = settled)
    }

    /** 记下此刻显示哪一路 (子类覆盖 [onShowsCardChanged] 时调). */
    protected fun markShowsCard(card: Boolean) {
        showsCard = card
        modeSettled = true
    }

    /** 按当前来源换背景图与文字. [reset] = 换来源的重建 (文字当场换). 子类有顶部区域时覆盖. */
    protected open fun applySources(reset: Boolean) {
        if (guest != null) return
        val src = if (showsCard) cardHero else null
        if (src == null) {
            backdrop.show(null)
            stage.showWall(null, replace = reset)
            heroText.setText(null, TvNativeTextTransition.Reset)
            return
        }
        // 按下即压暗: hero 态里换了卡就压暗
        if (lastDimSubject != null && lastDimSubject != src.rawSubjectId) backdrop.triggerPressDim()
        lastDimSubject = src.rawSubjectId
        backdrop.dimming = src.dimming
        backdrop.show(src.backdrop)
        // 模糊背景: 聚焦卡铺那一部的横版背景图 (整部的那张). 换来源的重建 (看不见的那一刻换) 当场撤掉旧的那张, 不跟新的交叉淡入
        stage.showWall(src.wall, replace = reset)
        heroText.setText(stage.textOf(src), if (reset) TvNativeTextTransition.Reset else TvNativeTextTransition.Key)
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = metrics
        val width = m.bleedLeftPx + m.pageWidthPx
        val height = m.pageHeightPx
        fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.EXACTLY)
        if (guest == null) {
            wallBackdrop?.measure(exactly(width), exactly(height))
            if (padHero) {
                val backdropHeight = padBackdropHeightPx()
                backdrop.measure(exactly((backdropHeight * TV_BACKDROP_ASPECT_RATIO).roundToInt()), exactly(backdropHeight))
                heroBox.measure(exactly(padTitleWidthPx), exactly(padTextBoxBottomPx() - m.heroTopPx))
            } else {
                backdrop.measure(exactly(m.backdropWidthPx), exactly(m.backdropHeightPx))
                heroBox.measure(exactly(m.pageWidthPx - m.heroStartPx - m.heroEndPadPx), exactly(m.heroBlockExpandedPx))
            }
        }
        list.measure(exactly(width), exactly(height - m.listTopPx + m.listTopBleedPx))
        if (anchorRing.parent != null) anchorRing.measure(exactly(style.cardWidthPx), exactly(style.cardHeightPx))
        measureTopArea()
        setMeasuredDimension(width, height)
    }

    /** 子类量顶部区域自己的子视图 (探索页的圆点). */
    protected open fun measureTopArea() {}

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val m = metrics
        if (guest == null) {
            wallBackdrop?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
            val bx = m.bleedLeftPx + m.pageWidthPx - backdrop.measuredWidth
            backdrop.layout(bx, 0, bx + backdrop.measuredWidth, backdrop.measuredHeight)
            // 以右上角为轴缩放 (图的比例几何不变)
            backdrop.pivotX = backdrop.measuredWidth.toFloat()
            backdrop.pivotY = 0f
            val hx = m.bleedLeftPx + m.heroStartPx
            heroBox.layout(hx, m.heroTopPx, hx + heroBox.measuredWidth, m.heroTopPx + heroBox.measuredHeight)
        }
        val ly = m.listTopPx - m.listTopBleedPx
        list.layout(0, ly, list.measuredWidth, ly + list.measuredHeight)
        if (anchorRing.parent != null) {
            // hero 态聚焦行的卡片顶 = 列表内容顶 + 组标题 + hero 线 (见 heroScroll), 卡格左缘 = 行首停靠线
            val ry = m.listTopPx + m.headerPx + m.heroHeaderTopPx
            anchorRing.layout(m.rowStartPx, ry, m.rowStartPx + anchorRing.measuredWidth, ry + anchorRing.measuredHeight)
        }
        layoutTopArea()
        applyFrame()
        // 建出来之前就发过来的落点
        if (!resolveTopAreaPending() && pendingRow != null) post { resolvePending(scrollIfMissing = true) }
        // 就近接住那一发来的时候还一行都没排出来 (见 enterNearRowNow)
        if (enterNearDeferred) {
            post {
                if (enterNearDeferred) {
                    enterNearDeferred = false
                    enterNearRowNow()
                }
            }
        }
    }

    /** 子类排顶部区域自己的子视图. */
    protected open fun layoutTopArea() {}

    /** 建出来之前就发给顶部区域的落点 (探索页的 hero 按钮): 有就送 (post) 并返回 true. */
    protected open fun resolveTopAreaPending(): Boolean = false

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        if (guest == null) TvNativeFocusCues.detachPage(this)
        super.onDetachedFromWindow()
        scrollTracker.stop()
        if (guest == null) stage.detach()
    }

    /**
     * hero 文字块 + 顶部区域的附加块 ([extra], 探索页的轮播按钮; 没有时 null), 纵向排: 块高两档 (有附加块
     * [TvNativeExploreMetrics.heroBlockExpandedPx] / 没有 [TvNativeExploreMetrics.heroBlockPx]), 文字吃掉附加块之外的全部高度;
     * 附加块离文字 [TvNativeExploreMetrics.buttonsTopGapPx].
     */
    protected inner class HeroBox(context: Context) : TvNativeBoundaryLayout(context) {
        var extra: View? = null
            set(value) {
                if (field === value) return
                field?.let { removeView(it) }
                field = value
                value?.let { addView(it) }
                requestLayout()
            }

        /** 附加块此刻占不占位 (淡没撤掉时不占). */
        var extraPresent = false
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        init {
            clipChildren = false
            clipToPadding = false
        }

        override fun measureContent(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val m = metrics
            val width = MeasureSpec.getSize(widthMeasureSpec)
            if (padHero) {
                // 平板版: 文字占满整块、贴底排 (见 TvNativeHeroTextView.padLayout), 按钮不在块里
                val height = MeasureSpec.getSize(heightMeasureSpec)
                heroText.titleWidthPx = width
                heroText.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                setMeasuredDimension(width, height)
                return
            }
            val ex = extra?.takeIf { extraPresent }
            val block = if (ex != null) m.heroBlockExpandedPx else m.heroBlockPx
            var extraHeight = 0
            if (ex != null) {
                ex.setPadding(0, m.buttonsTopGapPx, 0, 0)
                ex.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                extraHeight = ex.measuredHeight
            }
            heroText.titleWidthPx = m.titleWidthPx
            heroText.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((block - extraHeight).coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun layoutContent(width: Int, height: Int) {
            heroText.layout(0, 0, heroText.measuredWidth, heroText.measuredHeight)
            val ex = extra?.takeIf { extraPresent } ?: return
            val y = heroText.measuredHeight
            ex.layout(0, y, ex.measuredWidth, y + ex.measuredHeight)
        }

        override fun hasOverlappingRendering(): Boolean = false
    }
}

/**
 * 滚动容器在不在挪 (判据同 ReportTvScrollActivity): 开始滚动 (拖动 / 平滑滚动) 即算在滚; 之后连续 [TV_SCROLL_STILL_FRAMES]
 * 帧每帧挪动不超过 [TV_SCROLL_STILL_PX] 就算停稳 (spring 尾巴每帧挪一两像素时肉眼已经停了), 滚动结束也算停.
 */
internal class TvNativeScrollTracker(private val onChanged: (Boolean) -> Unit) {
    private var scrolling = false
    private var frameDelta = 0
    private var stillFrames = 0
    private var host: View? = null
    private val frame = object : Runnable {
        override fun run() {
            val h = host ?: return
            if (frameDelta > TV_SCROLL_STILL_PX) {
                stillFrames = 0
                set(true)
            } else {
                stillFrames++
                if (stillFrames >= TV_SCROLL_STILL_FRAMES) {
                    set(false)
                    host = null
                    return
                }
            }
            frameDelta = 0
            h.postOnAnimation(this)
        }
    }

    fun onStateChanged(view: View, state: Int) {
        if (state == RecyclerView.SCROLL_STATE_IDLE) {
            set(false)
            host?.removeCallbacks(frame)
            host = null
            return
        }
        set(true)
        stillFrames = 0
        frameDelta = 0
        if (host == null) {
            host = view
            view.postOnAnimation(frame)
        }
    }

    fun onMoved(delta: Int) {
        frameDelta += abs(delta)
    }

    fun stop() {
        host?.removeCallbacks(frame)
        host = null
        set(false)
    }

    private fun set(value: Boolean) {
        if (scrolling == value) return
        scrolling = value
        onChanged(value)
    }
}

/** 远跳途中按方向键当场落地, 超过这么久焦点还停放着 (那一趟布局没来) 就当落地失败, 下一次按键按挂着的落点重送 (见 handleKeyDown). */
private const val TV_NATIVE_LANDING_STALE_MILLIS = 400L

/** 远跳的纵向距离在这么多行 (含组标题) 以内按逐格的 spring 滚 (见 TvNativeRowWallView.focusCard). */
private const val TV_NATIVE_NEAR_JUMP_ROWS = 2

/** 换内容滑动开始时跨出那一屏宽的卡淡出多久 (见 TvNativeRowWallView.beginLane): 赶在整片滑起来之前没掉. */
private const val TV_NATIVE_LANE_CARD_OUT_MILLIS = 120L

/** 算「用键盘 / 遥控器操作」的键 (同 iPad 的 tvWallInputMode): 方向键与确认键. */
internal fun tvNativeIsWallInputKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
    else -> tvNativeIsConfirmKey(keyCode)
}
