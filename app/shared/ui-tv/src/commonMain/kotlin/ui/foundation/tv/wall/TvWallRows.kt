/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.foundation.focus.TvNoBringIntoViewSpec
import me.him188.ani.app.ui.foundation.focus.TvScrollAnimator
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.TvWallConfirm
import me.him188.ani.app.ui.foundation.tv.TvWallTouchTap
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreItem
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeRowWallListener
import me.him188.ani.app.ui.foundation.tv.nativeview.TvRowWallSurface
import me.him188.ani.app.ui.foundation.tv.tvWallConfirm
import me.him188.ani.app.ui.foundation.tv.tvWallTapConfirmsWhileFocusing
import me.him188.ani.app.ui.foundation.tv.tvWallTouchTap
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.math.abs

/*
 * 行式海报墙的 Compose 版 (安卓电视上是原生的 TvNativeRowWallView, 结构与行为见那边): 组标题 + 横滑行, 各项定高 ([TvNativeExploreMetrics]),
 * 停位直接按高度算. 卡片上按确定进 hero 态: 聚焦行的组标题落到 hero 线上, 上面的项越线淡出; hero 态里再按确定进详情页.
 * 探索页 (TvExplorationComposeWall: 顶上加热门轮播与两颗按钮, hero 的背景图与文字自己画) 与网格页里的「追更」(宾客, 见 TvWallRowsGuest:
 * hero 态、背景图与文字都是网格页的) 共用.
 *
 * 焦点规则: 上下键落到上一行 / 下一行屏上同一列那张 (行短了落到最后一张), 首行按上交给 [TvWallRowsSurface.onFirstRowUp];
 * 行首按左出页面 ([TvNativeRowWallListener.onExitLeft]), 行尾按右交给页面 ([TvNativeRowWallListener.onExitRight]), 末行按下吞掉.
 */

/** 第 [index] 项的高度 (px). */
internal fun TvNativeExploreMetrics.wallHeightOf(item: TvNativeExploreItem): Int = when (item) {
    is TvNativeExploreItem.Spacer -> spacerPx
    is TvNativeExploreItem.Header -> headerPx
    is TvNativeExploreItem.Row -> rowPx
}

/** 第 [index] 项的顶离列表内容顶多远 (px). */
internal fun TvNativeExploreMetrics.wallItemTop(items: List<TvNativeExploreItem>, index: Int): Int {
    var top = 0
    for (i in 0 until index.coerceAtMost(items.size)) top += wallHeightOf(items[i])
    return top
}

/** 聚焦第 [index] 行时列表该滚到哪: 行停在视口垂直正中, 两头放不到正中就贴边 (同原生的 centeredScroll). */
internal fun TvNativeExploreMetrics.wallCenteredScroll(items: List<TvNativeExploreItem>, index: Int): Int {
    val rowHeight = rowPx - rowGapPx
    val centered = wallItemTop(items, index) + rowHeight / 2 - viewportPx / 2
    return centered.coerceAtMost(wallEndScroll(items)).coerceAtLeast(0)
}

/** 卡片墙最远滚到哪: 末行贴着视口底边 (留 [TvNativeExploreMetrics.endMarginPx]). 列表末尾补的那一整屏只给 hero 态的停位用. */
internal fun TvNativeExploreMetrics.wallEndScroll(items: List<TvNativeExploreItem>): Int =
    (wallItemTop(items, items.size) - rowGapPx + endMarginPx - viewportPx).coerceAtLeast(0)

/** hero 态聚焦第 [index] 行时列表该滚到哪: 这一行的组标题位置落在 hero 线上 (同原生的 heroScroll); 前几行滚到顶也落不到时停在顶. */
internal fun TvNativeExploreMetrics.wallHeroScroll(items: List<TvNativeExploreItem>, index: Int): Int =
    wallHeroOffset(items, index).coerceAtLeast(0)

/** hero 态聚焦第 [index] 行时列表整片往下推多少 (px): 前几行滚到顶也落不到 hero 线上, 差的那一截 (同原生的 shiftToStop). */
internal fun TvNativeExploreMetrics.wallHeroShift(items: List<TvNativeExploreItem>, index: Int): Int =
    (-wallHeroOffset(items, index)).coerceAtLeast(0)

private fun TvNativeExploreMetrics.wallHeroOffset(items: List<TvNativeExploreItem>, index: Int): Int =
    wallItemTop(items, index) - headerPx - heroHeaderTopPx

/** [rowKey] 那一行所在组的标题项 (往上最近的一个标题; 一组切成几行时后面几行也算它的). */
private fun tvWallHeaderKeyOf(items: List<TvNativeExploreItem>, rowKey: String): String? {
    var i = items.indexOfFirst { it.key == rowKey }
    while (i > 0) {
        i--
        val item = items[i]
        if (item is TvNativeExploreItem.Header) return item.key
    }
    return null
}

/**
 * 行式海报墙的卡片列表: 顶上出血一截 (离场行越过顶线继续上移), 末尾补一整屏 (任何一行的停位都滚得到). 摆在 [modifier] 所在的框里:
 * [pageTopPx] = 页面顶在这个框里的纵坐标 (几何都从页面顶算; 框就是整页时为 0). [extraFade] = 卡片与组标题另乘的淡化 (点开时跟着淡没).
 * 列表与各行的滚动、送焦、进出 hero 态的停位在这里接; 方向键与输入方式由调用方在外层接 (见 [TvWallRowsSurface.handleKey]).
 */
@Composable
internal fun TvWallRowsList(
    surface: TvWallRowsSurface,
    items: List<TvNativeExploreItem>,
    style: TvWallCardStyle,
    headerStyle: TextStyle,
    extraFade: () -> Float,
    pageTopPx: Int = 0,
) {
    val density = LocalDensity.current
    val metrics = surface.metrics
    val transitions = LocalThemeSettings.current.visualEffects.transitions
    val timeline = surface.timeline

    // 进页恢复 / 页面给的恢复: 列表回到存着的停位 (数据到了才排得出来)
    LaunchedEffect(surface, items.isNotEmpty(), surface.restoreRequest) {
        if (items.isNotEmpty()) surface.applyRestore()
    }
    LaunchedEffect(surface) {
        snapshotFlow { surface.listState.isScrollInProgress }.collect { surface.rowListener?.onScrollingChanged(it) }
    }
    // 触屏: hero 态里手指一拖列表就退回卡片墙, 卡片跟着手指走 (见 exitHeroInPlace)
    LaunchedEffect(surface) {
        surface.listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && surface.touchMode) surface.exitHeroInPlace(byDrag = true)
        }
    }
    // 进出 hero 态: 聚焦行滚到 hero 线 / 回到正中 (远跳那组 spring)
    LaunchedEffect(surface) {
        snapshotFlow { timeline.active }.drop(1).collect { surface.onHeroToggled(it) }
    }
    // 等着的落点那一行到了就送
    LaunchedEffect(surface, items) { surface.retryDeferred() }
    // 挂着的落点那张排出来了就送焦
    LaunchedEffect(surface) {
        snapshotFlow { surface.pending?.let { surface.requesters[it] } }.collect { requester ->
            if (requester != null) runCatching { requester.requestFocus() }
        }
    }

    val emphasizedHeader by remember(surface) {
        derivedStateOf {
            surface.focusedRowKey?.takeIf { surface.cardAreaHasFocus }?.let { tvWallHeaderKeyOf(surface.items, it) }
        }
    }
    val listTop = pageTopPx + metrics.listTopPx - metrics.listTopBleedPx
    val listHeight = metrics.pageHeightPx - metrics.listTopPx + metrics.listTopBleedPx
    val touchScrollLimit = remember(surface) { TvWallTouchScrollLimit(scrolled = { surface.scrolledPx }, end = { surface.endScrollPx() }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvNoBringIntoViewSpec) {
        LazyColumn(
            Modifier
                .tvWallPlace(x = 0, y = listTop, width = -1, height = listHeight)
                .onGloballyPositioned { surface.listLineInWindow = it.positionInWindow().y + metrics.listTopBleedPx }
                // hero 态里前几行整片往下推 (见 wallHeroShift)
                .graphicsLayer { translationY = surface.shift.value }
                // 触屏: 手指甩不进末尾补的那一整屏
                .then(if (surface.touchInput) Modifier.nestedScroll(touchScrollLimit) else Modifier),
            state = surface.listState,
            // 自己滚停位的那一下不接手指 (按下会被当成「按住停下滚动」吃掉, 卡片点不到)
            userScrollEnabled = !surface.autoScrolling,
            contentPadding = with(density) {
                PaddingValues(top = metrics.listTopBleedPx.toDp(), bottom = (metrics.viewportPx + metrics.listBottomBleedPx).toDp())
            },
        ) {
            items(items.size, key = { items[it].key }) { index ->
                val item = items[index]
                val fade = { surface.itemFade(index) * extraFade() }
                when (item) {
                    is TvNativeExploreItem.Spacer -> Spacer(Modifier.fillMaxWidth().height(with(density) { metrics.spacerPx.toDp() }))
                    is TvNativeExploreItem.Header -> {
                        val emphasis by animateFloatAsState(
                            if (emphasizedHeader == item.key) 1f else 0f,
                            if (transitions) tween(150) else snap(),
                        )
                        Box(
                            Modifier.fillMaxWidth()
                                .height(with(density) { metrics.headerPx.toDp() })
                                .padding(start = with(density) { metrics.rowStartPx.toDp() }, end = with(density) { metrics.endPadPx.toDp() })
                                .graphicsLayer {
                                    val idle = surface.headerIdleAlpha
                                    // hero 态里组标题也淡没 (只剩一排卡, 同网格页)
                                    val shown = if (surface.hideHeadersInHero) 1f - timeline.above else 1f
                                    alpha = fade() * (idle + (1f - idle) * emphasis) * shown
                                },
                            // 字贴着这一格的顶 (格高 = 字那一行 + 到海报的间距), 同原生的组标题
                            contentAlignment = Alignment.TopStart,
                        ) {
                            Text(item.title, style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    is TvNativeExploreItem.Row -> TvWallRowsRow(surface, item, style, fade)
                }
            }
        }
    }
}

/** 一条横滑行: 按需挪 (焦点走到行尾露一截的那张才整行挪), 越过行首停靠线的卡压暗着出屏. */
@Composable
private fun TvWallRowsRow(
    surface: TvWallRowsSurface,
    item: TvNativeExploreItem.Row,
    style: TvWallCardStyle,
    fade: () -> Float,
) {
    val density = LocalDensity.current
    val metrics = surface.metrics
    val timeline = surface.timeline
    val rowKey = item.key
    val rowState = surface.rowStateFor(rowKey)
    val animator = remember(surface.animatedScroll) { TvScrollAnimator(animated = surface.animatedScroll) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(rowState, animator) {
        snapshotFlow { surface.rowLeft[rowKey] ?: 0 }.drop(1).collectLatest { left ->
            // 触屏上照行此刻的位置记下的行首: 行不动 (见 setRowLeftQuietly)
            if (surface.consumeQuietRowLeft(rowKey, left)) return@collectLatest
            autoScrolling = true
            try {
                animator.animateScrollToItem(rowState, left)
            } finally {
                autoScrolling = false
            }
        }
    }
    // 触屏: 手指把这一行拖停了, 行首照它停下的位置记下 (行不动), 之后按焦点挪行从这里算
    LaunchedEffect(rowState) {
        snapshotFlow { rowState.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling && surface.touchMode) surface.setRowLeftQuietly(rowKey, tvRowFirstFullIndex(rowState))
        }
    }
    val fadeDistancePx = metrics.fadeDistancePx
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvNoBringIntoViewSpec) {
        LazyRow(
            Modifier.fillMaxWidth().height(with(density) { metrics.rowPx.toDp() }),
            state = rowState,
            // 自己挪行首的那一下不接手指 (同列表)
            userScrollEnabled = !autoScrolling,
            contentPadding = with(density) { PaddingValues(start = metrics.rowStartPx.toDp(), end = metrics.endPadPx.toDp()) },
            // 列距跟着海报墙大小缩放 (同原生的行与网格, 见 LocalTvPosterWallScale)
            horizontalArrangement = Arrangement.spacedBy(TV_POSTER_WALL_COLUMN_SPACING * LocalTvPosterWallScale.current),
            verticalAlignment = Alignment.Top,
        ) {
            items(item.cards.size) { index ->
                val card = item.cards[index]
                val requester = remember { FocusRequester() }
                val key = rowKey to index
                DisposableEffect(key) {
                    surface.requesters[key] = requester
                    onDispose { if (surface.requesters[key] === requester) surface.requesters.remove(key) }
                }
                LaunchedEffect(key) { surface.rowListener?.onBindCard(rowKey, index) }
                val more = card?.more != null
                TvWallCard(
                    card,
                    style,
                    onClick = { surface.onCardClick(rowKey, index, more) },
                    modifier = Modifier.focusRequester(requester),
                    onFocusChanged = { focused -> if (focused) surface.onCardFocused(rowKey, index) },
                    onLongPress = if (more) null else ({ rect -> surface.rowListener?.onCardLongPress(rowKey, index, rect) }),
                    dimAlpha = { fade() * tvPastAnchorDim(rowState, index, fadeDistancePx) },
                    labelAlpha = { 1f - timeline.above },
                    holdFocusLook = surface.heldFocus == key,
                    // 卡片墙上点一下 = 聚焦并进 hero 态; hero 态里点另一张只换焦点, 「更多」卡也只聚焦
                    touchTwoStep = !tvWallTapConfirmsWhileFocusing(more, surface.heroEnabled, timeline.active),
                    // 按触屏的规矩走时卡片墙不画聚焦放大 (hero 态里聚焦那张照画), 按下也不动焦点 (点一下怎么走见 onCardClick)
                    showFocusLook = !surface.touchMode || timeline.active,
                    touchFocusOnPress = !surface.touchMode,
                )
            }
        }
    }
}

/**
 * 行式海报墙的画面 (Compose 版): 焦点簿记、停位、hero 态; 给页面的操作见 [TvRowWallSurface]. 探索页 (加了轮播与两颗按钮) 与「追更」
 * (网格页的宾客, 见 TvWallRowsGuest) 各自继承, 覆盖首行按上、焦点停放、点开、hero 态开关这几处.
 *
 * [parkRequester] = 焦点停放处 (见 [parkFocus]); 其余参数是返回本页时恢复的停位与焦点簿记.
 */
@Stable
internal open class TvWallRowsSurface(
    val timeline: TvWallHeroTimeline,
    protected val scope: CoroutineScope,
    protected val parkRequester: FocusRequester,
    restoredScrollPx: Int,
    restoredRowLeft: Map<String, Int>,
    restoredRowFocused: Map<String, Int>,
    restoredRowKey: String?,
    restoredIndex: Int,
) : TvRowWallSurface {
    override var rowListener: TvNativeRowWallListener? = null

    /** 设备有触摸屏 (LocalTvTouchInputEnabled). */
    override var touchInput = false

    /** 最近一次输入是手指 (见 tvWallInputMode). */
    var lastInputTouch: Boolean by mutableStateOf(true)

    /**
     * 此刻按触屏的规矩走 (同上游的平板界面): 列表与各行只由手指滚, 焦点变化不滚动、卡片墙上不画聚焦效果; 程序送来的焦点 (进页落点、恢复落点、
     * 焦点兜底) 停在停放处 ([parkFocus]). 只有点卡片进 hero 态、hero 态里换卡才按焦点挪. 平板接着键盘 / 遥控器按方向键时回到电视的规矩.
     */
    open val touchMode: Boolean get() = touchInput && lastInputTouch

    override var heroEnabled: Boolean = true
    override var hideHeadersInHero: Boolean by mutableStateOf(false)
    override var headerIdleAlpha: Float by mutableFloatStateOf(1f)
    private var shownItems: List<TvNativeExploreItem> by mutableStateOf(emptyList())

    /** 列表项 (页面经 [setItems] 给). */
    val items: List<TvNativeExploreItem> get() = shownItems
    lateinit var metrics: TvNativeExploreMetrics
    var animatedScroll: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            listAnimator = TvScrollAnimator(animated = value)
        }

    /** hero 态里前几行放不到 hero 线时整片往下推 (见 wallHeroShift); 列表顶上有占位 (探索页的轮播区) 时用不着. */
    protected open val heroPushDown: Boolean get() = false

    /** 日志行首的标签 (焦点、停位、hero 态的变化); null = 不记. */
    protected open val logTag: String? get() = null

    protected fun log(message: () -> String) {
        val tag = logTag ?: return
        logger.info { "[$tag] ${message()}" }
    }

    val listState = LazyListState()

    /** 整片往下推的量 (px, 见 [heroPushDown]). */
    val shift = Animatable(0f)

    /** 列表顶线在窗口里的纵坐标 (TvWallRowsList 排好时记下). */
    internal var listLineInWindow = 0f

    /** 列表正在自己滚停位 (这期间不接手指). */
    var autoScrolling: Boolean by mutableStateOf(false)
        private set

    /** 列表的停位动画 (连按时继承速度, 见 TvScrollAnimator). */
    private var listAnimator = TvScrollAnimator(animated = true)
    private var stopJob: Job? = null
    private var stopTarget = -1
    private var shiftJob: Job? = null
    private val rowStates = HashMap<String, LazyListState>()

    /** 要恢复到的列表停位 (px); [restoreRequest] 变了 (页面给的恢复) 就再恢复一次, 见 [applyRestore]. */
    private var restoreScrollPx = restoredScrollPx
    var restoreRequest: Int by mutableIntStateOf(0)
        private set
    private var restoredRequest = -1

    /** 各行的行首 (第几张贴在起始停靠线上). */
    val rowLeft = mutableStateMapOf<String, Int>().apply { putAll(restoredRowLeft) }
    protected val rowFocused = HashMap<String, Int>(restoredRowFocused)

    /** 上次聚焦的行 (null = 从未进过卡片区; 探索页焦点在 hero 按钮上时也是 null). */
    var focusedRowKey: String? by mutableStateOf(restoredRowKey)
    protected var focusedCardIndex = restoredIndex
    private var focusedColumn = 0
    var cardAreaHasFocus: Boolean by mutableStateOf(false)
        private set
    var hasAnyFocus = false

    /** 还没送出去的落点 (那张卡还没排出来): 行键, 下标. */
    var pending: Pair<String, Int>? by mutableStateOf(null)

    /** 等那一行到了再送的落点 (行键, 下标, 远跳), 见 [focusCard]. */
    protected var deferred: Triple<String, Int, Boolean>? = null

    /** 列表项换了: 等着的落点那一行到了就送. */
    fun retryDeferred() {
        val (rowKey, index, far) = deferred ?: return
        focusCard(rowKey, index, far)
    }

    /** 远跳 (返回键回组首行 / 回顶) 途中: 列表在按远跳 spring 滚. */
    protected var farJumping = false

    /** 没有焦点也画成聚焦态的那张 (进详情页时交出焦点的那张; 返回时焦点交还前也不缩). */
    var heldFocus: Pair<String, Int>? by mutableStateOf(null)

    val requesters = mutableStateMapOf<Pair<String, Int>, FocusRequester>()

    fun rowStateFor(rowKey: String): LazyListState =
        rowStates.getOrPut(rowKey) { LazyListState(firstVisibleItemIndex = rowLeft[rowKey] ?: 0) }

    /** 只记不挪的行首 (行键 → 行首), 见 [setRowLeftQuietly]. */
    private val quietRowLeft = HashMap<String, Int>()

    /** 把 [rowKey] 行的行首记成 [left], 行不动 (触屏上行停在手指放下的位置). */
    fun setRowLeftQuietly(rowKey: String, left: Int) {
        if (rowLeft[rowKey] == left) return
        quietRowLeft[rowKey] = left
        rowLeft[rowKey] = left
    }

    /** 行首换成 [left] 是 [setRowLeftQuietly] 记的 (行不挪). */
    fun consumeQuietRowLeft(rowKey: String, left: Int): Boolean = quietRowLeft.remove(rowKey) == left

    /**
     * 让 [rowKey] 行第 [target] 张 (整行 [count] 张) 完整露出: 算出行首记进 [rowLeft] (行跟着挪), 返回行首. 按触屏的规矩走时从行此刻的位置算,
     * 那张已经完整露出就只记不挪.
     */
    private fun revealInRow(rowKey: String, target: Int, count: Int): Int {
        val columns = metrics.columns.coerceAtLeast(1)
        if (!touchMode) {
            val left = tvStripLeftIndex(rowLeft[rowKey] ?: 0, target, count, columns)
            rowLeft[rowKey] = left
            return left
        }
        val current = tvRowFirstFullIndex(rowStateFor(rowKey))
        val left = tvStripLeftIndex(current, target, count, columns)
        if (left == current) setRowLeftQuietly(rowKey, left) else rowLeft[rowKey] = left
        return left
    }

    // ---- 位置 ----

    override val scrolledPx: Int
        get() = metrics.wallItemTop(items, listState.firstVisibleItemIndex) + listState.firstVisibleItemScrollOffset

    /** 卡片墙最远滚到哪 (末行贴着视口底边, 见 wallEndScroll). */
    fun endScrollPx(): Int = metrics.wallEndScroll(items)

    /**
     * 第 [index] 项的淡化 (1 = 不淡): hero 态里越过 hero 线的项在 fadeDistancePx 内淡没; 卡片墙上不淡. 进出途中不按位置淡: 聚焦行连同它的组标题
     * 及以下不淡, 上面的项整项跟着进度淡. 绘制阶段调用.
     */
    fun itemFade(index: Int): Float {
        val p = timeline.above
        if (p <= 0f) return 1f
        if (timeline.content < 1f) return if (index < keepFromIndex()) 1f - p else 1f
        val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: return 1f
        val above = metrics.heroHeaderTopPx - (offset + shift.value)
        if (above <= 0f) return 1f
        return 1f - (above / metrics.fadeDistancePx).coerceIn(0f, 1f) * p
    }

    private fun keepFromIndex(): Int {
        val rowKey = focusedRowKey ?: return 0
        val item = items.indexOfFirst { it.key == rowKey }
        if (item < 0) return 0
        return if (item > 0 && items[item - 1] is TvNativeExploreItem.Header) item - 1 else item
    }

    protected fun stopFor(item: Int): Int =
        if (timeline.active) metrics.wallHeroScroll(items, item) else metrics.wallCenteredScroll(items, item)

    private fun shiftFor(item: Int): Int =
        if (heroPushDown && timeline.active) metrics.wallHeroShift(items, item) else 0

    /** 滚到第 [item] 项的停位 (连同整片下推, 见 [heroPushDown]). */
    protected fun scrollToStop(item: Int, pace: TvScrollSpring) {
        animateShift(shiftFor(item), pace)
        scrollTo(stopFor(item), pace)
    }

    protected fun scrollTo(target: Int, pace: TvScrollSpring) {
        // 同一个停位正在滚: 不重起 (落焦后的再对一次停位)
        if (target == stopTarget && stopJob?.isActive == true) return
        stopJob?.cancel()
        stopTarget = target
        stopJob = scope.launch {
            autoScrolling = true
            try {
                runCatching { listAnimator.animateScrollBy(listState, (target - scrolledPx).toFloat(), pace) }
            } finally {
                autoScrolling = false
            }
            if (pace == TvScrollSpring.Far) farJumping = false
        }
    }

    /** 整片下推挪到 [target] (px); [pace] 为 null 或关了动画滚动时当场到位. */
    private fun animateShift(target: Int, pace: TvScrollSpring?) {
        val t = target.toFloat()
        if (shift.targetValue == t) return
        shiftJob?.cancel()
        shiftJob = scope.launch {
            if (pace != null && animatedScroll) {
                shift.animateTo(t, spring(dampingRatio = pace.dampingRatio, stiffness = pace.stiffness))
            } else {
                shift.snapTo(t)
            }
        }
    }

    /** 当场跳到 [targetPx] (进页恢复 / 远跳当场落地). */
    fun jumpTo(targetPx: Int) {
        stopJob?.cancel()
        var remaining = targetPx.coerceAtLeast(0)
        var index = 0
        while (index < items.size) {
            val h = metrics.wallHeightOf(items[index])
            if (remaining < h) break
            remaining -= h
            index++
        }
        scope.launch { listState.scrollToItem(index.coerceAtMost((items.size - 1).coerceAtLeast(0)), remaining) }
    }

    /** 当场跳到第 [item] 项的停位 (连同整片下推). */
    protected fun jumpToStop(item: Int) {
        animateShift(shiftFor(item), pace = null)
        jumpTo(stopFor(item))
    }

    /** 按聚焦行 (与是否 hero 态) 滚到停位: 进出 hero 态与远跳用 Far, 逐格用 Step. */
    fun updateStop(heroToggled: Boolean) {
        val rowKey = focusedRowKey ?: return
        if (!cardAreaHasFocus && !heroToggled) return
        val item = items.indexOfFirst { it.key == rowKey }
        if (item < 0) return
        scrollToStop(item, if (heroToggled || farJumping) TvScrollSpring.Far else TvScrollSpring.Step)
    }

    /** 列表项到了 (或页面给了恢复): 回到存着的停位, 每次恢复只做一次. */
    internal fun applyRestore() {
        if (restoredRequest == restoreRequest) return
        restoredRequest = restoreRequest
        log { "恢复停位 ${restoreScrollPx}px (hero=${timeline.active}, 聚焦 $focusedRowKey/$focusedCardIndex)" }
        restoreStop(restoreScrollPx)
    }

    /** 恢复时回到哪: 默认就是存着的停位 [scrollPx]. */
    protected open fun restoreStop(scrollPx: Int) = jumpTo(scrollPx)

    /**
     * 对上此刻的 hero 态 (宾客: 宿主在这份内容不在屏上时进出过 hero 态): hero 态里聚焦行当场落到 hero 停位 (返回 true), 不在 hero 态时撤掉整片下推.
     */
    internal fun syncHeroStop(): Boolean {
        if (!timeline.active) {
            animateShift(0, pace = null)
            return false
        }
        // 没聚焦过 (在标签行上换过来的): 按第一行停, 同网格 (不然顶上几行全在 hero 线以上被淡没)
        val rowKey = focusedRowKey ?: firstRowKey() ?: return false
        val item = items.indexOfFirst { it.key == rowKey }
        if (item < 0) return false
        jumpToStop(item)
        return true
    }

    /** 进出了 hero 态: 聚焦行滚到 hero 线 / 回到正中; 原地退出 (触屏) 时列表不动, 只撤掉整片下推 (卡片靠上去不留空白). */
    internal fun onHeroToggled(active: Boolean) {
        if (consumeStayInPlace() || touchMode && !active) {
            log { "hero=$active, 原地 (触屏=$touchMode)" }
            if (!active) animateShift(0, TvScrollSpring.Far)
            return
        }
        log { "hero=$active, 聚焦行 $focusedRowKey 滚到${if (active) " hero 线" else "正中"}" }
        updateStop(heroToggled = true)
    }

    // ---- 焦点 ----

    /**
     * 按触屏的规矩走时程序送来的焦点停在 [parkRequester] (看不见的节点): 画面不滚、不画聚焦. 还在 hero 态就退回卡片墙: hero 背景与文字照常淡出,
     * 列表停在原地; 淡完才报停放 ([reportParked]). [request] = 把焦点送过去 (停放处自己拿到焦点时不用再送).
     */
    protected fun parkFocus(request: Boolean = true) {
        log { "焦点停放 (hero=${timeline.active})" }
        pending = null
        deferred = null
        heldFocus = null
        if (request) scope.launch { runCatching { parkRequester.requestFocus() } }
        if (parkJob?.isActive == true) return
        if (!timeline.active) {
            reportParked()
            return
        }
        stayInPlace = true
        setHeroActive(false, animated = true)
        parkJob = scope.launch {
            snapshotFlow { timeline.active || timeline.visible || timeline.above > 0f }.first { !it }
            reportParked()
        }
    }

    /** 等 hero 态淡完再报停放 (见 [parkFocus]); 这期间卡片拿到了焦点就作废. */
    protected var parkJob: Job? = null

    /** 焦点停放好了: 卡片区没有焦点了 (探索页另回到轮播态). */
    protected open fun reportParked() {
        setCardAreaFocus(false)
    }

    /**
     * 送焦到 [rowKey] 行的第 [index] 张 (-1 = 这一行上次停的那张). 那张没排出来就先把列表与那一行滚过去, 排出来再送 ([pending]).
     * [far] = 远跳: 列表按远跳 spring 滚过去. 按触屏的规矩走时卡片墙上不送 (焦点停放, 见 [parkFocus]), hero 态里照送.
     * 返回 false = 这一行还没到 (记下了, 到了再送).
     */
    override fun focusCard(rowKey: String, index: Int, far: Boolean): Boolean {
        if (touchMode && !timeline.active) {
            parkFocus()
            return true
        }
        val item = items.indexOfFirst { it.key == rowKey }
        val row = items.getOrNull(item) as? TvNativeExploreItem.Row
        if (row == null || row.cards.isEmpty()) {
            // 这一行还没到 (进页时数据在路上): 记下, 到了再送
            log { "送焦 $rowKey/$index: 这一行还没到, 先记着" }
            deferred = Triple(rowKey, index, far)
            return false
        }
        deferred = null
        val target = (if (index >= 0) index else rowFocused[rowKey] ?: rowLeft[rowKey] ?: 0).coerceIn(0, row.cards.size - 1)
        revealInRow(rowKey, target, row.cards.size)
        if (!cardAreaHasFocus) heldFocus = rowKey to target
        pending = rowKey to target
        log { "送焦 $rowKey/$target${if (far) " (远跳)" else ""}" }
        if (far) farJumping = true
        scrollToStop(item, if (far) TvScrollSpring.Far else TvScrollSpring.Step)
        return true
    }

    /** 远跳途中当场落地 (返回键分层): 没在远跳返回 false. */
    open fun settleFarJump(): Boolean {
        if (!farJumping) return false
        farJumping = false
        val rowKey = pending?.first ?: return false
        val item = items.indexOfFirst { it.key == rowKey }
        if (item >= 0) jumpToStop(item)
        return true
    }

    override fun landFarJump(): Boolean = settleFarJump()

    fun onCardFocused(rowKey: String, index: Int) {
        heldFocus = null
        // 按触屏的规矩走时卡片墙上的焦点 (程序以外的来路, 如焦点兜底) 不算数: 不滚不报; 点卡片进 hero 态时才按聚焦走 (见 onCardClick)
        if (touchMode && !timeline.active) return
        takeCardFocus(rowKey, index)
        updateStop(heroToggled = false)
    }

    /** 焦点到了 [rowKey] 行第 [index] 张: 记下、行按需挪 (走到行尾留白里那张才挪, 挪到它刚好完整露出)、报给页面. */
    private fun takeCardFocus(rowKey: String, index: Int) {
        parkJob?.cancel()
        val row = items.firstOrNull { it.key == rowKey } as? TvNativeExploreItem.Row
        val count = row?.cards?.size ?: (index + 1)
        val left = revealInRow(rowKey, index, count)
        rowFocused[rowKey] = index
        focusedRowKey = rowKey
        focusedCardIndex = index
        focusedColumn = index - left
        if (pending == rowKey to index) pending = null
        log { "焦点到 $rowKey/$index (屏上第 $focusedColumn 列)" }
        setCardAreaFocus(true)
        rowListener?.onCardFocused(rowKey, index, focusedColumn)
    }

    protected fun setCardAreaFocus(has: Boolean) {
        if (cardAreaHasFocus == has) return
        cardAreaHasFocus = has
        rowListener?.onCardAreaFocusChanged(has)
    }

    /**
     * 点 (确认) [rowKey] 行第 [index] 张. 按触屏的规矩走时 (按下不动焦点, 见 TvWallCard 的 touchFocusOnPress): 卡片墙上点一下就聚焦它并进 hero 态
     * (没有 hero 态就直接进), hero 态里点没聚焦的那张只换焦点.
     */
    fun onCardClick(rowKey: String, index: Int, more: Boolean) {
        val key = rowKey to index
        if (touchMode) {
            val focused = focusedRowKey to focusedCardIndex == key
            val tap = tvWallTouchTap(more, heroEnabled, timeline.active, focused, isAboveFocusedRow(rowKey))
            log { "点了 $rowKey/$index: $tap (hero=${timeline.active})" }
            when (tap) {
                TvWallTouchTap.OpenFocused -> {
                    openFocusedCard()
                    return
                }

                TvWallTouchTap.FocusOnly -> {
                    requesters[key]?.let { runCatching { it.requestFocus() } }
                    return
                }

                TvWallTouchTap.FocusAndConfirm -> {
                    requesters[key]?.let { runCatching { it.requestFocus() } }
                    takeCardFocus(rowKey, index)
                }

                TvWallTouchTap.Confirm -> {}
            }
        }
        // 卡片墙上先切到 hero 态, hero 态里才进详情页 (这时有 hero 背景图, 走放大转场); 没有 hero 态时直接交给页面
        val confirm = tvWallConfirm(more, heroEnabled, timeline.active)
        log { "确定 $rowKey/$index: $confirm" }
        when (confirm) {
            TvWallConfirm.More -> rowListener?.onMoreClick(rowKey)
            TvWallConfirm.EnterHero -> setHeroActive(true, animated = true)
            TvWallConfirm.Open -> {
                // 进详情页: 焦点交出去之后这张卡仍画成聚焦态, 返回后焦点交还前也不缩
                heldFocus = rowKey to index
                openCard(rowKey, index)
            }
        }
    }

    /** 打开 [rowKey] 行第 [index] 张 (交给页面进详情页 / 播放); 铺着模糊背景时由子类先对焦再进. */
    protected open fun openCard(rowKey: String, index: Int) {
        rowListener?.onCardClick(rowKey, index)
    }

    /** 触屏上 hero 态里点 hero 区 (背景图、文字, 及淡没在那里的上面几行): 同点聚焦的那张 (进详情页). */
    fun openFocusedCard() {
        val rowKey = focusedRowKey ?: return
        val row = items.firstOrNull { it.key == rowKey } as? TvNativeExploreItem.Row ?: return
        val index = focusedCardIndex
        onCardClick(rowKey, index, row.cards.getOrNull(index)?.more != null)
    }

    private fun isAboveFocusedRow(rowKey: String): Boolean {
        val focused = items.indexOfFirst { it.key == focusedRowKey }
        val item = items.indexOfFirst { it.key == rowKey }
        return focused >= 0 && item in 0 until focused
    }

    protected fun rowKeys(): List<String> = items.filterIsInstance<TvNativeExploreItem.Row>().map { it.key }

    /** 方向键 (按键预览): 返回 true = 吃掉. */
    fun handleKey(key: Key): Boolean {
        val vertical = key == Key.DirectionUp || key == Key.DirectionDown
        val horizontal = key == Key.DirectionLeft || key == Key.DirectionRight
        if (!vertical && !horizontal) return false
        if (farJumping) {
            settleFarJump()
            return true
        }
        val rowKey = focusedRowKey
        if (!cardAreaHasFocus || rowKey == null) return handleKeyOffCards(key)
        val from = pending ?: (rowKey to focusedCardIndex)
        if (vertical) {
            val keys = rowKeys()
            val row = keys.indexOf(from.first)
            if (row < 0) return true
            if (key == Key.DirectionUp && row == 0) {
                log { "首行按上" }
                onFirstRowUp()
                return true
            }
            val target = row + if (key == Key.DirectionUp) -1 else 1
            if (target in keys.indices) focusAtColumn(keys[target], focusedColumn)
            return true
        }
        val target = from.second + if (key == Key.DirectionLeft) -1 else 1
        // 上一下还没落地 (焦点还在往行缘赶): 这一下不出页面
        if (target < 0) {
            if (pending == null) {
                log { "行首按左出页面" }
                rowListener?.onExitLeft()
            }
            return true
        }
        val count = (items.firstOrNull { it.key == from.first } as? TvNativeExploreItem.Row)?.cards?.size ?: 0
        if (target < count) {
            val columns = metrics.columns.coerceAtLeast(1)
            rowLeft[from.first] = tvStripLeftIndex(rowLeft[from.first] ?: 0, target, count, columns)
            pending = from.first to target
        } else if (pending == null) {
            log { "行尾按右" }
            rowListener?.onExitRight()
        }
        return true
    }

    /** 焦点不在卡上时的方向键 (停放着): 回到上次那张. 探索页覆盖成 hero 按钮之间的走法. */
    protected open fun handleKeyOffCards(key: Key): Boolean {
        if (!hasAnyFocus) return false
        requestCardsFocus()
        return true
    }

    /** 首行按上: 交给页面 (回标签行等). */
    protected open fun onFirstRowUp() {
        rowListener?.onTopRowUp()
    }

    /** 落到 [rowKey] 行屏上第 [column] 列那张 (行短了落到最后一张). */
    protected fun focusAtColumn(rowKey: String, column: Int) {
        val row = items.firstOrNull { it.key == rowKey } as? TvNativeExploreItem.Row ?: return
        if (row.cards.isEmpty()) return
        val index = ((rowLeft[rowKey] ?: 0) + column).coerceIn(0, row.cards.size - 1)
        focusCard(rowKey, index)
    }

    // ---- hero 态 ----

    override val heroActive: Boolean get() = timeline.active

    /** 下一次退出 hero 态停在原地 (见 [exitHeroInPlace]). */
    protected var stayInPlace = false

    fun consumeStayInPlace(): Boolean = stayInPlace.also { stayInPlace = false }

    /**
     * 触屏上退出 hero 态 (点卡片以外的地方、拖列表 ([byDrag])、按返回): hero 背景与文字照常淡出, 列表停在原地 (不滚回正中); 按触屏的规矩走时
     * 焦点停放 (见 [parkFocus]). 子类可以改道 (见 [exitHeroToTop]).
     */
    fun exitHeroInPlace(byDrag: Boolean = false) {
        if (!timeline.active) return
        log { "原地退出 hero (拖动=$byDrag, 触屏=$touchMode)" }
        if (touchMode) {
            if (exitHeroToTop(scrollToTop = !byDrag)) return
            parkFocus()
        } else {
            stayInPlace = true
            setHeroActive(false, animated = true)
        }
    }

    /** 触屏上退出 hero 态时另走的路 (探索页: 第一排回轮播); 返回 true = 接了. */
    protected open fun exitHeroToTop(scrollToTop: Boolean): Boolean = false

    protected fun setHeroActive(active: Boolean, animated: Boolean) {
        if (active && !heroEnabled) return
        if (timeline.active == active) return
        applyHeroActive(active, animated)
    }

    /** 真正进出 hero 态: 自己有 hero 时间线的切它, 宾客交给宿主. */
    protected open fun applyHeroActive(active: Boolean, animated: Boolean) {
        timeline.setActive(active, animated)
        rowListener?.onHeroActiveChanged(active)
    }

    override val rowLeftIndex: Map<String, Int> get() = rowLeft.toMap()

    override val rowFocusedIndexMap: Map<String, Int> get() = rowFocused.toMap()

    open fun hasFocus(): Boolean = hasAnyFocus

    // ---- 页面的接线 (TvRowWallSurface) ----

    override fun setItems(items: List<TvNativeExploreItem>) {
        if (logTag != null) {
            val rows = items.filterIsInstance<TvNativeExploreItem.Row>().map { "${it.key}:${it.cards.size}" }
            val before = shownItems.filterIsInstance<TvNativeExploreItem.Row>().map { "${it.key}:${it.cards.size}" }
            if (rows != before) log { "列表项 $rows" }
        }
        shownItems = items
    }

    override fun restore(
        scrollPx: Int,
        rowLeftIndex: Map<String, Int>,
        rowFocusedIndex: Map<String, Int>,
        focusedRowKey: String?,
        focusedCardIndex: Int,
        heroActive: Boolean,
        holdLook: Boolean,
    ) {
        restoreScrollPx = scrollPx
        rowLeft.clear()
        rowLeft.putAll(rowLeftIndex)
        rowFocused.clear()
        rowFocused.putAll(rowFocusedIndex)
        this.focusedRowKey = focusedRowKey
        this.focusedCardIndex = focusedCardIndex
        heldFocus = if (holdLook && focusedRowKey != null) focusedRowKey to focusedCardIndex else null
        log { "页面恢复: 停位 ${scrollPx}px, 聚焦 $focusedRowKey/$focusedCardIndex, 回卡片=$holdLook" }
        restoreRequest++
    }

    /**
     * 窗口纵坐标 [windowY] 处那一行能不能左右滚 (卡片多过一屏): 能的话横着拖是拖这一行; 落在组标题、空白或放得下的行上时 false.
     */
    fun rowScrollsAt(windowY: Float): Boolean {
        for (info in listState.layoutInfo.visibleItemsInfo) {
            val row = items.getOrNull(info.index) as? TvNativeExploreItem.Row ?: continue
            val top = listLineInWindow + shift.value + info.offset
            if (windowY < top || windowY >= top + metrics.rowPx) continue
            val state = rowStateFor(row.key)
            return state.canScrollForward || state.canScrollBackward
        }
        return false
    }

    /** 第 [item] 项那一行卡片的纵向中心 (窗口坐标); 不在屏上时 null. */
    private fun rowCenterInWindow(item: Int): Int? {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == item } ?: return null
        return (listLineInWindow + shift.value + info.offset + (metrics.rowPx - metrics.rowGapPx) / 2f).toInt()
    }

    override fun enterNearRow(windowY: Int, fromLeft: Boolean) {
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (info in listState.layoutInfo.visibleItemsInfo) {
            val row = items.getOrNull(info.index) as? TvNativeExploreItem.Row ?: continue
            if (row.cards.isEmpty()) continue
            val center = rowCenterInWindow(info.index) ?: continue
            val distance = abs(center - windowY)
            if (distance < bestDistance) {
                bestDistance = distance
                best = row.key
            }
        }
        log { "从旁边的网格进来 (卡片墙): 按高度 $windowY 找行 → ${best ?: "没排出来, 第一行"}${if (fromLeft) ", 从左边" else ""}" }
        (best ?: rowKeys().firstOrNull())?.let { landAtEdge(it, fromLeft) }
    }

    override fun enterRow(number: Int, fromLeft: Boolean) {
        val keys = rowKeys()
        if (keys.isEmpty()) return
        log { "从旁边的网格进来 (hero 态): 第 $number 行${if (fromLeft) ", 从左边" else ""}" }
        landAtEdge(keys[number.coerceIn(0, keys.size - 1)], fromLeft)
    }

    /** 下一次送焦 ([requestCardsFocus]) 落在 [rowKey] 行屏上的行缘那张: [fromLeft] = 最左边那张, 否则最右边那张. */
    private fun landAtEdge(rowKey: String, fromLeft: Boolean) {
        val row = items.firstOrNull { it.key == rowKey } as? TvNativeExploreItem.Row ?: return
        if (row.cards.isEmpty()) return
        val left = if (touchMode) tvRowFirstFullIndex(rowStateFor(rowKey)) else rowLeft[rowKey] ?: 0
        val columns = metrics.columns.coerceAtLeast(1)
        val index = (if (fromLeft) left else left + columns - 1).coerceIn(0, row.cards.size - 1)
        rowFocused[rowKey] = index
        focusedRowKey = rowKey
        focusedCardIndex = index
    }

    override fun focusedRowNumber(): Int? {
        val rowKey = focusedRowKey?.takeIf { cardAreaHasFocus } ?: return null
        return rowKeys().indexOf(rowKey).takeIf { it >= 0 }
    }

    override fun focusedCardCenterYInWindow(): Int? {
        val rowKey = focusedRowKey ?: return null
        return rowCenterInWindow(items.indexOfFirst { it.key == rowKey })
    }

    override fun firstRowKey(): String? = rowKeys().firstOrNull()

    override fun requestCardsFocus(): Boolean {
        val keys = rowKeys()
        if (keys.isEmpty()) {
            log { "页面要送焦进来: 还没有行" }
            return false
        }
        return focusCard(focusedRowKey?.takeIf { it in keys } ?: keys.first(), -1)
    }
}

private val logger = logger("TvWallRows")
