/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.coroutines

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThrottleLatestTest {
    @Test
    fun `burst keeps first and last`() = runTest {
        val emitted = flowOf(1, 2, 3, 4).throttleLatest(100).toList()
        assertEquals(listOf(1, 4), emitted)
    }

    @Test
    fun `completion flushes the pending value without waiting for the period`() = runTest {
        val emitted = flow {
            emit(1)
            delay(10)
            emit(2)
        }.throttleLatest(1_000).toList()
        assertEquals(listOf(1, 2), emitted)
        assertEquals(10, currentTime)
    }

    @Test
    fun `value after an idle gap is emitted at once`() = runTest {
        val upstream = MutableSharedFlow<Int>()
        val received = mutableListOf<Pair<Long, Int>>()
        val job = launch { upstream.throttleLatest(100).collect { received += currentTime to it } }
        runCurrent()
        upstream.emit(1)
        runCurrent()
        upstream.emit(2) // within the cooldown: held until it ends
        runCurrent()
        assertEquals(listOf(0L to 1), received)
        advanceTimeBy(101)
        assertEquals(listOf(0L to 1, 100L to 2), received)
        advanceTimeBy(1_000) // idle well past the cooldown
        upstream.emit(3)
        runCurrent()
        assertEquals(listOf(0L to 1, 100L to 2, 1101L to 3), received)
        job.cancel()
    }

    @Test
    fun `null values pass through`() = runTest {
        assertEquals(listOf(null, 2), flowOf(null, 1, 2).throttleLatest(100).toList())
    }

    @Test
    fun `upstream failure is rethrown`() = runTest {
        assertFailsWith<IllegalStateException> {
            flow<Int> {
                emit(1)
                throw IllegalStateException("boom")
            }.throttleLatest(100).toList()
        }
    }
}
