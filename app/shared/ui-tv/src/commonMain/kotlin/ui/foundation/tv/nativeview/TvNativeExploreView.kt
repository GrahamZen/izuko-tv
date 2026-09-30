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
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.runtime.Immutable
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.sketch.Sketch
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_FADE_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_FADE_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_STILL_FRAMES
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_STILL_PX
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import kotlin.math.abs
import me.him188.ani.app.ui.foundation.tv.tvPosterWallToneGate

/**
 * 探索页原生海报墙的几何 (px). 原生根视图从屏幕左缘画起 (比页面往左多 [bleedLeftPx], 横滑行从侧边栏底下滑过), 纵坐标 = 页面坐标.
 * 由页面 (TvExplorationPage) 按窗口尺寸与各 TV_EXPLORATION_* 常量算好.
 */
@Immutable
data class TvNativeExploreMetrics(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val bleedLeftPx: Int,
    val columns: Int,
    /** 卡片区顶线 (TV_EXPLORATION_WALL_TOP). */
    val listTopPx: Int,
    val listTopBleedPx: Int,
    val listBottomBleedPx: Int,
    val spacerPx: Int,
    val headerPx: Int,
    val rowPx: Int,
    val rowGapPx: Int,
    /** 顶线到屏幕底边. */
    val viewportPx: Int,
    val endMarginPx: Int,
    /** hero 态聚焦行组标题停的那条线 (列表内容坐标). */
    val heroHeaderTopPx: Int,
    /** 行首停靠线离屏幕左缘 (TV_EXPLORATION_ROW_START_BLEED). */
    val rowStartPx: Int,
    val endPadPx: Int,
    val fadeDistancePx: Float,
    /** 轮播背景图 (未缩放) 的下缘离页面顶. */
    val carouselBottomPx: Float,
    /** 背景图比 hero 占位长出去的那一截 (见 tvNativeCarouselShift). */
    val overhangPx: Int,
    val backdropWidthPx: Int,
    val backdropHeightPx: Int,
    /** hero 态 (聚焦卡) 背景图相对轮播的缩放 (TV_CARD_HERO_TUNING / TV_WALL_BACKDROP_HEIGHT). */
    val cardBackdropScale: Float,
    val heroStartPx: Int,
    val heroTopPx: Int,
    val heroEndPadPx: Int,
    val heroBlockPx: Int,
    val heroBlockExpandedPx: Int,
    val titleWidthPx: Int,
    val carouselSummaryWidthPx: Int,
    val cardSummaryWidthPx: Int,
    val buttonsTopGapPx: Int,
    val buttonGapPx: Int,
    /** 轮播指示器中心离页面顶 (分界线下方 TV_CAROUSEL_INDICATOR_OFFSET). */
    val dotsCenterYPx: Int,
    val dotPx: Float,
    val dotSelectedWidthPx: Float,
    val dotGapPx: Float,
    /** 非当前项的不透明度 (乘在当前项的颜色上). */
    val dotInactiveAlpha: Float,
)

/**
 * hero 的一路展示内容 (热门轮播 / 聚焦卡各一路, 由页面在 Compose 里按换挡规则算好): [backdrop] 停稳后的背景图, [dimming] = 目标已换、
 * 展示还没跟上 (按下即压暗要等它放开), [rawSubjectId] = 此刻真实的目标 (换了就压暗), [text] = 文字 (null = 滚动 / 连发中藏起来),
 * [autoAdvanced] = 这次换页是自动轮播推进的 (文字用放慢的过渡).
 */
@Immutable
data class TvNativeHeroSource(
    val backdrop: TvNativeBackdropTarget?,
    val dimming: Boolean,
    val rawSubjectId: Int?,
    val text: TvNativeHeroText?,
    val autoAdvanced: Boolean = false,
)

/** 探索页原生海报墙的事件 (页面实现). 行键就是页面焦点簿记用的那套 (TV_FOLLOWED_ROW_KEY / tvRecRowKey). */
interface TvNativeExploreListener {
    /** 卡片拿到焦点: [column] = 行按需挪之后它落在屏上第几列. */
    fun onCardFocused(rowKey: String, index: Int, column: Int)

    /** hero 态里的卡片确定键 (进详情页). 卡片墙上按确定先进 hero 态, 不回调. */
    fun onCardClick(rowKey: String, index: Int)

    fun onCardLongPress(rowKey: String, index: Int, anchor: Rect)

    /** 卡片被绑定 (分页的访问提示). */
    fun onBindCard(rowKey: String, index: Int)

    /** hero 按钮拿到焦点 ([button] 0 = 立即观看, 1 = 新番时间表). */
    fun onHeroButtonFocused(button: Int)

    fun onHeroButtonClick(button: Int)

    /** hero 按钮上按左 / 右翻轮播; 返回 false = 没翻 (第一项按左: 交给侧边栏). */
    fun onSwitchCarousel(delta: Int): Boolean

    /** 按左出页面 (进侧边栏). */
    fun onExitLeft()

    /** 进 / 出 hero 态 (页面存起来, 返回本页时恢复). */
    fun onHeroActiveChanged(active: Boolean)

    /** 焦点进出卡片区. */
    fun onCardAreaFocusChanged(hasFocus: Boolean)

    /** 整屏黑度 (hero 态时间线的 tone, 见 TvNativeHeroTimeline) 与轮播分界线 (px, 从页面顶算) 变了: 交给主壳画整屏底色. */
    fun onToneChanged(tone: Float, splitY: Float)

    /** 滚动容器在不在挪 (hero 文字滚动期间藏起来, 见 TvScrollActivity). */
    fun onScrollingChanged(scrolling: Boolean)
}

/**
 * 探索页海报墙: 背景图 ([TvNativeBackdropView]) < hero 文字与轮播按钮 < 卡片列表 ([TvNativeExploreList]) < 轮播指示器 (卡片列表压在
 * hero 上面: 离场的行淡出时从 hero 上面滑过). 数据、hero 媒体流水线、换挡规则、导航、返回键分层留在 Compose 页面里 ([TvNativeExploreListener] 回调 + 本类的
 * set / focus 方法); 焦点、滚动、hero 态时间线与所有逐帧的联动 (轮播跟着列表上移、背景图在轮播与聚焦卡两套尺寸间缩放、上面几行淡没)
 * 在这里, 每帧只动 RenderNode 属性.
 *
 * 焦点规则: 上下键落到上一行 / 下一行屏上同一列那张 (行短了落到最后一张), 首行按上回「新番时间表」, 「新番时间表」按下进首行
 * 屏上第一列; 行首按左、hero 按钮在第一项按左出页面 (侧边栏); 行尾按右、末行按下吞掉. 长按方向键纵向 6 次 / 秒, 横向 8 次 / 秒.
 */
@SuppressLint("ViewConstructor")
class TvNativeExploreView(
    context: Context,
    private val sketch: Sketch,
    scope: CoroutineScope,
    style: TvNativeWallStyle,
    metrics: TvNativeExploreMetrics,
    heroTextStyle: TvNativeHeroTextStyle,
    buttonStyle: TvNativeHeroButtonStyle,
    headerStyle: TvNativeTextStyle,
) : FrameLayout(context), TvNativeAmbientAnimations {
    var listener: TvNativeExploreListener? = null

    var style: TvNativeWallStyle = style
        private set
    var metrics: TvNativeExploreMetrics = metrics
        private set

    /** 视觉效果: 过渡 (hero 态时间线、背景图尺寸动画、按钮淡入) 与动画滚动. */
    var transitions: Boolean = true
    var animatedScroll: Boolean = true
        set(value) {
            field = value
            list.scroll.animated = value
        }

    /** Compose 根视图 (放大转场的框按它的坐标登记). */
    var composeRoot: View? = null
        set(value) {
            field = value
            backdrop.composeRoot = value
            heroText.composeRoot = value
        }

    private val backdrop = TvNativeBackdropView(context, sketch, scope)

    /** 此刻在不在导航: 背景图的剧照升档等它为 false 才去取原图 (见 [TvNativeBackdropView.navigating]). */
    var backdropNavigating: () -> Boolean
        get() = backdrop.navigating
        set(value) {
            backdrop.navigating = value
        }

    private val heroBox = HeroBox(context)
    val heroText = TvNativeHeroTextView(context, heroTextStyle)
    private val buttons = LinearLayout(context)
    private val primaryButton = TvNativeHeroButton(context, buttonStyle, filled = true)
    private val scheduleButton = TvNativeHeroButton(context, buttonStyle, filled = false)
    private val list = TvNativeExploreList(context, sketch, style, metrics, headerStyle)
    private val dots = TvNativeCarouselDotsView(context, metrics.dotPx, metrics.dotSelectedWidthPx, metrics.dotGapPx, metrics.dotInactiveAlpha)

    private val timeline = TvNativeHeroTimeline { onTimeline() }
    private val scrollTracker = TvNativeScrollTracker { listener?.onScrollingChanged(it) }

    override fun setAmbientAnimationsPaused(paused: Boolean) {
        heroText.marqueePaused = paused
    }

    // ---- 焦点簿记 ----
    /** 上次聚焦的行 (null = 焦点在 hero 按钮上, 或从未进过卡片区). 焦点去侧边栏时不清. */
    var focusedRowKey: String? = null
        private set
    var focusedCardIndex: Int = 0
        private set
    private var focusedColumn = 0
    /** 焦点此刻在卡片区. */
    var cardAreaHasFocus: Boolean = false
        private set
    /** 上次停的那颗 hero 按钮 (0 立即观看 / 1 新番时间表). */
    var lastHeroButton: Int = 0

    /** 还没送出去的落点 (目标行还没排出来): 行键, 下标 (-1 = 屏上第 [pendingColumn] 列), 远跳. */
    private var pendingRow: String? = null
    private var pendingIndex = -1
    private var pendingColumn = 0

    /** 目标行已在平滑滚向挂着的落点: 重试只在那张排出来 / 滚动停下时送焦, 不再发起滚动 (见 [resolvePending]). */
    private var pendingSmooth = false
    private var farJumpRow: String? = null

    /** 远跳途中按了确认: 落到目标时点它 (见 [dispatchKeyEvent]). */
    private var farJumpConfirmQueued = false

    /** 远跳已落地、在等目标那一行的卡拿到焦点再点 (见 [onCardFocused]); null = 没有. */
    private var clickOnLandingRow: String? = null
    private var lastVerticalRepeat = 0L
    private var lastHorizontalRepeat = 0L
    private var pendingScrollPx = -1
    private var pendingHeroButton = -1

    /** 各行上次停的那张 (从行外进来 / 返回键分层回这一行时落在它上面), 按行键. */
    private val rowFocusedIndex = HashMap<String, Int>()

    // ---- hero ----
    private var carouselSource: TvNativeHeroSource? = null
    private var cardSource: TvNativeHeroSource? = null
    private var showsCard = false
    private var modeSettled = false
    private var mode = 0f
    private var modeAnimator: ValueAnimator? = null
    private var carouselGone = false
    private var lastDimSource: Boolean? = null
    private var lastDimSubject: Int? = null
    private var buttonsAnimator: ValueAnimator? = null
    private var dotsShown = true
    private var dotsFade = 1f
    private var dotsAnimator: ValueAnimator? = null
    private var titleHeightPx = Int.MAX_VALUE / 2

    /** 遮罩 (按背景图尺寸插值 0 = 轮播, 1 = 聚焦卡), 页面给. */
    var treatmentFor: ((mode: Float) -> TvBackdropTreatment)? = null
        set(value) {
            field = value
            lastTreatmentMode = Float.NaN
            applyFrame()
        }
    private var lastTreatmentMode = Float.NaN

    /** 深色主题: hero 态整屏压黑, 背景图等黑透才露面 (浅色两者同色, 不等). */
    var dark: Boolean = true
        set(value) {
            field = value
            timeline.dark = value
            applyFrame()
        }

    /** 换页交接途中分界线那层的浓度 (TvPosterWallTone.splitGate), 乘在轮播背景图上. */
    var splitGate: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            applyFrame()
        }

    /** 背景图遮罩色 (hero 的底). */
    var fadeColor: Int
        get() = backdrop.fadeColor
        set(value) {
            backdrop.fadeColor = value
        }

    init {
        clipChildren = false
        clipToPadding = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isFocusable = false
        addView(backdrop)
        buttons.orientation = LinearLayout.VERTICAL
        buttons.clipChildren = false
        buttons.addView(primaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        buttons.addView(scheduleButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        heroBox.addView(heroText)
        heroBox.addView(buttons)
        addView(heroBox)
        addView(list)
        addView(dots)
        primaryButton.onFocused = { onHeroButtonFocused(0) }
        scheduleButton.onFocused = { onHeroButtonFocused(1) }
        primaryButton.setOnClickListener { listener?.onHeroButtonClick(0) }
        scheduleButton.setOnClickListener { listener?.onHeroButtonClick(1) }
        list.cardListener = { rowKey -> RowListener(rowKey) }
        list.onBindCard = { rowKey, index -> listener?.onBindCard(rowKey, index) }
        list.onItemsMoved = { onListMoved() }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                scrollTracker.onStateChanged(this@TvNativeExploreView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) finishFarJump()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                scrollTracker.onMoved(dy)
            }
        })
        timeline.dark = dark
        applyButtons(visible = true, animated = false)
        applyFrame()
    }

    // ------------------------------------------------------------------
    // 页面给的数据
    // ------------------------------------------------------------------

    fun update(style: TvNativeWallStyle, metrics: TvNativeExploreMetrics, heroTextStyle: TvNativeHeroTextStyle, buttonStyle: TvNativeHeroButtonStyle) {
        val changed = this.metrics != metrics
        this.style = style
        this.metrics = metrics
        heroText.style = heroTextStyle
        primaryButton.style = buttonStyle
        scheduleButton.style = buttonStyle
        list.update(style, metrics)
        if (changed) requestLayout()
        applyFrame()
    }

    fun setButtons(primaryText: String, primaryIcon: Bitmap?, scheduleText: String, scheduleIcon: Bitmap?) {
        primaryButton.bind(primaryText, primaryIcon)
        scheduleButton.bind(scheduleText, scheduleIcon)
    }

    fun setItems(items: List<TvNativeExploreItem>) {
        val structural = items.size != list.items.size || items.indices.any { items[it].key != list.items[it].key }
        list.submit(items)
        if (pendingScrollPx >= 0 && items.isNotEmpty()) {
            val px = pendingScrollPx
            pendingScrollPx = -1
            list.jumpTo(px)
        } else if (structural) {
            realignFocusedRow()
        }
        // 等的落点行到了
        post { resolvePending(scrollIfMissing = true) }
    }

    fun setCarousel(count: Int, selected: Int, color: Int) {
        dots.count = count
        dots.selectedIndex = selected
        dots.color = color
    }

    /** 两路 hero 内容 (轮播 / 聚焦卡). */
    fun setSources(carousel: TvNativeHeroSource, card: TvNativeHeroSource) {
        carouselSource = carousel
        cardSource = card
        applySources(reset = false)
    }

    /** 进页恢复: 列表停位、各行行首与上次停的那张、焦点簿记、hero 态 (直接到位, 不走过渡). */
    fun restore(
        scrollPx: Int,
        rowLeftIndex: Map<String, Int>,
        rowFocusedIndex: Map<String, Int>,
        focusedRowKey: String?,
        focusedCardIndex: Int,
        heroActive: Boolean,
    ) {
        list.rowLeftIndex.putAll(rowLeftIndex)
        this.rowFocusedIndex.putAll(rowFocusedIndex)
        this.focusedRowKey = focusedRowKey
        this.focusedCardIndex = focusedCardIndex
        // 返回本页 (页面重建, 如从播放器回来) 时焦点会回这一行上次停的那张 (见页面的进页恢复): 从排出来的第一帧起就按住它的聚焦态.
        // 等落点请求送到再按住就晚了 —— 那时它多半已经按未聚焦画过一帧, 焦点到了再放大一遍
        if (focusedRowKey != null) {
            list.heldFocus = focusedRowKey to rememberedIndex(focusedRowKey, list.rowLeftIndex[focusedRowKey] ?: 0)
        }
        if (heroActive) timeline.setActive(true, animated = false)
        // 列表停位等数据到了再定 (空列表定不了位)
        if (list.items.isNotEmpty()) list.jumpTo(scrollPx) else pendingScrollPx = scrollPx
        applyFrame()
    }

    val scrolledPx: Int get() = list.scrolledPx()
    val rowLeftIndex: Map<String, Int> get() = list.rowLeftIndex
    val rowFocusedIndexMap: Map<String, Int> get() = rowFocusedIndex
    val heroActive: Boolean get() = timeline.active

    // ------------------------------------------------------------------
    // 焦点命令 (页面发: 进页恢复 / 返回键分层 / 回到主界面)
    // ------------------------------------------------------------------

    /** 送焦到 hero 按钮. 还没布局 (刚建出来) 时记下, 布局完成再送. */
    fun focusHeroButton(button: Int): Boolean {
        cancelPending()
        if (!isLaidOut) {
            pendingHeroButton = button
            return false
        }
        pendingHeroButton = -1
        val target = if (button == 1) scheduleButton else primaryButton
        if (target.isFocused) {
            // 已经持着焦点: requestFocus 不会再回调, 当场按到位报上去 (页面据此清掉挂着的落点请求)
            onHeroButtonFocused(button)
            return true
        }
        return target.requestFocus()
    }


    /**
     * 送焦到 [rowKey] 行的第 [index] 张 (-1 = 这一行上次停的那张). [far] = 远跳 (返回键跳回组首行): 按 [TvScrollSpring.Far] 滚过去,
     * 途中不显示聚焦效果, 到位再把焦点给目标. 目标行还没排出来就记下, 排出来时再送.
     */
    fun focusCard(rowKey: String, index: Int, far: Boolean = false): Boolean {
        pendingHeroButton = -1
        // 新的落点请求: 远跳途中排队的确认作废
        farJumpConfirmQueued = false
        clickOnLandingRow = null
        val item = list.indexOfKey(rowKey)
        pendingRow = rowKey
        pendingIndex = index
        pendingColumn = -1
        pendingSmooth = false
        if (!far && !cardAreaHasFocus) holdLandingLook(rowKey, index)
        if (item < 0 || !isLaidOut) return false
        if (far) {
            farJumpRow = rowKey
            setFocusEffectSuppressed(true)
            parkFocus()
            val stop = if (timeline.active) metrics.heroScroll(list.items, item) else metrics.centeredScroll(list.items, item)
            list.scrollToStop(stop, TvScrollSpring.Far, animatedScroll)
            // 已经在停位上 / 流畅档一步到位: 不会再有滚动停下的回调, 当场落焦
            if (list.scrollState == RecyclerView.SCROLL_STATE_IDLE) finishFarJump()
            return true
        }
        return resolvePending(scrollIfMissing = true)
    }

    /**
     * 焦点从本页以外送进来 (返回本页 / 回到前台) 而目标卡还没排出来: 先按住它的聚焦态, 排出来的第一帧就是放大的, 焦点到位时画面不变
     * (见 [TvNativeExploreList.heldFocus]). 目标已在屏上就不按住, 照常走聚焦的放大动画. 任何一张卡 / hero 按钮拿到焦点就放开.
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

    private fun cancelPending() {
        pendingRow = null
        farJumpConfirmQueued = false
        clickOnLandingRow = null
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

    /** 远跳途中按了方向键: 当场落到目标 (这一下吞掉), 之后的按键从目标接着走 —— 出发那张多半已滚出屏被回收, 从它算不出下一步. */
    private fun landFarJumpNow() {
        val rowKey = farJumpRow ?: return
        farJumpRow = null
        farJumpConfirmQueued = false
        setFocusEffectSuppressed(false)
        val item = list.indexOfKey(rowKey)
        if (item >= 0) list.jumpTo(if (timeline.active) metrics.heroScroll(list.items, item) else metrics.centeredScroll(list.items, item))
        pendingRow = rowKey
        pendingSmooth = false
        // 列表按新位置排完才有那一行: 布局 / 挪动时 onListMoved 会再试, 这里补一次
        list.post { resolvePending(scrollIfMissing = false) }
    }

    /**
     * 把挂着的落点送出去; 送出去了返回 true. 目标行还没排出来时 [scrollIfMissing] = true 就滚到它的停位让它排出来 (列表每次挪动 /
     * 布局都会再来这里试一次, 那时不再发起滚动).
     */
    private fun resolvePending(scrollIfMissing: Boolean): Boolean {
        val rowKey = pendingRow ?: return false
        if (farJumpRow != null) return false
        val item = list.indexOfKey(rowKey)
        if (item < 0) return false
        val row = list.rowAt(item)
        if (row == null) {
            if (scrollIfMissing) {
                val stop = if (timeline.active) metrics.heroScroll(list.items, item) else metrics.centeredScroll(list.items, item)
                list.scrollToStop(stop, TvScrollSpring.Step, animatedScroll)
            }
            return false
        }
        val count = row.cards.itemCount
        if (count == 0) return false
        val left = list.rowLeftIndex[rowKey] ?: row.leftIndex()
        val target = when {
            pendingIndex >= 0 -> pendingIndex
            pendingColumn >= 0 -> left + pendingColumn
            else -> rememberedIndex(rowKey, left)
        }.coerceIn(0, count - 1)
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

            // 行已排好、那张在屏外 (返回键回行首时行首左边好几张、左右键连按超前): 平滑滚过去, 停下再送
            row.childCount > 0 -> {
                pendingSmooth = true
                row.scrollToCardSmooth(target) { if (pendingRow == rowKey) resolvePending(scrollIfMissing = false) }
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

    private fun finishFarJump() {
        val rowKey = farJumpRow ?: return
        farJumpRow = null
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

    /** 进 / 出 hero 态 (返回键在页面里, 调这里). */
    fun setHeroActive(active: Boolean) {
        if (timeline.active == active) return
        timeline.setActive(active, animated = transitions)
        listener?.onHeroActiveChanged(active)
        // 焦点没换, 只是进出 hero 态: 停位换成 hero 线 / 视口正中, 走 Far
        updateStop(heroToggled = true)
    }

    private fun onTimeline() {
        applyFrame()
        listener?.onToneChanged(timeline.tone, metrics.carouselBottomPx - carouselShift())
    }

    // ------------------------------------------------------------------
    // 按键
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
        if (event.repeatCount == 0) {
            // 用户按了新方向: 挂着的程序化落点 (含远跳) 作废, 从此刻的焦点接着走 (同 Compose 页面根的按键预览)
            cancelPending()
        }
        val focused = findFocus()
        // hero 按钮
        if (focused === primaryButton || focused === scheduleButton) {
            return when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (listener?.onSwitchCarousel(-1) == true) true else exitLeft()
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    listener?.onSwitchCarousel(1)
                    true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (focused === scheduleButton) primaryButton.requestFocus()
                    true
                }

                else -> {
                    if (focused === primaryButton) {
                        scheduleButton.requestFocus()
                    } else {
                        // 新番时间表按下: 进首行屏上第一列
                        firstRowKey()?.let { focusCardAtColumn(it, 0) }
                    }
                    true
                }
            }
        }
        val rowKey = focusedRowKey
        if (!cardAreaHasFocus || rowKey == null) return false
        if (vertical) {
            if (event.repeatCount > 0) {
                val now = SystemClock.uptimeMillis()
                if (now - lastVerticalRepeat < TV_NATIVE_VERTICAL_REPEAT_MILLIS) return true
                lastVerticalRepeat = now
            }
            navigateVertical(rowKey, if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            return true
        }
        if (event.repeatCount > 0) {
            // 同 TvNativeRowView 的横向连发限速 (按键在这里就吃掉了, 到不了行)
            val now = SystemClock.uptimeMillis()
            if (now - lastHorizontalRepeat < TV_NATIVE_HORIZONTAL_REPEAT_MILLIS) return true
            lastHorizontalRepeat = now
        }
        navigateHorizontal(rowKey, if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, firstPress = event.repeatCount == 0)
        return true
    }

    /**
     * 左右键: 同一行的上一张 / 下一张 (按需挪由横滑行在卡拿到焦点时自己滚). 不交给系统找焦点: 方向键没被原生树吃掉时由 Compose 用
     * FocusFinder 在整个宿主里按布局位置挑目标 —— 不计平移 (随列表上移出屏的 hero 按钮照样按原位参与), 行首停靠线左边露一截的那张
     * 常被 hero 按钮或别的行的卡比下去. 行首再按左出页面 (侧边栏, 只认新按下的那一下); 行尾按右吞掉.
     */
    private fun navigateHorizontal(rowKey: String, direction: Int, firstPress: Boolean) {
        // 连按时上一下还没落地 (目标卡还没排出来): 从它接着算
        val pending = pendingRow?.takeIf { pendingIndex >= 0 }
        val fromKey = pending ?: rowKey
        val from = if (pending != null) pendingIndex else focusedCardIndex
        val item = list.indexOfKey(fromKey)
        val row = (if (item >= 0) list.rowAt(item) else null) ?: return
        val target = from + direction
        if (target < 0) {
            if (firstPress && pending == null) exitLeft()
            return
        }
        if (target >= row.cards.itemCount) return
        pendingRow = fromKey
        pendingIndex = target
        pendingColumn = -1
        // 每一下都是新目标: 没排出来就重新平滑滚向它 (落点留着, 连按从它接着算); 送上了由卡的聚焦回调当场清掉落点
        pendingSmooth = false
        resolvePending(scrollIfMissing = false)
    }

    private fun exitLeft(): Boolean {
        listener?.onExitLeft()
        return true
    }

    private fun rowKeys(): List<String> = list.items.filterIsInstance<TvNativeExploreItem.Row>().map { it.key }

    private fun firstRowKey(): String? = list.items.firstOrNull { it is TvNativeExploreItem.Row }?.key

    /** 上下键: 落到上一行 / 下一行屏上同一列那张; 首行按上回「新番时间表」; 末行按下吞掉. 上一次的落点还在路上时从它接着算. */
    private fun navigateVertical(rowKey: String, direction: Int) {
        val keys = rowKeys()
        val fromKey = pendingRow ?: rowKey
        val row = keys.indexOf(fromKey)
        if (row < 0) return
        val column = if (pendingRow != null && pendingColumn >= 0) pendingColumn else focusedColumn
        if (direction < 0 && row == 0) {
            focusHeroButton(1)
            return
        }
        val target = row + direction
        if (target !in keys.indices) return
        focusCardAtColumn(keys[target], column)
    }

    private fun focusCardAtColumn(rowKey: String, column: Int) {
        pendingRow = rowKey
        pendingIndex = -1
        pendingColumn = column
        pendingSmooth = false
        resolvePending(scrollIfMissing = true)
    }

    /**
     * 卡片 / 按钮排出来时不往上报"有可聚焦的视图了": 窗口此刻没有焦点的话 (冷启动; 返回本页时页面刚重建, 旧页面的焦点节点已经没了),
     * 系统会把焦点直接塞给第一个报上来的视图 (ViewRootImpl.focusableViewAvailable), 抢在页面自己的落点 (hero 按钮 / 离开前那张卡) 之前 ——
     * 卡片获焦的回调把它当成停下的位置记下, 页面随后按它"恢复", 于是回到视口里第一张卡上. 本页的焦点一律由页面送 ([focusCard] /
     * [focusHeroButton] / 从页面外进来的 [onRequestFocusInDescendants]) 或按键移动, 用不着系统代劳.
     */
    override fun focusableViewAvailable(v: View?) = Unit

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        // 从页面外进来 (侧边栏按右 / 返回、全局兜底): 回上次那张卡, 否则回上次停的那颗 hero 按钮
        val rowKey = focusedRowKey
        if (rowKey != null) {
            focusCard(rowKey, -1)
            if (findFocus() != null) return true
        }
        return focusHeroButton(lastHeroButton)
    }

    // ------------------------------------------------------------------
    // 焦点回调
    // ------------------------------------------------------------------

    private inner class RowListener(private val rowKey: String) : TvNativeCardListener {
        override fun onFocused(index: Int) = onCardFocused(rowKey, index)

        override fun onClick(index: Int) {
            // 卡片墙上先切到 hero 态, hero 态里才进详情页 (这时有 hero 背景图, 走放大转场)
            if (!timeline.active) {
                setHeroActive(true)
            } else {
                // 进详情页: 焦点交出去之后这张卡仍画成聚焦态, 返回后焦点交还前也不缩 (见 holdLandingLook)
                list.heldFocus = rowKey to index
                listener?.onCardClick(rowKey, index)
            }
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            listener?.onCardLongPress(rowKey, index, anchor)
        }
    }

    private fun onCardFocused(rowKey: String, index: Int) {
        // 真焦点到了: 按住的聚焦态放开 (落在它自己身上画面不变, 落在别处它照失焦缩回)
        list.heldFocus = null
        val item = list.indexOfKey(rowKey)
        val row = if (item >= 0) list.rowAt(item) else null
        // 按需挪之后的行首: 走到行尾留白里那张才挪, 挪到它刚好完整露出
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
        setCardAreaFocus(true)
        listener?.onCardFocused(rowKey, index, focusedColumn)
        if (clickOnLandingRow != null) {
            // 远跳落地: 途中按的确认点在目标上 (走卡自己的点击, 同按一下确认; 放到这次焦点回调之后)
            val card = if (clickOnLandingRow == rowKey) row?.findViewHolderForAdapterPosition(index)?.itemView else null
            clickOnLandingRow = null
            card?.let { post { if (it.isFocused) it.performClick() } }
        }
        updateStop(heroToggled = false)
        applyFrame()
    }

    private fun onHeroButtonFocused(button: Int) {
        list.heldFocus = null
        lastHeroButton = button
        cancelPending()
        focusedRowKey = null
        setCardAreaFocus(false)
        listener?.onHeroButtonFocused(button)
        // 焦点回到轮播按钮: 回卡片墙, 列表按远跳 spring 滚回顶 (轮播随列表一起滑回原位)
        if (timeline.active) {
            timeline.setActive(false, animated = transitions)
            listener?.onHeroActiveChanged(false)
        }
        list.scrollToStop(0, TvScrollSpring.Far, animatedScroll)
        applyFrame()
    }

    private fun setCardAreaFocus(has: Boolean) {
        if (cardAreaHasFocus == has) return
        cardAreaHasFocus = has
        listener?.onCardAreaFocusChanged(has)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        // 远跳停放的焦点落地了 (目标卡 / hero 按钮拿到焦点)
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
        if (!tvNativeIsInside(newFocus, this)) setCardAreaFocus(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
    }

    /**
     * 行结构变了 (「继续观看」分页晚到插在头部、登录后推荐重组): 聚焦行跟着自己的新位置瞬时对回停位 —— RecyclerView 整体刷新时按下标
     * 保位置, 头部插了项聚焦行就被挤出停位. 数据到货不是用户动作, 不做动画.
     * 焦点不在卡片区 (在轮播按钮上, 列表停在顶上) 时不动.
     */
    private fun realignFocusedRow() {
        val rowKey = focusedRowKey ?: return
        if (!cardAreaHasFocus || farJumpRow != null) return
        val item = list.indexOfKey(rowKey)
        if (item < 0) return
        list.jumpTo(if (timeline.active) metrics.heroScroll(list.items, item) else metrics.centeredScroll(list.items, item))
    }

    /** 按聚焦行 (与是否 hero 态) 滚到停位: 进出 hero 态用 Far, 远跳那一行用 Far, 逐格用 Step. */
    private fun updateStop(heroToggled: Boolean) {
        val rowKey = focusedRowKey ?: return
        if (!cardAreaHasFocus && !heroToggled) return
        val item = list.indexOfKey(rowKey)
        if (item < 0) return
        val stop = if (timeline.active) metrics.heroScroll(list.items, item) else metrics.centeredScroll(list.items, item)
        val far = heroToggled || rowKey == farJumpRow
        list.scrollToStop(stop, if (far) TvScrollSpring.Far else TvScrollSpring.Step, animatedScroll)
    }

    // ------------------------------------------------------------------
    // 逐帧联动
    // ------------------------------------------------------------------

    private fun carouselShift(): Float {
        val m = metrics
        val items = list.items
        val first = items.indexOfFirst { it is TvNativeExploreItem.Row }
        val firstRowStop = if (first < 0) m.spacerPx else m.centeredScroll(items, first)
        return tvNativeCarouselShift(list.scrolledPx().toFloat(), firstRowStop, m.overhangPx)
    }

    private fun carouselAlpha(): Float = if (focusedRowKey != null) 1f - timeline.above else 1f

    private fun onListMoved() {
        // 等着的落点行排出来了就送 (不再发起滚动)
        if (pendingRow != null && farJumpRow == null) resolvePending(scrollIfMissing = false)
        applyFrame()
        listener?.onToneChanged(timeline.tone, metrics.carouselBottomPx - carouselShift())
    }

    /** 按此刻的滚动量、hero 态进度、尺寸插值, 摆背景图 / 文字 / 指示器, 算各项的淡化. 只动 RenderNode 属性. */
    private fun applyFrame() {
        val m = metrics
        val scrolled = list.scrolledPx().toFloat()
        val shift = carouselShift()
        // 换来源 (轮播 ↔ 聚焦卡) 的判据: 进时等轮播露着的那截随上面几行淡没, 出时等聚焦卡淡完
        val card = timeline.visible && focusedRowKey != null && if (timeline.active) timeline.above >= 1f else timeline.content > 0f
        if (card != showsCard || !modeSettled) onShowsCardChanged(card)
        val gone = shift >= m.carouselBottomPx
        if (gone != carouselGone) {
            carouselGone = gone
            // 轮播的图整个滚出屏就不挂 (挂着还会登记成放大转场的来源), 滚回来时直接出现, 不交叉淡入
            if (!showsCard) {
                backdrop.rebuild()
                applySources(reset = false)
            }
        }
        val md = mode
        val carouselA = carouselAlpha()
        val contentGate = if (dark) tvPosterWallToneGate(timeline.tone) else 1f
        backdrop.alpha = if (showsCard) timeline.content * contentGate else carouselA * splitGate
        val scale = 1f + (m.cardBackdropScale - 1f) * md
        backdrop.scaleX = scale
        backdrop.scaleY = scale
        backdrop.translationY = -shift * (1f - md)
        if (md != lastTreatmentMode) {
            lastTreatmentMode = md
            backdrop.treatment = treatmentFor?.invoke(md)
        }
        backdrop.publishZoom()
        heroBox.translationY = -scrolled * (1f - md)
        heroBox.alpha = if (showsCard) timeline.text else carouselA
        dots.alpha = dotsFade * carouselA
        dots.translationY = -shift * (1f - md)
        // 轮播标题随文字块移出屏幕上缘就停跑马灯 (看不见的跑马灯照样每帧重画)
        titleHeightPx = heroText.titleHeight.takeIf { it > 0 } ?: titleHeightPx
        val titleOnScreen = scrolled * (1f - md) < m.heroTopPx + titleHeightPx
        heroText.setTitleMarquee(showsCard || titleOnScreen)
        heroText.publishTitle()
        applyItemFades()
    }

    /**
     * hero 态里越过 hero 线的项在 [TvNativeExploreMetrics.fadeDistancePx] 内淡没, 卡片番名跟着 above 淡.
     * 卡片墙上不淡 (上面的行大半还在屏上). 进出途中 (hero 态还没停稳) 不按位置淡: 聚焦行连同它的组标题及以下不淡 —— 卡片墙上聚焦行停在正中,
     * 在 hero 线之上, 途中按位置淡就是整行先暗一下再亮回来; 上面的项整项跟着 above 淡 —— 退场时它们随列表往下挪、从 hero 线上方经过,
     * 按位置淡就在背景图还没淡完时先露出来.
     */
    private fun applyItemFades() {
        val m = metrics
        val p = timeline.above
        val keepFrom = keepFromIndex()
        val items = list.items
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos >= items.size) continue
            val a = if (p <= 0f) {
                1f
            } else if (timeline.content < 1f) {
                if (pos < keepFrom) 1f - p else 1f
            } else {
                val above = m.heroHeaderTopPx - (child.top - list.paddingTop)
                if (above <= 0) 1f else 1f - (above / m.fadeDistancePx).coerceIn(0f, 1f) * p
            }
            when (child) {
                is TvNativeRowView -> {
                    child.rowAlpha = a
                    child.cards.setTitleVisibility(child, 1f - p)
                }

                else -> child.alpha = a
            }
        }
    }

    /** 进出 hero 态途中不按位置淡的起点: 聚焦行的组标题 (紧挨在上面时), 否则聚焦行本身; 焦点在轮播上时 0. */
    private fun keepFromIndex(): Int {
        val rowKey = focusedRowKey ?: return 0
        val item = list.indexOfKey(rowKey)
        if (item < 0) return 0
        return if (item > 0 && list.items[item - 1] is TvNativeExploreItem.Header) item - 1 else item
    }

    private fun onShowsCardChanged(card: Boolean) {
        val target = if (card) 1f else 0f
        val visibleBefore = if (card) carouselAlpha() > 0f else timeline.content > 0f
        modeAnimator?.cancel()
        val settled = modeSettled
        showsCard = card
        if (settled && transitions && visibleBefore) {
            // 换之前那一路看得见 (hero 态里在首行按上回轮播): 尺寸缩放过去, 图交叉淡入, 文字按换条目的节奏换
            val from = mode
            modeAnimator = ValueAnimator.ofFloat(from, target).apply {
                duration = TV_NATIVE_WALL_BACKDROP_RESIZE_MILLIS
                interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
                addUpdateListener {
                    mode = it.animatedValue as Float
                    applyFrame()
                }
                start()
            }
            applySources(reset = false)
        } else {
            // 看不见的那一刻换: 直接到位, 图与文字整个重建 (不交叉淡入, 否则没褪完的旧图 / 旧字会跟着露出来)
            modeSettled = true
            mode = target
            if (settled) backdrop.rebuild()
            applySources(reset = settled)
        }
        applyButtons(visible = !card, animated = transitions && settled)
        applyDots(visible = !card)
        heroText.summaryWidthPx = if (card) metrics.cardSummaryWidthPx else metrics.carouselSummaryWidthPx
        heroBox.requestLayout()
    }

    /** 按当前来源换背景图与文字. [reset] = 换来源的重建 (文字当场换). */
    private fun applySources(reset: Boolean) {
        val src = (if (showsCard) cardSource else carouselSource) ?: return
        // 按下即压暗: 同一来源里换了条目 (轮播翻页 / hero 态里换卡) 就压暗, 换来源不压
        if (lastDimSource == showsCard && lastDimSubject != src.rawSubjectId) backdrop.triggerPressDim()
        lastDimSource = showsCard
        lastDimSubject = src.rawSubjectId
        backdrop.dimming = src.dimming
        val target = if (!showsCard && carouselGone) null else src.backdrop
        backdrop.show(target)
        val transition = when {
            reset -> TvNativeTextTransition.Reset
            !showsCard && src.autoAdvanced -> TvNativeTextTransition.Carousel
            else -> TvNativeTextTransition.Key
        }
        heroText.setText(src.text, transition)
    }

    private fun applyButtons(visible: Boolean, animated: Boolean) {
        buttonsAnimator?.cancel()
        buttons.visibility = if (visible) VISIBLE else GONE
        heroBox.buttonsPresent = visible
        if (!visible) return
        // 轮播按钮每次重新出现都从 0 淡入 (hero 态里回轮播时文字在交叉淡入, 按钮不该当帧冒出来)
        if (!animated) {
            buttons.alpha = 1f
            return
        }
        buttons.alpha = 0f
        buttonsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TV_HERO_BUTTON_FADE_IN_MILLIS.toLong()
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener { buttons.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun applyDots(visible: Boolean) {
        if (dotsShown == visible) return
        dotsShown = visible
        dotsAnimator?.cancel()
        if (!transitions) {
            dotsFade = if (visible) 1f else 0f
            applyFrame()
            return
        }
        val from = dotsFade
        dotsAnimator = ValueAnimator.ofFloat(from, if (visible) 1f else 0f).apply {
            duration = (if (visible) TV_HERO_BUTTON_FADE_IN_MILLIS else TV_HERO_BUTTON_FADE_OUT_MILLIS).toLong()
            interpolator = TV_NATIVE_FAST_OUT_SLOW_IN
            addUpdateListener {
                dotsFade = it.animatedValue as Float
                dots.alpha = dotsFade * carouselAlpha()
            }
            start()
        }
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = metrics
        val width = m.bleedLeftPx + m.pageWidthPx
        val height = m.pageHeightPx
        fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.EXACTLY)
        backdrop.measure(exactly(m.backdropWidthPx), exactly(m.backdropHeightPx))
        heroBox.measure(exactly(m.pageWidthPx - m.heroStartPx - m.heroEndPadPx), exactly(m.heroBlockExpandedPx))
        list.measure(exactly(width), exactly(height - m.listTopPx + m.listTopBleedPx))
        dots.measure(exactly(m.pageWidthPx), exactly(m.dotPx.toInt() * 3))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val m = metrics
        val bx = m.bleedLeftPx + m.pageWidthPx - backdrop.measuredWidth
        backdrop.layout(bx, 0, bx + backdrop.measuredWidth, backdrop.measuredHeight)
        // 以右上角为轴缩放 (图的比例几何不变)
        backdrop.pivotX = backdrop.measuredWidth.toFloat()
        backdrop.pivotY = 0f
        val hx = m.bleedLeftPx + m.heroStartPx
        heroBox.layout(hx, m.heroTopPx, hx + heroBox.measuredWidth, m.heroTopPx + heroBox.measuredHeight)
        val ly = m.listTopPx - m.listTopBleedPx
        list.layout(0, ly, list.measuredWidth, ly + list.measuredHeight)
        val dy = m.dotsCenterYPx - dots.measuredHeight / 2
        dots.layout(m.bleedLeftPx, dy, m.bleedLeftPx + dots.measuredWidth, dy + dots.measuredHeight)
        applyFrame()
        // 建出来之前就发过来的落点
        if (pendingHeroButton >= 0) {
            val button = pendingHeroButton
            post { if (pendingHeroButton == button) focusHeroButton(button) }
        } else if (pendingRow != null) {
            post { resolvePending(scrollIfMissing = true) }
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        super.onDetachedFromWindow()
        scrollTracker.stop()
    }

    /**
     * hero 文字块 + 轮播按钮块, 纵向排: 块高两档 (有按钮 [TvNativeExploreMetrics.heroBlockExpandedPx] / 没按钮
     * [TvNativeExploreMetrics.heroBlockPx], 1080p 电视 100% 缩放时 264 / 240dp), 文字吃掉按钮块之外的全部高度.
     */
    private inner class HeroBox(context: Context) : TvNativeBoundaryLayout(context) {
        var buttonsPresent = true
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
            val block = if (buttonsPresent) m.heroBlockExpandedPx else m.heroBlockPx
            var buttonsHeight = 0
            if (buttonsPresent) {
                buttons.setPadding(0, m.buttonsTopGapPx, 0, 0)
                (scheduleButton.layoutParams as? LinearLayout.LayoutParams)?.topMargin = m.buttonGapPx
                buttons.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                buttonsHeight = buttons.measuredHeight
            }
            heroText.titleWidthPx = m.titleWidthPx
            heroText.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((block - buttonsHeight).coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun layoutContent(width: Int, height: Int) {
            heroText.layout(0, 0, heroText.measuredWidth, heroText.measuredHeight)
            if (buttonsPresent) {
                val y = heroText.measuredHeight
                buttons.layout(0, y, buttons.measuredWidth, y + buttons.measuredHeight)
            }
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

/** 海报墙背景图在轮播与聚焦卡两套尺寸之间缩放的时长 (hero 态里在首行按上回到轮播), 同 hero 背景图淡入. */
private const val TV_NATIVE_WALL_BACKDROP_RESIZE_MILLIS = 400L
