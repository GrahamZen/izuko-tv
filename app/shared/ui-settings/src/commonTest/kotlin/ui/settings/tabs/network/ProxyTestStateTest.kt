/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyTestStateTest {
    @Test
    fun `completed count only includes items that have a result`() {
        val state = ProxyTestState(
            testRunning = true,
            items = listOf(
                ProxyTestItem(ProxyTestCase.BangumiApi, ProxyTestCaseState.SUCCESS),
                ProxyTestItem(ProxyTestCase.BangumiNextApi, ProxyTestCaseState.FAILED),
                ProxyTestItem(ProxyTestCase.TmdbApi, ProxyTestCaseState.RUNNING),
                ProxyTestItem(ProxyTestCase.TmdbImage, ProxyTestCaseState.INIT),
            ),
        )
        assertEquals(2, state.completedCount)
    }

    @Test
    fun `no items means nothing completed`() {
        assertEquals(0, ProxyTestState.Default.completedCount)
    }
}
