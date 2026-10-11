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
    fun `paused is the ui clock and resumes from it`() {
        val clock = DanmakuRenderClock()
        clock.elapsedAt(0, paused = false, nowNanos = 0)
        assertEquals(42L, clock.elapsedAt(42L, paused = true, nowNanos = 1_000_000_000L))
        assertEquals(50L, clock.elapsedAt(50L, paused = false, nowNanos = 2_000_000_000L), "恢复播放那一帧从界面时钟起步")
        assertEquals(50L + frame, clock.elapsedAt(50L + frame, paused = false, nowNanos = 2_000_000_000L + frame))
    }
}
