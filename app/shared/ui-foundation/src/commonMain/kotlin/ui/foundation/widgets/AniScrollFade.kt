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
 *
 * 渐隐盖在内容上, 不另外给它留空: 列表上下的留白只按首末项聚焦放大、投影要的余量给, 不按 [fade] 给 —— 滚到头的那一边不隐, 内容贴着正常的边;
 * 还有内容的那一边, 露出来的那一截往边上淡掉. 遥控器上焦点项滚进来时避开渐隐那一截靠 [ProvideScrollFadeFocusMargin] (只在滚的时候), 也不靠留空.
 *
 * 只知道能不能滚、不知道还藏着多少时用这个: 能滚就是整条渐隐. 内容贴着边排时, 滚动的第一下渐隐整条盖上去、滚到头那一下整条撤掉,
 * 看得出一条带子跳出来 / 消失 —— 知道藏着多少的用 [aniVerticalScrollFadeByHidden] (下面几个按滚动状态的重载都是).
 *
 * @param topFade 顶边那条渐隐的高, 默认同底边 ([fade]).
 * @param topClear 顶边往下这么高整个擦掉, 渐隐从它下面开始 (往上滚的时候才有). 给往上伸进别处空地的内容用: 平时首行的投影画得进那截空地,
 *   滚动时内容到不了那里.
 */
fun Modifier.aniVerticalScrollFade(
    canScrollUp: () -> Boolean,
    canScrollDown: () -> Boolean,
    fade: Dp = ANI_SCROLL_FADE,
    topFade: Dp = fade,
    topClear: Dp = 0.dp,
): Modifier = aniVerticalScrollFadeByHidden(
    { if (canScrollUp()) Float.POSITIVE_INFINITY else 0f },
    { if (canScrollDown()) Float.POSITIVE_INFINITY else 0f },
    fade, topFade, topClear,
)

/**
 * 同 [aniVerticalScrollFade], 渐隐跟着那一边还藏着多少滑进来: 藏着的不到那条渐隐的高 (顶上算上 [topClear]) 时, 那条渐隐往边外挪出去
 * 「高 - 藏着的」那么多, 只露出靠边的一截. 看着等于渐隐一直在边外, 内容滚多少就有多少滚进渐隐里 (同首行上面留了一截空、渐隐盖在空白上),
 * 不在滚动的第一下整条盖到内容上 —— 内容贴着边排, 那样看得出一条带子浮出来; 快滚到头时同样一点点滑出去.
 *
 * @param hiddenAbove 上面还藏着多少 (px): 滚到头是 0, 不知道具体多少就给 [Float.POSITIVE_INFINITY]. [hiddenBelow] 同理. 都在绘制阶段读.
 */
fun Modifier.aniVerticalScrollFadeByHidden(
    hiddenAbove: () -> Float,
    hiddenBelow: () -> Float,
    fade: Dp = ANI_SCROLL_FADE,
    topFade: Dp = fade,
    topClear: Dp = 0.dp,
): Modifier = this
    // 离屏合成: 下面用 DstOut 把边缘那一条的内容「擦淡」, 只擦这一层, 不连带擦到后面的面板
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = fade.toPx().coerceAtMost(size.height / 2)
        val topClearPx = topClear.toPx().coerceIn(0f, size.height / 2)
        val topF = topFade.toPx().coerceAtMost(size.height / 2 - topClearPx)
        val topLength = topClearPx + topF
        val topOut = edgeFadeOutside(hiddenAbove(), topLength)
        if (topOut < topLength) {
            // 整条往上挪出去 topOut, 挪出层外的部分不画
            val clearEnd = topClearPx - topOut
            if (clearEnd > 0f) drawRect(Color.Black, size = Size(size.width, clearEnd), blendMode = BlendMode.DstOut)
            if (topF > 0f) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = clearEnd, endY = topLength - topOut),
                    topLeft = Offset(0f, clearEnd.coerceAtLeast(0f)),
                    size = Size(size.width, topLength - topOut - clearEnd.coerceAtLeast(0f)),
                    blendMode = BlendMode.DstOut,
                )
            }
        }
        val bottomOut = edgeFadeOutside(hiddenBelow(), f)
        if (bottomOut < f) {
            // 整条往下挪出去 bottomOut
            val start = size.height - f + bottomOut
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = start, endY = start + f),
                topLeft = Offset(0f, start),
                size = Size(size.width, size.height - start),
                blendMode = BlendMode.DstOut,
            )
        }
    }

/** 一边的渐隐要往边外挪出去多少: 藏着的有渐隐那么高就不挪 (0), 没有藏着的就整条挪出去 ([length]). */
internal fun edgeFadeOutside(hidden: Float, length: Float): Float =
    if (length <= 0f || hidden <= 0f) length else (length - hidden).coerceAtLeast(0f)

/** 见 [aniVerticalScrollFadeByHidden]. */
fun Modifier.aniVerticalScrollFade(state: LazyGridState, fade: Dp = ANI_SCROLL_FADE, topFade: Dp = fade): Modifier =
    aniVerticalScrollFadeByHidden({ state.hiddenAbove() }, { state.hiddenBelow() }, fade, topFade)

/** 见 [aniVerticalScrollFadeByHidden]. */
fun Modifier.aniVerticalScrollFade(state: LazyListState, fade: Dp = ANI_SCROLL_FADE): Modifier =
    aniVerticalScrollFadeByHidden({ state.hiddenAbove() }, { state.hiddenBelow() }, fade)

/** 见 [aniVerticalScrollFadeByHidden]. */
fun Modifier.aniVerticalScrollFade(state: ScrollState, fade: Dp = ANI_SCROLL_FADE): Modifier =
    aniVerticalScrollFadeByHidden({ state.value.toFloat() }, { (state.maxValue - state.value).toFloat() }, fade)

// 懒加载列表只排了看得见的几项: 首项 (末项) 不在视野里时不知道藏着多少, 当作很多
private fun LazyGridState.hiddenAbove(): Float = when {
    !canScrollBackward -> 0f
    firstVisibleItemIndex > 0 -> Float.POSITIVE_INFINITY
    else -> firstVisibleItemScrollOffset.toFloat()
}

private fun LazyGridState.hiddenBelow(): Float {
    if (!canScrollForward) return 0f
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return Float.POSITIVE_INFINITY
    if (last.index < info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    return (last.offset.y + last.size.height + info.afterContentPadding - info.viewportEndOffset).toFloat()
}

private fun LazyListState.hiddenAbove(): Float = when {
    !canScrollBackward -> 0f
    firstVisibleItemIndex > 0 -> Float.POSITIVE_INFINITY
    else -> firstVisibleItemScrollOffset.toFloat()
}

private fun LazyListState.hiddenBelow(): Float {
    if (!canScrollForward) return 0f
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return Float.POSITIVE_INFINITY
    if (last.index < info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
}

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

/**
 * 弹窗标题行下面那条渐隐的高: 内容在离标题行一段距离处淡掉, 碰不到标题. 这一截在没滚的时候是空的, 所以比 [ANI_SCROLL_FADE] 窄,
 * 标题行下面不空一大块.
 */
val ANI_SCROLL_FADE_UNDER_TITLE: Dp = 20.dp
