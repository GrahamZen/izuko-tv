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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
 * 触屏上海报墙的两个手势 (卡片本身的点按由卡片处理): [onBlankTap] = 点在卡片之间的空白处 (卡片没有消费这次抬起、也没拖动);
 * [onSwipe] = 左右滑 (左滑 +1, 右滑 −1; 竖着滑交给网格滚动). 只旁听, 不消费. [enabled] 为 false (电视) 时原样返回.
 */
@Composable
internal fun Modifier.tvWallTouchGestures(
    enabled: Boolean,
    onBlankTap: () -> Unit,
    onSwipe: ((delta: Int) -> Unit)?,
): Modifier {
    if (!enabled) return this
    val currentBlankTap by rememberUpdatedState(onBlankTap)
    val currentSwipe by rememberUpdatedState(onSwipe)
    return pointerInput(Unit) {
        val swipeDistance = TV_WALL_SWIPE_DISTANCE.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var dy = 0f
            var upConsumed = true
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                dx = change.position.x - down.position.x
                dy = change.position.y - down.position.y
                if (change.changedToUpIgnoreConsumed()) {
                    upConsumed = change.isConsumed
                    break
                }
                if (!change.pressed) break
            }
            val moved = abs(dx) > slop || abs(dy) > slop
            val swipe = currentSwipe
            when {
                !moved && !upConsumed -> currentBlankTap()
                swipe != null && abs(dx) > swipeDistance && abs(dx) > abs(dy) * 1.5f -> swipe(if (dx < 0) 1 else -1)
            }
        }
    }
}

/** 左右滑至少多远才算换页. */
private val TV_WALL_SWIPE_DISTANCE = 48.dp
