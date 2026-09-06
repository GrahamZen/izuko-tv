/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs

/**
 * 竖向滚动容器里几片折行排布 (FlowRow) 的可聚焦项 —— 弹窗里的筛选胶囊 —— 的上下导航, 照卡片列表的做法: 上下键由这里按各项的坐标
 * 算出相邻那一行里横向最接近的一项、直接送焦, 不交给框架的空间焦点搜索; 焦点换到哪一项, [TvFlowFocusScrollEffect] 就用
 * [TvScrollAnimator] 把它所在的行滚到视口中间, 框架自己的 bring-into-view 由调用方关掉 ([TvNoBringIntoViewSpec]).
 * 竖着连走时横坐标沿用起步那一项的 (同卡片列表「上下落到屏上同一列」), 不随各行胶囊宽窄斜着漂.
 *
 * 各项必须**全部排好** (普通 Column + verticalScroll, 不用懒加载): 按住方向键时每一发都有下一项可去, 不会因为下一行还没排出来、
 * 或几套滚动互相打断而找不到落点停在原地.
 *
 * 左右键照常交给框架 (同一行里左右相邻). 下面 / 上面没有行时方向键也交还框架 (弹窗底部的确认钮就这样走到).
 *
 * 用法: 滚动容器挂 [tvFlowFocusViewport] (在 verticalScroll 之前), 里面的内容挂 [tvFlowFocusContent] (在 verticalScroll 之后),
 * 每一项挂 [tvFlowFocusItem] (在它的 focusable 之前), 在同一作用域放 [TvFlowFocusScrollEffect].
 */
@Stable
class TvFlowFocusState {
    private class Item(val requester: FocusRequester) {
        var coordinates: LayoutCoordinates? = null
    }

    private val items = HashMap<Any, Item>()
    private var content: LayoutCoordinates? = null

    /** 滚动容器 (视口) 的高 (px). */
    internal var viewportHeight: Int = 0

    /** 此刻持焦的那一项; null = 焦点不在这些项上. */
    var focusedKey: Any? by mutableStateOf(null)
        private set

    /** 竖着连走时沿用的横坐标 (内容坐标); NaN = 下一次上下键从当前项的中线重新起步. */
    private var columnX = Float.NaN

    /** 正在由 [move] 送焦: 这一次获焦不重置 [columnX]. */
    private var moving = false

    internal fun onContentPlaced(coordinates: LayoutCoordinates) {
        content = coordinates
    }

    internal fun place(key: Any, requester: FocusRequester, coordinates: LayoutCoordinates) {
        items.getOrPut(key) { Item(requester) }.coordinates = coordinates
    }

    internal fun register(key: Any, requester: FocusRequester) {
        items.getOrPut(key) { Item(requester) }
    }

    internal fun unregister(key: Any) {
        items.remove(key)
        if (focusedKey == key) focusedKey = null
    }

    internal fun onItemFocusChanged(key: Any, focused: Boolean) {
        if (focused) {
            focusedKey = key
            // 左右键 / 进弹窗落点 / 从别处回来: 竖着走的起步列按新的这一项重算
            if (!moving) columnX = Float.NaN
        } else if (focusedKey == key) {
            focusedKey = null
        }
    }

    /** [key] 那一项在滚动内容里的框 (不随滚动变); 还没排出来时 null. */
    fun boundsOf(key: Any): Rect? {
        val content = content?.takeIf { it.isAttached } ?: return null
        val coordinates = items[key]?.coordinates?.takeIf { it.isAttached } ?: return null
        return content.localBoundingBoxOf(coordinates)
    }

    /**
     * 按一次下 ([down]) / 上键: 送焦给相邻那一行里离 [columnX] 最近的一项, 送出去了返回 true. 相邻那一行 = 方向上最近的一行
     * (顶边在当前项中线以下 / 底边在中线以上的项里最靠近的那一排); 同一行 = 顶边与那一排相差不到当前项的半个高.
     */
    internal fun move(down: Boolean): Boolean {
        val key = focusedKey ?: return false
        val from = boundsOf(key) ?: return false
        val candidates = items.keys.mapNotNull { k -> if (k == key) null else boundsOf(k)?.let { k to it } }
        val rowTop = if (down) {
            candidates.filter { it.second.top >= from.center.y }.minOfOrNull { it.second.top }
        } else {
            candidates.filter { it.second.bottom <= from.center.y }.maxOfOrNull { it.second.top }
        } ?: return false
        if (columnX.isNaN()) columnX = from.center.x
        val tolerance = from.height / 2f
        val target = candidates
            .filter { abs(it.second.top - rowTop) < tolerance }
            .minByOrNull { abs(it.second.center.x - columnX) }
            ?: return false
        moving = true
        try {
            items[target.first]?.requester?.requestFocus()
        } finally {
            moving = false
        }
        return true
    }
}

@Composable
fun rememberTvFlowFocusState(): TvFlowFocusState = remember { TvFlowFocusState() }

/** 挂在滚动容器 (带 verticalScroll 的那一层, 在 verticalScroll 之前): 记下视口高, 接管上下键. */
fun Modifier.tvFlowFocusViewport(state: TvFlowFocusState): Modifier =
    onSizeChanged { state.viewportHeight = it.height }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> state.move(down = true)
                Key.DirectionUp -> state.move(down = false)
                else -> false
            }
        }

/** 挂在滚动的内容上 (verticalScroll 之后): 各项的坐标按它算, 不随滚动变. */
fun Modifier.tvFlowFocusContent(state: TvFlowFocusState): Modifier =
    onPlaced { state.onContentPlaced(it) }

/** 挂在每一项上 (在它的 focusable 之前). [key] 在这片区域里唯一. */
@Composable
fun Modifier.tvFlowFocusItem(state: TvFlowFocusState, key: Any): Modifier {
    val requester = remember(state, key) { FocusRequester() }
    DisposableEffect(state, key) {
        state.register(key, requester)
        onDispose { state.unregister(key) }
    }
    return focusRequester(requester)
        .onPlaced { state.place(key, requester, it) }
        .onFocusChanged { state.onItemFocusChanged(key, it.isFocused) }
}

/**
 * 焦点换到某一项时把它所在的行滚到视口中间 (夹在滚动范围里), 用卡片列表那条 spring ([TvScrollAnimator], 连发时速度接着上一段).
 * 焦点离开这些项 (如走到下面的确认钮) 时不打断在走的那一段: 按住下键走出最后一行时, 最后一行照样滚到位, 不停在半路.
 * [animated] = false 时一步到位 (视觉效果流畅档). 放在弹窗内容的根作用域 —— 放在随重组重建的内层作用域里, 快速操作时协程会被反复取消.
 */
@Composable
fun TvFlowFocusScrollEffect(state: TvFlowFocusState, scrollState: ScrollState, animated: Boolean = true) {
    val animator = remember(animated) { TvScrollAnimator(animated) }
    LaunchedEffect(state, scrollState, animator) {
        snapshotFlow { state.focusedKey }.filterNotNull().collectLatest { key ->
            val bounds = state.boundsOf(key) ?: return@collectLatest
            val viewport = state.viewportHeight
            if (viewport <= 0) return@collectLatest
            val target = (bounds.center.y - viewport / 2f).coerceIn(0f, scrollState.maxValue.toFloat())
            animator.animateScrollBy(scrollState, target - scrollState.value)
        }
    }
}
