/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.fetch.SearchConcurrency.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SearchConcurrencyTest {
    private val time = TestTimeSource()
    private val concurrency = SearchConcurrency(time)

    private fun searching() = concurrency.onInFlightChanged(1)

    @Test
    fun `unlimited by default and follows the setting`() {
        assertEquals(SearchConcurrency.UNLIMITED, concurrency.current())
        concurrency.onSetting(10)
        assertEquals(10, concurrency.current())
        concurrency.onSetting(0)
        assertEquals(SearchConcurrency.UNLIMITED, concurrency.current())
    }

    @Test
    fun `each trigger lowers one step and waits the interval before the next`() = runTest {
        searching()
        concurrency.onMemoryPressure(critical = false)
        assertEquals(16, concurrency.current())

        concurrency.onSearchStutter()
        assertEquals(16, concurrency.current())

        time += SearchConcurrency.STEP_INTERVAL
        concurrency.onSearchStutter()
        assertEquals(10, concurrency.current())
        time += SearchConcurrency.STEP_INTERVAL
        concurrency.onMemoryPressure(critical = false)
        assertEquals(6, concurrency.current())
        time += SearchConcurrency.STEP_INTERVAL
        concurrency.onMemoryPressure(critical = false)
        assertEquals(6, concurrency.current())

        val adjustments = concurrency.adjustments.take(3).toList()
        assertEquals(listOf(16, 10, 6), adjustments.map { it.limit })
        assertEquals(listOf(Reason.MemoryLow, Reason.SearchStutter, Reason.MemoryLow), adjustments.map { it.reason })
    }

    @Test
    fun `memory pressure counts only while searching`() {
        concurrency.onMemoryPressure(critical = false)
        assertEquals(SearchConcurrency.UNLIMITED, concurrency.current())
        searching()
        concurrency.onMemoryPressure(critical = false)
        assertEquals(16, concurrency.current())
    }

    @Test
    fun `critical memory goes to the floor at once`() {
        searching()
        concurrency.onMemoryPressure(critical = false)
        concurrency.onMemoryPressure(critical = true)
        assertEquals(SearchConcurrency.FLOOR, concurrency.current())
    }

    @Test
    fun `killed while searching lowers one step from the limit at that time`() = runTest {
        concurrency.onKilledWhileSearching(10)
        assertEquals(6, concurrency.current())
        assertEquals(Reason.KilledWhileSearching, concurrency.adjustments.first().reason)
    }

    @Test
    fun `old setting value is ignored until the lowered one is written back`() {
        searching()
        concurrency.onMemoryPressure(critical = false)
        assertEquals(16, concurrency.current())
        // 写回之前设置流里还是不限
        concurrency.onSetting(0)
        assertEquals(16, concurrency.current())
        concurrency.onSetting(16)
        // 写回之后用户改回不限
        concurrency.onSetting(0)
        assertEquals(SearchConcurrency.UNLIMITED, concurrency.current())
    }

    @Test
    fun `low memory at the start of a search lowers a step and is checked at most every few seconds`() = runTest {
        var low = true
        var checks = 0
        concurrency.install(
            object : SearchConcurrency.Platform {
                override fun lowMemory(): Boolean {
                    checks++
                    return low
                }

                override fun markSearching(limit: Int?) {}
            },
            backgroundScope,
        )
        concurrency.beforeBatch()
        assertEquals(16, concurrency.current())
        concurrency.beforeBatch()
        assertEquals(1, checks)

        time += 6.seconds
        low = false
        concurrency.beforeBatch()
        assertEquals(2, checks)
        assertEquals(16, concurrency.current())
    }

    @Test
    fun `marks the limit while searching and clears it when done`() = runTest {
        val marks = mutableListOf<Int?>()
        concurrency.install(
            object : SearchConcurrency.Platform {
                override fun lowMemory() = false
                override fun markSearching(limit: Int?) {
                    marks += limit
                }
            },
            backgroundScope,
        )
        runCurrent()
        concurrency.onInFlightChanged(1)
        runCurrent()
        concurrency.onInFlightChanged(2)
        runCurrent()
        concurrency.onMemoryPressure(critical = false)
        runCurrent()
        concurrency.onInFlightChanged(0)
        runCurrent()
        assertEquals(listOf(null, SearchConcurrency.UNLIMITED, 16, null), marks)
    }
}
