/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.player

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class LeadingTrailingSyncGateTest {
    @Test
    fun `first request runs immediately and requests during cooldown merge into one trailing run`() = runTest {
        val runTimes = mutableListOf<Long>()
        val gate = LeadingTrailingSyncGate(backgroundScope, 5.seconds) {
            runTimes += testScheduler.currentTime
        }

        gate.request()
        runCurrent()
        assertEquals(listOf(0L), runTimes)

        gate.request()
        gate.request()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(listOf(0L), runTimes)

        gate.request()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 5_000L), runTimes)
    }

    @Test
    fun `request after an idle cooldown runs immediately`() = runTest {
        val runTimes = mutableListOf<Long>()
        val gate = LeadingTrailingSyncGate(backgroundScope, 5.seconds) {
            runTimes += testScheduler.currentTime
        }

        gate.request()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        gate.request()
        runCurrent()
        assertEquals(listOf(0L, 5_000L), runTimes)
    }
}
