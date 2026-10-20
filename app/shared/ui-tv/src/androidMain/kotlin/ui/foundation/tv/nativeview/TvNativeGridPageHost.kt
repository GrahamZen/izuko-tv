/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.preference.TvBackdropBlurLevel
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.NativeSendFocusEffect
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.rememberNsfwPolicy
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.LocalTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvPosterWallBackground
import me.him188.ani.app.ui.foundation.tv.wall.TvWallGridPage

/*
 * 网格页 (追番 / 搜索) 原生海报墙在 Compose 这一侧的接线, 两页共用: 建视图、换数据、把页面的网格送焦请求 (TvGridFocusState) 转给原生、
 * 长按菜单的锚点、放大转场缩回期间的标题. 页面只给数据映射、hero 内容与事件回调.
 */

/** 安卓上画面就是原生视图. */
internal val TvNativeGridPageState.view: TvNativeGridPageView? get() = surface as? TvNativeGridPageView

/**
 * 安卓上一组一行的内容 ([TvNativeGridPageRows]) 的卡片、几何 (从页顶算) 与组标题.
 */
@Immutable
internal class TvNativeGridPageRowsSpec(
    override val key: Int,
    val style: TvNativeWallStyle,
    val metrics: TvNativeExploreMetrics,
    val headerStyle: TvNativeTextStyle,
) : TvNativeGridPageRows

@Composable
internal actual fun <T : Any> TvNativeGridPageHostContent(
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
    labelVibrancy: Boolean,
    rows: TvNativeGridPageRows?,
    emptyContent: @Composable BoxScope.() -> Unit,
) {
    // 调试开关: 换成 Compose 版 (iOS 上画的那一套) 对照
    if (DevSwitches.tvComposeWalls.value) {
        TvWallGridPage(
            state, metrics, cardWidth, gridKey, slideDirection, cards, itemAt, itemCount, onBind, heroEnabled, badge, source, fadeColor,
            treatment, gridFocus, farJump, onFarJumpConsumed, callbacks, menuFor, wallBackdrop, modifier, landingIndex, topBarScrollAwayPx,
            columnSpacing, cardHeight, emptyContent,
        )
        return
    }
    val sketch = LocalSketch.current
    val scope = rememberCoroutineScope()
    val composeRoot = LocalView.current
    val density = LocalDensity.current
    // hero 态在整页底下铺模糊背景, 按确定对焦变清晰再进详情页 (设置里的开关, 见 TvNativeGridPageView.heroBlur); 没有 hero 态的新番时间表不管它
    val heroBlur = heroEnabled && LocalThemeSettings.current.tvWallBackdropBlur != TvBackdropBlurLevel.None
    // 新番时间表的模糊背景恒铺, 海报墙选了不模糊时按中 (见 ThemeSettings.tvScheduleBlurLevel); 开着 hero 模糊时两者同一档
    val blurSpec = tvBackdropBlurSpec(LocalThemeSettings.current.tvScheduleBlurLevel)
    // 铺着模糊背景时卡片的字也照 vibrancy 画 (深色主题, 见 TvNativeWallStyle.labelVibrancy)
    val style = rememberTvNativeWallStyle(cardWidth, metrics.grid.columns, badge, columnSpacing, cardHeight, labelVibrancy || heroBlur)
    val textStyle = rememberTvNativeHeroTextStyle(titleMaxLines = 2, lineSpacing = 8.dp)
    val visualEffects = LocalThemeSettings.current.visualEffects
    // 「海报上按确定」(见 TvPosterConfirmAction): 直接播放时按确定当场交给页面播 (新番时间表不先对焦)
    val posterConfirm = LocalThemeSettings.current.tvPosterConfirm
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // 模糊背景的压暗: 深色主题照 Apple TV 分两档 (卡片墙底色 20%, 主色很亮的图 55%, 见 TV_HERO_BLUR_DIM_ALPHA); 浅色主题是卡片墙的底色 +
    // 起步的透明度, 按 hero 标题的颜色压到看得清 (同新番时间表按页面底色压). 铺着模糊背景时 hero 态不压黑, 整屏底色一直是卡片墙那档
    // (见 TvNativeGridPageView.heroBlur)
    val heroBlurMask = tvPosterWallBackground().copy(alpha = if (dark) TV_HERO_BLUR_DIM_ALPHA else TV_FULLSCREEN_BACKDROP_DIM_ALPHA)
    val heroBlurText = tvHeroContentColor()
    // 卡片区在滚动 / 方向键按住: 背景图的剧照升档等它们都停了才去取原图 (见 TvNativeBackdropView.navigating)
    val scrollActivity = LocalTvScrollActivity.current
    val navKeys = LocalTvNavKeyTracker.current
    val touchInput = LocalTvTouchInputEnabled.current
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentItemAt by rememberUpdatedState(itemAt)
    val currentItemCount by rememberUpdatedState(itemCount)
    val currentOnBind by rememberUpdatedState(onBind)

    // 焦点停放在网格页上 (换标签途中, 见 TvNativeGridPageView): 送焦请求没了 (新标签是空的被取消 / 超时) 就落不了地, 当场交给页面回顶栏
    // (追番页 = 选中的标签). 不然焦点一直停在网格页自己身上, 画面上哪儿都不亮, 要再按一下才回到标签
    var nativeParked by remember { mutableStateOf(false) }
    LaunchedEffect(gridFocus) {
        snapshotFlow { nativeParked && !gridFocus.switching }.collect { stranded ->
            if (stranded) currentCallbacks.onTopRowUp()
        }
    }

    var pagePosition by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { pagePosition = it.positionInWindow() }) {
        TvNativeHost(
            factory = { context ->
                TvNativeGridPageView(context, sketch, scope, style, metrics, textStyle).also { view ->
                    if (wallBackdrop != null) view.enableWallBackdrop()
                    view.composeRoot = composeRoot
                    view.restorePositions(state.savedPositions)
                    // 返回本页 (重建) 时焦点要回的那张: 排出来就按住聚焦态 (见 TvNativeGridPageView.holdLandingLook)
                    if (landingIndex >= 0) view.holdLandingLook(landingIndex)
                    view.heroText.onShownSubjectChanged = { state.titleSubjectId = it }
                    state.surface = view
                }
            },
            update = { view ->
                view.listener = object : TvNativeGridPageListener {
                    override fun onFocused(index: Int) {
                        currentCallbacks.onCardFocused(index, currentItemAt(index))
                        gridFocus.onNativeItemFocused(index)
                        state.capture()
                    }

                    override fun onClick(index: Int) {
                        currentItemAt(index)?.let { item ->
                            val play = currentCallbacks.onCardPlay.takeIf { posterConfirm == TvPosterConfirmAction.Play }
                            if (play != null) play(index, item) else currentCallbacks.onCardClick(index, item)
                        }
                    }

                    override fun onLongPress(index: Int, anchor: AndroidRect) {
                        val item = currentItemAt(index)
                        // 弹不出菜单: 原生那边已经进了长按的对焦, 当场倒放
                        if (item != null) state.menu = item to anchor.toComposeRect() else view.endWallPeek()
                    }

                    override fun onTopRowUp(): Boolean = currentCallbacks.onTopRowUp()

                    override fun onRowEdge(direction: Int, row: Int): Boolean = currentCallbacks.onRowEdge(direction, row)

                    override fun onHeroActiveChanged(active: Boolean) {
                        state.heroActive = active
                    }

                    override fun onToneChanged(tone: Float) {
                        // 整屏底色与原生视图同一帧变: 写完当场派发
                        if (state.tone != tone) tvNativeWriteSnapshot { state.tone = tone }
                    }

                    override fun onScrollingChanged(scrolling: Boolean) {
                        currentCallbacks.onScrollingChanged(scrolling)
                        if (!scrolling) state.capture()
                    }

                    override fun onGridFocusChanged(hasFocus: Boolean) = currentCallbacks.onGridFocusChanged(hasFocus)

                    override fun onFocusParkedChanged(parked: Boolean) {
                        gridFocus.onNativeFocusParked(parked)
                        nativeParked = parked
                    }

                    override fun onWallOpeningChanged(opening: Boolean) {
                        state.wallOpening = opening
                    }

                    override fun onWallOpened(index: Int) {
                        state.wallOpenIndex = index
                        state.wallOpenLeft = false
                    }

                    override fun onWallFade(fade: Float) {
                        // 顶栏与卡片同一帧淡: 写完当场派发
                        if (state.wallFade != fade) tvNativeWriteSnapshot { state.wallFade = fade }
                    }

                    override fun onContentScrolled(offsetPx: Int) {
                        // 顶栏与网格同一帧挪: 写完当场派发
                        if (state.contentScroll != offsetPx) tvNativeWriteSnapshot { state.contentScroll = offsetPx }
                    }
                }
                view.onBindCard = { index -> currentOnBind(index) }
                view.heroEnabled = heroEnabled
                view.onTouchSwipe = if (touchInput && callbacks.onSwipe != null) { delta -> currentCallbacks.onSwipe?.invoke(delta) } else null
                view.touchInput = touchInput
                view.confirmFocusesWall = !(posterConfirm == TvPosterConfirmAction.Play && callbacks.onCardPlay != null)
                view.contentScrollLimitPx = topBarScrollAwayPx
                view.transitions = visualEffects.transitions
                view.animatedScroll = visualEffects.animatedScroll
                view.backdropNavigating = { scrollActivity?.isScrolling == true || navKeys?.held == true }
                view.dark = dark
                view.fadeColor = fadeColor.toArgb()
                view.treatment = treatment
                view.heroBlur = heroBlur
                view.wallBackdrop?.let { wb ->
                    wallBackdrop?.let {
                        wb.maskColor = it.maskColor.toArgb()
                        wb.textColor = it.textColor.toArgb()
                    }
                    if (heroBlur) {
                        wb.maskColor = heroBlurMask.toArgb()
                        wb.textColor = heroBlurText.toArgb()
                        wb.brightMaskAlpha = if (dark) TV_HERO_BLUR_BRIGHT_DIM_ALPHA else Float.NaN
                    }
                    wb.blur = blurSpec
                    wb.coverWidthPx = style.coverWidthPx
                    wb.coverHeightPx = style.coverHeightPx
                    wb.crossfade = visualEffects.transitions
                }
                view.update(style, metrics, textStyle)
            },
            // 海报墙大小一变整块重建: 卡片视图的尺寸建好就定了. 各份网格的位置存在 state 里, 新视图照返回本页那样恢复 (见 factory)
            rebuildKey = LocalTvPosterWallScale.current,
        )
        emptyContent()
        // 长按卡片的收藏菜单: 锚在那张卡的封面上 (原生视图报上来的封面框, 窗口坐标)
        // 收起时照样组合着上一次的目标, 菜单淡完才撤 (见 rememberTvMenuTarget)
        rememberTvMenuTarget(state.menu)?.let { (item, rect) ->
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

    // NSFW 设为模糊时按卡片 / hero 的条目 id 打码 (见 withNsfw): 各页只管给卡片填 subjectId
    val nsfw = rememberNsfwPolicy()
    val shownCards = remember(cards, nsfw.snapshot) { cards.withNsfw(nsfw) }
    val shownSource = source?.withNsfw(nsfw)
    val view = state.view
    SideEffect {
        if (view != null) {
            // 一组一行的内容: 建出来 (样式与几何跟着换), 交给页面接数据
            val rowsView = (rows as TvNativeGridPageRowsSpec?)?.let { view.ensureRows(it.style, it.metrics, it.headerStyle) }
            if (state.rows !== rowsView) state.rows = rowsView
            val previous = view.currentKey as? Int
            val direction = if (previous == null || previous == gridKey) 0 else slideDirection(previous, gridKey)
            if (rows != null && gridKey == rows.key) {
                view.showRows(gridKey, direction, animated = visualEffects.transitions)
            } else {
                view.showGrid(gridKey, direction, animated = visualEffects.transitions)
                view.setCards(gridKey, shownCards)
            }
            shownSource?.let { view.setSource(it) }
        }
    }
    // 返回本页时恢复在 hero 态 (直接到位); 没有 hero 态 (「海报上按确定」改成了别的档) 时撤掉存着的 hero 态, 返回键不再先回卡片墙
    LaunchedEffect(view, heroEnabled) {
        if (!heroEnabled) {
            state.heroActive = false
        } else if (view != null && state.heroActive && !view.heroActive) {
            view.setHeroActive(true, animated = false)
        }
    }

    if (wallBackdrop != null || heroBlur) TvNativeWallBackdropEffects(state, view, wallBackdrop)

    // 页面的网格送焦请求 (进页恢复 / 顶栏下键 / 换标签落点 / 返回键回首卡) 交给原生送焦
    gridFocus.NativeSendFocusEffect(
        columns = { metrics.grid.columns },
        itemCount = { currentItemCount() },
        focusNative = { index ->
            val v = state.view
            when {
                v == null -> false
                farJump() -> {
                    onFarJumpConsumed()
                    v.farJumpTo(index)
                    false
                }

                else -> {
                    val already = v.grid?.findViewHolderForAdapterPosition(index)?.itemView?.isFocused == true
                    if (!already) v.focusItem(index)
                    already
                }
            }
        },
    )

    // 放大 / 缩回期间的标题: 绘制权在详情页那份 (放大) 或转场层 (缩回) 手里时隐藏, 否则按缩回让位平移; 缩回途中停跑马灯
    // (见 TvNativeHeroTextView.setTitleHandoff)
    LaunchedEffect(state) {
        snapshotFlow {
            val id = state.titleSubjectId
            val v = state.view
            if (id == null || v == null) {
                null
            } else {
                TvNativeGridTitleHandoff(
                    v,
                    TvHeroZoomHandoff.titleOwnedByOverlay(id) || TvHeroZoomHandoff.titleOwnedByDetails(id),
                    TvHeroZoomHandoff.shrinkTitleOffset(id),
                    TvHeroZoomHandoff.titleSettling(id),
                )
            }
        }.collect { h ->
            h?.view?.heroText?.setTitleHandoff(h.hidden, h.offset?.x ?: 0f, h.offset?.y ?: 0f, h.settling)
        }
    }
}

/**
 * 整屏背景 (新番时间表 / hero 态的模糊背景) 在 Compose 这一侧的接线: 新番时间表按停稳后的聚焦条目换图 ([spec]; hero 态的模糊背景由视图按
 * hero 内容里的 TvNativeHeroSource.wall 自己换, 为 null); 长按的菜单关了倒放; 点开进了详情页、回到本页之后倒放, 点开途中按返回取消
 * (见 [TvNativeWallOpenEffects]).
 */
@Composable
private fun TvNativeWallBackdropEffects(state: TvNativeGridPageState, view: TvNativeGridPageView?, spec: TvNativeWallBackdropSpec?) {
    val currentSpec by rememberUpdatedState(spec)
    // NSFW 设为模糊时不对焦变清晰 (见 withNsfw); 快照读, 设置一变重发
    val nsfw = rememberNsfwPolicy()
    LaunchedEffect(view, spec != null) {
        if (view == null || spec == null) return@LaunchedEffect
        snapshotFlow { currentSpec?.target?.invoke()?.withNsfw(nsfw) }.collect { view.setWallTarget(it) }
    }
    LaunchedEffect(view) {
        if (view == null) return@LaunchedEffect
        snapshotFlow { state.menu == null }.collect { closed -> if (closed) view.endWallPeek() }
    }
    TvNativeWallOpenEffects(
        view = view,
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

private data class TvNativeGridTitleHandoff(
    val view: TvNativeGridPageView,
    val hidden: Boolean,
    val offset: Offset?,
    val settling: Boolean,
)

/** 越界取 null ([LazyPagingItems.peek] 是直接下标访问, 越界当场抛). */
