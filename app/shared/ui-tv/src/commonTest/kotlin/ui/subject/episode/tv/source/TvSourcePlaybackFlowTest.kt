/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.ui.subject.episode.tv.source.TvSourcePlayback.Buffering
import me.him188.ani.app.ui.subject.episode.tv.source.TvSourcePlayback.Failed
import me.him188.ani.app.ui.subject.episode.tv.source.TvSourcePlayback.Normal

/**
 * 「正在播放」右端的状态 ([withSettledBuffering]): 缓冲挂满 3 秒才写, 但面板在缓冲中打开时第一个值不能扣着 (面板内容要等它才拼).
 */
class TvSourcePlaybackFlowTest {
    /** 收 [upstream] 报出来的每个值, 记成「第几毫秒: 状态」. */
    private fun TestScope.record(upstream: Flow<TvSourcePlayback>): List<String> {
        val out = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            upstream.withSettledBuffering(3.seconds).collect { out += "${testScheduler.currentTime}: $it" }
        }
        return out
    }

    /** 往后走 [millis] 毫秒, 到点的都跑完. */
    private fun TestScope.wait(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `opened while buffering reports pending at once and buffering once it settles`() = runTest {
        val out = record(MutableStateFlow(Buffering))
        assertEquals(listOf("0: Pending"), out)
        wait(3_000)
        assertEquals(listOf("0: Pending", "3000: Buffering"), out)
    }

    @Test
    fun `opened while playing reports at once without pending`() = runTest {
        val out = record(MutableStateFlow(Normal))
        wait(5_000)
        assertEquals(listOf("0: Normal"), out)
    }

    @Test
    fun `a short stall keeps the previous status and a long one is reported`() = runTest {
        val upstream = MutableStateFlow(Normal)
        val out = record(upstream)
        upstream.value = Buffering
        wait(1_000)
        upstream.value = Normal
        wait(5_000)
        assertEquals(listOf("0: Normal"), out)

        upstream.value = Buffering
        wait(3_000)
        assertEquals(listOf("0: Normal", "9000: Buffering"), out)
    }

    @Test
    fun `a failure is reported at once even while buffering is settling`() = runTest {
        val upstream = MutableStateFlow(Buffering)
        val out = record(upstream)
        wait(1_000)
        upstream.value = Failed
        runCurrent()
        assertEquals(listOf("0: Pending", "1000: Failed"), out)
    }
}
