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
 * - 暂停, 或视频已经停了而界面线程还没转告 (按暂停时界面线程常要忙一阵) 时停在当前位置, 不跳回界面时钟;
 *   继续播放后从停住的位置接着走, 照上面慢慢靠拢.
 *
 * 不是线程安全的, 只在渲染线程上用.
 */
internal class DanmakuRenderClock(
    private val snapNanos: Long = 2_000_000_000L,
    private val maxCorrectionRatio: Float = 0.03f,
    private val correctionGain: Float = 0.05f,
) {
    private var started = false
    private var lastElapsed = 0L
    private var lastNow = 0L

    // 视频停了、界面还没说暂停: 从哪一刻开始停住的 (0 = 没有)
    private var stoppedAheadSinceNanos = 0L

    /** 本次对齐前与界面时钟差了多少 (正 = 界面时钟在前); 诊断用. */
    var lastErrorNanos = 0L
        private set

    /** 差得太多直接对齐过几次; 诊断用. */
    var snaps = 0
        private set

    /** 视频停了之后多久界面才说暂停, 取最大的; 诊断用, [drainMaxStopLeadNanos] 取走清零. */
    private var maxStopLeadNanos = 0L

    fun drainMaxStopLeadNanos(): Long = maxStopLeadNanos.also { maxStopLeadNanos = 0L }

    /**
     * 渲染时刻 [nowNanos] 的弹幕时间. [referenceNanos] 是界面时钟推到这一刻的值 ([DanmakuLayerFrame.elapsedAt]),
     * [paused] 是界面发来的暂停状态, [videoStopped] 是渲染线程自己读到的「视频此刻没在播」.
     */
    fun elapsedAt(referenceNanos: Long, paused: Boolean, nowNanos: Long, videoStopped: Boolean = false): Long {
        if (!started) {
            started = true
            return remember(referenceNanos, nowNanos)
        }
        if (paused || videoStopped) {
            if (!paused) {
                if (stoppedAheadSinceNanos == 0L) stoppedAheadSinceNanos = nowNanos
            } else if (stoppedAheadSinceNanos != 0L) {
                maxStopLeadNanos = maxOf(maxStopLeadNanos, nowNanos - stoppedAheadSinceNanos)
                stoppedAheadSinceNanos = 0L
            }
            val error = referenceNanos - lastElapsed
            lastErrorNanos = error
            // 停着时界面时钟一般不动; 差得远 (重新装填等) 才跟过去
            if (abs(error) > snapNanos) {
                snaps++
                return remember(referenceNanos, nowNanos)
            }
            return remember(lastElapsed, nowNanos)
        }
        stoppedAheadSinceNanos = 0L
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
