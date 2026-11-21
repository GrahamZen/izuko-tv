/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.flow.collectLatest
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import kotlin.math.abs

/*
 * 选集卡与选集行 (Compose 版, iOS 上与安卓的调试开关 tvComposeWalls 下用) 在触屏上的手势, 同原生选集行 (TvNativeEpisodeRowView /
 * TvNativeEpisodeCardView) 的触摸. 开关 LocalTvTouchInputEnabled 关着 (电视) 时一律原样返回, 一个节点都不多.
 */

/**
 * 选集卡的触摸: 没聚焦时点一下只选中 (拿焦点, 行把这张滑进聚焦框), 已聚焦再点才是 [onClick] (开播); 按住不动到长按时长 = 选中并
 * [onLongPress] (单集详情弹窗), 这次按住剩下的事件随后吞掉. 按下那一刻不动焦点: 这一下可能是拖行的开头, 先拿焦点的话行会先往这张滑、
 * 聚焦卡左边的压暗跟着闪一下. 卡片在手指下滑走不算手指移动 (按窗口坐标量); 手指挪过 touch slop 就是拖行, 交给行去滚.
 * 挂在卡片的 clickable 之前.
 */
@Composable
internal fun Modifier.focusEpisodeCardTouch(onClick: () -> Unit, onLongPress: (() -> Unit)?): Modifier {
    if (!LocalTvTouchInputEnabled.current) return this
    val requester = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    val holder = remember { FocusEpisodeCardTouchHolder() }
    val currentClick by rememberUpdatedState(onClick)
    val currentLongPress by rememberUpdatedState(onLongPress)
    return this
        .onGloballyPositioned { holder.coordinates = it }
        .onFocusChanged { holder.focused = it.isFocused }
        .pointerInput(inputModeManager) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val wasFocused = holder.focused
                val select = {
                    // 弹窗是独立窗口, 有自己的输入模式管理器, 根部那次请求管不到这里 (同 tvTouchFocusOnTap)
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    runCatching { requester.requestFocus() }
                }
                val start = holder.toWindow(down.position)
                val slop = viewConfiguration.touchSlop
                val longPress = currentLongPress
                var finished = false
                val up = if (longPress == null) {
                    awaitFocusEpisodeCardTap(down, start, slop, holder).also { finished = true }
                } else {
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitFocusEpisodeCardTap(down, start, slop, holder).also { finished = true }
                    }
                }
                when {
                    !finished -> {
                        select()
                        longPress?.invoke()
                        // 吞掉这次按住剩下的事件: 不拖行, 也不算点击
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                        }
                    }

                    up != null -> {
                        // 抬起由这里接走, 下面的 clickable 当作取消
                        up.consume()
                        if (wasFocused) currentClick() else select()
                    }
                }
            }
        }
        .focusRequester(requester)
}

private class FocusEpisodeCardTouchHolder {
    var focused = false
    var coordinates: LayoutCoordinates? = null

    fun toWindow(local: Offset): Offset = coordinates?.takeIf { it.isAttached }?.localToWindow(local) ?: local
}

/** 等这次按下的结局: 原地抬起 = 那次抬起; 手指 (按窗口坐标) 挪过 [slop] / 被取消 = null. */
private suspend fun AwaitPointerEventScope.awaitFocusEpisodeCardTap(
    down: PointerInputChange,
    start: Offset,
    slop: Float,
    holder: FocusEpisodeCardTouchHolder,
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (change.changedToUpIgnoreConsumed()) return change
        if (!change.pressed) return null
        if ((holder.toWindow(change.position) - start).getDistance() > slop) return null
    }
}

/** [rememberFocusEpisodeRowTouchDrag] 的结果: [dragging] = 手指在拖 / 甩; [modifier] 挂在行上. */
internal class FocusEpisodeRowTouchDrag(val dragging: () -> Boolean, val modifier: Modifier)

/**
 * 选集行在触屏上拖完 (连同惯性) 停下: 就近停到一张卡 ([onSettle] 给它的下标, 调用方滚过去并送焦), 同原生选集行「松手就近停」.
 * 手指又按到行上 (甩着时点卡片、再拖一下) 就作废还没做的就近停, 由这一下自己定 (点卡片 = 选中那张; 再拖 = 拖完再停).
 * 拖 / 甩期间固定聚焦框藏起来 (卡片离开了框). 开关关着恒 false、修饰符为空.
 */
@Composable
internal fun rememberFocusEpisodeRowTouchDrag(listState: LazyListState, onSettle: (index: Int) -> Unit): FocusEpisodeRowTouchDrag {
    val dragging = remember { mutableStateOf(false) }
    if (!LocalTvTouchInputEnabled.current) return remember { FocusEpisodeRowTouchDrag({ false }, Modifier) }
    val currentSettle by rememberUpdatedState(onSettle)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragging.value = true }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling || !dragging.value) return@collectLatest
            // 松手到惯性起步之间可能有一两帧不算在滚: 等两帧再看
            withFrameNanos { }
            withFrameNanos { }
            if (listState.isScrollInProgress) return@collectLatest
            dragging.value = false
            val nearest = listState.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset) } ?: return@collectLatest
            currentSettle(nearest.index)
        }
    }
    return remember {
        FocusEpisodeRowTouchDrag(
            dragging = { dragging.value },
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    dragging.value = false
                }
            },
        )
    }
}
