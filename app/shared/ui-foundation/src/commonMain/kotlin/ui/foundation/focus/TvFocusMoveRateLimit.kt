/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import me.him188.ani.app.ui.foundation.isAutoRepeat
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/**
 * 长按**左右**键时焦点移动的频率上限 (次/秒): 高于系统连发 (按住期间约 50ms 一发 ≈ 20 次/秒), 按住时每一发都走一格.
 *
 * 照 Apple TV 安卓版 (2026-09-29 Shield 逐帧实测: 横向约 20 张/秒, 不另外限速): 每一段滚动接着上一段的速度, 连成一段匀速滑动,
 * 途中焦点领先滚动两三格 (原生横滑行因此行外多排几张, 见 ui-tv 的 TV_NATIVE_STRIP_HOLD_LEAD_CARDS). 留着上限只挡连发异常快的遥控器.
 * 用户定的, 改前先问.
 */
const val TV_FOCUS_MOVE_MAX_PER_SECOND_HORIZONTAL = 25

/**
 * 长按**上下**键时焦点移动的频率上限 (次/秒), 同横向 ([TV_FOCUS_MOVE_MAX_PER_SECOND_HORIZONTAL]): Apple TV 安卓版纵向实测约 20 行/秒.
 * 纵向列表 / 网格上下各多排一屏, 滚动落后焦点的两三行都已排好; hero 文字与背景图等连发停下来才换 (TvScrollActivity 的静默期).
 * 用户定的, 改前先问.
 */
const val TV_FOCUS_MOVE_MAX_PER_SECOND_VERTICAL = 25

/** 遥控器左右键. */
val TV_HORIZONTAL_KEYS = setOf(Key.DirectionLeft, Key.DirectionRight)

/** 遥控器上下键. */
val TV_VERTICAL_KEYS = setOf(Key.DirectionUp, Key.DirectionDown)

/**
 * 给一整片焦点区域的方向键限流: 长按方向键时把"每秒移动几格"压到上限以内, 超频的那几发连发
 * 直接吞掉 (不下传, 于是既不移动焦点也不触发区域内自定义的方向键处理).
 *
 * **横纵两个独立的闸门**: 各有各的上限, 各自计时互不影响 —— 共用一个闸门的话刚换过行会连带压掉紧接着的左右移动.
 *
 * **只限流系统连发**: `isAutoRepeat == false` 的新按下一律放行 —— 手动连按再快也不该丢键
 * (拿不到连发信息的平台上退化为全部限流, 焦点驱动的 TV UI 只在 Android 上启用).
 * 也因此不需要在抬起时复位: 松手再按就是一次新按下, 天然免疫.
 *
 * 挂在**区域容器**上 (卡片区 Box / 轮播 LazyRow), 不是每张卡上: `onPreviewKeyEvent` 自根向下
 * 传, 容器先于其中的卡片拿到事件; 计时状态也随容器唯一, 换卡不重置 (挂在卡上就成了"每张卡
 * 各自限流", 一路向右每张卡都放行第一发, 等于没限).
 *
 * @param horizontalMaxPerSecond 左右键上限, 见 [TV_FOCUS_MOVE_MAX_PER_SECOND_HORIZONTAL]
 * @param verticalMaxPerSecond 上下键上限, 见 [TV_FOCUS_MOVE_MAX_PER_SECOND_VERTICAL]
 */
fun Modifier.tvFocusMoveRateLimit(
    horizontalMaxPerSecond: Int = TV_FOCUS_MOVE_MAX_PER_SECOND_HORIZONTAL,
    verticalMaxPerSecond: Int = TV_FOCUS_MOVE_MAX_PER_SECOND_VERTICAL,
): Modifier = composed {
    val horizontal = remember(horizontalMaxPerSecond) {
        TvKeyRateLimiter((1000L / horizontalMaxPerSecond).milliseconds)
    }
    val vertical = remember(verticalMaxPerSecond) {
        TvKeyRateLimiter((1000L / verticalMaxPerSecond).milliseconds)
    }
    onPreviewKeyEvent { event ->
        val limiter = when (event.key) {
            in TV_HORIZONTAL_KEYS -> horizontal
            in TV_VERTICAL_KEYS -> vertical
            else -> null
        }
        when {
            limiter == null -> false
            event.type != KeyEventType.KeyDown -> false
            event.isAutoRepeat == false -> false // 新的一次按下: 从不丢
            else -> !limiter.tryPass() // 超频 -> 消费掉这一发连发
        }
    }
}

/** 单调时钟上的最小间隔闸门; 只在按键回调里读写, 不进组合. */
private class TvKeyRateLimiter(private val minInterval: Duration) {
    private var lastPassed: TimeSource.Monotonic.ValueTimeMark? = null

    /** true = 放行并记账; false = 距上一次放行还不够久. */
    fun tryPass(): Boolean {
        val last = lastPassed
        if (last != null && last.elapsedNow() < minInterval) return false
        lastPassed = TimeSource.Monotonic.markNow()
        return true
    }
}
