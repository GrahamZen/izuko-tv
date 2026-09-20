/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 方向键是否正被按着 —— 「按住」的真信号 (TV 壳根部拦截器登记; 手机没有 = 恒 false).
 *
 * 用来回答"这一格是单击还是按住的第一格". 靠时间猜不行: 系统的按键自动重复 ~400ms 才开始, 而单格滚动
 * ~250ms 就停稳, 于是按住的第一格总被当成单击处理完 —— 文字闪出第二项、背景换到第二张后整个按住期间停在
 * 第二张; 反过来给背景加 500ms 守卫, 慢慢一格一格走的人每到一张卡背景都还是上一张 (2026-09-10 两条路
 * 都在 Shield 上试过, 用户都不要). **抬起事件才是判据**: 单击的抬起在 +100ms 左右, 远早于停稳, 单击零延迟;
 * 按住要到松手才抬起, 文字与背景就一直等着.
 *
 * [held] 是 snapshot 状态但只在按下 / 抬起翻转时写, 连发不写, 不会每一发都触发失效 (同 TvKeyLongPressHost
 * 的顾虑). 抬起丢失的兜底 (按着方向键时弹出别的窗口, KeyUp 去了别处): 连发停了
 * [TV_NAV_KEY_HELD_TIMEOUT_MILLIS] 没再来就当松开 —— 系统连发每 ~50ms 一发, 没有连发就不可能还按着.
 */
@Stable
class TvNavKeyTracker internal constructor(private val scope: CoroutineScope) {
    var held: Boolean by mutableStateOf(false)
        private set

    /**
     * 系统自动连发已经开始 (按住不放约 400ms 后, 第二个 KeyDown 起), 直到抬起. 与 [held] 的区别是把按住的
     * **第一格**排除在外: 单击也会经过 held == true 的 ~100ms.
     *
     * 为什么需要它: 自动连发的起始延迟 (Shield 实测 405ms) 比 [TV_NAV_SETTLE_MILLIS] 长, 第二拍在静默闸门
     * 看来不是连发; 而新目标一到"这一拍滚过卡片"要重新计, 滚动又要一两帧才起步 —— 这 40ms 里文字块没有任何
     * 藏的理由, 停稳前的旧文字就带着入场动画闪一下 (用户 2026-09-10 报, TvHoldProbe 日志定的). 判"连发"不靠
     * repeatCount (commonMain 拿不到): KeyDown 来时已经 held 就是连发, 因为抬起一定先清 held.
     */
    var repeating: Boolean by mutableStateOf(false)
        private set
    private var release: Job? = null

    /** 根部拦截器的事件入口, 从不消费. */
    fun onRootKeyEvent(event: KeyEvent): Boolean {
        if (event.key !in TV_NAV_KEYS) return false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (held) repeating = true else held = true
                release?.cancel()
                release = scope.launch {
                    delay(TV_NAV_KEY_HELD_TIMEOUT_MILLIS)
                    held = false
                    repeating = false
                }
            }

            KeyEventType.KeyUp -> {
                release?.cancel()
                release = null
                held = false
                repeating = false
            }

            else -> {}
        }
        return false
    }
}

/** 当前的方向键跟踪器; null = 没装 (手机布局), 各处不等抬起. */
val LocalTvNavKeyTracker = staticCompositionLocalOf<TvNavKeyTracker?> { null }

@Composable
fun rememberTvNavKeyTracker(): TvNavKeyTracker {
    val scope = rememberCoroutineScope()
    return remember { TvNavKeyTracker(scope) }
}

/** 挂在 TV 壳根节点上, 与 `tvKeyLongPressInterceptor` 并列. */
fun Modifier.tvNavKeyInterceptor(tracker: TvNavKeyTracker): Modifier =
    onPreviewKeyEvent { tracker.onRootKeyEvent(it) }

private val TV_NAV_KEYS = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/** 连发多久没来就当松开: 大于自动重复的起始延迟 (ViewConfiguration 默认 400ms) 并留余量. */
private const val TV_NAV_KEY_HELD_TIMEOUT_MILLIS = 700L
