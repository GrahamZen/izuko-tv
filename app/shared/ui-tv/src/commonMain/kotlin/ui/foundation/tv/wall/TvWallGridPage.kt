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
import androidx.compose.foundation.interaction.DragInteraction
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
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FADE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_TAB_CONTENT_SLIDE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvWallConfirm
import me.him188.ani.app.ui.foundation.tv.TvWallDirection
import me.him188.ani.app.ui.foundation.tv.TvWallGridKey
import me.him188.ani.app.ui.foundation.tv.TvWallTouchTap
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_HERO_BLUR_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.TvGridPageSurface
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCardBadgeStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageCallbacks
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageRows
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallBackdropSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallOpenEffects
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.withNsfw
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground
import me.him188.ani.app.ui.foundation.tv.tvWallConfirm
import me.him188.ani.app.ui.foundation.tv.tvWallGridKey
import me.him188.ani.app.ui.foundation.tv.tvWallTapConfirmsWhileFocusing
import me.him188.ani.app.ui.foundation.tv.tvWallTouchTap
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

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
    rows: TvNativeGridPageRows?,
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
    surface.touchInput = touchInput
    surface.heroEnabled = heroEnabled
    surface.columnsValue = columns
    surface.shownKey = gridKey
    surface.bindItemCount { currentItemCount() }
    // 一组一行的内容 (追番页「追更」, 见 TvWallGridRows.kt): 建出来交给页面接数据与事件
    val rowsGuest = if (rows != null) remember(surface) { TvWallRowsGuest(surface, scope) } else null
    if (rows != null && rowsGuest != null) {
        rowsGuest.metrics = rows.metrics
        rowsGuest.touchInput = touchInput
        rowsGuest.animatedScroll = visualEffects.animatedScroll
    }
    surface.rowsKey = rows?.key
    surface.rowsGuest = rowsGuest
    DisposableEffect(rowsGuest) {
        state.rows = rowsGuest
        onDispose { if (state.rows === rowsGuest) state.rows = null }
    }
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
    // 触屏上 (平板 / iPad) hero 态用平板版画法 (同探索页的轮播, 见 TvPadHeroLayer)
    val padHero = touchInput
    // 整屏底色 (主壳画): hero 态的进度; 平板版画法的 hero 区自己铺满, 不压暗
    LaunchedEffect(timeline, heroBlur, padHero) {
        snapshotFlow { if (heroBlur || padHero) 0f else timeline.tone }.collect { state.tone = it }
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

    // 整屏模糊底的点开 (新番时间表的卡片墙 / hero 态铺着模糊背景时按确定): 卡片淡没、模糊底变清晰再进详情页 (见 TvWallOpen)
    val wallOpen = remember(surface) { TvWallOpen(scope) }
    wallOpen.transitions = visualEffects.transitions
    val currentWallTarget by rememberUpdatedState(
        if (heroBlur) heroSource?.wall else wallBackdrop?.target()?.withNsfw(nsfw),
    )
    wallOpen.restoreTarget = { currentWallTarget }
    val openWall: (Int, () -> Unit) -> Boolean = { index, proceed ->
        val target = currentWallTarget
        val subjectId = shownCards.getOrNull(index)?.subjectId
        val matches = (wallBackdrop != null || heroBlur) && target != null && target.subjectId != null && target.subjectId == subjectId &&
            !(heroBlur && heroSource?.dimming == true)
        matches && wallOpen.open(target) {
            state.wallOpenIndex = index
            state.wallOpenLeft = false
            proceed()
        }
    }
    // 一组一行的内容里 hero 态按确定: 模糊背景此刻是那一部的就先对焦再进 (同网格)
    rowsGuest?.openWall = { subjectId, index, proceed ->
        val target = currentWallTarget
        val matches = heroBlur && target != null && target.subjectId == subjectId && heroSource?.dimming != true
        matches && wallOpen.open(target) {
            state.wallOpenIndex = index
            state.wallOpenLeft = false
            proceed()
        }
    }
    LaunchedEffect(wallOpen) { snapshotFlow { wallOpen.opening }.collect { state.wallOpening = it } }
    // 顶栏等 Compose 部件跟着卡片淡没
    LaunchedEffect(wallOpen) { snapshotFlow { wallOpen.fade.value }.collect { state.wallFade = it } }
    if (wallBackdrop != null || heroBlur) {
        TvNativeWallOpenEffects(
            view = wallOpen,
            opened = { state.wallOpenIndex >= 0 },
            opening = { state.wallOpening },
            left = { state.wallOpenLeft },
            markLeft = { state.wallOpenLeft = true },
            clear = {
                state.wallOpenIndex = -1
                state.wallOpenLeft = false
            },
        )
    }

    // 返回本页 (重建) 时焦点要回的那张: 先按住聚焦态, 任何一张卡拿到焦点就作废
    var landingHeld by remember { mutableIntStateOf(landingIndex) }

    var pagePosition by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { pagePosition = it.positionInWindow() }
            // 触屏设备: 手指按下 / 按方向键分别切到触屏 / 电视的规矩 (见 TvWallGridSurface.touchMode); 先于网格的按键处理
            .tvWallInputMode(touchInput) { surface.lastInputTouch = it }
            // 触屏, hero 态里: 点 hero 区 (聚焦行行顶以上: 背景图、文字) 进聚焦那张 (同点它); 点别处 (空白) 退回卡片墙, 卡片留在原地
            .tvWallBlankTap(touchInput) { p ->
                if (!timeline.active) return@tvWallBlankTap
                // 一组一行的内容在屏上时交给它 (聚焦行的卡顶同样落在 hero 线上)
                val guest = rowsGuest?.takeIf { surface.rowsShown }
                when {
                    p.y >= metrics.gridTopPx + g.heroLinePx -> if (guest != null) guest.exitHeroInPlace() else surface.exitHeroInPlace()
                    guest != null -> guest.openFocusedCard()
                    else -> surface.onCardClick(surface.focusedIndex, posterConfirm, currentCallbacks, currentItemAt, openWall)
                }
            },
    ) {
        // 新番时间表的整屏模糊底: 按停稳后的聚焦条目换图
        if (wallBackdrop != null) {
            val target = wallBackdrop.target()?.withNsfw(nsfw)
            TvWallBlurBackdrop(target, blurSpec, wallBackdrop.maskColor, wallBackdrop.textColor)
        }
        // 点开时模糊底上叠的清晰原图
        TvWallSharpLayer(wallOpen)
        if (heroVisible && heroBlur) {
            TvWallBlurBackdrop(heroSource?.wall, blurSpec, heroBlurMask, heroBlurText, alpha = { timeline.content })
        }
        if (heroVisible && padHero) {
            // 平板版画法: 铺到聚焦行行顶为止 (底下铺着整页模糊背景时不另铺模糊底); 点开时跟着卡片淡没, 露出底下变清晰的那张
            TvPadHeroLayer(
                heroSource,
                textStart = with(density) { (metrics.bleedLeftPx + metrics.heroLeftPx).toDp() },
                textBottom = TV_PAD_HERO_TEXT_BOTTOM,
                pageWidth = with(density) { metrics.pageWidthPx.toDp() },
                modifier = Modifier.tvWallPlace(x = 0, y = 0, width = -1, height = metrics.gridTopPx + g.heroLinePx),
                // 没有按钮, 多放几行简介
                summaryMaxLines = TV_PAD_HERO_WALL_SUMMARY_LINES,
                ownBlur = !heroBlur,
                backdropAlpha = { timeline.content * (1f - wallOpen.fade.value) },
                textAlpha = { timeline.text * (1f - wallOpen.fade.value) },
            )
        }
        if (heroVisible && !padHero) {
            TvWallHeroBackdrop(
                heroSource?.backdrop,
                dimming = heroSource?.dimming == true,
                treatment = { treatment },
                fadeColor = fadeColor,
                modifier = Modifier
                    .tvWallPlace(fromEnd = true, x = 0, y = 0, width = metrics.backdropWidthPx, height = metrics.backdropHeightPx)
                    .graphicsLayer { alpha = timeline.content * (1f - wallOpen.fade.value) },
                // 底下铺着模糊背景: 图的边缘擦成透明露出它
                feather = heroBlur,
            )
            TvWallHeroText(
                heroSource?.text,
                titleMaxLines = 2,
                titleWidth = with(density) { metrics.titleWidthPx.toDp() },
                summaryWidth = with(density) { metrics.summaryWidthPx.toDp() },
                lineSpacing = 8.dp,
                // 点开时标题以外的几行跟着卡片淡没
                bodyAlpha = { 1f - wallOpen.fade.value },
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
                .onGloballyPositioned { surface.gridLineInWindow = it.positionInWindow().y + g.topBleedPx }
                .onFocusChanged {
                    // 一组一行的内容里的焦点不算网格的
                    val has = it.hasFocus && !surface.rowsShown
                    if (surface.gridHasFocus != has) {
                        surface.gridHasFocus = has
                        currentCallbacks.onGridFocusChanged(has)
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val index = surface.focusedIndex
                    val count = currentItemCount()
                    if (index < 0 || count <= 0 || !surface.gridHasFocus) return@onPreviewKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionUp -> TvWallDirection.Up
                        Key.DirectionDown -> TvWallDirection.Down
                        Key.DirectionLeft -> TvWallDirection.Left
                        Key.DirectionRight -> TvWallDirection.Right
                        else -> return@onPreviewKeyEvent false
                    }
                    when (val key = tvWallGridKey(direction, index, count, columns)) {
                        // 墙内挪一格: 交给 Compose 的焦点搜索
                        is TvWallGridKey.MoveTo -> false
                        TvWallGridKey.TopRowUp -> currentCallbacks.onTopRowUp()
                        // 行缘: 交给页面 (追番页换标签); 页面不接时按右吞掉 (不往下一行跑)、按左放出去
                        is TvWallGridKey.RowEdge -> currentCallbacks.onRowEdge(key.direction, key.row) || key.swallowWhenUnhandled
                        // 末行按下吞掉
                        TvWallGridKey.Swallow -> true
                    }
                }
                // 触屏: 左右滑一下换一份网格 (追番页换标签); 横向先过就整个手势归它, 网格不跟着上下滚.
                // 一组一行的内容里按在能左右滚的那一行上不认 (横向拖是拖那一行)
                .tvWallHorizontalSwipe(
                    enabled = touchInput && callbacks.onSwipe != null,
                    area = { p ->
                        val guest = rowsGuest?.takeIf { surface.rowsShown }
                        guest == null || !guest.rowScrollsAt(surface.gridLineInWindow - g.topBleedPx + p.y)
                    },
                    onSwipe = { delta -> currentCallbacks.onSwipe?.invoke(delta) },
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
            if (rows != null && rowsGuest != null && key == rows.key) {
                TvWallGridRowsContent(rowsGuest, rows, slotTopPx = gridTop, extraFade = { 1f - wallOpen.fade.value })
                // 这一格没有网格: 网格的那几样操作空着
                if (isCurrent) surface.clearGridActions()
                return@AnimatedContent
            }
            val list = if (isCurrent) shownCards else lastCards[key].orEmpty()
            val gridState = surface.gridStateFor(key)
            if (isCurrent) LaunchedEffect(gridState) { surface.restoreWhenLoaded(key, gridState) { currentItemCount() } }
            val shift = remember(gridState) { Animatable(0f) }
            val animator = remember(gridState, visualEffects.animatedScroll) { TvScrollAnimator(animated = visualEffects.animatedScroll) }
            val count = if (isCurrent) currentItemCount() else 0
            CompositionLocalProvider(LocalBringIntoViewSpec provides TvNoBringIntoViewSpec) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationY = shift.value },
                    // 自己滚停位的那一下不接手指 (按下会被当成「按住停下滚动」吃掉, 卡片点不到)
                    userScrollEnabled = !surface.autoScrolling,
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
                            onClick = { if (isCurrent) surface.onCardClick(index, posterConfirm, currentCallbacks, currentItemAt, openWall) },
                            modifier = if (isCurrent) Modifier.tvGridFocusItem(gridFocus, index, count) else Modifier,
                            onFocusChanged = { focused ->
                                val target = gridFocus.pendingIndex
                                // 按触屏的规矩走时只认送过来的那张 (在途目标、手指点的 (先记成聚焦那张再送)、返回本页的落点); 别的来路 (离开 / 回到本页时
                                // 焦点跌落到的卡、页面进组的默认落点、焦点兜底) 不算数: 不报不滚 —— 认了它 hero 就换成它, 页面也把它记成上次那张, 返回时落错.
                                // 送焦还在路上就再送一次那张 (同 TvWallRowsSurface.onCardFocused)
                                val counts = !surface.touchMode || index == target || index == surface.focusedIndex || index == landingHeld
                                if (focused && isCurrent && !counts) {
                                    if (target != null) gridFocus.focusItem(target, reason = "touch-refocus")
                                } else if (focused && isCurrent) {
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
                                    surface.stopIndexOf(key), shift.value,
                                ) * (1f - wallOpen.fade.value)
                            },
                            labelAlpha = { 1f - timeline.above },
                            holdFocusLook = isCurrent && index == landingHeld,
                            // 卡片墙上点一下 = 聚焦并进 hero 态; hero 态里 / 没有 hero 态 (确认就进详情页或播放) 第一下只换焦点
                            touchTwoStep = !tvWallTapConfirmsWhileFocusing(isMore = false, heroEnabled, timeline.active),
                            // 按触屏的规矩走时卡片墙不画聚焦放大 (hero 态里聚焦那张照画), 按下也不动焦点 (点一下怎么走见 onCardClick)
                            showFocusLook = !surface.touchMode || timeline.active,
                            touchFocusOnPress = !surface.touchMode,
                        )
                    }
                }
            }
            if (isCurrent) {
                TvWallGridScrollEffect(surface, key, gridState, geometry, columns, g.heroLinePx, shift, animator, visualEffects.animatedScroll)
                // 触屏: hero 态里手指一拖网格就退回卡片墙, 卡片跟着手指走 (见 exitHeroInPlace)
                LaunchedEffect(gridState) {
                    gridState.interactionSource.interactions.collect { interaction ->
                        if (interaction is DragInteraction.Start && surface.touchMode) surface.exitHeroInPlace()
                    }
                }
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
                    bringIntoView = bring@{ index ->
                        // 按触屏的规矩走时卡片墙上送焦不滚网格 (返回本页的恢复: 进详情页前停在哪, 回来就停在哪; 送不到那张就算了)
                        if (surface.touchMode && !timeline.active) return@bring
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
                surface.cardCentersY = {
                    gridState.layoutInfo.visibleItemsInfo.map { info ->
                        info.index to (surface.gridLineInWindow + shift.value + info.offset.y + info.size.height / 2f).toInt()
                    }
                }
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
 * 按触屏的规矩走时 ([TvWallGridSurface.touchMode]) 网格由手指滚: 只有进 hero 态与 hero 态里换卡才滚到停位 (焦点在卡片墙上换了、条目数变了都不动);
 * 触屏上退出 hero 态停在原地 (见 [TvWallGridSurface.exitHeroInPlace]).
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
        snapshotFlow { Triple(surface.stopIndexOf(key), surface.itemCountForScroll(), surface.timeline.active) }
            .collectLatest { (focused, count, heroActive) ->
                if (focused < 0 || count <= 0) return@collectLatest
                val heroToggled = focused == lastFocused && heroActive != lastHero
                val navigatedNow = focused != lastFocused || heroActive != lastHero
                val heroExited = lastHero && !heroActive
                val stayed = heroToggled && !heroActive && surface.consumeStayInPlace()
                if (stayed || (surface.touchMode && !(heroActive && navigatedNow))) {
                    lastFocused = focused
                    lastHero = heroActive
                    // 退回卡片墙停在原地: hero 态为把前几行放到 hero 线整片挪过 (网格滚不到负数), 挪回来, 卡片靠上去不留空白
                    if (heroExited && shift.targetValue != 0f) {
                        if (animatedScroll) {
                            shift.animateTo(0f, spring(dampingRatio = TvScrollSpring.Far.dampingRatio, stiffness = TvScrollSpring.Far.stiffness))
                        } else {
                            shift.snapTo(0f)
                        }
                    }
                    return@collectLatest
                }
                val stop = geometry.stopFor(focused, columns, count, heroActive, heroLinePx)
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
                        surface.autoScrolling = true
                        try {
                            runCatching {
                                if (navigated && !firstStop) {
                                    animator.animateScrollBy(gridState, (stop.scrollPx - scrolled).toFloat(), pace)
                                } else {
                                    gridState.scrollToItem(stop.scrollPx / geometry.pitchPx * columns, stop.scrollPx % geometry.pitchPx)
                                }
                            }
                        } finally {
                            surface.autoScrolling = false
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
    /** 设备有触摸屏 (LocalTvTouchInputEnabled). */
    var touchInput = false

    /** 最近一次输入是手指 (见 tvWallInputMode). */
    var lastInputTouch: Boolean by mutableStateOf(true)

    /**
     * 此刻按触屏的规矩走 (同上游的平板界面): 网格只由手指滚, 焦点在卡片墙上换了不滚动、不画聚焦效果, 按下卡片不动焦点; 只有点卡片进 hero 态、
     * hero 态里换卡才按焦点滚. 平板接着键盘 / 遥控器按方向键时回到电视的规矩.
     */
    val touchMode: Boolean get() = touchInput && lastInputTouch

    var heroEnabled: Boolean = true
    var columnsValue: Int = 1
    /** hero 内容 (页面给的聚焦那张). */
    var heroSource: TvNativeHeroSource? by mutableStateOf(null)
        private set

    /** 此刻显示的那份网格 (页面给的 gridKey). */
    var shownKey: Int = 0

    override val currentKey: Any? get() = shownKey

    /**
     * 各份网格上次聚焦的那张 (按网格的 key; 没有 = -1): 换标签回来时那份网格照它停. 页面重建时从存着的接上 —— 不然 hero 态的第一拍按第一张停
     * (滚回顶), 焦点落回来又滚回那张, 返回本页时整片先跳走再滚回来.
     */
    private val focusedIndexes = mutableStateMapOf<Int, Int>().apply { putAll(state.savedFocused) }

    /** 此刻那份网格里聚焦的那张 (-1 = 没有). */
    val focusedIndex: Int get() = focusedIndexOf(shownKey)

    fun focusedIndexOf(key: Int): Int = focusedIndexes[key] ?: -1

    /**
     * [key] 那份网格按哪一张停 (停位与越线淡化): 聚焦过的那张; 没聚焦过而在 hero 态 (在标签行上换过来的) 时按第一张 —— 同原生,
     * 不然这份网格不挪, 顶上几行卡全在 hero 线以上被淡没, 屏上一张卡都没有.
     */
    fun stopIndexOf(key: Int): Int = focusedIndexOf(key).let { if (it < 0 && timeline.active) 0 else it }

    fun setFocused(key: Int, index: Int) {
        focusedIndexes[key] = index
    }
    var gridHasFocus: Boolean = false
    var farJumping: Boolean = false

    // 此刻那份网格装上的操作 (见 TvWallGridPage)
    var landFarJumpAction: () -> Boolean = { false }
    var firstBelowTopLine: () -> Int? = { null }
    var focusAt: (Int) -> Unit = {}

    /** 此刻那份网格排出来的卡: 下标 → 卡的纵向中心 (窗口坐标). */
    var cardCentersY: () -> List<Pair<Int, Int>> = { emptyList() }

    /** 网格顶线 (第一行不滚时的顶) 在窗口里的纵坐标. */
    internal var gridLineInWindow = 0f

    /** 换到一组一行的内容: 网格的那几样操作空着. */
    fun clearGridActions() {
        landFarJumpAction = { false }
        firstBelowTopLine = { null }
        focusAt = {}
        cardCentersY = { emptyList() }
    }

    /** 一组一行的内容 (追番页「追更」, 见 TvWallGridRows.kt) 占的 key 与它的画面; 没有时 null. */
    var rowsKey: Int? = null
    var rowsGuest: TvWallRowsGuest? = null

    /** 此刻显示的是一组一行的内容. */
    val rowsShown: Boolean get() = rowsKey != null && shownKey == rowsKey
    private var itemCountProvider: () -> Int = { 0 }

    private val gridStates = HashMap<Any, LazyGridState>()

    /** [key] 那份网格的滚动状态: 第一次显示时从页面存着的位置排起. */
    fun gridStateFor(key: Int): LazyGridState =
        gridStates.getOrPut(key) {
            LazyGridState(firstVisibleItemIndex = state.savedPositions[key] ?: 0, firstVisibleItemScrollOffset = state.savedOffsets[key] ?: 0)
        }

    /**
     * 页面重建时网格先排出来、条目后到 (分页数据晚一拍): 空着排的那一下把滚动位置清回了顶上, 条目到了照存着的位置原样摆回去 (进详情页前停在哪,
     * 回来就停在哪). 网格自己已经不在顶上 (没被清掉, 或已经滚过) 时不动.
     */
    suspend fun restoreWhenLoaded(key: Int, gridState: LazyGridState, itemCount: () -> Int) {
        val index = state.savedPositions[key] ?: return
        val offset = state.savedOffsets[key] ?: 0
        if (index == 0 && offset == 0) return
        snapshotFlow { itemCount() }.first { it > 0 }
        if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) gridState.scrollToItem(index, offset)
    }

    fun itemCountForScroll(): Int = itemCountProvider()

    /**
     * 点第 [index] 张: 卡片墙上先切到 hero 态, hero 态里 (或没有 hero 态) 才进详情页 / 播放. 进详情页前先试 [openWall]
     * (铺着整屏模糊底时先对焦再进, 见 TvWallOpen; 它接手了返回 true); 直接播放不对焦. 按触屏的规矩走时 (按下不动焦点): 卡片墙上点一下就聚焦它
     * 并进 hero 态 (没有 hero 态就直接进, 焦点同时送到这张, 回来时落在它上面), hero 态里点没聚焦的那张只换焦点.
     */
    fun <T : Any> onCardClick(
        index: Int,
        posterConfirm: TvPosterConfirmAction,
        callbacks: TvNativeGridPageCallbacks<T>,
        itemAt: (Int) -> T?,
        openWall: (index: Int, proceed: () -> Unit) -> Boolean,
    ) {
        val cols = columnsValue.coerceAtLeast(1)
        val touchTap = if (touchMode) {
            val above = focusedIndex >= 0 && index / cols < focusedIndex / cols
            tvWallTouchTap(isMore = false, heroEnabled, timeline.active, index == focusedIndex, above)
        } else {
            null
        }
        if (touchTap == TvWallTouchTap.OpenFocused) {
            onCardClick(focusedIndex, posterConfirm, callbacks, itemAt, openWall)
            return
        }
        val item = itemAt(index) ?: return
        if (touchTap != null) {
            if (index != focusedIndex) {
                setFocused(shownKey, index)
                focusAt(index)
                // 手指点的当场报给页面 (hero 内容、页面记的「上次那张」), 不等焦点落到卡上: 点完马上进详情页时焦点还没到,
                // 页面记的还是前一张, 返回时就落回前一张
                callbacks.onCardFocused(index, item)
            }
            if (touchTap == TvWallTouchTap.FocusOnly) return
        }
        if (tvWallConfirm(isMore = false, heroEnabled, timeline.active) == TvWallConfirm.EnterHero) {
            setHeroActive(true)
            return
        }
        val play = callbacks.onCardPlay.takeIf { posterConfirm == TvPosterConfirmAction.Play }
        if (play != null) {
            play(index, item)
            return
        }
        if (!openWall(index) { callbacks.onCardClick(index, item) }) callbacks.onCardClick(index, item)
    }

    /** 网格正在自己滚停位 (这期间不接手指). */
    var autoScrolling: Boolean by mutableStateOf(false)

    override val heroActive: Boolean get() = timeline.active

    override fun setHeroActive(active: Boolean, animated: Boolean) {
        if (active && !heroEnabled) return
        // 按触屏的规矩走时 (hero 态里按返回) 同 exitHeroInPlace
        if (!active && touchMode && timeline.active) {
            exitHeroInPlace(animated)
            return
        }
        if (timeline.active != active) timeline.setActive(active, animated)
        state.heroActive = active
    }

    /** 下一次退出 hero 态停在原地 (见 [exitHeroInPlace]). */
    private var stayInPlace = false

    fun consumeStayInPlace(): Boolean = stayInPlace.also { stayInPlace = false }

    /** 触屏上退出 hero 态 (点卡片以外的地方、拖网格、按返回): hero 背景与文字照常淡出, 网格停在原地 (不滚回正中). */
    fun exitHeroInPlace(animated: Boolean = true) {
        if (!timeline.active) return
        // 一组一行的内容在屏上时它自己停在原地 (见 TvWallRowsSurface.onHeroToggled), 网格不记
        stayInPlace = !rowsShown
        timeline.setActive(false, animated)
        state.heroActive = false
    }

    override fun savedPositions(): Map<Any, Int> = gridStates.mapValues { it.value.firstVisibleItemIndex }

    override fun savedOffsets(): Map<Any, Int> = gridStates.mapValues { it.value.firstVisibleItemScrollOffset }

    override fun savedFocused(): Map<Any, Int> = HashMap<Any, Int>(focusedIndexes)

    override fun forgetPosition(key: Any) {
        gridStates.remove(key)
        (key as? Int)?.let { focusedIndexes.remove(it) }
    }

    override fun focusItem(index: Int) = focusAt(index)

    override fun firstIndexBelowTopLine(): Int? = firstBelowTopLine()

    override fun landFarJump(): Boolean = landFarJumpAction()

    override fun hasFocus(): Boolean = gridHasFocus

    override val columns: Int get() = columnsValue

    // 下面两样在网格与一组一行的内容 (追番页「追更」) 之间换焦点时用: 按屏上的高度对行
    override fun focusedCardCenterYInWindow(): Int? {
        rowsGuest?.takeIf { rowsShown }?.let { return it.focusedCardCenterYInWindow() }
        val index = focusedIndex
        return cardCentersY().firstOrNull { it.first == index }?.second
    }

    override fun rowNearestWindowY(windowY: Int): Int? {
        if (rowsShown) return null
        val cols = columnsValue.coerceAtLeast(1)
        val row = cardCentersY().minByOrNull { abs(it.second - windowY) }?.let { it.first / cols }
        // 页面逐帧问到网格排出来为止, 找到了才记
        if (row != null) gridLogger.info { "[ProgressRows] 网格 $shownKey 按高度 $windowY 找行 → $row" }
        return row
    }

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

private val gridLogger = logger("TvWallGridPage")
