/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.ui.foundation.focus.TvNoBringIntoViewSpec
import me.him188.ani.app.ui.foundation.focus.TvScrollAnimator
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_FADE_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_FADE_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HERO_SPLIT_BAND
import me.him188.ani.app.ui.foundation.tv.TvHeroButton
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropGeometry
import me.him188.ani.app.ui.foundation.tv.TvWallConfirm
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_HERO_BLUR_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.TvExploreSurface
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreItem
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreListener
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallOpenEffects
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvPageBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground
import me.him188.ani.app.ui.foundation.tv.tvPosterWallHeaderStyle
import me.him188.ani.app.ui.foundation.tv.tvPosterWallSecondaryLabelAlpha
import me.him188.ani.app.ui.foundation.tv.tvPosterWallToneGate
import me.him188.ani.app.ui.foundation.tv.tvWallConfirm
import me.him188.ani.app.ui.foundation.tv.tvWallTapConfirmsWhileFocusing
import me.him188.ani.app.ui.foundation.tv.wall.MenuHolder
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_TEXT_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_WALL_SUMMARY_LINES
import me.him188.ani.app.ui.foundation.tv.wall.TvPadHeroLayer
import me.him188.ani.app.ui.foundation.tv.wall.TvWallBlurBackdrop
import me.him188.ani.app.ui.foundation.tv.wall.TvWallCard
import me.him188.ani.app.ui.foundation.tv.wall.TvWallCardStyle
import me.him188.ani.app.ui.foundation.tv.wall.TvWallHeroBackdrop
import me.him188.ani.app.ui.foundation.tv.wall.TvWallHeroText
import me.him188.ani.app.ui.foundation.tv.wall.TvWallHeroTimeline
import me.him188.ani.app.ui.foundation.tv.wall.TvWallOpen
import me.him188.ani.app.ui.foundation.tv.wall.TvWallSharpLayer
import me.him188.ani.app.ui.foundation.tv.wall.TvWallTouchScrollLimit
import me.him188.ani.app.ui.foundation.tv.wall.rememberTvWallCardStyle
import me.him188.ani.app.ui.foundation.tv.wall.rememberTvWallHeroTimeline
import me.him188.ani.app.ui.foundation.tv.wall.tvPastAnchorDim
import me.him188.ani.app.ui.foundation.tv.wall.tvRowFirstFullIndex
import me.him188.ani.app.ui.foundation.tv.wall.tvStripLeftIndex
import me.him188.ani.app.ui.foundation.tv.wall.tvWallBlankTap
import me.him188.ani.app.ui.foundation.tv.wall.tvWallHorizontalSwipe
import me.him188.ani.app.ui.foundation.tv.wall.tvWallInputMode
import me.him188.ani.app.ui.foundation.tv.wall.tvWallPlace
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exploration_schedule
import me.him188.ani.app.ui.lang.exploration_tv_watch_now
import org.jetbrains.compose.resources.stringResource

/*
 * 探索页海报墙的 Compose 版 (安卓电视上是原生的 TvNativeExploreView, 结构与行为见那边): 背景图 < hero 文字与轮播按钮 < 卡片列表 < 轮播指示器.
 * 列表结构: [hero 占位] + [继续观看标题, 继续观看行] + [各组: 标题, 行], 各项定高 ([TvNativeExploreMetrics]), 停位直接按高度算.
 * 进卡片区时 hero 占位随列表滚上去, 轮播的文字与背景图跟着上移 (背景图多挪一截, 见 [tvWallCarouselShift]). 卡片上按确定进 hero 态:
 * 背景与文字换成聚焦卡、回到 hero 的位置, 聚焦行的组标题落到 hero 线上, 上面的项越线淡出; 再按确定进详情页.
 *
 * 焦点规则: 上下键落到上一行 / 下一行屏上同一列那张 (行短了落到最后一张), 首行按上回「新番时间表」, 「新番时间表」按下进首行屏上第一列;
 * 行首按左、hero 按钮在第一项按左出页面 (侧边栏); 行尾按右、末行按下吞掉.
 */

/** 第 [index] 项的高度 (px). */
private fun TvNativeExploreMetrics.wallHeightOf(item: TvNativeExploreItem): Int = when (item) {
    is TvNativeExploreItem.Spacer -> spacerPx
    is TvNativeExploreItem.Header -> headerPx
    is TvNativeExploreItem.Row -> rowPx
}

/** 第 [index] 项的顶离列表内容顶多远 (px). */
private fun TvNativeExploreMetrics.wallItemTop(items: List<TvNativeExploreItem>, index: Int): Int {
    var top = 0
    for (i in 0 until index.coerceAtMost(items.size)) top += wallHeightOf(items[i])
    return top
}

/** 聚焦第 [index] 行时列表该滚到哪: 行停在视口垂直正中, 两头放不到正中就贴边 (同原生的 centeredScroll). */
private fun TvNativeExploreMetrics.wallCenteredScroll(items: List<TvNativeExploreItem>, index: Int): Int {
    val rowHeight = rowPx - rowGapPx
    val centered = wallItemTop(items, index) + rowHeight / 2 - viewportPx / 2
    return centered.coerceAtMost(wallEndScroll(items)).coerceAtLeast(0)
}

/** 卡片墙最远滚到哪: 末行贴着视口底边 (留 [TvNativeExploreMetrics.endMarginPx]). 列表末尾补的那一整屏只给 hero 态的停位用. */
private fun TvNativeExploreMetrics.wallEndScroll(items: List<TvNativeExploreItem>): Int =
    (wallItemTop(items, items.size) - rowGapPx + endMarginPx - viewportPx).coerceAtLeast(0)

/** hero 态聚焦第 [index] 行时列表该滚到哪: 这一行的组标题位置落在 hero 线上 (同原生的 heroScroll). */
private fun TvNativeExploreMetrics.wallHeroScroll(items: List<TvNativeExploreItem>, index: Int): Int =
    (wallItemTop(items, index) - headerPx - heroHeaderTopPx).coerceAtLeast(0)

/**
 * 热门轮播的背景图此刻往上挪了多少 (px): 跟着列表一起滚, 再按进度额外上移 [overhangPx] (进度 = 列表滚过的量 / 第一行居中时的量
 * [firstRowStopPx], 到 1 为止). 同原生的 tvNativeCarouselShift.
 */
private fun tvWallCarouselShift(scrolledPx: Float, firstRowStopPx: Int, overhangPx: Int): Float {
    val progress = if (firstRowStopPx > 0) (scrolledPx / firstRowStopPx).coerceIn(0f, 1f) else 1f
    return scrolledPx + overhangPx * progress
}

@Composable
internal fun TvExplorationComposeWall(
    state: TvExplorationNativeState,
    metrics: TvNativeExploreMetrics,
    cardWidth: Dp,
    items: List<TvNativeExploreItem>,
    carouselCount: Int,
    carouselIndex: () -> Int,
    fadeColor: Color,
    geometry: TvPageBackdropGeometry,
    splitGate: () -> Float,
    listener: TvNativeExploreListener,
    cardFocusRequest: () -> TvCardFocusRequest?,
    heroFocusRequest: () -> TvHeroFocusRequest?,
    farJumpRow: () -> String?,
    focusedRowKey: () -> String?,
    focusedCardIndex: () -> Int,
    menuFor: (subjectId: Int) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val themeSettings = LocalThemeSettings.current
    val visualEffects = themeSettings.visualEffects
    // 「海报上按确定」只有先看简介这一档有 hero 态 (见 TvPosterConfirmAction); 模糊背景也只对它有用
    val heroEnabled = themeSettings.tvPosterConfirm == TvPosterConfirmAction.Hero
    val wallBlur = themeSettings.tvWallBackdropBlur
    val heroBlur = heroEnabled && wallBlur != TvBackdropBlurLevel.None
    val blurSpec = tvBackdropBlurSpec(wallBlur)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val wallColor = tvPosterWallBackground()
    val heroBlurMask = wallColor.copy(alpha = if (dark) TV_HERO_BLUR_DIM_ALPHA else TV_FULLSCREEN_BACKDROP_DIM_ALPHA)
    val heroBlurText = tvHeroContentColor()
    val touchInput = LocalTvTouchInputEnabled.current
    val style = rememberTvWallCardStyle(cardWidth)
    val headerStyle = tvPosterWallHeaderStyle()
    val headerIdleAlpha = tvPosterWallSecondaryLabelAlpha()
    val splitFloorPx = with(density) { -TV_POSTER_WALL_HERO_SPLIT_BAND.toPx() }
    val currentListener by rememberUpdatedState(listener)
    val currentSplitGate by rememberUpdatedState(splitGate)
    val scope = rememberCoroutineScope()

    // 铺着模糊背景时 hero 态不压黑; 浅色主题 hero 的底色与卡片墙同色
    val darkens = dark && !heroBlur
    val timeline = rememberTvWallHeroTimeline(initial = state.heroActive && heroEnabled, darkens = darkens)
    val surface = remember(state) {
        TvExploreComposeSurface(state, timeline, scope, focusedRowKey(), focusedCardIndex())
    }
    surface.listener = listener
    surface.touchInput = touchInput
    surface.heroEnabled = heroEnabled
    surface.items = items
    surface.metrics = metrics
    surface.animatedScroll = visualEffects.animatedScroll
    // 铺着模糊背景时 hero 态里按确定: 卡片淡没、模糊底变清晰再进详情页 (见 TvWallOpen)
    val wallOpen = remember(surface) { TvWallOpen(scope) }
    wallOpen.transitions = visualEffects.transitions
    wallOpen.restoreTarget = { surface.card?.wall }
    surface.wallOpen = if (heroBlur) wallOpen else null
    DisposableEffect(surface) {
        state.surface = surface
        onDispose {
            state.capture()
            if (state.surface === surface) state.surface = null
        }
    }
    // 没有 hero 态 (「海报上按确定」改成了别的档) 时撤掉存着的 hero 态
    LaunchedEffect(heroEnabled) {
        if (!heroEnabled) {
            surface.setHeroActive(false)
            state.heroActive = false
        }
    }
    // 返回本页 (页面重建): 列表回到存着的停位 (数据到了才排得出来)
    LaunchedEffect(surface, items.isNotEmpty()) {
        if (items.isNotEmpty() && !surface.restored) {
            surface.restored = true
            surface.jumpTo(state.savedScrollPx)
        }
    }

    // 背景图在轮播 (0) 与聚焦卡 (1) 两套尺寸之间
    val showsCardState by remember(surface, timeline) {
        derivedStateOf {
            timeline.visible && surface.focusedRowKey != null && if (timeline.active) timeline.above >= 1f else timeline.content > 0f
        }
    }
    // 组合里取一次 (换来源时重组): 下面的图层参数块里读它, 不在参数块里读派生状态
    val showsCard = showsCardState
    val mode = remember { Animatable(if (showsCard) 1f else 0f) }
    LaunchedEffect(mode, visualEffects.transitions) {
        snapshotFlow { showsCardState }.drop(1).collectLatest { card ->
            val target = if (card) 1f else 0f
            // 换之前那一路看得见 (hero 态里在首行按上回轮播): 尺寸缩放过去; 看不见时直接到位
            val visibleBefore = if (card) surface.carouselAlpha() > 0f else timeline.content > 0f
            if (visualEffects.transitions && visibleBefore) {
                mode.animateTo(target, tween(TV_WALL_BACKDROP_RESIZE_MILLIS, easing = FastOutSlowInEasing))
            } else {
                mode.snapTo(target)
            }
        }
    }
    // 触屏上 (平板 / iPad) 热门轮播与 hero 态用平板版画法 (见 TvExplorationPadCarousel.kt / TvPadHeroLayer)
    val padCarousel = touchInput
    // 平板版画法的轮播区: 卡片区顶线到首组标题 (列表停在顶上时 hero 占位垫着的那一段)
    val padCarouselHeightPx = metrics.listTopPx + metrics.spacerPx
    val carouselGone by remember(surface) { derivedStateOf { surface.carouselShift() >= metrics.carouselBottomPx } }
    val source = if (showsCard) surface.card else surface.carousel
    // 平板版画法 (轮播与 hero 态都画在 TvPadHeroLayer 里) 不用电视版的背景图与文字块: 透明, 也不登记成放大转场的来源
    val backdropTarget = if (padCarousel || (!showsCard && carouselGone)) null else source?.backdrop

    // 整屏底色 (主壳画): hero 态的黑度与轮播分界线 (跟着轮播背景图的下缘走)
    LaunchedEffect(surface, darkens, padCarousel) {
        snapshotFlow {
            val pad = padCarousel
            // 平板版画法的轮播区 / hero 区自己铺满, 下缘渐变到卡片墙底色: 整屏底色不压暗、不分界
            val tone = if (darkens && !pad) timeline.tone else 0f
            val y = if (pad) {
                splitFloorPx
            } else {
                (metrics.carouselBottomPx - surface.carouselShift()).coerceAtLeast(splitFloorPx)
            }
            tone to y
        }.collect { (tone, y) ->
            state.tone = tone
            state.splitY = y
            currentListener.onToneChanged(tone, y)
        }
    }
    LaunchedEffect(surface) {
        snapshotFlow { surface.listState.isScrollInProgress }.collect { currentListener.onScrollingChanged(it) }
    }
    // 触屏: hero 态里手指一拖列表就退回卡片墙, 卡片跟着手指走 (见 exitHeroInPlace)
    LaunchedEffect(surface) {
        surface.listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && surface.touchMode) surface.exitHeroInPlace()
        }
    }
    // 点开: 途中按返回取消, 进了详情页回到本页倒放
    LaunchedEffect(wallOpen) { snapshotFlow { wallOpen.opening }.collect { state.wallOpening = it } }
    if (heroBlur) {
        TvNativeWallOpenEffects(
            view = wallOpen,
            opened = { state.wallOpened },
            opening = { state.wallOpening },
            left = { state.wallOpenLeft },
            markLeft = { state.wallOpenLeft = true },
            clear = {
                state.wallOpened = false
                state.wallOpenLeft = false
            },
        )
    }
    // 进出 hero 态: 聚焦行滚到 hero 线 / 回到正中 (远跳那组 spring)
    LaunchedEffect(surface) {
        snapshotFlow { timeline.active }.drop(1).collect { if (!surface.consumeStayInPlace()) surface.updateStop(heroToggled = true) }
    }
    // 页面的程序化落点 (进页恢复 / 返回键分层 / 回到主界面): 请求按实例区分, 同参重发也会再送一次
    LaunchedEffect(surface) {
        snapshotFlow { cardFocusRequest() }.collect { req ->
            if (req != null) surface.focusCard(req.rowKey, req.cardIndex, far = req.rowKey == farJumpRow())
        }
    }
    LaunchedEffect(surface) {
        snapshotFlow { heroFocusRequest() }.collect { req -> if (req != null) surface.focusHeroButton(req.button.ordinal) }
    }
    // 等着的落点那一行到了就送
    LaunchedEffect(surface, items) { surface.retryDeferred() }
    // 挂着的落点那张排出来了就送焦
    LaunchedEffect(surface) {
        snapshotFlow { surface.pending?.let { surface.requesters[it] } }.collect { requester ->
            if (requester != null) runCatching { requester.requestFocus() }
        }
    }

    val watchNow = stringResource(Lang.exploration_tv_watch_now)
    val schedule = stringResource(Lang.exploration_schedule)
    var pagePosition by remember { mutableStateOf(Offset.Zero) }
    val listTop = metrics.listTopPx - metrics.listTopBleedPx
    val listHeight = metrics.pageHeightPx - metrics.listTopPx + metrics.listTopBleedPx
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { pagePosition = it.positionInWindow() }
            .onFocusChanged { surface.hasAnyFocus = it.hasFocus }
            // 触屏设备: 手指按下 / 按方向键分别切到触屏 / 电视的规矩 (见 TvExploreComposeSurface.touchMode); 先于下面的按键处理
            .tvWallInputMode(touchInput) { surface.lastInputTouch = it }
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && surface.handleKey(event.key) }
            // 触屏: 点卡片以外的地方
            .tvWallBlankTap(touchInput) { p ->
                when {
                    // hero 态里: 点 hero 区 (聚焦行的组标题以上: 背景图、文字) 进聚焦那张的详情页 (同点它); 点别处 (组标题、空白) 退回卡片墙,
                    // 卡片留在原地
                    timeline.active -> if (p.y < metrics.listTopPx + metrics.heroHeaderTopPx) surface.openFocusedCard() else surface.exitHeroInPlace()
                    // 平板版画法: 点轮播画面进这一部的详情页 (同「立即观看」)
                    padCarousel && !showsCard && p.y < padCarouselHeightPx - surface.scrolledPx -> currentListener.onHeroButtonClick(0)
                }
            }
            // 触屏: 正显示轮播时, 首行卡片以上左右滑一下翻一部 (同遥控器在 hero 按钮上按左右); 横向先过就整个手势归它, 列表不跟着上下滚
            .tvWallHorizontalSwipe(
                enabled = touchInput,
                area = { p -> !showsCard && !carouselGone && surface.carouselAlpha() >= 0.5f && p.y < surface.firstRowTopPx() },
                onSwipe = { delta -> currentListener.onSwitchCarousel(delta) },
            ),
    ) {
        // 页面送焦进来的入口 (页面在侧边栏按右 / 返回、全局兜底时请求它, 同原生视图的 onRequestFocusInDescendants): 拿到焦点就转给上次那张卡,
        // 否则上次停的那颗 hero 按钮. 页面直接送焦到卡 / 按钮的请求不经过这里. 按触屏的规矩走时焦点就停在这里 (见 parkFocus)
        Box(
            Modifier.size(1.dp)
                .focusRequester(state.focusRequester)
                .onFocusChanged { if (it.isFocused) scope.launch { surface.enter() } }
                .focusable(),
        )
        // hero 态在整页底下铺模糊背景 (跟着 hero 时间线显隐)
        if (heroBlur && showsCard) {
            TvWallBlurBackdrop(surface.card?.wall, blurSpec, heroBlurMask, heroBlurText, alpha = { timeline.content })
        }
        // 点开时模糊底上叠的清晰原图
        TvWallSharpLayer(wallOpen)
        TvWallHeroBackdrop(
            backdropTarget,
            dimming = source?.dimming == true,
            treatment = { tvPageBackdropTreatment(mode.value, topScrim = false, fadeColor = fadeColor, geometry = geometry) },
            fadeColor = fadeColor,
            modifier = Modifier
                .tvWallPlace(fromEnd = true, x = 0, y = 0, width = metrics.backdropWidthPx, height = metrics.backdropHeightPx)
                .graphicsLayer {
                    val md = mode.value
                    val scale = 1f + (metrics.cardBackdropScale - 1f) * md
                    // 以右上角为轴缩放 (图的比例几何不变)
                    transformOrigin = TransformOrigin(1f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationY = -surface.carouselShift() * (1f - md)
                    val gate = if (darkens) tvPosterWallToneGate(timeline.tone) else 1f
                    alpha = when {
                        padCarousel -> 0f
                        showsCard -> timeline.content * gate * (1f - wallOpen.fade.value)
                        else -> surface.carouselAlpha() * currentSplitGate()
                    }
                },
            // hero 态底下铺着模糊背景: 图的边缘擦成透明露出它 (轮播底下没铺, 照旧渐隐到页面底色)
            feather = heroBlur && showsCard,
        )
        // hero 文字块 + 轮播按钮块: 块高两档 (有按钮 / 没按钮), 文字吃掉按钮块之外的全部高度
        val heroWidth = metrics.pageWidthPx - metrics.heroStartPx - metrics.heroEndPadPx
        var buttonsHeightPx by remember { mutableIntStateOf(0) }
        Column(
            Modifier
                .tvWallPlace(
                    x = metrics.bleedLeftPx + metrics.heroStartPx,
                    y = metrics.heroTopPx,
                    width = heroWidth,
                    height = metrics.heroBlockExpandedPx,
                )
                .graphicsLayer {
                    translationY = -surface.scrolledPx * (1f - mode.value)
                    alpha = when {
                        padCarousel -> 0f
                        showsCard -> timeline.text
                        else -> surface.carouselAlpha()
                    }
                },
        ) {
            TvWallHeroText(
                if (padCarousel) null else source?.text,
                titleMaxLines = 1,
                titleWidth = with(density) { metrics.titleWidthPx.toDp() },
                summaryWidth = with(density) { (if (showsCard) metrics.cardSummaryWidthPx else metrics.carouselSummaryWidthPx).toDp() },
                lineSpacing = 10.dp,
                // 点开时标题以外的几行跟着卡片淡没 (标题交给详情页)
                bodyAlpha = { 1f - wallOpen.fade.value },
                // 聚焦卡时没有按钮块, 文字块是矮的那一档
                modifier = Modifier.fillMaxWidth().then(
                    if (showsCard) Modifier.height(with(density) { metrics.heroBlockPx.toDp() }) else Modifier.weight(1f),
                ),
            )
            // 按钮块的位置 (按钮另起一层压在卡片列表上面, 见下)
            if (!showsCard) Spacer(Modifier.height(with(density) { buttonsHeightPx.toDp() }))
        }

        if (padCarousel) {
            TvPadCarouselLayer(
                surface.carousel,
                carouselCount,
                carouselIndex(),
                textStart = with(density) { (metrics.bleedLeftPx + metrics.heroStartPx).toDp() },
                pageWidth = with(density) { metrics.pageWidthPx.toDp() },
                modifier = Modifier
                    .tvWallPlace(x = 0, y = 0, width = -1, height = padCarouselHeightPx)
                    .graphicsLayer {
                        // 跟着列表一起滚 (轮播区就是列表顶上那一段)
                        translationY = -surface.scrolledPx * (1f - mode.value)
                        alpha = if (showsCard) 0f else surface.carouselAlpha()
                    },
            )
        }
        // 平板版画法的 hero 态: 同轮播一套画法, 铺到聚焦行的组标题为止, 跟着 hero 时间线显隐 (底下铺着整页模糊背景时不另铺模糊底)
        if (padCarousel && showsCard) {
            TvPadHeroLayer(
                surface.card,
                textStart = with(density) { (metrics.bleedLeftPx + metrics.heroStartPx).toDp() },
                textBottom = TV_PAD_HERO_TEXT_BOTTOM,
                pageWidth = with(density) { metrics.pageWidthPx.toDp() },
                modifier = Modifier.tvWallPlace(x = 0, y = 0, width = -1, height = metrics.listTopPx + metrics.heroHeaderTopPx),
                // 没有按钮, 多放几行简介
                summaryMaxLines = TV_PAD_HERO_WALL_SUMMARY_LINES,
                ownBlur = !heroBlur,
                // 点开时跟着卡片淡没, 露出底下变清晰的那张
                backdropAlpha = { timeline.content * (1f - wallOpen.fade.value) },
                textAlpha = { timeline.text * (1f - wallOpen.fade.value) },
            )
        }

        // 卡片列表: 顶上出血一截 (离场行越过顶线继续上移), 末尾补一整屏 (任何一行的停位都滚得到)
        val emphasizedHeader by remember(surface) {
            derivedStateOf {
                surface.focusedRowKey?.takeIf { surface.cardAreaHasFocus }?.let { tvWallHeaderKeyOf(surface.items, it) }
            }
        }
        val list = items
        val touchScrollLimit = remember(surface) { TvWallTouchScrollLimit(scrolled = { surface.scrolledPx }, end = { surface.endScrollPx() }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvNoBringIntoViewSpec) {
            LazyColumn(
                Modifier
                    .tvWallPlace(x = 0, y = listTop, width = -1, height = listHeight)
                    // 触屏: 手指甩不进末尾补的那一整屏
                    .then(if (touchInput) Modifier.nestedScroll(touchScrollLimit) else Modifier),
                state = surface.listState,
                // 自己滚停位的那一下不接手指 (按下会被当成「按住停下滚动」吃掉, 卡片点不到)
                userScrollEnabled = !surface.autoScrolling,
                contentPadding = with(density) {
                    PaddingValues(top = metrics.listTopBleedPx.toDp(), bottom = (metrics.viewportPx + metrics.listBottomBleedPx).toDp())
                },
            ) {
                items(list.size, key = { list[it].key }) { index ->
                    val item = list[index]
                    val fade = { surface.itemFade(index) * (1f - wallOpen.fade.value) }
                    when (item) {
                        is TvNativeExploreItem.Spacer -> Spacer(Modifier.fillMaxWidth().height(with(density) { metrics.spacerPx.toDp() }))
                        is TvNativeExploreItem.Header -> {
                            val emphasis by animateFloatAsState(
                                if (emphasizedHeader == item.key) 1f else 0f,
                                if (visualEffects.transitions) tween(150) else snap(),
                            )
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(with(density) { metrics.headerPx.toDp() })
                                    .padding(start = with(density) { metrics.rowStartPx.toDp() }, end = with(density) { metrics.endPadPx.toDp() })
                                    .graphicsLayer { alpha = fade() * (headerIdleAlpha + (1f - headerIdleAlpha) * emphasis) },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(item.title, style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        is TvNativeExploreItem.Row -> TvExploreComposeRow(surface, item, style, timeline, fade)
                    }
                }
            }
        }

        // 轮播按钮: 压在卡片列表上面 (列表整页大、叠在 hero 上, 按钮在它底下就点不到), 贴在 hero 块的下缘, 跟着文字块走.
        // 每次重新出现都从 0 淡入 (hero 态里回轮播时文字在交叉淡入, 按钮不该当帧冒出来). 聚焦卡时挪出屏外 (看不见、点不到),
        // 焦点照样送得到 (首行按上回「新番时间表」时先退出 hero 态)
        val buttonsAlpha by animateFloatAsState(
            if (showsCard) 0f else 1f,
            if (!visualEffects.transitions) snap() else tween(if (showsCard) TV_HERO_BUTTON_FADE_OUT_MILLIS else TV_HERO_BUTTON_FADE_IN_MILLIS),
        )
        if (padCarousel) {
            Box(Modifier.tvWallPlace(x = 0, y = 0, width = -1, height = padCarouselHeightPx)) {
                TvPadCarouselButtons(
                    surface.primaryRequester,
                    surface.scheduleRequester,
                    onClick = { currentListener.onHeroButtonClick(it) },
                    onFocused = { surface.onHeroButtonFocused(it) },
                    // 触屏上只留「新番时间表」: 点轮播画面就是进详情页 (见上面的 tvWallBlankTap); 平板接着键盘 / 遥控器按键时两颗都在
                    watchNow = !surface.touchMode,
                    touchFocusOnPress = !surface.touchMode,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset { if (showsCard) IntOffset(0, TV_WALL_BUTTONS_AWAY_PX) else IntOffset.Zero }
                        .graphicsLayer {
                            translationY = -surface.scrolledPx * (1f - mode.value)
                            alpha = buttonsAlpha * surface.carouselAlpha()
                        }
                        .padding(
                            start = with(density) { (metrics.bleedLeftPx + metrics.heroStartPx).toDp() },
                            bottom = TV_PAD_CAROUSEL_BUTTONS_BOTTOM,
                        ),
                )
            }
        } else {
            Box(
                Modifier.tvWallPlace(
                    x = metrics.bleedLeftPx + metrics.heroStartPx,
                    y = metrics.heroTopPx,
                    width = heroWidth,
                    height = metrics.heroBlockExpandedPx,
                ),
            ) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset { if (showsCard) IntOffset(0, TV_WALL_BUTTONS_AWAY_PX) else IntOffset.Zero }
                        .onSizeChanged { buttonsHeightPx = it.height }
                        .graphicsLayer {
                            translationY = -surface.scrolledPx * (1f - mode.value)
                            alpha = buttonsAlpha * surface.carouselAlpha()
                        }
                        .padding(top = with(density) { metrics.buttonsTopGapPx.toDp() }),
                    verticalArrangement = Arrangement.spacedBy(with(density) { metrics.buttonGapPx.toDp() }),
                ) {
                    TvHeroButton(
                        watchNow,
                        Icons.Rounded.PlayArrow,
                        filled = true,
                        onClick = { currentListener.onHeroButtonClick(0) },
                        onFocused = { surface.onHeroButtonFocused(0) },
                        focusRequester = surface.primaryRequester,
                        touchFocusOnPress = !surface.touchMode,
                    )
                    TvHeroButton(
                        schedule,
                        Icons.Rounded.CalendarMonth,
                        filled = false,
                        onClick = { currentListener.onHeroButtonClick(1) },
                        onFocused = { surface.onHeroButtonFocused(1) },
                        focusRequester = surface.scheduleRequester,
                        touchFocusOnPress = !surface.touchMode,
                    )
                }
            }
        }

        // 轮播指示器: 跟着轮播背景图走, 聚焦卡时淡出
        val dotsFade by animateFloatAsState(
            if (showsCard) 0f else 1f,
            if (!visualEffects.transitions) snap() else tween(if (showsCard) TV_HERO_BUTTON_FADE_OUT_MILLIS else TV_HERO_BUTTON_FADE_IN_MILLIS),
        )
        if (carouselCount > 1 && !padCarousel) {
            val selected = carouselIndex()
            val dotColor = MaterialTheme.colorScheme.onSurface
            Box(
                Modifier
                    .tvWallPlace(
                        x = metrics.bleedLeftPx,
                        y = metrics.dotsCenterYPx - metrics.dotPx.toInt() * 3 / 2,
                        width = metrics.pageWidthPx,
                        height = metrics.dotPx.toInt() * 3,
                    )
                    .graphicsLayer {
                        alpha = dotsFade * surface.carouselAlpha()
                        translationY = -surface.carouselShift() * (1f - mode.value)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(with(density) { metrics.dotGapPx.toDp() })) {
                    repeat(carouselCount) { i ->
                        val on = i == selected
                        Box(
                            Modifier
                                .size(
                                    width = with(density) { (if (on) metrics.dotSelectedWidthPx else metrics.dotPx).toDp() },
                                    height = with(density) { metrics.dotPx.toDp() },
                                )
                                .clip(CircleShape)
                                .background(if (on) dotColor else dotColor.copy(alpha = dotColor.alpha * metrics.dotInactiveAlpha)),
                        )
                    }
                }
            }
        }

        // 长按卡片的收藏菜单: 锚在那张卡的封面上 (窗口坐标). 收起时照样组合着上一次的目标, 菜单淡完才撤
        val menuHolder = remember { MenuHolder<Pair<Int, Rect>>() }
        val menuShown = state.menu
        if (menuShown != null) menuHolder.value = menuShown
        (menuShown ?: menuHolder.value)?.let { (subjectId, rect) ->
            val menu = remember(subjectId) { menuFor(subjectId) }
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

/** 一条横滑行: 按需挪 (焦点走到行尾露一截的那张才整行挪), 越过行首停靠线的卡压暗着出屏. */
@Composable
private fun TvExploreComposeRow(
    surface: TvExploreComposeSurface,
    item: TvNativeExploreItem.Row,
    style: TvWallCardStyle,
    timeline: TvWallHeroTimeline,
    fade: () -> Float,
) {
    val density = LocalDensity.current
    val metrics = surface.metrics
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
            horizontalArrangement = Arrangement.spacedBy(TV_POSTER_WALL_COLUMN_SPACING),
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
                LaunchedEffect(key) { surface.listener?.onBindCard(rowKey, index) }
                val more = card?.more != null
                TvWallCard(
                    card,
                    style,
                    onClick = { surface.onCardClick(rowKey, index, more) },
                    modifier = Modifier.focusRequester(requester),
                    onFocusChanged = { focused -> if (focused) surface.onCardFocused(rowKey, index) },
                    onLongPress = if (more) null else ({ rect -> surface.listener?.onCardLongPress(rowKey, index, rect) }),
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

/** 探索页海报墙的画面 (Compose 版): 焦点簿记、停位、hero 态; 给页面的操作见 [TvExploreSurface]. */
@Stable
private class TvExploreComposeSurface(
    private val state: TvExplorationNativeState,
    val timeline: TvWallHeroTimeline,
    private val scope: CoroutineScope,
    restoredRowKey: String?,
    restoredIndex: Int,
) : TvExploreSurface {
    var listener: TvNativeExploreListener? = null

    /** 设备有触摸屏 (LocalTvTouchInputEnabled). */
    var touchInput = false

    /** 最近一次输入是手指 (见 tvWallInputMode). */
    var lastInputTouch: Boolean by mutableStateOf(true)

    /**
     * 此刻按触屏的规矩走 (同上游的平板界面): 列表与各行只由手指滚, 焦点变化不滚动、卡片墙上不画聚焦效果; 程序送来的焦点 (进页落点、恢复落点、
     * 焦点兜底) 停在入口节点上 ([parkFocus]). 只有点卡片进 hero 态、hero 态里换卡才按焦点挪. 平板接着键盘 / 遥控器按方向键时回到电视的规矩.
     */
    val touchMode: Boolean get() = touchInput && lastInputTouch

    var heroEnabled: Boolean = true
    var items: List<TvNativeExploreItem> = emptyList()
    lateinit var metrics: TvNativeExploreMetrics
    var animatedScroll: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            listAnimator = TvScrollAnimator(animated = value)
        }
    var restored = false

    val listState = LazyListState()

    /** 点开 (铺着模糊背景时 hero 态里按确定); null = 没铺模糊背景, 当场进. */
    var wallOpen: TvWallOpen? = null

    /** 列表正在自己滚停位 (这期间不接手指). */
    var autoScrolling: Boolean by mutableStateOf(false)
        private set

    /** 列表的停位动画 (连按时继承速度, 见 TvScrollAnimator). */
    private var listAnimator = TvScrollAnimator(animated = true)
    private var stopJob: Job? = null
    private var stopTarget = -1
    private val rowStates = HashMap<String, LazyListState>()

    /** 各行的行首 (第几张贴在起始停靠线上). */
    val rowLeft = mutableStateMapOf<String, Int>().apply { putAll(state.savedRowLeft) }
    private val rowFocused = HashMap<String, Int>(state.savedRowFocused)

    /** 上次聚焦的行 (null = 焦点在 hero 按钮上, 或从未进过卡片区). */
    var focusedRowKey: String? by mutableStateOf(restoredRowKey)
    private var focusedCardIndex = restoredIndex
    private var focusedColumn = 0
    var cardAreaHasFocus: Boolean by mutableStateOf(false)
    var hasAnyFocus = false
    private var lastHeroButton = 0

    /** 还没送出去的落点 (那张卡还没排出来): 行键, 下标. */
    var pending: Pair<String, Int>? by mutableStateOf(null)

    /** 等那一行到了再送的落点 (行键, 下标, 远跳), 见 [focusCard]. */
    private var deferred: Triple<String, Int, Boolean>? = null

    /** 列表项换了: 等着的落点那一行到了就送. */
    fun retryDeferred() {
        val (rowKey, index, far) = deferred ?: return
        focusCard(rowKey, index, far)
    }

    /** 远跳 (返回键回组首行 / 回顶) 途中: 列表在按远跳 spring 滚. */
    private var farJumping = false

    /** 没有焦点也画成聚焦态的那张 (进详情页时交出焦点的那张; 返回时焦点交还前也不缩). */
    var heldFocus: Pair<String, Int>? by mutableStateOf(null)

    val requesters = mutableStateMapOf<Pair<String, Int>, FocusRequester>()
    val primaryRequester = FocusRequester()
    val scheduleRequester = FocusRequester()

    var carousel: TvNativeHeroSource? by mutableStateOf(null)
        private set
    var card: TvNativeHeroSource? by mutableStateOf(null)
        private set

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

    fun carouselShift(): Float {
        val first = items.indexOfFirst { it is TvNativeExploreItem.Row }
        val firstRowStop = if (first < 0) metrics.spacerPx else metrics.wallCenteredScroll(items, first)
        return tvWallCarouselShift(scrolledPx.toFloat(), firstRowStop, metrics.overhangPx)
    }

    fun carouselAlpha(): Float = if (focusedRowKey != null) 1f - timeline.above else 1f

    /** 卡片墙最远滚到哪 (末行贴着视口底边, 见 wallEndScroll). */
    fun endScrollPx(): Int = metrics.wallEndScroll(items)

    /** 首行卡片的顶边此刻在页面里的位置 (px); 没有卡片行时为无穷大. */
    fun firstRowTopPx(): Float {
        val first = items.indexOfFirst { it is TvNativeExploreItem.Row }
        if (first < 0) return Float.MAX_VALUE
        return (metrics.listTopPx + metrics.wallItemTop(items, first) - scrolledPx).toFloat()
    }

    /**
     * 第 [index] 项的淡化 (1 = 不淡): hero 态里越过 hero 线的项在 fadeDistancePx 内淡没; 卡片墙上不淡. 进出途中不按位置淡: 聚焦行连同它的组标题
     * 及以下不淡, 上面的项整项跟着进度淡. 绘制阶段调用.
     */
    fun itemFade(index: Int): Float {
        val p = timeline.above
        if (p <= 0f) return 1f
        if (timeline.content < 1f) return if (index < keepFromIndex()) 1f - p else 1f
        val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: return 1f
        val above = metrics.heroHeaderTopPx - offset
        if (above <= 0) return 1f
        return 1f - (above / metrics.fadeDistancePx).coerceIn(0f, 1f) * p
    }

    private fun keepFromIndex(): Int {
        val rowKey = focusedRowKey ?: return 0
        val item = items.indexOfFirst { it.key == rowKey }
        if (item < 0) return 0
        return if (item > 0 && items[item - 1] is TvNativeExploreItem.Header) item - 1 else item
    }

    private fun stopFor(item: Int): Int =
        if (timeline.active) metrics.wallHeroScroll(items, item) else metrics.wallCenteredScroll(items, item)

    private fun scrollTo(target: Int, pace: TvScrollSpring) {
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

    /** 按聚焦行 (与是否 hero 态) 滚到停位: 进出 hero 态与远跳用 Far, 逐格用 Step. */
    fun updateStop(heroToggled: Boolean) {
        val rowKey = focusedRowKey ?: return
        if (!cardAreaHasFocus && !heroToggled) return
        val item = items.indexOfFirst { it.key == rowKey }
        if (item < 0) return
        scrollTo(stopFor(item), if (heroToggled || farJumping) TvScrollSpring.Far else TvScrollSpring.Step)
    }

    // ---- 焦点 ----

    /**
     * 从页面外进来 (入口节点拿到焦点): 回上次那张卡 (没排出来就先滚过去, 排出来再送), 否则回上次停的那颗 hero 按钮. 按触屏的规矩走时卡片墙上
     * 焦点就停在入口节点上 (见 [parkFocus]).
     */
    fun enter() {
        val rowKey = focusedRowKey
        when {
            touchMode && !timeline.active -> parkFocus(request = false)
            rowKey != null && rowKey in rowKeys() -> focusCard(rowKey, -1)
            else -> focusHeroButton(lastHeroButton)
        }
    }

    /**
     * 按触屏的规矩走时程序送来的焦点停在这里: 入口节点 (1dp, 看不见) 拿着焦点, 画面不滚、不画聚焦. 还在 hero 态就退回卡片墙: hero 背景与文字照常淡出,
     * 列表停在原地; 淡完才对页面报焦点在上次那颗 hero 按钮上 (页面回到轮播态, 轮播照常翻), 这之前聚焦行留着 (hero 层还画着那张卡).
     * [request] = 把焦点送过去 (入口节点自己拿到焦点时不用再送).
     */
    private fun parkFocus(request: Boolean = true) {
        pending = null
        deferred = null
        heldFocus = null
        if (request) scope.launch { runCatching { state.focusRequester.requestFocus() } }
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
    private var parkJob: Job? = null

    private fun reportParked() {
        focusedRowKey = null
        setCardAreaFocus(false)
        listener?.onHeroButtonFocused(lastHeroButton)
        listener?.onFocusParked()
    }

    fun focusHeroButton(button: Int) {
        pending = null
        deferred = null
        if (touchMode) {
            lastHeroButton = button
            parkFocus()
            return
        }
        val target = if (button == 1) scheduleRequester else primaryRequester
        scope.launch { runCatching { target.requestFocus() } }
    }

    /**
     * 送焦到 [rowKey] 行的第 [index] 张 (-1 = 这一行上次停的那张). 那张没排出来就先把列表与那一行滚过去, 排出来再送 ([pending]).
     * [far] = 远跳: 列表按远跳 spring 滚过去. 按触屏的规矩走时卡片墙上不送 (焦点停在入口节点上, 见 [parkFocus]), hero 态里照送.
     */
    fun focusCard(rowKey: String, index: Int, far: Boolean = false) {
        if (touchMode && !timeline.active) {
            parkFocus()
            return
        }
        val item = items.indexOfFirst { it.key == rowKey }
        val row = items.getOrNull(item) as? TvNativeExploreItem.Row
        if (row == null || row.cards.isEmpty()) {
            // 这一行还没到 (进页时数据在路上): 记下, 到了再送
            deferred = Triple(rowKey, index, far)
            return
        }
        deferred = null
        val target = (if (index >= 0) index else rowFocused[rowKey] ?: rowLeft[rowKey] ?: 0).coerceIn(0, row.cards.size - 1)
        revealInRow(rowKey, target, row.cards.size)
        if (!cardAreaHasFocus) heldFocus = rowKey to target
        pending = rowKey to target
        if (far) farJumping = true
        scrollTo(stopFor(item), if (far) TvScrollSpring.Far else TvScrollSpring.Step)
    }

    override fun settleFarJump(): Boolean {
        if (!farJumping) return false
        farJumping = false
        val rowKey = pending?.first ?: return false
        val item = items.indexOfFirst { it.key == rowKey }
        if (item >= 0) jumpTo(stopFor(item))
        return true
    }

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
        setCardAreaFocus(true)
        listener?.onCardFocused(rowKey, index, focusedColumn)
    }

    fun onHeroButtonFocused(button: Int) {
        parkJob?.cancel()
        heldFocus = null
        lastHeroButton = button
        pending = null
        focusedRowKey = null
        setCardAreaFocus(false)
        listener?.onHeroButtonFocused(button)
        // 焦点回到轮播按钮: 回卡片墙, 列表按远跳 spring 滚回顶 (轮播随列表一起滑回原位). 按触屏的规矩走时画面不动
        if (touchMode) return
        if (timeline.active) setHeroActive(false)
        scrollTo(0, TvScrollSpring.Far)
    }

    private fun setCardAreaFocus(has: Boolean) {
        if (cardAreaHasFocus == has) return
        cardAreaHasFocus = has
        listener?.onCardAreaFocusChanged(has)
    }

    /**
     * 点 (确认) [rowKey] 行第 [index] 张. 按触屏的规矩走时 (按下不动焦点, 见 TvWallCard 的 touchFocusOnPress): 卡片墙上点一下就聚焦它并进 hero 态
     * (没有 hero 态就直接进), hero 态里点没聚焦的那张只换焦点.
     */
    fun onCardClick(rowKey: String, index: Int, more: Boolean) {
        val key = rowKey to index
        if (touchMode) {
            // hero 态里点到聚焦行上面那几行 (淡没在 hero 区里, 看不见): 算点 hero 区
            if (timeline.active && isAboveFocusedRow(rowKey)) {
                openFocusedCard()
                return
            }
            if (timeline.active && focusedRowKey to focusedCardIndex != key) {
                requesters[key]?.let { runCatching { it.requestFocus() } }
                return
            }
            if (tvWallTapConfirmsWhileFocusing(more, heroEnabled, timeline.active)) {
                requesters[key]?.let { runCatching { it.requestFocus() } }
                takeCardFocus(rowKey, index)
            }
        }
        // 卡片墙上先切到 hero 态, hero 态里才进详情页 (这时有 hero 背景图, 走放大转场); 没有 hero 态时直接交给页面
        when (tvWallConfirm(more, heroEnabled, timeline.active)) {
            TvWallConfirm.More -> listener?.onMoreClick(rowKey)
            TvWallConfirm.EnterHero -> setHeroActive(true)
            TvWallConfirm.Open -> {
                // 进详情页: 焦点交出去之后这张卡仍画成聚焦态, 返回后焦点交还前也不缩
                heldFocus = rowKey to index
                // 铺着模糊背景: 背景此刻是这张卡的、能变清晰就先对焦, 到位再进
                val src = card
                val wall = src?.wall
                val matches = src != null && !src.dimming && wall != null && wall.subjectId != null && wall.subjectId == src.rawSubjectId
                val opened = matches && wallOpen?.open(wall) {
                    state.wallOpened = true
                    state.wallOpenLeft = false
                    listener?.onCardClick(rowKey, index)
                } == true
                if (!opened) listener?.onCardClick(rowKey, index)
            }
        }
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

    private fun rowKeys(): List<String> = items.filterIsInstance<TvNativeExploreItem.Row>().map { it.key }

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
        if (!cardAreaHasFocus || rowKey == null) {
            // hero 按钮
            if (!hasAnyFocus) return false
            val onSchedule = lastHeroButton == 1
            when (key) {
                Key.DirectionLeft -> if (listener?.onSwitchCarousel(-1) != true) listener?.onExitLeft()
                Key.DirectionRight -> listener?.onSwitchCarousel(1)
                Key.DirectionUp -> if (onSchedule) focusHeroButton(0)
                // 新番时间表按下: 进首行屏上第一列
                else -> if (!onSchedule) focusHeroButton(1) else rowKeys().firstOrNull()?.let { focusAtColumn(it, 0) }
            }
            return true
        }
        val from = pending ?: (rowKey to focusedCardIndex)
        if (vertical) {
            val keys = rowKeys()
            val row = keys.indexOf(from.first)
            if (row < 0) return true
            if (key == Key.DirectionUp && row == 0) {
                focusHeroButton(1)
                return true
            }
            val target = row + if (key == Key.DirectionUp) -1 else 1
            if (target in keys.indices) focusAtColumn(keys[target], focusedColumn)
            return true
        }
        val target = from.second + if (key == Key.DirectionLeft) -1 else 1
        if (target < 0) {
            // 上一下还没落地 (焦点还在往行首赶): 这一下不出页面
            if (pending == null) listener?.onExitLeft()
            return true
        }
        val count = (items.firstOrNull { it.key == from.first } as? TvNativeExploreItem.Row)?.cards?.size ?: 0
        if (target < count) {
            val columns = metrics.columns.coerceAtLeast(1)
            rowLeft[from.first] = tvStripLeftIndex(rowLeft[from.first] ?: 0, target, count, columns)
            pending = from.first to target
        }
        return true
    }

    /** 落到 [rowKey] 行屏上第 [column] 列那张 (行短了落到最后一张). */
    private fun focusAtColumn(rowKey: String, column: Int) {
        val row = items.firstOrNull { it.key == rowKey } as? TvNativeExploreItem.Row ?: return
        if (row.cards.isEmpty()) return
        val index = ((rowLeft[rowKey] ?: 0) + column).coerceIn(0, row.cards.size - 1)
        focusCard(rowKey, index)
    }

    // ---- hero 态 ----

    override val heroActive: Boolean get() = timeline.active

    /** 页面让退出 (hero 态里按返回): 按触屏的规矩走时同 [exitHeroInPlace]. */
    override fun setHeroActive(active: Boolean) = if (!active && touchMode) exitHeroInPlace() else setHeroActive(active, animated = true)

    /** 下一次退出 hero 态停在原地 (见 [exitHeroInPlace]). */
    private var stayInPlace = false

    fun consumeStayInPlace(): Boolean = stayInPlace.also { stayInPlace = false }

    /**
     * 触屏上退出 hero 态 (点卡片以外的地方、拖列表、按返回): hero 背景与文字照常淡出, 列表停在原地 (不滚回正中); 按触屏的规矩走时焦点停回
     * 入口节点 (见 [parkFocus]).
     */
    fun exitHeroInPlace() {
        if (!timeline.active) return
        if (touchMode) {
            parkFocus()
        } else {
            stayInPlace = true
            setHeroActive(false, animated = true)
        }
    }

    private fun setHeroActive(active: Boolean, animated: Boolean) {
        if (active && !heroEnabled) return
        if (timeline.active == active) return
        timeline.setActive(active, animated)
        state.heroActive = active
        listener?.onHeroActiveChanged(active)
    }

    override val rowLeftIndex: Map<String, Int> get() = rowLeft.toMap()

    override val rowFocusedIndexMap: Map<String, Int> get() = rowFocused.toMap()

    override fun setSources(carousel: TvNativeHeroSource, card: TvNativeHeroSource) {
        this.carousel = carousel
        this.card = card
    }

    override fun hasFocus(): Boolean = hasAnyFocus
}

/** 聚焦卡时轮播按钮挪到多远 (屏外, 点不到). */
private const val TV_WALL_BUTTONS_AWAY_PX = -100_000

/** 背景图在轮播与聚焦卡两套尺寸之间缩放的时长. */
private const val TV_WALL_BACKDROP_RESIZE_MILLIS = 400
