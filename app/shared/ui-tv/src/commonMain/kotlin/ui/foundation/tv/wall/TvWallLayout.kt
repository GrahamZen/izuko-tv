/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 把这一层按像素摆在父布局 (整页) 里: 左上角 ([x], [y]), 大小 [width] × [height] ([width] < 0 = 父布局的宽). [fromEnd] = [x] 从右边算.
 * 海报墙的几何 (TvNativeGridPageMetrics / TvNativeExploreMetrics) 都是整页坐标的像素, 各层照着摆; 大小可以超出父布局 (网格往屏幕底边外多排一截).
 */
internal fun Modifier.tvWallPlace(x: Int, y: Int, width: Int, height: Int, fromEnd: Boolean = false): Modifier =
    layout { measurable, constraints ->
        val w = if (width < 0) constraints.maxWidth else width
        val placeable = measurable.measure(Constraints.fixed(w.coerceAtLeast(0), height.coerceAtLeast(0)))
        val parentWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else w
        val parentHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else height
        layout(parentWidth, parentHeight) {
            placeable.place(if (fromEnd) parentWidth - w - x else x, y)
        }
    }

/**
 * 触屏上点在卡片以外的地方 ([onBlankTap], 参数是按下点, 本层坐标): 没拖过 touch slop、这次抬起也没被里面的卡片 / 按钮接走.
 * 这样的点击一律由海报墙接住 (消费掉抬起, 顺手收起软键盘): 不然应用根部的「点空白清焦点」(clearFocusOnUnhandledTap) 会清掉焦点,
 * 焦点兜底随后把焦点塞回某张卡、列表跟着滚过去 —— 海报墙「自己动了」. [enabled] 为 false (电视) 时原样返回.
 */
@Composable
internal fun Modifier.tvWallBlankTap(enabled: Boolean, onBlankTap: (Offset) -> Unit): Modifier {
    if (!enabled) return this
    val currentBlankTap by rememberUpdatedState(onBlankTap)
    val keyboard by rememberUpdatedState(LocalSoftwareKeyboardController.current)
    return pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) break
                if (change.changedToUpIgnoreConsumed()) {
                    if (!change.isConsumed) {
                        change.consume()
                        keyboard?.hide()
                        currentBlankTap(down.position)
                    }
                    break
                }
                if (!change.pressed) break
            }
        }
    }
}

/**
 * 触屏上的左右滑一下翻一项 (同原生的 TvNativeHorizontalSwipe): 按下落在 [area] 里 (本层坐标), 先横着越过 touch slop 且横向占优才接管 ——
 * 之后的事件在祖先这一遍就消费掉, 里面的列表不滚、按钮不点; 先竖着越过的整个手势放给里面. 画面不跟手指动: 松手时横向位移过
 * [TV_WALL_SWIPE_FLIP_DISTANCE] 或横向速度过 [TV_WALL_SWIPE_FLIP_VELOCITY] (方向与位移一致) 就翻一项 (左滑 +1, 右滑 −1), 过渡由页面自己播.
 * [enabled] 为 false (电视) 时原样返回.
 */
@Composable
internal fun Modifier.tvWallHorizontalSwipe(
    enabled: Boolean,
    area: (Offset) -> Boolean,
    onSwipe: (delta: Int) -> Unit,
): Modifier {
    if (!enabled) return this
    val currentArea by rememberUpdatedState(area)
    val currentSwipe by rememberUpdatedState(onSwipe)
    return pointerInput(Unit) {
        val distance = TV_WALL_SWIPE_FLIP_DISTANCE.toPx()
        val velocityThreshold = TV_WALL_SWIPE_FLIP_VELOCITY.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!currentArea(down.position)) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!claimed) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        claimed = true
                    } else if (abs(dy) > slop) {
                        // 竖着先过: 整个手势放给里面 (列表滚动)
                        break
                    }
                }
                if (claimed) change.consume()
                if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                    if (claimed) {
                        val vx = tracker.calculateVelocity().x
                        if (abs(dx) >= distance || (abs(vx) >= velocityThreshold && vx * dx > 0f)) currentSwipe(if (dx < 0) 1 else -1)
                    }
                    break
                }
            }
        }
    }
}

/**
 * 触屏设备上 ([enabled]) 分辨此刻的输入: 手指按下报 true (海报墙按触屏的规矩走: 手指滚动, 焦点不驱动滚动、不画聚焦效果), 按方向键 / 确认键
 * (平板接着键盘或遥控器) 报 false (回到电视的规矩). 只旁听 (指针在祖先这一遍、按键在预览阶段), 不消费. [enabled] 为 false (电视) 时原样返回.
 */
@Composable
internal fun Modifier.tvWallInputMode(enabled: Boolean, onInput: (touch: Boolean) -> Unit): Modifier {
    if (!enabled) return this
    val currentInput by rememberUpdatedState(onInput)
    return this
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in TV_WALL_NAV_KEYS) currentInput(false)
            false
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) currentInput(true)
                }
            }
        }
}

/**
 * 触屏上手指拖 / 甩列表最远到 [end] (px, 与 [scrolled] 同一套坐标): 列表末尾为程序停位多补的空白 (末几行也滚得到 hero 线) 手指甩不进去;
 * 已经在那以下 (hero 态停在末几行后退回卡片墙) 时只能往回拉. 挂在列表的 nestedScroll 上 (先于列表拿到手指的位移); 程序滚动不经过这里.
 */
internal class TvWallTouchScrollLimit(
    private val scrolled: () -> Int,
    private val end: () -> Int,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 手指往上 (y 为负) = 列表往后滚
        val forward = -available.y
        if (forward <= 0f) return Offset.Zero
        val allowed = (end() - scrolled()).coerceAtLeast(0).toFloat()
        return if (forward > allowed) Offset(0f, -(forward - allowed)) else Offset.Zero
    }
}

/** 算「用键盘 / 遥控器操作」的键. */
private val TV_WALL_NAV_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)

/** [state] 这一行此刻完整露在起始停靠线以后的第一张 (第一张越过了停靠线就算下一张). */
internal fun tvRowFirstFullIndex(state: LazyListState): Int =
    state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > 0) 1 else 0

/** 左右滑翻一项要的横向位移. */
private val TV_WALL_SWIPE_FLIP_DISTANCE = 64.dp

/** 位移不够时, 横向速度过这么多也翻 (每秒, 同 Compose 分页器的翻页速度门槛). */
private val TV_WALL_SWIPE_FLIP_VELOCITY = 400.dp
