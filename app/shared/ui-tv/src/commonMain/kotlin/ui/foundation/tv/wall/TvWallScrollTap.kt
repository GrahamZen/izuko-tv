/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.app.ui.foundation.tv.tvWallTapPassesScroll
import kotlin.math.abs

/*
 * 触屏上列表 / 行还在滑时按下去 (见 tvWallTapPassesScroll): Compose 的列表只要还在滚, 按下就当场开始拖动 (按住接住), 这一下被列表吃掉、
 * 卡片的点击取消 —— 不管滑得多慢. 滑得慢 (减速的尾巴, 看着已经停了) 时由卡片自己接住按下与抬起, 照常算点击; 快的照旧只停住.
 */

/** 列表此刻滑得多快 (dp/秒): 在滚的时候每帧量一次 (同一项在相邻两帧里挪了多少), 停下为 0. 见 [rememberTvWallScrollSpeed]. */
internal class TvWallScrollSpeed(private val state: ScrollableState) {
    internal var dpPerSecond: Float = 0f

    /** 按下的这一刻: 列表在滚且滑得慢 (不在滚 = false, 那时卡片的点击照常, 不用接). */
    fun slowScrolling(): Boolean = state.isScrollInProgress && tvWallTapPassesScroll(dpPerSecond)

    /** 按下的这一刻: 列表在滚且滑得快 (这一下只停住). */
    fun fastScrolling(): Boolean = state.isScrollInProgress && !tvWallTapPassesScroll(dpPerSecond)
}

@Composable
internal fun rememberTvWallScrollSpeed(state: LazyListState): TvWallScrollSpeed =
    rememberTvWallScrollSpeed(
        state,
        firstIndex = { state.layoutInfo.visibleItemsInfo.firstOrNull()?.index },
        offsetOf = { index -> state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset },
    )

@Composable
internal fun rememberTvWallScrollSpeed(state: LazyGridState): TvWallScrollSpeed =
    rememberTvWallScrollSpeed(
        state,
        firstIndex = { state.layoutInfo.visibleItemsInfo.firstOrNull()?.index },
        // 竖着滚的网格: 主轴是 y
        offsetOf = { index -> state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.y },
    )

@Composable
private fun rememberTvWallScrollSpeed(
    state: ScrollableState,
    firstIndex: () -> Int?,
    offsetOf: (index: Int) -> Int?,
): TvWallScrollSpeed {
    val speed = remember(state) { TvWallScrollSpeed(state) }
    val density = LocalDensity.current.density
    LaunchedEffect(state, density) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            speed.dpPerSecond = 0f
            if (!scrolling) return@collectLatest
            var index = -1
            var offset = 0
            var lastNanos = 0L
            while (true) {
                withFrameNanos { now ->
                    // 上一帧记下的那一项此刻挪了多少 (它滑出去了就等下一帧重新记)
                    val moved = if (index >= 0) offsetOf(index)?.let { abs(it - offset) } else null
                    if (moved != null && lastNanos != 0L && now > lastNanos) {
                        speed.dpPerSecond = moved / density * 1_000_000_000f / (now - lastNanos)
                    }
                    firstIndex()?.let {
                        index = it
                        offset = offsetOf(it) ?: 0
                    }
                    lastNanos = now
                }
            }
        }
    }
    return speed
}

/** 手指按下就调 [onPress] (抬起前, 还不知道是点还是拖; 不消费事件, 点击与拖动照旧). */
internal fun Modifier.tvTouchPress(onPress: State<() -> Unit>): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Touch) onPress.value()
        }
    }

/**
 * 卡片自己接住「列表滑得慢时按下又抬起」这一下, 照常调 [onTap] (见文件头). [takeOver] 在按下的那一刻判断要不要接 (列表在滚、滑得慢,
 * 且没有哪个列表滑得快); 不接的时候什么都不做, 点击照旧由卡片的 clickable 处理. 只认手指; 挪过 touch slop、多指、按太久都不算.
 */
internal fun Modifier.tvTapThroughSlowScroll(takeOver: State<() -> Boolean>, onTap: State<() -> Unit>): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Touch || !takeOver.value()) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) {
                    if (change.uptimeMillis <= deadline) onTap.value()
                    return@awaitEachGesture
                }
                if ((change.position - down.position).getDistance() > slop) return@awaitEachGesture
            }
        }
    }
