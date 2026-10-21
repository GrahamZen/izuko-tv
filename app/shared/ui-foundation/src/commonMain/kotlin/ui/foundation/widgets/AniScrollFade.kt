/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior

/**
 * 可滚内容在上下边缘渐隐 (同 tvOS 的模态面板): 上面还有没露出来的就顶边 [fade] 之内渐隐, 下面还有就底边渐隐, 滚到头的那一边不隐.
 * 用在弹窗里会滚的列表上 —— 不然多出来的卡片在面板内边距那条线上一刀切断, 看着像被一道边框截住.
 *
 * 能不能往上 / 往下滚在绘制阶段读 ([canScrollUp] / [canScrollDown]), 滚动只重画不重组.
 */
fun Modifier.aniVerticalScrollFade(
    canScrollUp: () -> Boolean,
    canScrollDown: () -> Boolean,
    fade: Dp = ANI_SCROLL_FADE,
): Modifier = this
    // 离屏合成: 下面用 DstOut 把边缘那一条的内容「擦淡」, 只擦这一层, 不连带擦到后面的面板
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = fade.toPx().coerceAtMost(size.height / 2)
        if (f <= 0f) return@drawWithContent
        if (canScrollUp()) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = 0f, endY = f),
                size = Size(size.width, f),
                blendMode = BlendMode.DstOut,
            )
        }
        if (canScrollDown()) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - f, endY = size.height),
                topLeft = Offset(0f, size.height - f),
                size = Size(size.width, f),
                blendMode = BlendMode.DstOut,
            )
        }
    }

/** 见 [aniVerticalScrollFade]. */
fun Modifier.aniVerticalScrollFade(state: LazyGridState, fade: Dp = ANI_SCROLL_FADE): Modifier =
    aniVerticalScrollFade({ state.canScrollBackward }, { state.canScrollForward }, fade)

/** 见 [aniVerticalScrollFade]. */
fun Modifier.aniVerticalScrollFade(state: LazyListState, fade: Dp = ANI_SCROLL_FADE): Modifier =
    aniVerticalScrollFade({ state.canScrollBackward }, { state.canScrollForward }, fade)

/** 见 [aniVerticalScrollFade]. */
fun Modifier.aniVerticalScrollFade(state: ScrollState, fade: Dp = ANI_SCROLL_FADE): Modifier =
    aniVerticalScrollFade({ state.canScrollBackward }, { state.canScrollForward }, fade)

/**
 * 配合 [aniVerticalScrollFade]: 焦点驱动的设备上 (遥控器), 焦点项滚进视野时上下各多留 [fade], 焦点所在那一行不落在渐隐的半透明里;
 * 其余同默认 (整个看得见就不滚). 指针设备上没有焦点滚动, 原样.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideScrollFadeFocusMargin(fade: Dp = ANI_SCROLL_FADE, content: @Composable () -> Unit) {
    if (!LocalAniUiBehavior.current.focusDrivenNavigation) {
        content()
        return
    }
    val density = LocalDensity.current
    val spec = remember(density, fade) { FadeMarginBringIntoViewSpec(with(density) { fade.toPx() }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** 见 [ProvideScrollFadeFocusMargin]. */
@OptIn(ExperimentalFoundationApi::class)
private class FadeMarginBringIntoViewSpec(private val marginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - marginPx
        val end = offset + size + marginPx
        return when {
            start >= 0f && end <= containerSize -> 0f
            // 比视野还高: 顶边对齐, 不来回抖
            end - start > containerSize -> start
            start < 0f -> start
            else -> end - containerSize
        }
    }
}

/** 渐隐那一条的高. */
val ANI_SCROLL_FADE: Dp = 32.dp
