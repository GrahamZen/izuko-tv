/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 详情页在触屏设备 (平板装了 TV 包) 上的手指滑动: 跟手滚, 松手吸附到某一页的起点, 不停在两页中间.
 *
 * 详情页按页翻 (见 SubjectDetailsTvPage 的 TvDetailsPager): 遥控器上焦点进哪一页, 滚动量就是那一页的起点. 手指拖动直接改滚动量,
 * 默认惯性停在哪是哪 —— 这里换掉松手后的惯性: 按松手位置与速度挑一页滚过去, 停稳后由页面把这一页记进翻页器并送焦 (同遥控器翻页),
 * 之后的遥控器 / 返回键分层都从这一页接着算.
 */

/** 可停的一页: [page] 页序号, [start] 起点 (滚动量), [restEnd] 能停到的最远滚动量 (本页比一屏高时大于 [start], 其余等于). */
internal data class TvDetailsSnapPage(val page: Int, val start: Float, val restEnd: Float)

/** 吸附落点: 停在滚动量 [scroll], 属于第 [page] 页. */
internal data class TvDetailsSnapTarget(val page: Int, val scroll: Float)

/**
 * 松手时停在哪 (纯函数). [pages] 按起点升序, [scroll] 是松手时的滚动量, [velocity] 是滚动方向的速度 (正 = 往下翻, 即滚动量变大).
 *
 * - 落在某页能停的范围里 (比一屏高的页的页内, 或正好在页起点): 速度不够就原地停; 往下甩先到本页底, 已在页底才去下一页; 往上甩回本页起点.
 * - 落在两页之间: 速度够就按甩的方向去那一页, 否则去离得近的那一边 (上一页的页底 / 下一页的起点).
 * - 过了最后一页能停的位置: 回最后一页的页底.
 *
 * 没有可停的页时返回 null (照默认惯性).
 */
internal fun tvDetailsTouchSnapTarget(
    pages: List<TvDetailsSnapPage>,
    scroll: Float,
    velocity: Float,
    velocityThreshold: Float,
): TvDetailsSnapTarget? {
    if (pages.isEmpty()) return null
    val index = pages.indexOfLast { it.start <= scroll }
    if (index < 0) return pages.first().let { TvDetailsSnapTarget(it.page, it.start) }
    val here = pages[index]
    val next = pages.getOrNull(index + 1)
    val fling = abs(velocity) >= velocityThreshold
    if (scroll <= here.restEnd) {
        return when {
            !fling -> TvDetailsSnapTarget(here.page, scroll)
            velocity > 0f && scroll < here.restEnd -> TvDetailsSnapTarget(here.page, here.restEnd)
            velocity > 0f -> next?.let { TvDetailsSnapTarget(it.page, it.start) } ?: TvDetailsSnapTarget(here.page, here.restEnd)
            else -> TvDetailsSnapTarget(here.page, here.start)
        }
    }
    if (next == null) return TvDetailsSnapTarget(here.page, here.restEnd)
    val toNext = when {
        fling -> velocity > 0f
        else -> next.start - scroll < scroll - here.restEnd
    }
    return if (toNext) TvDetailsSnapTarget(next.page, next.start) else TvDetailsSnapTarget(here.page, here.restEnd)
}

/** 松手后滚到 [tvDetailsTouchSnapTarget] 挑的位置, 停稳再报 [onSettled] (被新的触摸打断则不报). */
internal class TvDetailsTouchSnapFling(
    private val scrollState: ScrollState,
    private val pages: () -> List<TvDetailsSnapPage>,
    private val velocityThreshold: Float,
    private val onSettled: (page: Int, scroll: Int) -> Unit,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val from = scrollState.value.toFloat()
        val target = tvDetailsTouchSnapTarget(pages(), from, initialVelocity, velocityThreshold) ?: return initialVelocity
        val to = target.scroll.roundToInt().coerceIn(0, scrollState.maxValue).toFloat()
        if (to != from) {
            // 时长按距离 (折算到 1080p) 取, 同遥控器翻页的量级; 先快后慢, 接得上手指的速度
            val distance1080 = abs(to - from) * 1080f / scrollState.viewportSize.coerceAtLeast(1)
            val millis = (TV_DETAILS_SNAP_MIN_MILLIS + (TV_DETAILS_SNAP_MAX_MILLIS - TV_DETAILS_SNAP_MIN_MILLIS) *
                    ((distance1080 - 150f) / 300f).coerceIn(0f, 1f)).roundToInt()
            var last = from
            AnimationState(from).animateTo(to, tween(millis, easing = LinearOutSlowInEasing)) {
                val delta = value - last
                val consumed = scrollBy(delta)
                last += consumed
                if (abs(consumed - delta) > 0.5f) cancelAnimation()
            }
        }
        onSettled(target.page, scrollState.value)
        return 0f
    }
}

/**
 * 详情页整页滚动的触屏吸附惯性; 电视上 (没有触摸屏) 返回 null, 整页滚动照默认, 一处都不变.
 * [pages] / [onSettled] 见 [TvDetailsTouchSnapFling], 每次松手时现取.
 */
@Composable
internal fun rememberTvDetailsTouchSnap(
    scrollState: ScrollState,
    pages: () -> List<TvDetailsSnapPage>,
    onSettled: (page: Int, scroll: Int) -> Unit,
): FlingBehavior? {
    if (!LocalTvTouchInputEnabled.current) return null
    val threshold = with(LocalDensity.current) { TV_DETAILS_SNAP_VELOCITY_DP.dp.toPx() }
    val currentPages by rememberUpdatedState(pages)
    val currentOnSettled by rememberUpdatedState(onSettled)
    return remember(scrollState, threshold) {
        TvDetailsTouchSnapFling(scrollState, { currentPages() }, threshold) { page, scroll -> currentOnSettled(page, scroll) }
    }
}

/** 甩得够快就按方向翻页的速度门槛 (同 Compose 分页器). */
private const val TV_DETAILS_SNAP_VELOCITY_DP = 400

private const val TV_DETAILS_SNAP_MIN_MILLIS = 160
private const val TV_DETAILS_SNAP_MAX_MILLIS = 300
