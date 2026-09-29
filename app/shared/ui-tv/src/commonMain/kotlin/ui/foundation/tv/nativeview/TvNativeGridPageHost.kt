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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.NativeSendFocusEffect
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff

/*
 * 网格页 (追番 / 搜索) 原生海报墙在 Compose 这一侧的接线, 两页共用: 建视图、换数据、把页面的网格送焦请求 (TvGridFocusState) 转给原生、
 * 长按菜单的锚点、放大转场缩回期间的标题. 页面只给数据映射、hero 内容与事件回调.
 */

/**
 * 网格页原生海报墙在页面这一侧的状态: 视图引用、hero 态 (跨导航保存, 返回本页时停在 hero 态)、整屏黑度、各份网格的位置 (页面重建时恢复)、
 * 长按菜单锚点、整屏背景的点开 (新番时间表, 跨导航保存: 返回时页面重建也从清晰图起).
 */
@Stable
class TvNativeGridPageState internal constructor(
    heroActive: Boolean,
    internal val savedPositions: HashMap<Int, Int>,
    wallOpenIndex: Int,
) {
    var view: TvNativeGridPageView? by mutableStateOf(null)
        internal set

    /** hero 态 (页面的返回键按它开关). */
    var heroActive: Boolean by mutableStateOf(heroActive)
        internal set

    /** 整屏黑度 (hero 态进度), 画整屏底色的那一层读. */
    var tone: Float by mutableFloatStateOf(0f)
        internal set

    internal var titleSubjectId: Int? by mutableStateOf(null)
    /** 长按菜单的锚点: 那一项 (页面的列表项), 封面在窗口里的框. */
    internal var menu: Pair<Any, AndroidRect>? by mutableStateOf(null)

    /** 整屏背景点开途中 (对焦还没到位、还没进详情页): 返回键取消点开. */
    internal var wallOpening: Boolean by mutableStateOf(false)

    /** 整屏背景点开进了详情页的那张 (-1 = 没有): 回到本页倒放. */
    internal var wallOpenIndex: Int by mutableIntStateOf(wallOpenIndex)

    /** 点开之后本页离开过前台 (真进了详情页); 恢复出来的状态算离开过. */
    internal var wallOpenLeft: Boolean = wallOpenIndex >= 0

    /** 点开时顶栏等 Compose 部件跟着卡片淡没的程度 (0..1), 页面在绘制里读. */
    var wallFade: Float by mutableFloatStateOf(0f)
        internal set

    /** 退出 hero 态 (返回键). */
    fun exitHero() {
        view?.setHeroActive(false)
        heroActive = false
    }

    internal fun capture() {
        val v = view ?: return
        v.savedPositions().forEach { (key, pos) -> (key as? Int)?.let { savedPositions[it] = pos } }
        heroActive = v.heroActive
    }
}

@Composable
fun rememberTvNativeGridPageState(): TvNativeGridPageState =
    rememberSaveable(saver = TvNativeGridPageStateSaver) {
        TvNativeGridPageState(heroActive = false, savedPositions = HashMap(), wallOpenIndex = -1)
    }

private val TvNativeGridPageStateSaver = Saver<TvNativeGridPageState, ArrayList<Any>>(
    save = { state ->
        state.capture()
        arrayListOf(state.heroActive, ArrayList(state.savedPositions.keys), ArrayList(state.savedPositions.values), state.wallOpenIndex)
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        val keys = list[1] as List<Int>

        @Suppress("UNCHECKED_CAST")
        val values = list[2] as List<Int>
        TvNativeGridPageState(
            heroActive = list[0] as Boolean,
            savedPositions = HashMap(keys.zip(values).toMap()),
            wallOpenIndex = list.getOrNull(3) as? Int ?: -1,
        )
    },
)

/**
 * 网格页海报墙底下的整屏背景 (新番时间表, 见 TvNativeWallBackdropView 与 TvNativeGridPageView 的对焦一节): [target] = 此刻该铺哪张
 * (页面按停稳后的聚焦条目算; 在协程里读, 读到的快照状态变了就换), [maskColor] = 烘进模糊图里的整屏压暗 (页面底色 + 透明度).
 */
class TvNativeWallBackdropSpec(
    val target: () -> TvNativeWallBackdropTarget?,
    val maskColor: Color,
)

/** 原生网格的事件, 由页面给 (焦点簿记、hero、导航). [T] = 列表项. */
class TvNativeGridPageCallbacks<T : Any>(
    val onCardFocused: (index: Int, item: T?) -> Unit,
    val onCardClick: (index: Int, item: T) -> Unit,
    val onTopRowUp: () -> Boolean,
    val onRowEdge: (direction: Int, row: Int) -> Boolean = { _, _ -> false },
    val onGridFocusChanged: (Boolean) -> Unit,
    val onScrollingChanged: (Boolean) -> Unit,
)

/**
 * 网格页的原生海报墙: [gridKey] 那份网格显示 [items] (分页快照, 用 [cardOf] 换成卡片), 换 [gridKey] 时新旧两份按 [slideDirection] 水平滑过
 * (追番页换标签; 搜索页恒一份). 页面的网格送焦请求 ([gridFocus]) 转给原生送焦 ([farJump] 为 true 的那一发是返回键回首卡的远跳).
 * [emptyContent] 画在网格上面 (空列表提示 / 首屏加载), 由页面决定何时出现. [landingIndex] = 返回本页 (页面重建) 时进页落点要回的那张 (-1 = 不回网格):
 * 建视图时就按住它的聚焦态, 焦点到了不再放大一遍 (只在建视图那一次读).
 */
@Composable
fun <T : Any> TvNativeGridPageHost(
    state: TvNativeGridPageState,
    metrics: TvNativeGridPageMetrics,
    cardWidth: Dp,
    gridKey: Int,
    slideDirection: (from: Int, to: Int) -> Int,
    items: LazyPagingItems<T>,
    cardsKey: Any?,
    cardOf: (T) -> TvNativeCard,
    source: TvNativeHeroSource?,
    fadeColor: Color,
    treatment: TvBackdropTreatment,
    gridFocus: TvGridFocusState,
    farJump: () -> Boolean,
    onFarJumpConsumed: () -> Unit,
    callbacks: TvNativeGridPageCallbacks<T>,
    menuFor: (T) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    landingIndex: Int = -1,
    emptyContent: @Composable BoxScope.() -> Unit = {},
) {
    val currentItems by rememberUpdatedState(items)
    val currentCardOf by rememberUpdatedState(cardOf)
    val snapshot = items.itemSnapshotList
    val cards = remember(snapshot, cardsKey) { snapshot.map { it?.let(currentCardOf) } }
    TvNativeGridPageHostContent(
        state, metrics, cardWidth, gridKey, slideDirection, cards,
        itemAt = { index -> currentItems.peekAt(index) },
        itemCount = { currentItems.itemCount },
        // 分页的访问提示: 绑到哪张, 分页就往后取到哪 (读一次 items[index] 就是向分页报告访问到了这里)
        onBind = { index -> if (index in 0 until currentItems.itemCount) currentItems[index] },
        heroEnabled = true, badge = null, source, fadeColor, treatment, gridFocus, farJump, onFarJumpConsumed, callbacks, menuFor,
        wallBackdrop = null, modifier, landingIndex, emptyContent,
    )
}

/**
 * 网格页的原生海报墙, 数据是一份现成的列表 (新番时间表的一天): [items] 与 [cards] 一一对应, null 是还没到的占位. 页面的网格送焦请求只认
 * 前 [focusableCount] 张 (占位期间给 0: 占位卡不收落点, 等真数据). [heroEnabled] = false 时没有 hero 态: 卡片墙上按确定直接进详情页
 * ([TvNativeGridPageCallbacks.onCardClick]). [badge] 给了就按卡片的 [TvNativeCard.badge] 在封面右上角画角标.
 * 其余同分页那一版.
 */
@Composable
fun <T : Any> TvNativeGridPageHost(
    state: TvNativeGridPageState,
    metrics: TvNativeGridPageMetrics,
    cardWidth: Dp,
    gridKey: Int,
    slideDirection: (from: Int, to: Int) -> Int,
    items: List<T?>,
    cards: List<TvNativeCard?>,
    focusableCount: () -> Int,
    heroEnabled: Boolean,
    badge: TvNativeCardBadgeStyle?,
    fadeColor: Color,
    treatment: TvBackdropTreatment,
    gridFocus: TvGridFocusState,
    farJump: () -> Boolean,
    onFarJumpConsumed: () -> Unit,
    callbacks: TvNativeGridPageCallbacks<T>,
    menuFor: (T) -> @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    wallBackdrop: TvNativeWallBackdropSpec? = null,
    modifier: Modifier = Modifier,
    landingIndex: Int = -1,
    emptyContent: @Composable BoxScope.() -> Unit = {},
) {
    val currentItems by rememberUpdatedState(items)
    TvNativeGridPageHostContent(
        state, metrics, cardWidth, gridKey, slideDirection, cards,
        itemAt = { index -> currentItems.getOrNull(index) },
        itemCount = focusableCount,
        onBind = {},
        heroEnabled = heroEnabled, badge = badge, source = null, fadeColor, treatment, gridFocus, farJump, onFarJumpConsumed, callbacks,
        menuFor, wallBackdrop, modifier, landingIndex, emptyContent,
    )
}

/** 两个入口共用的接线: [itemAt] 取第几项 (越界 / 占位为 null), [itemCount] 是送焦认的张数, [onBind] 是绑卡时的访问提示. */
@Composable
private fun <T : Any> TvNativeGridPageHostContent(
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
    emptyContent: @Composable BoxScope.() -> Unit,
) {
    val sketch = LocalSketch.current
    val scope = rememberCoroutineScope()
    val composeRoot = LocalView.current
    val density = LocalDensity.current
    val style = rememberTvNativeWallStyle(cardWidth, metrics.grid.columns, badge)
    val textStyle = rememberTvNativeHeroTextStyle(titleMaxLines = 2, lineSpacing = 8.dp)
    val visualEffects = LocalThemeSettings.current.visualEffects
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
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
                    state.view = view
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
                        currentItemAt(index)?.let { currentCallbacks.onCardClick(index, it) }
                    }

                    override fun onLongPress(index: Int, anchor: AndroidRect) {
                        val item = currentItemAt(index)
                        // 弹不出菜单: 原生那边已经进了长按的对焦, 当场倒放
                        if (item != null) state.menu = item to anchor else view.endWallPeek()
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
                }
                view.onBindCard = { index -> currentOnBind(index) }
                view.heroEnabled = heroEnabled
                view.transitions = visualEffects.transitions
                view.animatedScroll = visualEffects.animatedScroll
                view.dark = dark
                view.fadeColor = fadeColor.toArgb()
                view.treatment = treatment
                view.wallBackdrop?.let { wb ->
                    wallBackdrop?.let { wb.maskColor = it.maskColor.toArgb() }
                    wb.coverWidthPx = style.coverWidthPx
                    wb.coverHeightPx = style.coverHeightPx
                    wb.crossfade = visualEffects.transitions
                }
                view.update(style, metrics, textStyle)
            },
            bleedLeft = with(LocalDensity.current) { metrics.bleedLeftPx.toDp() },
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
                    .size(with(density) { rect.width().toDp() }, with(density) { rect.height().toDp() }),
            ) {
                menu(state.menu != null) { state.menu = null }
            }
        }
    }

    val view = state.view
    SideEffect {
        if (view != null) {
            val previous = view.currentKey as? Int
            val direction = if (previous == null || previous == gridKey) 0 else slideDirection(previous, gridKey)
            view.showGrid(gridKey, direction, animated = visualEffects.transitions)
            view.setCards(gridKey, cards)
            source?.let { view.setSource(it) }
        }
    }
    // 返回本页时恢复在 hero 态 (直接到位)
    LaunchedEffect(view) {
        if (view != null && state.heroActive && !view.heroActive) view.setHeroActive(true, animated = false)
    }

    if (wallBackdrop != null) TvNativeWallBackdropEffects(state, view, wallBackdrop)

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
 * 整屏背景 (新番时间表) 在 Compose 这一侧的接线: 停稳后的聚焦条目换图; 长按的菜单关了倒放; 点开进了详情页, 回到本页 (栈顶) 且缩回层
 * 撤掉之后倒放 (返回时页面重建先恢复成点开的样子, 缩回落地时列表页与缩回层是同一张清晰图); 点开途中按返回取消.
 */
@Composable
private fun TvNativeWallBackdropEffects(state: TvNativeGridPageState, view: TvNativeGridPageView?, spec: TvNativeWallBackdropSpec) {
    val currentSpec by rememberUpdatedState(spec)
    val pageForeground = LocalPageIsForeground.current
    LaunchedEffect(view) {
        if (view == null) return@LaunchedEffect
        snapshotFlow { currentSpec.target() }.collect { view.setWallTarget(it) }
    }
    LaunchedEffect(view) {
        if (view == null) return@LaunchedEffect
        snapshotFlow { state.menu == null }.collect { closed -> if (closed) view.endWallPeek() }
    }
    LaunchedEffect(view) {
        if (view == null) return@LaunchedEffect
        if (state.wallOpenIndex >= 0) view.restoreWallOpen()
        snapshotFlow { TvNativeWallOpenSignal(state.wallOpenIndex >= 0, pageForeground.value, TvHeroZoomHandoff.shrinking) }
            .collectLatest { s ->
                if (!s.opened) return@collectLatest
                if (!s.foreground) {
                    state.wallOpenLeft = true
                    return@collectLatest
                }
                if (s.shrinking) return@collectLatest
                // 点开之后一直没离开前台: 导航没发出去 (前进导航的转场闸门挡了), 过一会儿还在就当没进
                if (!state.wallOpenLeft) delay(TV_WALL_OPEN_LEAVE_TIMEOUT_MILLIS)
                state.wallOpenIndex = -1
                state.wallOpenLeft = false
                view.endWallOpen()
            }
    }
    BackHandler(enabled = state.wallOpening) { state.view?.cancelWallOpen() }
}

private data class TvNativeWallOpenSignal(val opened: Boolean, val foreground: Boolean, val shrinking: Boolean)

/** 整屏背景点开之后多久还没离开前台就当导航没发出去 (倒放回卡片墙). */
private const val TV_WALL_OPEN_LEAVE_TIMEOUT_MILLIS = 1_000L

private data class TvNativeGridTitleHandoff(
    val view: TvNativeGridPageView,
    val hidden: Boolean,
    val offset: Offset?,
    val settling: Boolean,
)

/** 越界取 null ([LazyPagingItems.peek] 是直接下标访问, 越界当场抛). */
private fun <T : Any> LazyPagingItems<T>.peekAt(index: Int): T? = if (index in 0 until itemCount) peek(index) else null
