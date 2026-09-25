/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.paging.LoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RetryFailedLoadsTest {
    private val idle: LoadState = LoadState.NotLoading(endOfPaginationReached = false)
    private val states = MutableStateFlow(idle)

    /** 每次重试时的虚拟时间 (毫秒). */
    private val retries = mutableListOf<Long>()

    private fun fail() {
        states.value = LoadState.Error(RuntimeException("offline"))
    }

    private fun TestScope.startRetrying() {
        backgroundScope.launch {
            retryFailedLoads(states, { failures -> 5.seconds * failures }, { retries += testScheduler.currentTime })
        }
        runCurrent()
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `退避时间 - 5 秒起翻倍, 最长 5 分钟`() {
        assertEquals(
            listOf(5, 10, 20, 40, 80, 160, 300, 300).map { it.seconds },
            (1..8).map { trendingRetryDelay(it) },
        )
    }

    @Test
    fun `出错后等退避时间再重试, 连续失败越等越久`() = runTest {
        startRetrying()
        fail()
        advance(4.seconds)
        assertEquals(emptyList(), retries)
        advance(1.seconds)
        assertEquals(listOf(5_000L), retries)

        // 重试又失败: 第二次等 10 秒
        states.value = LoadState.Loading
        runCurrent()
        fail()
        advance(9.seconds)
        assertEquals(listOf(5_000L), retries)
        advance(1.seconds)
        assertEquals(listOf(5_000L, 15_000L), retries)
    }

    @Test
    fun `成功后失败次数清零`() = runTest {
        startRetrying()
        fail()
        advance(5.seconds)
        states.value = LoadState.Loading
        runCurrent()
        fail()
        advance(10.seconds)
        assertEquals(listOf(5_000L, 15_000L), retries)

        states.value = idle
        runCurrent()
        fail()
        advance(5.seconds)
        assertEquals(listOf(5_000L, 15_000L, 20_000L), retries)
    }

    @Test
    fun `等的时候别处已经重取了就不再重试`() = runTest {
        startRetrying()
        fail()
        advance(2.seconds)
        // 例如连接设置改了, 那边直接 refresh
        states.value = LoadState.Loading
        runCurrent()
        advance(30.seconds)
        assertEquals(emptyList(), retries)
    }
}
