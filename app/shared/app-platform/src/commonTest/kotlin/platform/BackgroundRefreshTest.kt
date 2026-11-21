/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后台刷新: 先取后发、失败与超时怎么记、记录的存取.
 */
class BackgroundRefreshTest {
    @Test
    fun `fetch jobs run before notify jobs and failures are recorded`() = runBlocking {
        val order = mutableListOf<String>()
        BackgroundRefresh.register(BackgroundRefresh.Job("t-notify", BackgroundRefresh.Stage.Notify) { order += "notify"; "posted 1" })
        BackgroundRefresh.register(BackgroundRefresh.Job("t-fetch", BackgroundRefresh.Stage.Fetch) { order += "fetch"; null })
        BackgroundRefresh.register(BackgroundRefresh.Job("t-broken", BackgroundRefresh.Stage.Fetch) { error("boom") })
        val run = BackgroundRefresh.runAll("test")
        assertEquals(listOf("fetch", "notify"), order)
        assertTrue("t-fetch: ok" in run.results, run.results.toString())
        assertTrue("t-notify: posted 1" in run.results, run.results.toString())
        assertTrue(run.results.any { it.startsWith("t-broken: failed") }, run.results.toString())
        assertEquals(run, BackgroundRefresh.runs.value.first())
        unregisterAll()
    }

    @Test
    fun `a run that exceeds its budget keeps what finished`() = runBlocking {
        BackgroundRefresh.register(BackgroundRefresh.Job("t-quick", BackgroundRefresh.Stage.Fetch) { null })
        BackgroundRefresh.register(BackgroundRefresh.Job("t-stuck", BackgroundRefresh.Stage.Fetch) { awaitCancellation() })
        val run = BackgroundRefresh.runAll("test", budget = 200.milliseconds)
        assertTrue("t-quick: ok" in run.results, run.results.toString())
        assertTrue(run.results.last().startsWith("timeout") && "t-stuck" in run.results.last(), run.results.toString())
        unregisterAll()
    }

    @Test
    fun `runs survive encoding`() {
        val runs = listOf(
            BackgroundRefresh.Run(1700000000000, "system", 1234, listOf("feedback: ok", "notices: posted 0")),
            BackgroundRefresh.Run(1700000300000, "debug", 5, emptyList()),
        )
        assertEquals(runs, BackgroundRefresh.decode(BackgroundRefresh.encode(runs)))
    }

    private fun unregisterAll() {
        listOf("t-notify", "t-fetch", "t-broken", "t-quick", "t-stuck").forEach(BackgroundRefresh::unregister)
    }
}
