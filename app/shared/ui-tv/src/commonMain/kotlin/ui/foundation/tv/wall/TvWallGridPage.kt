/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.focus.TvNoBringIntoViewSpec
import me.him188.ani.app.ui.foundation.focus.TvScrollAnimator
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import me.him188.ani.app.ui.foundation.focus.tvGridFocusItem
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_TAB_CONTENT_SLIDE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_HERO_BLUR_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.TvGridPageSurface
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCardBadgeStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageCallbacks
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallBackdropSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.withNsfw
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground

/*
 * 网格页 (追番 / 搜索 / 新番时间表) 海报墙的 Compose 版 (安卓电视上是原生的 TvNativeGridPageView, 参数与行为见 TvNativeGridPageHost):
 * 底下依次是整屏模糊底 (新番时间表 / hero 态铺模糊背景时)、hero 背景图、hero 文字, 上面是网格. 换 [gridKey] 时新旧两份网格水平滑过.
 * 页面的网格送焦请求 (TvGridFocusState) 走 Compose 网格的送焦协议 (tvGridFocusItem + SendFocusEffect).
 */

@Composable
internal fun <T : Any> TvWallGridPage(
    state: TvNativeGridPageState,
    metrics: TvNativeGridPageMetrics,
    cardWidth: Dp,
    gridKey: Int,
    slideDirection: (from: Int, to: Int) -> Int,
    cards: List<TvNativeCard?>,
    itemAt: (Int) -> T?,
    itemCount: () -> Int,
    onBind: (Int) -> Unit,
    heroEnabled: Boolean,
    badge: TvNativeCardBadgeStyle?,
    source: TvNativeHeroSource?,
    fadeColor: Color,
    treatment: TvBackdropTreatment,
    gridFocus: TvGridFocusState,
    farJump: () -> Boolean,
    onFarJumpConsumed: () -> Unit,
    callbacks: TvNativeGridPageCallbacks<T>,
    menuFor: (T) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    wallBackdrop: TvNativeWallBackdropSpec?,
    modifier: Modifier,
    landingIndex: Int,
    topBarScrollAwayPx: Int,
    columnSpacing: Dp,
    cardHeight: Dp?,
    emptyContent: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val themeSettings = LocalThemeSettings.current
    val visualEffects = themeSettings.visualEffects
    // hero 态在整页底下铺模糊背景 (设置里的开关); 没有 hero 态的新番时间表不管它
    val heroBlur = heroEnabled && themeSettings.tvWallBackdropBlur != TvBackdropBlurLevel.None
    // 新番时间表的模糊背景恒铺, 海报墙选了不模糊时按中 (见 ThemeSettings.tvScheduleBlurLevel); 开着 hero 模糊时两者同一档
    val blurSpec = tvBackdropBlurSpec(themeSettings.tvScheduleBlurLevel)
    // 「海报上按确定」(见 TvPosterConfirmAction): 直接播放时按确定当场交给页面播
    val posterConfirm = themeSettings.tvPosterConfirm
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val heroBlurMask = tvPosterWallBackground().copy(alpha = if (dark) TV_HERO_BLUR_DIM_ALPHA else TV_FULLSCREEN_BACKDROP_DIM_ALPHA)
    val heroBlurText = tvHeroContentColor()
    val touchInput = LocalTvTouchInputEnabled.current
    val style = rememberTvWallCardStyle(cardWidth, cardHeight, badge)
    val g = metrics.grid
    val columns = g.columns.coerceAtLeast(1)
    val geometry = remember(style, g, metrics.pageHeightPx, metrics.gridTopPx) {
        TvWallGridGeometry(style.blockHeightPx, style.rowSpacingPx, metrics.pageHeightPx - metrics.gridTopPx, g.endMarginPx)
    }
    val fadeDistancePx = with(density) { TV_CARD_FADE_DISTANCE.toPx() }
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentItemAt by rememberUpdatedState(itemAt)
    val currentItemCount by rememberUpdatedState(itemCount)
    val currentOnBind by rememberUpdatedState(onBind)
    val currentFarJump by rememberUpdatedState(farJump)
    val currentOnFarJumpConsumed by rememberUpdatedState(onFarJumpConsumed)

    val scope = rememberCoroutineScope()
    // 铺着模糊背景时 hero 态不压黑, 整屏底色一直是卡片墙那档; 浅色主题 hero 的底色与卡片墙同色
    val timeline = rememberTvWallHeroTimeline(initial = state.heroActive && heroEnabled, darkens = dark && !heroBlur)
    val surface = remember(state) { TvWallGridSurface(state, timeline, scope) }
    surface.heroEnabled = heroEnabled
    surface.columnsValue = columns
    surface.shownKey = gridKey
    surface.bindItemCount { currentItemCount() }
    DisposableEffect(surface) {
        state.surface = surface
        onDispose {
            state.capture()
            if (state.surface === surface) state.surface = null
        }
    }
    SideEffect { source?.let(surface::setSource) }
    // 没有 hero 态 (「海报上按确定」改成了别的档) 时撤掉存着的 hero 态, 返回键不再先回卡片墙
    LaunchedEffect(heroEnabled) {
        if (!heroEnabled) surface.setHeroActive(false, animated = false)
    }
    // 整屏底色 (主壳画): hero 态的进度
    LaunchedEffect(timeline, heroBlur) {
        snapshotFlow { if (heroBlur) 0f else timeline.tone }.collect { state.tone = it }
    }

    // NSFW 设为模糊时按卡片 / hero 的条目 id 打码
    val nsfw = rememberNsfwPolicy()
    val shownCards = remember(cards, nsfw.snapshot) { cards.withNsfw(nsfw) }
    // 各份网格最后一次的卡片 (换标签时滑出去的那份照旧画它自己的)
    val lastCards = remember { HashMap<Int, List<TvNativeCard?>>() }
    lastCards[gridKey] = shownCards
    // hero 内容: 退出途中定格, 不跟着导航换 (正在淡出的图与字换一张只会晃一下)
    val frozen = remember { HeroHolder() }
    if (timeline.active) frozen.source = surface.heroSource?.withNsfw(nsfw)
    val heroVisible = timeline.visible
    if (!heroVisible) frozen.source = null
    val heroSource = frozen.source

    // 返回本页 (重建) 时焦点要回的那张: 先按住聚焦态, 任何一张卡拿到焦点就作废
    var landingHeld by remember { mutableIntStateOf(landingIndex) }

    var pagePosition by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { pagePosition = it.positionInWindow() }) {
        // 新番时间表的整屏模糊底: 按停稳后的聚焦条目换图
        if (wallBackdrop != null) {
            val target = wallBackdrop.target()?.withNsfw(nsfw)
            TvWallBlurBackdrop(target, blurSpec, wallBackdrop.maskColor, wallBackdrop.textColor)
        }
        if (heroVisible) {
            if (heroBlur) {
                TvWallBlurBackdrop(heroSource?.wall, blurSpec, heroBlurMask, heroBlurText, alpha = { timeline.content })
            }
            TvWallHeroBackdrop(
                heroSource?.backdrop,
                dimming = heroSource?.dimming == true,
                treatment = { treatment },
                fadeColor = fadeColor,
                modifier = Modifier
                    .tvWallPlace(fromEnd = true, x = 0, y = 0, width = metrics.backdropWidthPx, height = metrics.backdropHeightPx)
                    .graphicsLayer { alpha = timeline.content },
            )
            TvWallHeroText(
                heroSource?.text,
                titleMaxLines = 2,
                titleWidth = with(density) { metrics.titleWidthPx.toDp() },
                summaryWidth = with(density) { metrics.summaryWidthPx.toDp() },
                lineSpacing = 8.dp,
                modifier = Modifier
                    .tvWallPlace(x = metrics.bleedLeftPx + metrics.heroLeftPx, y = metrics.heroTopPx, width = metrics.heroWidthPx, height = metrics.heroHeightPx)
                    .graphicsLayer { alpha = timeline.text },
            )
        }

        val gridTop = metrics.gridTopPx - g.topBleedPx
        val gridHeight = metrics.pageHeightPx - metrics.gridTopPx + g.topBleedPx + g.bottomBleedPx
        AnimatedContent(
            targetState = gridKey,
            modifier = Modifier
                .tvWallPlace(x = 0, y = gridTop, width = -1, height = gridHeight)
                .onFocusChanged {
                    if (surface.gridHasFocus != it.hasFocus) {
                        surface.gridHasFocus = it.hasFocus
                        currentCallbacks.onGridFocusChanged(it.hasFocus)
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val index = surface.focusedIndex
                    val count = currentItemCount()
                    if (index < 0 || count <= 0 || !surface.gridHasFocus) return@onPreviewKeyEvent false
                    val row = index / columns
                    val column = index % columns
                    when (event.key) {
                        Key.DirectionUp -> row == 0 && currentCallbacks.onTopRowUp()
                        Key.DirectionLeft -> column == 0 && currentCallbacks.onRowEdge(-1, row)
                        // 行尾按右: 交给页面 (追番页换标签), 不交就吞掉 (不往下一行跑)
                        Key.DirectionRight -> if (column == columns - 1 || index == count - 1) {
                            currentCallbacks.onRowEdge(1, row)
                            true
                        } else {
                            false
                        }
                        // 末行按下吞掉
                        Key.DirectionDown -> row == (count - 1) / columns
                        else -> false
                    }
                }
                .tvWallTouchGestures(
                    enabled = touchInput,
                    // hero 态里点卡片之间的空白处退回卡片墙
                    onBlankTap = { if (timeline.active) surface.setHeroActive(false) },
                    onSwipe = callbacks.onSwipe?.let { { delta -> currentCallbacks.onSwipe?.invoke(delta) } },
                ),
            transitionSpec = {
                val direction = slideDirection(initialState, targetState)
                if (visualEffects.transitions && direction != 0) {
                    slideInHorizontally(tween(TV_TAB_CONTENT_SLIDE_MILLIS)) { w -> if (direction > 0) w else -w } togetherWith
                            slideOutHorizontally(tween(TV_TAB_CONTENT_SLIDE_MILLIS)) { w -> if (direction > 0) -w else w }
                } else {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                }
            },
            label = "tvWallGrid",
        ) { key ->
            val isCurrent = key == gridKey
            val list = if (isCurrent) shownCards else lastCards[key].orEmpty()
            val gridState = surface.gridStateFor(key)
            val shift = remember(gridState) { Animatable(0f) }
            val animator = remember(gridState, visualEffects.animatedScroll) { TvScrollAnimator(animated = visualEffects.animatedScroll) }
            val count = if (isCurrent) currentItemCount() else 0
            CompositionLocalProvider(LocalBringIntoViewSpec provides TvNoBringIntoViewSpec) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationY = shift.value },
                    contentPadding = with(density) {
                        PaddingValues(
                            start = (metrics.bleedLeftPx + g.startPx).toDp(),
                            top = g.topBleedPx.toDp(),
                            end = g.endPx.toDp(),
                            bottom = (g.bottomBleedPx + g.endMarginPx).toDp(),
                        )
                    },
                    horizontalArrangement = Arrangement.spacedBy(columnSpacing),
                    verticalArrangement = Arrangement.spacedBy(with(density) { style.rowSpacingPx.toDp() }),
                ) {
                    items(list.size, key = { it }) { index ->
                        if (isCurrent) LaunchedEffect(index) { currentOnBind(index) }
                        TvWallCard(
                            card = list.getOrNull(index),
                            style = style,
                            onClick = { if (isCurrent) surface.onCardClick(index, posterConfirm, currentCallbacks, currentItemAt) },
                            modifier = if (isCurrent) Modifier.tvGridFocusItem(gridFocus, index, count) else Modifier,
                            onFocusChanged = { focused ->
                                if (focused && isCurrent) {
                                    landingHeld = -1
                                    if (currentFarJump()) currentOnFarJumpConsumed()
                                    surface.setFocused(key, index)
                                    currentCallbacks.onCardFocused(index, currentItemAt(index))
                                    state.capture()
                                }
                            },
                            onLongPress = { rect ->
                                if (isCurrent) currentItemAt(index)?.let { item -> state.menu = item to rect }
                            },
                            dimAlpha = {
                                tvWallGridItemAlpha(
                                    gridState, index, timeline, g.heroLinePx, columns, fadeDistancePx,
                                    surface.focusedIndexOf(key), shift.value,
                                )
                            },
                            labelAlpha = { 1f - timeline.above },
                            holdFocusLook = isCurrent && index == landingHeld,
                            // 卡片墙上点一下 = 聚焦并进 hero 态; hero 态里 / 没有 hero 态 (确认就进详情页或播放) 第一下只换焦点
                            touchTwoStep = !(heroEnabled && !timeline.active),
                        )
                    }
                }
            }
            if (isCurrent) {
                TvWallGridScrollEffect(surface, key, gridState, geometry, columns, g.heroLinePx, shift, animator, visualEffects.animatedScroll)
                LaunchedEffect(gridState) {
                    snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
                        currentCallbacks.onScrollingChanged(scrolling)
                        if (!scrolling) state.capture()
                    }
                }
                if (topBarScrollAwayPx > 0) {
                    LaunchedEffect(gridState, topBarScrollAwayPx) {
                        snapshotFlow { geometry.scrolledOf(gridState, columns).coerceIn(0, topBarScrollAwayPx) }
                            .collect { state.contentScroll = it }
                    }
                }
                // 页面的网格送焦请求 (进页恢复 / 顶栏下键 / 换标签落点 / 返回键回首卡): 目标不在视口里时滚到它的停位 (返回键回首卡一路滚上去)
                gridFocus.SendFocusEffect(
                    gridState,
                    bringIntoView = { index ->
                        val far = currentFarJump()
                        if (far) currentOnFarJumpConsumed()
                        val n = currentItemCount()
                        val stop = geometry.stopFor(index, columns, n, timeline.active, g.heroLinePx)
                        if (far && visualEffects.animatedScroll) {
                            surface.farJumping = true
                            try {
                                animator.animateScrollBy(gridState, (stop.scrollPx - geometry.scrolledOf(gridState, columns)).toFloat(), TvScrollSpring.Far)
                            } finally {
                                surface.farJumping = false
                            }
                        } else {
                            shift.snapTo(stop.shiftPx.toFloat())
                            gridState.scrollToItem(stop.scrollPx / geometry.pitchPx * columns, stop.scrollPx % geometry.pitchPx)
                        }
                    },
                    itemCount = { currentItemCount() },
                )
                surface.landFarJumpAction = {
                    if (surface.farJumping) {
                        scope.launch {
                            val target = surface.focusedIndex.coerceAtLeast(0)
                            val stop = geometry.stopFor(target, columns, currentItemCount(), false, 0)
                            gridState.scrollToItem(stop.scrollPx / geometry.pitchPx * columns, stop.scrollPx % geometry.pitchPx)
                        }
                        true
                    } else {
                        false
                    }
                }
                surface.firstBelowTopLine = { gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.offset.y >= 0 }?.index }
                surface.focusAt = { index -> gridFocus.focusItem(index, reason = "surface") }
            }
        }
        emptyContent()
        // 长按卡片的收藏菜单: 锚在那张卡的封面上 (窗口坐标). 收起时照样组合着上一次的目标, 菜单淡完才撤
        val menuHolder = remember { MenuHolder<Pair<Any, Rect>>() }
        val menuShown = state.menu
        if (menuShown != null) menuHolder.value = menuShown
        (menuShown ?: menuHolder.value)?.let { (item, rect) ->
            @Suppress("UNCHECKED_CAST")
            val menu = remember(item) { menuFor(item as T) }
            Box(
                Modifier
                    .offset { IntOffset((rect.left - pagePosition.x).toInt(), (rect.top - pagePosition.y).toInt()) }
                    .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
            ) {
                menu(state.menu != null) { state.menu = null }
            }
        }
    }
}

/**
 * 聚焦行的停位: 焦点换了 (用户导航) 跑 spring; 焦点没换、只是条目数变了 (分页重载) 就瞬时对回. 进出 hero 态焦点没换, 滚动与整片平移走
 * [TvScrollSpring.Far]. 头一拍 (这份网格刚组合: 换标签滑进来、页面重建) 直接到位.
 */
@Composable
private fun TvWallGridScrollEffect(
    surface: TvWallGridSurface,
    key: Int,
    gridState: LazyGridState,
    geometry: TvWallGridGeometry,
    columns: Int,
    heroLinePx: Int,
    shift: Animatable<Float, AnimationVector1D>,
    animator: TvScrollAnimator,
    animatedScroll: Boolean,
) {
    LaunchedEffect(gridState, columns, geometry, heroLinePx, animatedScroll) {
        var lastFocused = -1
        var lastHero = false
        snapshotFlow { Triple(surface.focusedIndexOf(key), surface.itemCountForScroll(), surface.timeline.active) }
            .collectLatest { (focused, count, heroActive) ->
                if (focused < 0 || count <= 0) return@collectLatest
                val stop = geometry.stopFor(focused, columns, count, heroActive, heroLinePx)
                val heroToggled = focused == lastFocused && heroActive != lastHero
                val pace = if (heroToggled) TvScrollSpring.Far else TvScrollSpring.Step
                val navigated = focused != lastFocused || heroActive != lastHero
                val firstStop = lastFocused < 0
                lastFocused = focused
                lastHero = heroActive
                coroutineScope {
                    val shiftAnim = shift
                    if (shiftAnim.targetValue != stop.shiftPx.toFloat()) {
                        launch {
                            if (navigated && animatedScroll && !firstStop) {
                                shiftAnim.animateTo(stop.shiftPx.toFloat(), spring(dampingRatio = pace.dampingRatio, stiffness = pace.stiffness))
                            } else {
                                shiftAnim.snapTo(stop.shiftPx.toFloat())
                            }
                        }
                    }
                    val scrolled = geometry.scrolledOf(gridState, columns)
                    if (stop.scrollPx != scrolled) {
                        runCatching {
                            if (navigated && !firstStop) {
                                animator.animateScrollBy(gridState, (stop.scrollPx - scrolled).toFloat(), pace)
                            } else {
                                gridState.scrollToItem(stop.scrollPx / geometry.pitchPx * columns, stop.scrollPx % geometry.pitchPx)
                            }
                        }
                    }
                }
            }
    }
}

/** 网格页海报墙的画面 (Compose 版) 给页面用的操作, 见 [TvGridPageSurface]. */
@Stable
internal class TvWallGridSurface(
    private val state: TvNativeGridPageState,
    val timeline: TvWallHeroTimeline,
    private val scope: CoroutineScope,
) : TvGridPageSurface {
    var heroEnabled: Boolean = true
    var columnsValue: Int = 1
    /** hero 内容 (页面给的聚焦那张). */
    var heroSource: TvNativeHeroSource? by mutableStateOf(null)
        private set

    /** 此刻显示的那份网格 (页面给的 gridKey). */
    var shownKey: Int = 0

    override val currentKey: Any? get() = shownKey

    /** 各份网格上次聚焦的那张 (按网格的 key; 没有 = -1): 换标签回来时那份网格照它停. */
    private val focusedIndexes = mutableStateMapOf<Int, Int>()

    /** 此刻那份网格里聚焦的那张 (-1 = 没有). */
    val focusedIndex: Int get() = focusedIndexOf(shownKey)

    fun focusedIndexOf(key: Int): Int = focusedIndexes[key] ?: -1

    fun setFocused(key: Int, index: Int) {
        focusedIndexes[key] = index
    }
    var gridHasFocus: Boolean = false
    var farJumping: Boolean = false

    // 此刻那份网格装上的操作 (见 TvWallGridPage)
    var landFarJumpAction: () -> Boolean = { false }
    var firstBelowTopLine: () -> Int? = { null }
    var focusAt: (Int) -> Unit = {}
    private var itemCountProvider: () -> Int = { 0 }

    private val gridStates = HashMap<Any, LazyGridState>()

    /** [key] 那份网格的滚动状态: 第一次显示时从页面存着的位置排起. */
    fun gridStateFor(key: Int): LazyGridState =
        gridStates.getOrPut(key) { LazyGridState(firstVisibleItemIndex = state.savedPositions[key] ?: 0) }

    fun itemCountForScroll(): Int = itemCountProvider()

    fun <T : Any> onCardClick(
        index: Int,
        posterConfirm: TvPosterConfirmAction,
        callbacks: TvNativeGridPageCallbacks<T>,
        itemAt: (Int) -> T?,
    ) {
        val item = itemAt(index) ?: return
        // 卡片墙上先切到 hero 态, hero 态里才进详情页 (这时有 hero 背景图, 走放大转场)
        if (heroEnabled && !timeline.active) {
            setHeroActive(true)
            return
        }
        val play = callbacks.onCardPlay.takeIf { posterConfirm == TvPosterConfirmAction.Play }
        if (play != null) play(index, item) else callbacks.onCardClick(index, item)
    }

    override val heroActive: Boolean get() = timeline.active

    override fun setHeroActive(active: Boolean, animated: Boolean) {
        if (active && !heroEnabled) return
        if (timeline.active != active) timeline.active = active
        state.heroActive = active
        if (!animated) scope.launch { timeline.snapAll(if (active) 1f else 0f) }
    }

    override fun savedPositions(): Map<Any, Int> = gridStates.mapValues { it.value.firstVisibleItemIndex }

    override fun forgetPosition(key: Any) {
        gridStates.remove(key)
        (key as? Int)?.let { focusedIndexes.remove(it) }
    }

    override fun focusItem(index: Int) = focusAt(index)

    override fun firstIndexBelowTopLine(): Int? = firstBelowTopLine()

    override fun landFarJump(): Boolean = landFarJumpAction()

    override fun hasFocus(): Boolean = gridHasFocus

    override val columns: Int get() = columnsValue

    // 下面两样只在网格与一组一行的内容 (追番页「追更」) 之间换焦点时用; Compose 版没有那份内容
    override fun focusedCardCenterYInWindow(): Int? = null

    override fun rowNearestWindowY(windowY: Int): Int? = null

    override fun setSource(source: TvNativeHeroSource) {
        heroSource = source
    }

    internal fun bindItemCount(provider: () -> Int) {
        itemCountProvider = provider
    }
}

private class HeroHolder {
    var source: TvNativeHeroSource? = null
}

/** 长按菜单的目标, 收起后还留着 (菜单收起时要淡出, 这期间照样组合着上一次的目标、只把展开置假). */
internal class MenuHolder<T : Any> {
    var value: T? = null
}
