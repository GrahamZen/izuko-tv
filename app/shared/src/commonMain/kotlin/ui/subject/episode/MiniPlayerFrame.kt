/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 应用内小窗的框 (见 [TouchMiniPlayerHost]): 只管摆在哪、多大, 不管里面放什么. 在 [modifier] 给的区域里, 16:9.
 * - 位置: 停在四个角之一 ([corner], 0 左上 1 右上 2 左下 3 右下); 拖动时跟手, 松手吸到离窗口中心最近的角.
 * - 大小: 宽 = 区域宽 × [widthFraction], 限在 [MINI_PLAYER_MIN_FRACTION]~[MINI_PLAYER_MAX_FRACTION] (且不窄于 [MINI_PLAYER_MIN_WIDTH]、不高过区域);
 *   双指捏合绕窗口中心缩放, 双击在 [MINI_PLAYER_SMALL_FRACTION] 与 [MINI_PLAYER_LARGE_FRACTION] 两档之间切换 (贴着所在的角变).
 * [corner] 与 [widthFraction] 由调用方存着, 收起再出来还是原来的角和大小.
 *
 * 手势挂在 [video] 与 [controls] 之间的一层上: iOS 的视频是 UIKit 原生视图, 手势挂在它的父级上收不到触摸; [controls] 里的按钮在最上面, 点按钮不算点小窗.
 */
@Composable
internal fun MiniPlayerFrame(
    corner: MutableIntState,
    widthFraction: MutableFloatState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    video: @Composable BoxScope.() -> Unit,
    controls: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val areaWidth = with(density) { maxWidth.toPx() }
        val areaHeight = with(density) { maxHeight.toPx() }
        val maxWidthPx = minOf(areaWidth * MINI_PLAYER_MAX_FRACTION, areaHeight * 16f / 9f)
        val minWidthPx = maxOf(with(density) { MINI_PLAYER_MIN_WIDTH.toPx() }, areaWidth * MINI_PLAYER_MIN_FRACTION).coerceAtMost(maxWidthPx)
        fun widthOf(fraction: Float) = (areaWidth * fraction).coerceIn(minWidthPx, maxWidthPx)
        fun cornerOffset(corner: Int, width: Float) = Offset(
            if (corner % 2 == 1) (areaWidth - width).coerceAtLeast(0f) else 0f,
            if (corner >= 2) (areaHeight - width * 9f / 16f).coerceAtLeast(0f) else 0f,
        )

        var cornerIndex by corner
        // 正在显示的宽度比例: 捏合时跟手, 双击时动画过去; 停下来才写回 widthFraction
        var fraction by remember { mutableFloatStateOf(widthFraction.floatValue) }
        // 手指拖着 / 松手吸角途中的位置; null = 贴着 cornerIndex 那个角 (转屏、换大小都跟着角走)
        var dragged by remember { mutableStateOf<Offset?>(null) }
        val settle = remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val currentOnTap by rememberUpdatedState(onTap)

        val width = widthOf(fraction)
        val position = dragged ?: cornerOffset(cornerIndex, width)
        Box(
            Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .size(with(density) { width.toDp() }, with(density) { (width * 9f / 16f).toDp() })
                .shadow(MINI_PLAYER_ELEVATION, MINI_PLAYER_SHAPE)
                .clip(MINI_PLAYER_SHAPE)
                .background(Color.Black)
                .testTag(TOUCH_MINI_PLAYER_TAG),
        ) {
            video()
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(areaWidth, areaHeight, minWidthPx, maxWidthPx) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var moving = false
                            var zoomSum = 1f
                            var panSum = Offset.Zero
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) break
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (!moving) {
                                    zoomSum *= zoom
                                    panSum += pan
                                    val zoomMotion = abs(1 - zoomSum) * event.calculateCentroidSize(useCurrent = false)
                                    if (zoomMotion > viewConfiguration.touchSlop || panSum.getDistance() > viewConfiguration.touchSlop) {
                                        moving = true
                                        settle.value?.cancel()
                                    }
                                }
                                if (moving) {
                                    val oldWidth = widthOf(fraction)
                                    val newWidth = widthOf(fraction * zoom)
                                    // 绕窗口中心缩放, 再跟着手指平移; 不出区域
                                    val from = dragged ?: cornerOffset(cornerIndex, oldWidth)
                                    val grow = Offset((newWidth - oldWidth) / 2, (newWidth - oldWidth) * 9f / 16f / 2)
                                    val next = from - grow + pan
                                    fraction = newWidth / areaWidth
                                    dragged = Offset(
                                        next.x.coerceIn(0f, (areaWidth - newWidth).coerceAtLeast(0f)),
                                        next.y.coerceIn(0f, (areaHeight - newWidth * 9f / 16f).coerceAtLeast(0f)),
                                    )
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (moving) {
                                val endWidth = widthOf(fraction)
                                val start = dragged ?: cornerOffset(cornerIndex, endWidth)
                                cornerIndex = (if (start.x + endWidth / 2 > areaWidth / 2) 1 else 0) +
                                        (if (start.y + endWidth * 9f / 16f / 2 > areaHeight / 2) 2 else 0)
                                widthFraction.floatValue = fraction
                                val target = cornerOffset(cornerIndex, endWidth)
                                settle.value = scope.launch {
                                    animate(Offset.VectorConverter, start, target) { value, _ -> dragged = value }
                                    dragged = null
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { currentOnTap() },
                            onDoubleTap = {
                                val target = if (fraction < (MINI_PLAYER_SMALL_FRACTION + MINI_PLAYER_LARGE_FRACTION) / 2) {
                                    MINI_PLAYER_LARGE_FRACTION
                                } else {
                                    MINI_PLAYER_SMALL_FRACTION
                                }
                                widthFraction.floatValue = target
                                settle.value?.cancel()
                                dragged = null
                                settle.value = scope.launch { animate(fraction, target) { value, _ -> fraction = value } }
                            },
                        )
                    },
            )
            controls()
        }
    }
}

/** 宽占区域宽的比例: 默认 (= 双击的小档)、双击的大档、捏合的上下限; 再窄也不窄于 [MINI_PLAYER_MIN_WIDTH]. */
internal const val MINI_PLAYER_SMALL_FRACTION = 0.28f
internal const val MINI_PLAYER_LARGE_FRACTION = 0.45f
internal const val MINI_PLAYER_MIN_FRACTION = 0.2f
internal const val MINI_PLAYER_MAX_FRACTION = 0.5f
internal val MINI_PLAYER_MIN_WIDTH = 200.dp

internal const val MINI_PLAYER_DEFAULT_CORNER = 3

/** 圆角、投影. */
private val MINI_PLAYER_SHAPE = RoundedCornerShape(12.dp)
private val MINI_PLAYER_ELEVATION = 12.dp

const val TOUCH_MINI_PLAYER_TAG = "touch-mini-player"
