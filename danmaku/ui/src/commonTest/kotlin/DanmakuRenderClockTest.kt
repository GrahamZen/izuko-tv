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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmakuRenderClockTest {
    private val frame = 16_666_667L

    @Test
    fun `does not go back when the ui clock falls behind after a stall`() {
        val clock = DanmakuRenderClock()
        var now = 0L
        var rendered = clock.elapsedAt(referenceNanos = 0, paused = false, nowNanos = now)
        // 界面卡了 200ms 后恢复: 界面时钟只补了一帧, 落后真实时间 ~183ms
        now += 200_000_000L
        val reference = frame
        val next = clock.elapsedAt(reference, paused = false, nowNanos = now)
        assertTrue(next >= rendered + 200_000_000L * 97 / 100, "照真实时间往前走, 不往回跳: $next")
        rendered = next

        // 之后界面慢慢补齐, 渲染时钟每帧都只往前
        repeat(60) { i ->
            now += frame
            val out = clock.elapsedAt(reference + (i + 1) * frame * 13 / 10, paused = false, nowNanos = now)
            assertTrue(out > rendered)
            rendered = out
        }
    }

    @Test
    fun `follows the ui clock gradually`() {
        val clock = DanmakuRenderClock()
        var now = 0L
        clock.elapsedAt(0, paused = false, nowNanos = now)
        var out = 0L
        // 界面时钟固定领先 100ms
        repeat(600) {
            now += frame
            out = clock.elapsedAt(now + 100_000_000L, paused = false, nowNanos = now)
        }
        assertTrue(abs(now + 100_000_000L - out) < 10_000_000L, "十秒内基本追上: 还差 ${(now + 100_000_000L - out) / 1_000_000}ms")
    }

    @Test
    fun `snaps to the ui clock when far apart`() {
        val clock = DanmakuRenderClock()
        clock.elapsedAt(0, paused = false, nowNanos = 0)
        assertEquals(5_000_000_000L, clock.elapsedAt(5_000_000_000L, paused = false, nowNanos = frame))
        assertEquals(1, clock.snaps)
    }

    @Test
    fun `starts from the ui clock`() {
        val clock = DanmakuRenderClock()
        assertEquals(42L, clock.elapsedAt(42L, paused = true, nowNanos = 1_000_000_000L))
    }

    @Test
    fun `stops when the video stops before the ui says paused and does not jump back`() {
        val clock = DanmakuRenderClock()
        var now = 0L
        var reference = 0L
        repeat(10) {
            clock.elapsedAt(reference, paused = false, nowNanos = now)
            now += frame
            reference += frame
        }
        val atStop = clock.elapsedAt(reference, paused = false, nowNanos = now)
        // 视频停了, 界面线程卡了 300ms 才转告: 这期间界面时钟不走, 渲染线程不该往前推
        repeat(18) {
            now += frame
            assertEquals(atStop, clock.elapsedAt(reference, paused = false, nowNanos = now, videoStopped = true))
        }
        now += frame
        assertEquals(atStop, clock.elapsedAt(reference, paused = true, nowNanos = now), "界面说暂停了: 停在原处, 不跳")
        assertTrue(clock.drainMaxStopLeadNanos() >= 18 * frame)
    }

    @Test
    fun `resumes from where it stopped and drifts toward the ui clock`() {
        val clock = DanmakuRenderClock()
        var now = 0L
        var lastRunning = clock.elapsedAt(0, paused = false, nowNanos = now)
        // 界面卡住 200ms (界面时钟没走), 渲染线程照常往前
        repeat(12) {
            now += frame
            lastRunning = clock.elapsedAt(0, paused = false, nowNanos = now)
        }
        now += frame
        val held = clock.elapsedAt(0, paused = true, nowNanos = now)
        assertEquals(lastRunning, held, "暂停时停在画着的位置, 不跳回落后的界面时钟")
        now += 5_000_000_000L
        assertEquals(held, clock.elapsedAt(0, paused = true, nowNanos = now))

        var rendered = held
        var reference = 0L
        repeat(60) {
            now += frame
            reference += frame
            val out = clock.elapsedAt(reference, paused = false, nowNanos = now)
            assertTrue(out > rendered, "继续播放后只往前")
            assertTrue(out - rendered <= frame, "比界面时钟走得慢一点去等它, 不会往回跳")
            rendered = out
        }
    }

    @Test
    fun `a short pause does not push the danmaku ahead after resuming`() {
        val clock = DanmakuRenderClock()
        var now = 0L
        var reference = 0L
        repeat(30) {
            now += frame
            reference += frame
            clock.elapsedAt(reference, paused = false, nowNanos = now)
        }
        // 按暂停: 渲染线程只补画这一帧, 之后停着不出帧
        now += frame
        val held = clock.elapsedAt(reference, paused = true, nowNanos = now)
        // 1 秒后继续 (不到直接对齐的阈值), 界面时钟从停住的位置接着走
        now += 1_000_000_000L
        reference += frame
        val resumed = clock.elapsedAt(reference, paused = false, nowNanos = now)
        assertTrue(resumed - held <= frame, "继续后的第一帧最多走一帧, 不把暂停的那一秒算进去: ${resumed - held}")
    }

    @Test
    fun `follows a far jump of the ui clock while paused`() {
        val clock = DanmakuRenderClock()
        clock.elapsedAt(0, paused = false, nowNanos = 0)
        clock.elapsedAt(0, paused = true, nowNanos = frame)
        assertEquals(10_000_000_000L, clock.elapsedAt(10_000_000_000L, paused = true, nowNanos = 2 * frame))
    }
}
