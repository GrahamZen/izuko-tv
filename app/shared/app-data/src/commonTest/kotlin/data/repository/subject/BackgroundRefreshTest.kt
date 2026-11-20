/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BackgroundRefreshTest {
    @Test
    fun `hedged - an answer within the hedge delay is used alone`() = runTest {
        var calls = 0
        val result = hedged(5.seconds) {
            calls++
            delay(1.seconds)
            "first"
        }
        assertEquals("first", result)
        assertEquals(1, calls)
        assertEquals(1000, currentTime)
    }

    @Test
    fun `hedged - a stalled first request is raced by a second one and cancelled`() = runTest {
        var calls = 0
        var firstCancelled = false
        val result = hedged(5.seconds) {
            if (calls++ == 0) {
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled = true
                }
            }
            delay(300.milliseconds)
            "second"
        }
        assertEquals("second", result)
        assertEquals(2, calls)
        assertTrue(firstCancelled)
        assertEquals(5300, currentTime)
    }

    @Test
    fun `hedged - a slow first request still wins when it answers first`() = runTest {
        var calls = 0
        val result = hedged(5.seconds) {
            if (calls++ == 0) {
                delay(6.seconds)
                "first"
            } else {
                delay(10.seconds)
                "second"
            }
        }
        assertEquals("first", result)
        assertEquals(6000, currentTime)
    }

    @Test
    fun `hedged - when one of the two fails the other is awaited`() = runTest {
        var calls = 0
        val result = hedged(5.seconds) {
            if (calls++ == 0) {
                delay(6.seconds)
                throw IOException("first failed")
            }
            delay(3.seconds)
            "second"
        }
        assertEquals("second", result)
        assertEquals(8000, currentTime)
    }

    @Test
    fun `hedged - a failure before the hedge delay is thrown without a second request`() = runTest {
        var calls = 0
        val e = assertFailsWith<IOException> {
            hedged(5.seconds) {
                calls++
                delay(1.seconds)
                throw IOException("unreachable")
            }
        }
        assertEquals("unreachable", e.message)
        assertEquals(1, calls)
    }

    @Test
    fun `hedged - both failing throws the later failure`() = runTest {
        var calls = 0
        val e = assertFailsWith<IOException> {
            hedged(5.seconds) {
                val n = calls++
                delay(if (n == 0) 7.seconds else 1.seconds)
                throw IOException("failure $n")
            }
        }
        assertEquals("failure 0", e.message)
        assertEquals(7000, currentTime)
    }

    @Test
    fun `retryUntilDone - backs off by four times up to the max delay until an attempt succeeds`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        val failures = mutableListOf<Int>()
        retryUntilDone(
            initialDelay = 1.seconds,
            maxDelay = 10.seconds,
            onFailure = { _, n -> failures += n },
        ) {
            attemptTimes += currentTime
            if (attemptTimes.size < 5) throw IOException("down")
        }
        // 失败后等 1s, 4s, 10s (封顶), 10s
        assertEquals(listOf(0L, 1000L, 5000L, 15000L, 25000L), attemptTimes)
        assertEquals(listOf(1, 2, 3, 4), failures)
    }

    @Test
    fun `retryUntilDone - stops when the caller is cancelled`() = runTest {
        var attempts = 0
        val job = launch {
            retryUntilDone(1.seconds, 1.minutes, onFailure = { _, _ -> }) {
                attempts++
                throw IOException("down")
            }
        }
        advanceTimeBy(5500)
        job.cancel()
        val before = attempts
        advanceTimeBy(10.minutes)
        assertEquals(before, attempts)
    }

    @Test
    fun `refreshingInBackground - cached data is sent at once while refreshing`() = runTest {
        val local = MutableStateFlow(listOf("old"))
        val refreshed = CompletableDeferred<Unit>()
        val received = mutableListOf<List<String>>()
        val job = launch {
            local.refreshingInBackground(20.seconds) {
                refreshed.await()
                local.value = listOf("new")
            }.collect { received += it }
        }
        runCurrent()
        assertEquals(listOf(listOf("old")), received)
        refreshed.complete(Unit)
        runCurrent()
        assertEquals(listOf(listOf("old"), listOf("new")), received)
        job.cancel()
    }

    @Test
    fun `refreshingInBackground - empty data waits for the refresh and the stale empty list is not sent`() = runTest {
        val local = MutableStateFlow(emptyList<String>())
        val received = mutableListOf<List<String>>()
        val job = launch {
            local.refreshingInBackground(20.seconds) {
                delay(3.seconds)
                local.value = listOf("fetched")
            }.collect { received += it }
        }
        advanceTimeBy(2.seconds)
        assertEquals(emptyList(), received)
        advanceTimeBy(2.seconds)
        assertEquals(listOf(listOf("fetched")), received)
        job.cancel()
    }

    @Test
    fun `refreshingInBackground - empty data is sent after the max wait and the refresh keeps running`() = runTest {
        val local = MutableStateFlow(emptyList<String>())
        val received = mutableListOf<List<String>>()
        var refreshDone = false
        val job = launch {
            local.refreshingInBackground(20.seconds) {
                delay(1.minutes)
                refreshDone = true
                local.value = listOf("fetched")
            }.collect { received += it }
        }
        advanceTimeBy(20.seconds + 1.milliseconds)
        assertEquals(listOf(emptyList()), received)
        assertFalse(refreshDone)
        advanceTimeBy(1.minutes)
        assertTrue(refreshDone)
        assertEquals(listOf(emptyList(), listOf("fetched")), received)
        job.cancel()
    }

    @Test
    fun `refreshingInBackground - the refresh is cancelled with the collector`() = runTest {
        var refreshCancelled = false
        val flow = MutableStateFlow(listOf("cached")).refreshingInBackground(20.seconds) {
            try {
                awaitCancellation()
            } finally {
                refreshCancelled = true
            }
        }
        val job = launch { flow.toList(mutableListOf()) }
        runCurrent()
        assertFalse(refreshCancelled)
        job.cancel()
        runCurrent()
        assertTrue(refreshCancelled)
    }
}
