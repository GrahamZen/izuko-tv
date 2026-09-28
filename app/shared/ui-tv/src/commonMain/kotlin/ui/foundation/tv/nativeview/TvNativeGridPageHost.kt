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
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.NativeSendFocusEffect
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff

/*
 * 网格页 (追番 / 搜索) 原生海报墙在 Compose 这一侧的接线, 两页共用: 建视图、换数据、把页面的网格送焦请求 (TvGridFocusState) 转给原生、
 * 长按菜单的锚点、放大转场缩回期间的标题. 页面只给数据映射、hero 内容与事件回调.
 */

/**
 * 网格页原生海报墙在页面这一侧的状态: 视图引用、hero 态 (跨导航保存, 返回本页时停在 hero 态)、整屏黑度、各份网格的位置 (页面重建时恢复)、
 * 长按菜单锚点.
 */
@Stable
class TvNativeGridPageState internal constructor(
    heroActive: Boolean,
    internal val savedPositions: HashMap<Int, Int>,
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
    rememberSaveable(saver = TvNativeGridPageStateSaver) { TvNativeGridPageState(heroActive = false, savedPositions = HashMap()) }

private val TvNativeGridPageStateSaver = Saver<TvNativeGridPageState, ArrayList<Any>>(
    save = { state ->
        state.capture()
        arrayListOf(state.heroActive, ArrayList(state.savedPositions.keys), ArrayList(state.savedPositions.values))
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        val keys = list[1] as List<Int>

        @Suppress("UNCHECKED_CAST")
        val values = list[2] as List<Int>
        TvNativeGridPageState(heroActive = list[0] as Boolean, savedPositions = HashMap(keys.zip(values).toMap()))
    },
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
 * [emptyContent] 画在网格上面 (空列表提示 / 首屏加载), 由页面决定何时出现.
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
    emptyContent: @Composable BoxScope.() -> Unit = {},
) {
    val sketch = LocalSketch.current
    val scope = rememberCoroutineScope()
    val composeRoot = LocalView.current
    val density = LocalDensity.current
    val style = rememberTvNativeWallStyle(cardWidth, metrics.grid.columns)
    val textStyle = rememberTvNativeHeroTextStyle(titleMaxLines = 2, lineSpacing = 8.dp)
    val visualEffects = LocalThemeSettings.current.visualEffects
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentItems by rememberUpdatedState(items)
    val currentCardOf by rememberUpdatedState(cardOf)
    val snapshot = items.itemSnapshotList
    val cards = remember(snapshot, cardsKey) { snapshot.map { it?.let(currentCardOf) } }

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
                    view.composeRoot = composeRoot
                    view.restorePositions(state.savedPositions)
                    view.heroText.onShownSubjectChanged = { state.titleSubjectId = it }
                    state.view = view
                }
            },
            update = { view ->
                view.listener = object : TvNativeGridPageListener {
                    override fun onFocused(index: Int) {
                        currentCallbacks.onCardFocused(index, currentItems.peekAt(index))
                        gridFocus.onNativeItemFocused(index)
                        state.capture()
                    }

                    override fun onClick(index: Int) {
                        currentItems.peekAt(index)?.let { currentCallbacks.onCardClick(index, it) }
                    }

                    override fun onLongPress(index: Int, anchor: AndroidRect) {
                        currentItems.peekAt(index)?.let { state.menu = it to anchor }
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
                }
                // 分页的访问提示: 绑到哪张, 分页就往后取到哪 (读一次 items[index] 就是向分页报告访问到了这里)
                view.onBindCard = { index -> if (index in 0 until currentItems.itemCount) currentItems[index] }
                view.transitions = visualEffects.transitions
                view.animatedScroll = visualEffects.animatedScroll
                view.dark = dark
                view.fadeColor = fadeColor.toArgb()
                view.treatment = treatment
                view.update(style, metrics, textStyle)
            },
            bleedLeft = with(LocalDensity.current) { metrics.bleedLeftPx.toDp() },
        )
        emptyContent()
        // 长按卡片的收藏菜单: 锚在那张卡的封面上 (原生视图报上来的封面框, 窗口坐标)
        state.menu?.let { (item, rect) ->
            @Suppress("UNCHECKED_CAST")
            val menu = remember(item) { menuFor(item as T) }
            Box(
                Modifier
                    .offset { IntOffset((rect.left - pagePosition.x).toInt(), (rect.top - pagePosition.y).toInt()) }
                    .size(with(density) { rect.width().toDp() }, with(density) { rect.height().toDp() }),
            ) {
                menu(true) { state.menu = null }
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

    // 页面的网格送焦请求 (进页恢复 / 顶栏下键 / 换标签落点 / 返回键回首卡) 交给原生送焦
    gridFocus.NativeSendFocusEffect(
        columns = { metrics.grid.columns },
        itemCount = { currentItems.itemCount },
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

    // 放大转场缩回期间的标题: 绘制权交给转场层时隐藏, 否则按缩回让位平移; 缩回途中停跑马灯 (见 TvNativeHeroTextView.setTitleHandoff)
    LaunchedEffect(state) {
        snapshotFlow {
            val id = state.titleSubjectId
            val v = state.view
            if (id == null || v == null) {
                null
            } else {
                TvNativeGridTitleHandoff(
                    v,
                    TvHeroZoomHandoff.titleOwnedByOverlay(id),
                    TvHeroZoomHandoff.shrinkTitleOffset(id),
                    TvHeroZoomHandoff.titleSettling(id),
                )
            }
        }.collect { h ->
            h?.view?.heroText?.setTitleHandoff(h.hidden, h.offset?.x ?: 0f, h.offset?.y ?: 0f, h.settling)
        }
    }
}

private data class TvNativeGridTitleHandoff(
    val view: TvNativeGridPageView,
    val hidden: Boolean,
    val offset: Offset?,
    val settling: Boolean,
)

/** 越界取 null ([LazyPagingItems.peek] 是直接下标访问, 越界当场抛). */
private fun <T : Any> LazyPagingItems<T>.peekAt(index: Int): T? = if (index in 0 until itemCount) peek(index) else null
