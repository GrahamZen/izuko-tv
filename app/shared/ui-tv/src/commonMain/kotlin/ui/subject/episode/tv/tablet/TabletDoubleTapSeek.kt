/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tablet_player_double_tap_seek_seconds
import org.jetbrains.compose.resources.stringResource

/**
 * 画面横着分三列 (同竖滑: 左亮度 / 中间进出全屏 / 右音量), 双击时: 左边后退、中间播放 / 暂停、右边快进.
 */
internal enum class TabletTapZone { BACK, MIDDLE, FORWARD }

internal fun tabletTapZone(x: Float, width: Float): TabletTapZone = when {
    x < width / 3f -> TabletTapZone.BACK
    x > width * 2f / 3f -> TabletTapZone.FORWARD
    else -> TabletTapZone.MIDDLE
}

/**
 * 双击两侧快进退 (同 YouTube): 双击跳 [TABLET_DOUBLE_TAP_SEEK_STEP_MILLIS], 侧边提示还在 ([visible]) 时再点一下两侧立刻再跳一步,
 * 提示上的秒数累加; 换到另一侧从头记. 提示在最后一下之后 [TABLET_SEEK_STREAK_WINDOW_MILLIS] 收起, 收起后再点就是普通的单击.
 *
 * 连着跳时从上一下的落点接着算, 不读播放器的当前位置 (刚 seek 完那一刻位置还没更新, 读它会丢步).
 */
@Stable
internal class TabletSeekStreakState {
    var visible: Boolean by mutableStateOf(false)
        private set

    /** 这一串是快进还是后退. 收起后保留, 淡出时提示还画在原来那一侧. */
    var forward: Boolean by mutableStateOf(true)
        private set

    /** 这一串在 [forward] 方向上累计跳了多少. */
    var accumulatedMillis: Long by mutableLongStateOf(0L)
        private set

    /** 每跳一下自增, 收起提示的计时从最后一下重新算. */
    var tick: Int by mutableIntStateOf(0)
        private set

    private var targetMillis = 0L

    /**
     * 再跳一步, 返回要 seek 到的位置: 提示还在就从上一下的落点接着算, 否则从 [currentMillis] 起算.
     * [totalMillis] 已知 (> 0) 时落点夹在片头片尾之间, 未知时只保证不小于 0.
     */
    fun step(forward: Boolean, currentMillis: Long, totalMillis: Long): Long {
        val continuing = visible
        val base = if (continuing) targetMillis else currentMillis
        val raw = base + if (forward) TABLET_DOUBLE_TAP_SEEK_STEP_MILLIS else -TABLET_DOUBLE_TAP_SEEK_STEP_MILLIS
        targetMillis = if (totalMillis > 0L) raw.coerceIn(0L, totalMillis) else raw.coerceAtLeast(0L)
        accumulatedMillis = if (continuing && forward == this.forward) {
            accumulatedMillis + TABLET_DOUBLE_TAP_SEEK_STEP_MILLIS
        } else {
            TABLET_DOUBLE_TAP_SEEK_STEP_MILLIS
        }
        this.forward = forward
        visible = true
        tick++
        return targetMillis
    }

    /** 收起提示, 这一串结束. */
    fun end() {
        visible = false
    }
}

/** 双击两侧每下跳多少 (同 YouTube 默认). 遥控器左右键仍是 5 秒 (TV_PLAYER_SEEK_STEP_MILLIS). */
internal const val TABLET_DOUBLE_TAP_SEEK_STEP_MILLIS = 10_000L

/** 最后一下之后多久收起提示、结束这一串. */
internal const val TABLET_SEEK_STREAK_WINDOW_MILLIS = 800L

/**
 * 双击两侧的提示: 那一侧三分之一宽、整高的半椭圆淡白底, 中间快进 / 后退图标 + 累计秒数; 摆在视频框里 (matchParentSize).
 * 不接触摸, 点击照样落到下面的手势层.
 */
@Composable
internal fun TabletSeekStreakIndicator(state: TabletSeekStreakState, modifier: Modifier = Modifier) {
    if (state.visible) {
        LaunchedEffect(state.tick) {
            delay(TABLET_SEEK_STREAK_WINDOW_MILLIS)
            state.end()
        }
    }
    Box(modifier) {
        for (forward in listOf(false, true)) {
            AnimatedVisibility(
                visible = state.visible && state.forward == forward,
                modifier = Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxWidth(1f / 3f).fillMaxHeight(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight()
                        .clip(if (forward) ForwardArcShape else BackArcShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                            contentDescription = null,
                            Modifier.size(32.dp),
                            tint = Color.White,
                        )
                        Text(
                            stringResource(
                                Lang.tablet_player_double_tap_seek_seconds,
                                (state.accumulatedMillis / 1000).toInt(),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** 左侧提示的底: 圆心在左边缘外的椭圆, 露出的弧顶在三分之一处. */
private val BackArcShape = GenericShape { size, _ ->
    addOval(Rect(-size.width, -size.height * 0.25f, size.width, size.height * 1.25f))
}

/** 右侧提示的底, 与 [BackArcShape] 左右对称. */
private val ForwardArcShape = GenericShape { size, _ ->
    addOval(Rect(0f, -size.height * 0.25f, size.width * 2f, size.height * 1.25f))
}
