/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import me.him188.ani.app.domain.together.TogetherFollowerPace.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TogetherFollowerPaceTest {
    private fun local(buffering: Boolean = false, loading: Boolean = false, paused: Boolean = false) = TogetherWatchingInfo(
        subjectId = 1, episodeId = 1, subjectName = "S", episodeSort = "01", episodeName = "E",
        positionMillis = 0, positionAtMillis = 0, durationMillis = 1_440_000,
        paused = paused, buffering = buffering, loading = loading,
    )

    private fun host(positionMillis: Long, atMillis: Long) = TogetherWatchingInfo(
        subjectId = 1, episodeId = 1, subjectName = "S", episodeSort = "01", episodeName = "E",
        positionMillis = positionMillis, positionAtMillis = atMillis, durationMillis = 1_440_000, paused = false,
    )

    /** 自己落后纠正了一次, 20 秒后又落后: 认定跟不上 */
    private fun TogetherFollowerPace.fallBehind() {
        assertEquals(Decision.Correct, decide(0))
        assertEquals(Decision.FallBehind, decide(20_000))
    }

    @Test
    fun `a member that keeps up is corrected every time`() {
        val pace = TogetherFollowerPace()
        assertEquals(Decision.Correct, pace.decide(0))
        assertEquals(Decision.Correct, pace.decide(70_000))
        assertFalse(pace.lagging)
    }

    @Test
    fun `a member ahead of the host is pulled back every time and never counted as falling behind`() {
        val pace = TogetherFollowerPace()
        // 自己拖到前面、房主停着自己却在播: 一分钟里拉回几次都照拉, 不提示「落后」
        assertEquals(Decision.Correct, pace.decide(0, behind = false))
        assertEquals(Decision.Correct, pace.decide(10_000, behind = false))
        assertEquals(Decision.Correct, pace.decide(20_000, behind = false))
        assertFalse(pace.lagging)
        // 也不算进「自己落后」的那一次: 之后真落后时第一次照常跳
        assertEquals(Decision.Correct, pace.decide(25_000, behind = true))
        assertEquals(Decision.FallBehind, pace.decide(40_000, behind = true))
    }

    @Test
    fun `two own corrections within a minute mean falling behind`() {
        val pace = TogetherFollowerPace()
        pace.fallBehind()
        assertTrue(pace.lagging)
        // 只告诉一次
        assertEquals(Decision.StayBehind, pace.decide(21_000))
        assertEquals(Decision.StayBehind, pace.decide(40_000))
    }

    @Test
    fun `corrections for host jumps do not count as falling behind`() {
        val pace = TogetherFollowerPace()
        pace.observeHost(host(positionMillis = 100_000, atMillis = 0))
        pace.observeHost(host(positionMillis = 400_000, atMillis = 1_000))
        assertEquals(Decision.Correct, pace.decide(1_000))
        assertEquals(Decision.Correct, pace.decide(10_000))
        assertEquals(Decision.FallBehind, pace.decide(20_000))
    }

    @Test
    fun `a host that jumps is followed even while behind`() {
        val pace = TogetherFollowerPace()
        pace.fallBehind()
        pace.observeHost(host(positionMillis = 100_000, atMillis = 20_000))
        pace.observeHost(host(positionMillis = 101_000, atMillis = 21_000))
        assertEquals(Decision.StayBehind, pace.decide(21_000))
        // 房主自己往前拖了一大段: 跟一次, 之后照旧落后着播
        pace.observeHost(host(positionMillis = 400_000, atMillis = 22_000))
        assertEquals(Decision.Correct, pace.decide(22_000))
        assertEquals(Decision.StayBehind, pace.decide(23_000))
    }

    @Test
    fun `a host jump while the member is buffering is followed once it can`() {
        val pace = TogetherFollowerPace()
        pace.fallBehind()
        pace.observeHost(host(positionMillis = 100_000, atMillis = 20_000))
        // 房主跳的那一刻本机在缓冲 (管理器那一轮不问 decide)
        pace.observeLocal(local(buffering = true), 21_000)
        pace.observeHost(host(positionMillis = 400_000, atMillis = 21_000))
        pace.observeLocal(local(), 25_000)
        assertEquals(Decision.Correct, pace.decide(25_000))
    }

    @Test
    fun `a host change already caught up with is not followed again`() {
        val pace = TogetherFollowerPace()
        pace.fallBehind()
        pace.observeHost(host(positionMillis = 100_000, atMillis = 20_000))
        pace.observeHost(host(positionMillis = 400_000, atMillis = 21_000))
        // 那一下之后位置本来就对得上
        pace.inStep()
        assertEquals(Decision.StayBehind, pace.decide(30_000))
    }

    @Test
    fun `a minute of smooth playback tries to catch up again`() {
        val pace = TogetherFollowerPace()
        pace.fallBehind()
        pace.observeLocal(local(), 20_000)
        pace.observeLocal(local(), 50_000)
        assertTrue(pace.lagging)
        pace.observeLocal(local(), 80_000)
        assertFalse(pace.lagging)
        assertEquals(Decision.Correct, pace.decide(81_000))
    }

    @Test
    fun `buffering while behind restarts the smooth minute`() {
        val pace = TogetherFollowerPace()
        pace.fallBehind()
        pace.observeLocal(local(), 20_000)
        pace.observeLocal(local(buffering = true), 60_000)
        pace.observeLocal(local(), 61_000)
        pace.observeLocal(local(), 100_000)
        assertTrue(pace.lagging)
        pace.observeLocal(local(), 121_000)
        assertFalse(pace.lagging)
    }

    @Test
    fun `loading another source starts over`() {
        val pace = TogetherFollowerPace()
        pace.fallBehind()
        pace.observeLocal(local(loading = true), 25_000)
        assertFalse(pace.lagging)
        assertEquals(Decision.Correct, pace.decide(30_000))
    }
}
