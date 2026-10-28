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
import kotlin.time.Duration.Companion.milliseconds
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

    private fun TestScope.limiter(permits: Int, holdLimit: Duration = 1.hours, startWindow: Duration = Duration.ZERO) =
        MediaSourceSearchLimiter(permits, holdLimit, timerScope = backgroundScope, startWindow = { startWindow })

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
    fun `sources arriving together while permits are free start by priority after the window`() = runTest {
        val limiter = limiter(permits = 2, startWindow = 100.milliseconds)
        search(limiter, "bt", tier = 0u, kind = MediaSourceKind.BitTorrent)
        search(limiter, "t4", tier = 4u)
        search(limiter, "t2", tier = 2u)
        search(limiter, "t0", tier = 0u)
        runCurrent()
        // 层级最高的在线源不等这一批收齐
        assertEquals(listOf("t0"), started)

        advanceTimeBy(101.milliseconds)
        runCurrent()
        // 余下的按层级: 同层在线源在 BT 前, 0 层的 BT 在 2 层的在线源前
        assertEquals(listOf("t0", "bt"), started)
    }

    @Test
    fun `a source arriving alone after the window waits only the window`() = runTest {
        val limiter = limiter(permits = 2, startWindow = 100.milliseconds)
        val first = search(limiter, "first")
        advanceTimeBy(101.milliseconds)
        runCurrent()
        first.finish.complete(Unit)
        runCurrent()

        search(limiter, "later")
        runCurrent()
        assertEquals(listOf("first"), started)
        advanceTimeBy(101.milliseconds)
        runCurrent()
        assertEquals(listOf("first", "later"), started)
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
    fun `slow sources yielding their permits keep the running searches within twice the limit`() = runTest {
        val limiter = limiter(permits = 2, holdLimit = 10.seconds)
        val a = search(limiter, "a")
        search(limiter, "b")
        search(limiter, "c")
        search(limiter, "d")
        search(limiter, "e")
        runCurrent()
        assertEquals(listOf("a", "b"), started)

        // a、b 占满 10 秒让出名额, 接着查; c、d 开查
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d"), started)

        // c、d 也占满了, 但真正在查的已有 4 个 (上限的 2 倍): e 等着
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d"), started)

        a.finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d", "e"), started)
    }

    @Test
    fun `a search still running after yielding its permit counts as searching`() = runTest {
        val counts = mutableListOf<Int>()
        val limiter = MediaSourceSearchLimiter({ 1 }, 10.seconds, timerScope = backgroundScope, onInFlightChanged = { counts += it })
        val slow = search(limiter, "slow")
        val next = search(limiter, "next")
        runCurrent()
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf("slow", "next"), started)

        next.finish.complete(Unit)
        runCurrent()
        // 让过名额的 slow 还在查
        assertEquals(1, counts.last())

        slow.finish.complete(Unit)
        runCurrent()
        assertEquals(0, counts.last())
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
    fun `unlimited starts every source at once without waiting for the window`() = runTest {
        val limiter = MediaSourceSearchLimiter(
            { SearchConcurrency.UNLIMITED }, 1.hours, timerScope = backgroundScope, startWindow = { 100.milliseconds },
        )
        search(limiter, "bt", kind = MediaSourceKind.BitTorrent)
        search(limiter, "t4", tier = 4u)
        search(limiter, "t0", tier = 0u)
        runCurrent()
        assertEquals(listOf("bt", "t4", "t0"), started)
    }

    @Test
    fun `while unlimited a slow source keeps its permit past the hold limit`() = runTest {
        var limit = SearchConcurrency.UNLIMITED
        var inFlight = 0
        val limiter = MediaSourceSearchLimiter({ limit }, 10.seconds, timerScope = backgroundScope, onInFlightChanged = { inFlight = it })
        search(limiter, "slow")
        runCurrent()
        advanceTimeBy(25.seconds)
        runCurrent()
        assertEquals(1, inFlight)

        // 调小之后到了时限照常让出: 排着的开查, 让过名额的仍算在查
        limit = 1
        search(limiter, "next")
        runCurrent()
        assertEquals(listOf("slow"), started)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(listOf("slow", "next"), started)
        assertEquals(2, inFlight)
    }

    @Test
    fun `lowering the limit lets running searches finish and starts the next only below it`() = runTest {
        var limit = SearchConcurrency.UNLIMITED
        val limiter = MediaSourceSearchLimiter({ limit }, 1.hours, timerScope = backgroundScope)
        val a = search(limiter, "a")
        val b = search(limiter, "b")
        val c = search(limiter, "c")
        runCurrent()
        assertEquals(listOf("a", "b", "c"), started)

        limit = 2
        search(limiter, "d")
        search(limiter, "e")
        runCurrent()
        assertEquals(listOf("a", "b", "c"), started)

        a.finish.complete(Unit)
        runCurrent()
        // 还有两个在查, 不超过上限
        assertEquals(listOf("a", "b", "c"), started)

        b.finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d"), started)
        c.finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d", "e"), started)
    }

    @Test
    fun `raising the limit starts several waiting sources on the next release`() = runTest {
        var limit = 1
        val limiter = MediaSourceSearchLimiter({ limit }, 1.hours, timerScope = backgroundScope)
        val a = search(limiter, "a")
        search(limiter, "b")
        search(limiter, "c")
        search(limiter, "d")
        runCurrent()
        assertEquals(listOf("a"), started)

        limit = 3
        a.finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d"), started)
    }

    @Test
    fun `before batch is called only when nothing is searching`() = runTest {
        var batches = 0
        val limiter = MediaSourceSearchLimiter({ 2 }, 1.hours, timerScope = backgroundScope, beforeBatch = { batches++ })
        val a = search(limiter, "a")
        runCurrent()
        search(limiter, "b")
        runCurrent()
        assertEquals(1, batches)

        a.finish.complete(Unit)
        runCurrent()
        assertEquals(1, batches)
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
