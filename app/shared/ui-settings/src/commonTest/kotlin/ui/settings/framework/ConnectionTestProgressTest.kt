/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class ConnectionTestProgressTest {
    private fun tester(id: String) = ConnectionTester(id) { ConnectionTestResult.SUCCESS }

    @Test
    fun `nothing tested yet has no progress`() {
        assertNull(listOf(tester("a"), tester("b")).connectionTestProgress())
    }

    @Test
    fun `counts finished and failed results while others are still testing`() {
        val testers = List(4) { tester("t$it") }
        testers.forEach { it.isTesting = true }
        assertEquals(ConnectionTestProgress(completed = 0, failed = 0, total = 4), testers.connectionTestProgress())

        testers[0].apply { isTesting = false; result = ConnectionTestResult.SUCCESS }
        testers[1].apply { isTesting = false; result = ConnectionTestResult.FAILED }
        testers[2].apply { isTesting = false; result = ConnectionTestResult.NOT_ENABLED }
        assertEquals(ConnectionTestProgress(completed = 3, failed = 1, total = 4), testers.connectionTestProgress())
    }

    @Test
    fun `stopped testers without a result are not counted as finished`() {
        val testers = List(3) { tester("t$it") }
        // 另外两个被终止了: 不在测, 也没有结果
        testers[0].result = ConnectionTestResult.FAILED
        assertEquals(ConnectionTestProgress(completed = 1, failed = 1, total = 3), testers.connectionTestProgress())
    }

    @Test
    fun `starting a run clears the results of the previous run`() {
        val testers = List(2) { tester("t$it") }
        testers.forEach { it.result = ConnectionTestResult.FAILED }
        // 作用域已取消: 测试协程不会真的跑, 这里只看开跑那一刻的清理
        val runner = DefaultConnectionTesterRunner(testers, CoroutineScope(Job().apply { cancel() }))
        assertEquals(ConnectionTestProgress(completed = 2, failed = 2, total = 2), runner.progress)

        runner.testAll()
        assertNull(runner.progress)
    }
}
