/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

import kotlin.math.abs

/**
 * [DanmakuLayer] 渲染线程上的弹幕时钟: 按真实时间往前走, 只缓缓向界面线程发来的时钟靠拢.
 *
 * 界面那边的弹幕时钟 ([DanmakuHostState.elapsedFrameTimeNanos]) 掉帧时不一次补齐, 250ms 以内的亏欠分几十帧慢慢还
 * (见 [FrameTimeSmoother]); 渲染线程在界面卡住时照真实时间往前推, 界面恢复后发来的时钟还落在后面.
 * 直接照它画, 弹幕会往回跳一下再继续 (打开菜单时最明显). 所以这里:
 * - 每帧按真实经过的时间推进, 再向界面时钟修正, 修正量不超过这段时间的 [maxCorrectionRatio], 不倒退;
 * - 界面时钟只按帧往前加、跳进度也不重置, 连着几帧慢帧时亏欠能攒到几百毫秒; 差得超过 [snapNanos] 才直接对齐 (兜底);
 * - 暂停时就是界面时钟.
 *
 * 不是线程安全的, 只在渲染线程上用.
 */
internal class DanmakuRenderClock(
    private val snapNanos: Long = 2_000_000_000L,
    private val maxCorrectionRatio: Float = 0.03f,
    private val correctionGain: Float = 0.05f,
) {
    private var lastElapsed = 0L
    private var lastNow = 0L
    private var running = false

    /** 本次对齐前与界面时钟差了多少 (正 = 界面时钟在前); 诊断用. */
    var lastErrorNanos = 0L
        private set

    /** 差得太多直接对齐过几次; 诊断用. */
    var snaps = 0
        private set

    /** 渲染时刻 [nowNanos] 的弹幕时间. [referenceNanos] 是界面时钟推到这一刻的值 ([DanmakuLayerFrame.elapsedAt]). */
    fun elapsedAt(referenceNanos: Long, paused: Boolean, nowNanos: Long): Long {
        if (paused || !running) {
            running = !paused
            lastErrorNanos = 0L
            return remember(referenceNanos, nowNanos)
        }
        val dt = (nowNanos - lastNow).coerceAtLeast(0L)
        val free = lastElapsed + dt
        val error = referenceNanos - free
        lastErrorNanos = error
        if (abs(error) > snapNanos) {
            snaps++
            return remember(referenceNanos, nowNanos)
        }
        val maxCorrection = (dt * maxCorrectionRatio).toLong()
        val correction = (error * correctionGain).toLong().coerceIn(-maxCorrection, maxCorrection)
        return remember(free + correction, nowNanos)
    }

    private fun remember(elapsed: Long, now: Long): Long {
        lastElapsed = elapsed
        lastNow = now
        return elapsed
    }
}
