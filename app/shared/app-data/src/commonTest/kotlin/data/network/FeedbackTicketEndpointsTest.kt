/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 工单服务的地址清单: 只认 https 根地址, 没拉到过用包里内置的. */
class FeedbackTicketEndpointsTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    @Test
    fun `only https base urls are accepted`() {
        assertEquals("https://tickets.example.com", FeedbackTicketEndpoints.normalizeEndpoint(" https://tickets.example.com/ "))
        assertNull(FeedbackTicketEndpoints.normalizeEndpoint("http://tickets.example.com"))
        assertNull(FeedbackTicketEndpoints.normalizeEndpoint("https://"))
        assertNull(FeedbackTicketEndpoints.normalizeEndpoint("https://a b.example.com"))
    }

    @Test
    fun `built-in endpoints until the list is fetched then the fetched ones`() = runTest {
        val cache = MemorySettings(RepoHostedListCache())
        // 连不上: 建出来时的第一次检查拉不到, 用内置的
        val endpoints = FeedbackTicketEndpoints(cache, { error("offline") }, backgroundScope)
        runCurrent()
        assertEquals(FeedbackTicketService.OFFICIAL_ENDPOINTS, endpoints.current.value)

        cache.set(RepoHostedListCache(entries = listOf("https://tickets.example.com"), updatedAt = 1))
        runCurrent()
        assertEquals(listOf("https://tickets.example.com"), endpoints.current.value)
    }
}
