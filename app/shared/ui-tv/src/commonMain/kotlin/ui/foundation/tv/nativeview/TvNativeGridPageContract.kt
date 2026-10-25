/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment

/**
 * 网格页原生海报墙在页面这一侧的状态: 视图引用、hero 态 (跨导航保存, 返回本页时停在 hero 态)、整屏黑度、各份网格的位置 (页面重建时恢复)、
 * 长按菜单锚点、整屏背景的点开 (新番时间表 / hero 态的模糊背景, 跨导航保存: 返回时页面重建也从清晰图起).
 */
@Stable
class TvNativeGridPageState internal constructor(
    heroActive: Boolean,
    internal val savedPositions: HashMap<Int, Int>,
    wallOpenIndex: Int,
) {
    /** 画面 (安卓是原生的 TvNativeGridPageView, 别的端是 Compose 版). */
    var surface: TvGridPageSurface? by mutableStateOf(null)
        internal set

    /** 一组一行的内容 (给了 [TvNativeGridPageRows] 才建, 安卓上是行式海报墙的宾客模式): 页面对它接数据与事件. */
    var rows: TvRowWallSurface? by mutableStateOf(null)
        internal set

    /** hero 态 (页面的返回键按它开关). */
    var heroActive: Boolean by mutableStateOf(heroActive)
        internal set

    /** 整屏黑度 (hero 态进度), 画整屏底色的那一层读. */
    var tone: Float by mutableFloatStateOf(0f)
        internal set

    internal var titleSubjectId: Int? by mutableStateOf(null)
    /** 长按菜单的锚点: 那一项 (页面的列表项), 封面在窗口里的框. */
    internal var menu: Pair<Any, Rect>? by mutableStateOf(null)

    /** 整屏背景点开途中 (对焦还没到位、还没进详情页): 返回键取消点开. */
    internal var wallOpening: Boolean by mutableStateOf(false)

    /** 整屏背景点开进了详情页的那张 (-1 = 没有): 回到本页倒放. */
    internal var wallOpenIndex: Int by mutableIntStateOf(wallOpenIndex)

    /** 点开之后本页离开过前台 (真进了详情页); 恢复出来的状态算离开过. */
    internal var wallOpenLeft: Boolean = wallOpenIndex >= 0

    /** 点开时顶栏等 Compose 部件跟着卡片淡没的程度 (0..1), 页面在绘制里读. */
    var wallFade: Float by mutableFloatStateOf(0f)
        internal set

    /** 网格内容从页顶往上滚了多少 (px, 夹在 0..topBarScrollAwayPx, 见 TvNativeGridPageView.contentScrollLimitPx): 页面在绘制里读, 让顶栏跟着滚走. */
    var contentScroll: Int by mutableIntStateOf(0)
        internal set

    /** 忘掉 [key] 那份网格的位置: 下次显示从第一行排起. */
    fun forgetPosition(key: Int) {
        savedPositions.remove(key)
        surface?.forgetPosition(key)
    }

    /** 退出 hero 态 (返回键). */
    fun exitHero() {
        surface?.setHeroActive(false)
        heroActive = false
    }

    internal fun capture() {
        val v = surface ?: return
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
 * (页面按停稳后的聚焦条目算; 在协程里读, 读到的快照状态变了就换), [maskColor] = 整屏压暗 (页面底色 + 起步的透明度), [textColor] =
 * 压在背景上的主要文字 (卡片番名) 的颜色: 每张图按自己的亮度在起步那份上加深, 保证这个颜色的字看得清.
 */
class TvNativeWallBackdropSpec(
    val target: () -> TvNativeWallBackdropTarget?,
    val maskColor: Color,
    val textColor: Color,
)

/** 原生网格的事件, 由页面给 (焦点簿记、hero、导航). [T] = 列表项. */
class TvNativeGridPageCallbacks<T : Any>(
    val onCardFocused: (index: Int, item: T?) -> Unit,
    val onCardClick: (index: Int, item: T) -> Unit,
    val onTopRowUp: () -> Boolean,
    val onRowEdge: (direction: Int, row: Int) -> Boolean = { _, _ -> false },
    val onGridFocusChanged: (Boolean) -> Unit,
    val onScrollingChanged: (Boolean) -> Unit,
    /** 「海报上按确定」选直接播放时按确定做的事 (同播放键); null = 照旧 [onCardClick] (预览页). */
    val onCardPlay: ((index: Int, item: T) -> Unit)? = null,
    /** 触屏设备上海报墙左右滑 (左滑 +1, 右滑 −1, 见 TvNativeGridPageView.onTouchSwipe); null = 不认 (搜索页只有一份网格). */
    val onSwipe: ((delta: Int) -> Unit)? = null,
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
    rows: TvNativeGridPageRows? = null,
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
        // 「海报上按确定」只有先看简介这一档有 hero 态
        heroEnabled = LocalThemeSettings.current.tvPosterConfirm == TvPosterConfirmAction.Hero, badge = null, source, fadeColor, treatment, gridFocus, farJump, onFarJumpConsumed, callbacks, menuFor,
        wallBackdrop = null, modifier, landingIndex, topBarScrollAwayPx = 0, columnSpacing = TV_POSTER_WALL_COLUMN_SPACING,
        cardHeight = null, labelVibrancy = false, rows, emptyContent,
    )
}

/**
 * 网格页的原生海报墙, 数据是一份现成的列表 (新番时间表的一天): [items] 与 [cards] 一一对应, null 是还没到的占位. 页面的网格送焦请求只认
 * 前 [focusableCount] 张 (占位期间给 0: 占位卡不收落点, 等真数据). [heroEnabled] = false 时没有 hero 态: 卡片墙上按确定直接进详情页
 * ([TvNativeGridPageCallbacks.onCardClick]). [badge] 给了就按卡片的 [TvNativeCard.badge] 在封面右上角画角标.
 * [topBarScrollAwayPx] > 0 时逐帧报告网格内容往上滚了多少 ([TvNativeGridPageState.contentScroll], 夹在 0..它), 页面让顶栏跟着一起滚走.
 * [columnSpacing] = 卡格之间的距离, [cardHeight] = 卡格高 (null = 按卡宽与封面比例算), [labelVibrancy] = 番名照 tvOS 的 vibrancy 画
 * (压在模糊背景上时), 见 [rememberTvNativeWallStyle]. 其余同分页那一版.
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
    topBarScrollAwayPx: Int = 0,
    columnSpacing: Dp = TV_POSTER_WALL_COLUMN_SPACING,
    cardHeight: Dp? = null,
    labelVibrancy: Boolean = false,
    emptyContent: @Composable BoxScope.() -> Unit = {},
) {
    val currentItems by rememberUpdatedState(items)
    TvNativeGridPageHostContent(
        state, metrics, cardWidth, gridKey, slideDirection, cards,
        itemAt = { index -> currentItems.getOrNull(index) },
        itemCount = focusableCount,
        onBind = {},
        heroEnabled = heroEnabled, badge = badge, source = null, fadeColor, treatment, gridFocus, farJump, onFarJumpConsumed, callbacks,
        menuFor, wallBackdrop, modifier, landingIndex, topBarScrollAwayPx, columnSpacing, cardHeight, labelVibrancy, rows = null, emptyContent,
    )
}

/** 越界取 null ([LazyPagingItems.peek] 是直接下标访问, 越界当场抛). */
private fun <T : Any> LazyPagingItems<T>.peekAt(index: Int): T? = if (index in 0 until itemCount) peek(index) else null

/**
 * 网格页海报墙的画面 (安卓是原生 View TvNativeGridPageView, 别的端是 Compose 版) 给页面与 [TvNativeGridPageState] 用的操作.
 */
interface TvGridPageSurface {
    /** hero 态 (以画面为准). */
    val heroActive: Boolean

    fun setHeroActive(active: Boolean, animated: Boolean = true)

    /** 各份网格停在哪 (按网格的 key): 页面重建时恢复. */
    fun savedPositions(): Map<Any, Int>

    fun forgetPosition(key: Any)

    /** 送焦到第 [index] 张. */
    fun focusItem(index: Int)

    /** 网格顶线下第一张 (还没排出来为 null). */
    fun firstIndexBelowTopLine(): Int?

    /** 远跳途中当场落地 (返回键): 没在远跳返回 false. */
    fun landFarJump(): Boolean

    /** 焦点在不在网格里. */
    fun hasFocus(): Boolean

    /** 网格列数. */
    val columns: Int

    /** 此刻显示的那一份 (网格的 key, 或一组一行的内容的 key). */
    val currentKey: Any?

    /** 聚焦那张卡的纵向中心 (窗口坐标); 没有时 null. */
    fun focusedCardCenterYInWindow(): Int?

    /** 此刻那份网格里纵向离 [windowY] (窗口坐标) 最近的那一行; 没有时 null. */
    fun rowNearestWindowY(windowY: Int): Int?

    /** hero 内容 (聚焦的那张; 进 hero 态时显示). */
    fun setSource(source: TvNativeHeroSource)
}

/**
 * 网格页里与各份网格换着显示的一组一行的内容 (追番页「追更」): [key] = 它占的 gridKey, [metrics] = 几何 (从页顶算), [cardWidth] = 卡宽.
 * 原生版另带的卡片样式与组标题见 TvNativeGridPageRowsSpec; 数据与事件由页面对 [TvNativeGridPageState.rows] 接.
 */
interface TvNativeGridPageRows {
    val key: Int
    val metrics: TvNativeExploreMetrics
    val cardWidth: Dp
}

/** 两个入口共用的接线: 安卓接原生视图, 别的端画 Compose 版. 参数见两个 [TvNativeGridPageHost]. */
@Composable
internal expect fun <T : Any> TvNativeGridPageHostContent(
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
)
