/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.platform.NetworkStatus
import me.him188.ani.app.platform.onEachRecovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/** 断网恢复后把没取到的再取一次, 什么时候算恢复 (见 [onEachRecovery]). */
class NetworkRecoveryTest {
    private class FakeNetwork(initial: Boolean) : NetworkStatus {
        val available = MutableStateFlow(initial)
        override val isAvailable: StateFlow<Boolean> get() = available
        override val lastLostAt: TimeMark? = null
    }

    @Test
    fun `a network that stays up does nothing`() = runTest {
        val network = FakeNetwork(true)
        var recoveries = 0
        val job = launch { network.onEachRecovery { recoveries++ } }
        advanceTimeBy(1000.seconds)
        runCurrent()
        assertEquals(0, recoveries)
        job.cancel()
    }

    @Test
    fun `every recovery runs once after settling`() = runTest {
        val network = FakeNetwork(true)
        var recoveries = 0
        val job = launch { network.onEachRecovery { recoveries++ } }
        runCurrent()

        network.available.value = false
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(0, recoveries)

        network.available.value = true
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(0, recoveries)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, recoveries)

        advanceTimeBy(100.seconds)
        runCurrent()
        assertEquals(1, recoveries)

        network.available.value = false
        runCurrent()
        network.available.value = true
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, recoveries)
        job.cancel()
    }

    @Test
    fun `a drop while settling waits for the next recovery`() = runTest {
        val network = FakeNetwork(false)
        var recoveries = 0
        val job = launch { network.onEachRecovery { recoveries++ } }
        runCurrent()

        network.available.value = true
        advanceTimeBy(500.milliseconds)
        runCurrent()
        network.available.value = false
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(0, recoveries)

        network.available.value = true
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, recoveries)
        job.cancel()
    }
}
