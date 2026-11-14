/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.coroutines

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.coroutines.RejectionRefresh.RefreshResult
import me.him188.ani.utils.coroutines.RejectionRefresh.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 地址被拒时换不换新的 ([RejectionRefresh]).
 */
class RejectionRefreshTest {
    private var now = 0L
    private var refreshes = 0
    private var result: RefreshResult = RefreshResult.Refreshed()

    private fun policy() = RejectionRefresh(nowMillis = { now }) {
        refreshes++
        result
    }

    private fun afterCooldown() {
        now += RejectionRefresh.DEFAULT_COOLDOWN_MILLIS
    }

    @Test
    fun `requests rejected together refresh once and the late ones just retry`() = runTest {
        val policy = RejectionRefresh(nowMillis = { now }) {
            delay(1_000) // 换地址要一会儿, 别的请求这时也被拒了
            refreshes++
            RefreshResult.Refreshed()
        }
        val verdicts = (1..16).map { async { policy.rejected(0, requester = it) } }.awaitAll()
        assertEquals(List(16) { Verdict.Retry }, verdicts)
        assertEquals(1, refreshes)
        assertEquals(1, policy.read { it })
    }

    @Test
    fun `a fresh link rejected right away is retried with backoff and not refreshed again within the cooldown`() = runTest {
        val policy = policy()
        assertEquals(Verdict.Retry, policy.rejected(0))
        assertEquals(Verdict.Backoff, policy.rejected(1))
        now += RejectionRefresh.DEFAULT_COOLDOWN_MILLIS - 1
        assertEquals(Verdict.Backoff, policy.rejected(1))
        assertEquals(1, refreshes)
    }

    @Test
    fun `after a few fresh links that never work it stops refreshing until something works`() = runTest {
        val policy = policy()
        assertEquals(Verdict.Retry, policy.rejected(0))
        afterCooldown()
        assertEquals(Verdict.Retry, policy.rejected(1))
        afterCooldown()
        assertEquals(Verdict.Retry, policy.rejected(2))
        // 第三个新地址也被拒: 换了也没用 (站点临时限流之类), 不再换, 也不替使用方判失败
        afterCooldown()
        assertEquals(Verdict.Backoff, policy.rejected(3))
        afterCooldown()
        assertEquals(Verdict.Backoff, policy.rejected(3))
        assertEquals(3, refreshes)
        // 过后自己好了; 以后真过期了照常换
        policy.confirm(3)
        afterCooldown()
        assertEquals(Verdict.Retry, policy.rejected(3))
        assertEquals(4, refreshes)
    }

    @Test
    fun `a link that worked and expired later is refreshed again`() = runTest {
        val policy = policy()
        assertEquals(Verdict.Retry, policy.rejected(0))
        policy.confirm(1)
        repeat(5) { round ->
            afterCooldown()
            assertEquals(Verdict.Retry, policy.rejected(round + 1))
            policy.confirm(round + 2)
        }
        assertEquals(6, refreshes)
    }

    @Test
    fun `without a fresh link it backs off and asks again only after the cooldown`() = runTest {
        result = RefreshResult.Unavailable
        val policy = policy()
        assertEquals(Verdict.Backoff, policy.rejected(0))
        assertEquals(Verdict.Backoff, policy.rejected(0))
        assertEquals(1, refreshes)
        afterCooldown()
        assertEquals(Verdict.Backoff, policy.rejected(0))
        assertEquals(2, refreshes)
        assertEquals(0, policy.read { it })
    }

    @Test
    fun `after a few attempts that got nothing it stops asking`() = runTest {
        result = RefreshResult.Unavailable
        val policy = policy()
        repeat(6) {
            assertEquals(Verdict.Backoff, policy.rejected(0))
            afterCooldown()
        }
        // 换地址可能要开网页现抓: 连着三次没换成就不再试, 剩下的交给使用方自己的重试上限
        assertEquals(3, refreshes)
    }

    @Test
    fun `a failing refresh counts as unavailable`() = runTest {
        val policy = RejectionRefresh(nowMillis = { now }) { error("resolver crashed") }
        assertEquals(Verdict.Backoff, policy.rejected(0))
    }

    @Test
    fun `a request still rejected on a link that works for others stops triggering refreshes`() = runTest {
        val policy = policy()
        // 第 7 个分片坏了 (404), 别的分片都好
        assertEquals(Verdict.Retry, policy.rejected(0, requester = 7))
        policy.confirm(1, requester = 1)
        afterCooldown()
        assertEquals(Verdict.Retry, policy.rejected(1, requester = 7))
        policy.confirm(2, requester = 2)
        afterCooldown()
        // 换了两回它还是被拒: 不再为它换
        assertEquals(Verdict.Backoff, policy.rejected(2, requester = 7))
        afterCooldown()
        assertEquals(Verdict.Backoff, policy.rejected(2, requester = 7))
        assertEquals(2, refreshes)
        // 别的请求以后过期了照常换
        assertEquals(Verdict.Retry, policy.rejected(2, requester = 3))
        assertEquals(3, refreshes)
    }

    @Test
    fun `a refresh that cannot continue the old work tells everyone to restart`() = runTest {
        result = RefreshResult.Refreshed(restart = true)
        val policy = policy()
        assertEquals(Verdict.Restart, policy.rejected(0))
        // 手上还是旧地址的请求也要从头来, 不能拿新地址接着做
        assertEquals(Verdict.Restart, policy.rejected(0))
        assertEquals(1, refreshes)
    }
}
