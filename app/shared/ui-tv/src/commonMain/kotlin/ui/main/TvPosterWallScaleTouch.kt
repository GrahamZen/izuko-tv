/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_adjust_done
import me.him188.ani.app.ui.lang.tv_wall_scale_hint_drag
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 「海报墙大小」页 (TvPosterWallScalePage) 的触屏操作 (平板 / iPad): 滑块上点一下或上下拖, 换到离手指最近的那一档;
 * 说明换成触屏的一句, 下面一个「完成」离开本页 —— 触屏没有返回键, 这页是盖在页面上的窗口, 只能靠它出去.
 */

/**
 * 滑块上按下与拖动: 手指的高度换成百分数, 落到 [stops] 里最近的一档报给 [onPercent]. 几何同滑块的画法 (上下各留 [padding],
 * 下端是 [minPercent], 上端是 [maxPercent]).
 */
internal fun Modifier.tvWallScaleTrackTouch(
    stops: List<Int>,
    minPercent: Int,
    maxPercent: Int,
    padding: Dp,
    onPercent: (Int) -> Unit,
): Modifier = pointerInput(stops, minPercent, maxPercent, padding) {
    fun percentAt(y: Float): Int {
        val top = padding.toPx()
        val bottom = size.height - top
        val fraction = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        return tvWallScaleNearestStop(stops, minPercent + fraction * (maxPercent - minPercent))
    }
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onPercent(percentAt(down.position.y))
        verticalDrag(down.id) { change ->
            change.consume()
            onPercent(percentAt(change.position.y))
        }
    }
}

/** [stops] 里离 [percent] 最近的一档; 没有档位时取整. */
internal fun tvWallScaleNearestStop(stops: List<Int>, percent: Float): Int =
    stops.minByOrNull { abs(it - percent) } ?: percent.roundToInt()

/** 触屏时底板里说明与「完成」. */
@Composable
internal fun TvWallScaleTouchFooter(onDone: () -> Unit) {
    Text(
        stringResource(Lang.tv_wall_scale_hint_drag),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelLarge,
    )
    AniFocusActionButton(onClick = onDone) {
        Text(stringResource(Lang.tv_adjust_done))
    }
}
