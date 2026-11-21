/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 双击两侧快进退: 三列怎么分 ([tabletTapZone]), 连着点怎么累加 ([TabletSeekStreakState]).
 */
class TabletDoubleTapSeekTest {
    @Test
    fun `zones split the width into thirds`() {
        assertEquals(TabletTapZone.BACK, tabletTapZone(0f, 900f))
        assertEquals(TabletTapZone.BACK, tabletTapZone(299f, 900f))
        assertEquals(TabletTapZone.MIDDLE, tabletTapZone(300f, 900f))
        assertEquals(TabletTapZone.MIDDLE, tabletTapZone(600f, 900f))
        assertEquals(TabletTapZone.FORWARD, tabletTapZone(601f, 900f))
        assertEquals(TabletTapZone.FORWARD, tabletTapZone(900f, 900f))
    }

    @Test
    fun `first step starts from the current position`() {
        val state = TabletSeekStreakState()
        assertFalse(state.visible)
        assertEquals(70_000L, state.step(forward = true, currentMillis = 60_000L, totalMillis = 600_000L))
        assertTrue(state.visible)
        assertTrue(state.forward)
        assertEquals(10_000L, state.accumulatedMillis)
    }

    @Test
    fun `taps on the same side continue from the last target and add up`() {
        val state = TabletSeekStreakState()
        state.step(forward = true, currentMillis = 60_000L, totalMillis = 600_000L)
        // 播放器的位置还没跟上 (刚 seek 完), 照样从上一下的落点接着算
        assertEquals(80_000L, state.step(forward = true, currentMillis = 60_000L, totalMillis = 600_000L))
        assertEquals(90_000L, state.step(forward = true, currentMillis = 61_000L, totalMillis = 600_000L))
        assertEquals(30_000L, state.accumulatedMillis)
    }

    @Test
    fun `switching side restarts the count from the last target`() {
        val state = TabletSeekStreakState()
        state.step(forward = true, currentMillis = 60_000L, totalMillis = 600_000L)
        state.step(forward = true, currentMillis = 60_000L, totalMillis = 600_000L)
        assertEquals(70_000L, state.step(forward = false, currentMillis = 60_000L, totalMillis = 600_000L))
        assertFalse(state.forward)
        assertEquals(10_000L, state.accumulatedMillis)
    }

    @Test
    fun `after the streak ends the next step starts from the current position again`() {
        val state = TabletSeekStreakState()
        state.step(forward = true, currentMillis = 60_000L, totalMillis = 600_000L)
        state.end()
        assertFalse(state.visible)
        assertEquals(130_000L, state.step(forward = true, currentMillis = 120_000L, totalMillis = 600_000L))
        assertEquals(10_000L, state.accumulatedMillis)
    }

    @Test
    fun `targets stay within the episode`() {
        val state = TabletSeekStreakState()
        assertEquals(0L, state.step(forward = false, currentMillis = 4_000L, totalMillis = 600_000L))
        state.end()
        assertEquals(600_000L, state.step(forward = true, currentMillis = 595_000L, totalMillis = 600_000L))
        state.end()
        // 时长未知时只保证不小于 0
        assertEquals(15_000L, state.step(forward = true, currentMillis = 5_000L, totalMillis = 0L))
    }
}
