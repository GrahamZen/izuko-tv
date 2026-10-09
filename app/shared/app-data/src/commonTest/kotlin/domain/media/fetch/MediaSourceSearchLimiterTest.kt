/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class MediaSourceSearchLimiterTest {
    /** 一个数据源的查询: 拿到名额才记进 [started], 等 [finish] 完成才结束. */
    private class Search(val name: String) {
        val finish = CompletableDeferred<Unit>()
    }

    private val started = mutableListOf<String>()

    private fun TestScope.search(
        limiter: MediaSourceSearchLimiter,
        name: String,
        tier: UInt = 2u,
        kind: MediaSourceKind = MediaSourceKind.WEB,
    ): Search {
        val search = Search(name)
        backgroundScope.launch {
            flow<Unit> {
                started += name
                search.finish.await()
            }.withSearchPermit(limiter, kind, MediaSourceTier(tier), name).collect()
        }
        return search
    }

    private fun TestScope.limiter(permits: Int, holdLimit: Duration = 1.hours) =
        MediaSourceSearchLimiter(permits, holdLimit, timerScope = backgroundScope)

    @Test
    fun `at most permits sources search at once and lower tiers go first`() = runTest {
        val limiter = limiter(permits = 2)
        val a = search(limiter, "a")
        val b = search(limiter, "b")
        search(limiter, "bt", kind = MediaSourceKind.BitTorrent)
        search(limiter, "t4", tier = 4u)
        search(limiter, "web", tier = 2u)
        search(limiter, "t0", tier = 0u)
        runCurrent()
        assertEquals(listOf("a", "b"), started)

        a.finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b", "t0"), started)

        b.finish.complete(Unit)
        runCurrent()
        // 同层在线源在 BT 前, 层级高的最后
        assertEquals(listOf("a", "b", "t0", "web"), started)
    }

    @Test
    fun `a source that holds its permit too long lets the next one start`() = runTest {
        val limiter = limiter(permits = 1, holdLimit = 10.seconds)
        search(limiter, "slow")
        search(limiter, "next")
        runCurrent()
        assertEquals(listOf("slow"), started)

        advanceTimeBy(9.seconds)
        runCurrent()
        assertEquals(listOf("slow"), started)

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf("slow", "next"), started)
    }

    @Test
    fun `cancelled waiter does not take a permit`() = runTest {
        val limiter = limiter(permits = 1)
        val first = search(limiter, "first")
        val waiting = backgroundScope.launch {
            flow<Unit> { started += "cancelled" }.withSearchPermit(limiter, MediaSourceKind.WEB, null, "cancelled").collect()
        }
        search(limiter, "after")
        runCurrent()
        waiting.cancel()
        first.finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "after"), started)
    }

    @Test
    fun `local cache is not limited`() = runTest {
        val limiter = limiter(permits = 1)
        search(limiter, "web")
        search(limiter, "cache", kind = MediaSourceKind.LocalCache)
        runCurrent()
        assertEquals(listOf("web", "cache"), started)
    }
}
