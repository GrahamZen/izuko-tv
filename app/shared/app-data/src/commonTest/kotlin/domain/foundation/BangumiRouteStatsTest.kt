/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BangumiRouteStatsTest {
    private var now = 1_000_000L
    private val stats = BangumiRouteStats { now }

    @Test
    fun `in-flight requests are counted per host with the oldest age`() {
        val a = stats.requestStarted("next.bgm.tv")
        now += 30_000
        stats.requestStarted("next.bgm.tv")
        stats.requestStarted("api.bgm.tv")
        now += 5_000
        val hosts = stats.snapshot().inFlight.associateBy { it.host }
        assertEquals(2, hosts.getValue("next.bgm.tv").count)
        assertEquals(35_000, hosts.getValue("next.bgm.tv").oldestMillis)
        assertEquals(1, hosts.getValue("api.bgm.tv").count)
        stats.requestFinished(a)
        assertEquals(5_000, stats.snapshot().inFlight.single { it.host == "next.bgm.tv" }.oldestMillis)
    }

    @Test
    fun `route counts only cover the recent window`() {
        stats.routeFailed(BangumiRoute.Ech, "next.bgm.tv", "/p1/subjects/9912/characters", 30_000, "timeout")
        now += BangumiRouteStats.WINDOW_MILLIS + 1
        stats.routeSucceeded(BangumiRoute.Ech)
        stats.routeFailed(BangumiRoute.Origin, "api.bgm.tv", "/v0/me", 3_000, "connect timeout")
        val counts = stats.snapshot().routes.associateBy { it.route }
        assertEquals(1, counts.getValue("ech").ok)
        assertEquals(0, counts.getValue("ech").failed)
        assertEquals(1, counts.getValue("origin").failed)
        // 失败原因不按时间窗删, 留最近的若干条, 新的在前; 路径里的条目号换成 {n}
        assertEquals(listOf("origin", "ech"), stats.snapshot().failures.map { it.route })
        assertEquals(listOf("/v0/me", "/p1/subjects/{n}/characters"), stats.snapshot().failures.map { it.path })
    }

    @Test
    fun `all routes failing is emitted and counted`() = runTest {
        val received = CompletableDeferred<BangumiRouteStats.AllFailed>()
        val job = launch { received.complete(stats.allFailed.first()) }
        runCurrent()
        stats.allRoutesFailed("next.bgm.tv", "/p1/subjects/9912/staffs/positions", listOf(BangumiRoute.Origin, BangumiRoute.Ech), "timeout")
        runCurrent()
        val event = received.await()
        assertEquals(listOf("origin", "ech"), event.tried)
        assertEquals("/p1/subjects/{n}/staffs/positions", event.path)
        assertEquals("timeout", event.cause)
        assertEquals(1, stats.snapshot().allFailed.size)
        now += BangumiRouteStats.WINDOW_MILLIS + 1
        assertEquals(0, stats.snapshot().allFailed.size)
        job.cancel()
    }
}
