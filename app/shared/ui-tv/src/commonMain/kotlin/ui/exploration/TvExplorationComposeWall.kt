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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.ui.foundation.focus.TvScrollSpring
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_FADE_IN_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_FADE_OUT_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_HERO_SPLIT_BAND
import me.him188.ani.app.ui.foundation.tv.TvHeroButton
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropGeometry
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
import me.him188.ani.app.ui.foundation.tv.wall.MenuHolder
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_CAROUSEL_BUTTONS_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_TEXT_BOTTOM
import me.him188.ani.app.ui.foundation.tv.wall.TV_PAD_HERO_WALL_SUMMARY_LINES
import me.him188.ani.app.ui.foundation.tv.wall.TvPadHeroLayer
import me.him188.ani.app.ui.foundation.tv.wall.TvWallBlurBackdrop
import me.him188.ani.app.ui.foundation.tv.wall.TvWallHeroBackdrop
import me.him188.ani.app.ui.foundation.tv.wall.TvWallHeroText
import me.him188.ani.app.ui.foundation.tv.wall.TvWallHeroTimeline
import me.him188.ani.app.ui.foundation.tv.wall.TvWallOpen
import me.him188.ani.app.ui.foundation.tv.wall.TvWallRowsList
import me.him188.ani.app.ui.foundation.tv.wall.TvWallRowsSurface
import me.him188.ani.app.ui.foundation.tv.wall.TvWallSharpLayer
import me.him188.ani.app.ui.foundation.tv.wall.rememberTvWallCardStyle
import me.him188.ani.app.ui.foundation.tv.wall.rememberTvWallHeroTimeline
import me.him188.ani.app.ui.foundation.tv.wall.tvWallBlankTap
import me.him188.ani.app.ui.foundation.tv.wall.tvWallHorizontalSwipe
import me.him188.ani.app.ui.foundation.tv.wall.tvWallInputMode
import me.him188.ani.app.ui.foundation.tv.wall.tvWallPlace
import me.him188.ani.app.ui.foundation.tv.wall.wallCenteredScroll
import me.him188.ani.app.ui.foundation.tv.wall.wallItemTop
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
    // 继续观看那一行: 有新集的画没看几集的数字角标 (见 rememberTvExplorationNativeItems)
    val style = rememberTvWallCardStyle(cardWidth, countBadge = true)
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
    surface.setItems(items)
    surface.metrics = metrics
    surface.animatedScroll = visualEffects.animatedScroll
    surface.headerIdleAlpha = headerIdleAlpha
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
    // 页面的程序化落点 (进页恢复 / 返回键分层 / 回到主界面): 请求按实例区分, 同参重发也会再送一次
    LaunchedEffect(surface) {
        snapshotFlow { cardFocusRequest() }.collect { req ->
            if (req != null) surface.focusCard(req.rowKey, req.cardIndex, far = req.rowKey == farJumpRow())
        }
    }
    LaunchedEffect(surface) {
        snapshotFlow { heroFocusRequest() }.collect { req -> if (req != null) surface.focusHeroButton(req.button.ordinal) }
    }

    val watchNow = stringResource(Lang.exploration_tv_watch_now)
    val schedule = stringResource(Lang.exploration_schedule)
    var pagePosition by remember { mutableStateOf(Offset.Zero) }
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
                        // 跟着列表一起滚 (轮播区就是列表顶上那一段); 与聚焦卡互换时随 mode 淡入淡出
                        translationY = -surface.scrolledPx * (1f - mode.value)
                        alpha = surface.carouselAlpha() * (1f - mode.value)
                    },
                // 同电视: 往下翻时图比文字与列表多上移一截 (见 carouselShift), 两者错开
                backdropShift = { -(surface.carouselShift() - surface.scrolledPx) * (1f - mode.value) },
            )
        }
        // 平板版画法的 hero 态: 同轮播一套画法, 图铺到聚焦行的卡片顶 (组标题压在图下缘的渐隐里, 同网格页), 文字排在组标题之上;
        // 跟着 hero 时间线显隐 (底下铺着整页模糊背景时不另铺模糊底). 回轮播时 (换过去的那段, 见 mode) 随 mode 淡出, 轮播在它底下淡入
        val padCardLeaving by remember(mode) { derivedStateOf { mode.value > 0f } }
        if (padCarousel && (showsCard || padCardLeaving)) {
            TvPadHeroLayer(
                surface.card,
                textStart = with(density) { (metrics.bleedLeftPx + metrics.heroStartPx).toDp() },
                textBottom = TV_PAD_HERO_TEXT_BOTTOM + with(density) { metrics.headerPx.toDp() },
                pageWidth = with(density) { metrics.pageWidthPx.toDp() },
                modifier = Modifier.tvWallPlace(
                    x = 0,
                    y = 0,
                    width = -1,
                    height = metrics.listTopPx + metrics.heroHeaderTopPx + metrics.headerPx,
                ),
                // 没有按钮, 多放几行简介
                summaryMaxLines = TV_PAD_HERO_WALL_SUMMARY_LINES,
                ownBlur = !heroBlur,
                // 点开时跟着卡片淡没, 露出底下变清晰的那张
                backdropAlpha = { timeline.content * (1f - wallOpen.fade.value) * (if (showsCard) 1f else mode.value) },
                textAlpha = { timeline.text * (1f - wallOpen.fade.value) * (if (showsCard) 1f else mode.value) },
            )
        }

        // 卡片列表 (行式海报墙, 见 TvWallRowsList): 点开时跟着淡没
        TvWallRowsList(surface, items, style, headerStyle, extraFade = { 1f - wallOpen.fade.value })

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

/**
 * 探索页海报墙的画面 (Compose 版): 行式海报墙 ([TvWallRowsSurface]) 加上热门轮播与两颗 hero 按钮; 给页面的操作见 [TvExploreSurface].
 * 焦点停放在页面的入口节点上 ([TvExplorationNativeState.focusRequester]).
 */
@Stable
private class TvExploreComposeSurface(
    private val state: TvExplorationNativeState,
    timeline: TvWallHeroTimeline,
    scope: CoroutineScope,
    restoredRowKey: String?,
    restoredIndex: Int,
) : TvWallRowsSurface(
    timeline, scope, state.focusRequester, state.savedScrollPx, state.savedRowLeft, state.savedRowFocused, restoredRowKey, restoredIndex,
    state.savedRowScroll,
), TvExploreSurface {
    override val logTag: String get() = "ExploreRows"

    override val rowScrollPositions: Map<String, Pair<Int, Int>> get() = currentRowScroll

    var listener: TvNativeExploreListener?
        get() = rowListener as? TvNativeExploreListener
        set(value) {
            rowListener = value
        }

    /** 点开 (铺着模糊背景时 hero 态里按确定); null = 没铺模糊背景, 当场进. */
    var wallOpen: TvWallOpen? = null
    private var lastHeroButton = 0
    val primaryRequester = FocusRequester()
    val scheduleRequester = FocusRequester()

    var carousel: TvNativeHeroSource? by mutableStateOf(null)
        private set
    var card: TvNativeHeroSource? by mutableStateOf(null)
        private set

    // ---- 轮播 ----

    fun carouselShift(): Float {
        val first = items.indexOfFirst { it is TvNativeExploreItem.Row }
        val firstRowStop = if (first < 0) metrics.spacerPx else metrics.wallCenteredScroll(items, first)
        return tvWallCarouselShift(scrolledPx.toFloat(), firstRowStop, metrics.overhangPx)
    }

    fun carouselAlpha(): Float = if (focusedRowKey != null) 1f - timeline.above else 1f

    /** 首行卡片的顶边此刻在页面里的位置 (px); 没有卡片行时为无穷大. */
    fun firstRowTopPx(): Float {
        val first = items.indexOfFirst { it is TvNativeExploreItem.Row }
        if (first < 0) return Float.MAX_VALUE
        return (metrics.listTopPx + metrics.wallItemTop(items, first) - scrolledPx).toFloat()
    }

    // ---- 焦点 ----

    /**
     * 从页面外进来 (入口节点拿到焦点): 回上次那张卡 (没排出来就先滚过去, 排出来再送), 否则回上次停的那颗 hero 按钮. 按触屏的规矩走时卡片墙上
     * 焦点就停在入口节点上 (见 parkFocus).
     */
    fun enter() {
        log { "入口拿到焦点 (hero=${timeline.active}, 触屏=$touchMode, 行=$focusedRowKey)" }
        val rowKey = focusedRowKey
        when {
            touchMode && !timeline.active -> parkFocus(request = false)
            rowKey != null && rowKey in rowKeys() -> focusCard(rowKey, -1)
            else -> focusHeroButton(lastHeroButton)
        }
    }

    /** 停放好了 (hero 态淡完): 对页面报焦点在上次那颗 hero 按钮上 (页面回到轮播态, 轮播照常翻). */
    override fun reportParked() {
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
        if (timeline.active) setHeroActive(false, animated = true)
        scrollTo(0, TvScrollSpring.Far)
    }

    /** 焦点在 hero 按钮上时的方向键. */
    override fun handleKeyOffCards(key: Key): Boolean {
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

    /** 首行按上回「新番时间表」. */
    override fun onFirstRowUp() = focusHeroButton(1)

    /** 进详情页: 铺着模糊背景、背景此刻是这张卡的、能变清晰就先对焦, 到位再进. */
    override fun openCard(rowKey: String, index: Int) {
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

    // ---- hero 态 ----

    override fun applyHeroActive(active: Boolean, animated: Boolean) {
        timeline.setActive(active, animated)
        state.heroActive = active
        listener?.onHeroActiveChanged(active)
    }

    /** 页面让退出 (hero 态里按返回): 按触屏的规矩走时同 [exitHeroInPlace]. */
    override fun setHeroActive(active: Boolean) = if (!active && touchMode) exitHeroInPlace() else setHeroActive(active, animated = true)

    /**
     * 触屏上从第一排的 hero 态退出: 同电视在首行按上回轮播 ([onHeroButtonFocused]) —— 那一排当场让出, 聚焦卡还看得见时就换到轮播
     * (两层交叉淡入, 见 mode), 列表滚回顶 ([scrollToTop]; 手指拖动引起的不滚, 由手指带着). 焦点停回入口节点 (同 parkFocus).
     */
    override fun exitHeroToTop(scrollToTop: Boolean): Boolean {
        val key = focusedRowKey ?: return false
        val first = items.indexOfFirst { it is TvNativeExploreItem.Row }
        if (first < 0 || items[first].key != key) return false
        pending = null
        deferred = null
        heldFocus = null
        parkJob?.cancel()
        scope.launch { runCatching { parkRequester.requestFocus() } }
        // 退出时的停位由这里定 (滚回顶或由手指带), 不按卡片墙的停位滚
        stayInPlace = true
        setHeroActive(false, animated = true)
        reportParked()
        if (scrollToTop) scrollTo(0, TvScrollSpring.Far)
        return true
    }

    override fun setSources(carousel: TvNativeHeroSource, card: TvNativeHeroSource) {
        this.carousel = carousel
        this.card = card
    }
}

/** 聚焦卡时轮播按钮挪到多远 (屏外, 点不到). */
private const val TV_WALL_BUTTONS_AWAY_PX = -100_000

/** 背景图在轮播与聚焦卡两套尺寸之间缩放的时长. */
private const val TV_WALL_BACKDROP_RESIZE_MILLIS = 400
