/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TogetherBrokerListTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private class Harness(scheduler: TestCoroutineScheduler, cached: RepoHostedListCache = RepoHostedListCache()) {
        /** 清单文件的回应体; `null` = 连不上 */
        var body: String? = null
        var requests = 0

        private val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(scheduler)
                addHandler {
                    requests++
                    respond(body ?: throw IOException("blocked"), HttpStatusCode.OK)
                }
            }
        }

        // 构造时的自动刷新落在已取消的作用域里不会跑, 由用例自己触发
        val list = TogetherBrokerList(
            MemorySettings(cached),
            client = { client.asScopedHttpClient() },
            scope = CoroutineScope(Job().apply { cancel() }),
        )
    }

    @Test
    fun `accepts wss addresses with port and path`() {
        assertEquals("wss://broker.emqx.io:8084/mqtt", TogetherBrokerList.normalizeBroker("  wss://broker.emqx.io:8084/mqtt "))
        assertEquals("wss://mqtt.example.com/mqtt", TogetherBrokerList.normalizeBroker("wss://mqtt.example.com/mqtt"))
        assertEquals("wss://mqtt.example.com", TogetherBrokerList.normalizeBroker("wss://mqtt.example.com"))
    }

    @Test
    fun `rejects other schemes and things that are not addresses`() {
        for (input in listOf(
            "ws://broker.emqx.io:8083/mqtt",
            "https://broker.emqx.io:8084/mqtt",
            "mqtt://broker.emqx.io:1883",
            "broker.emqx.io:8084/mqtt",
            "wss://",
            "wss://localhost:8084/mqtt",
            "wss://broker.emqx.io:8084/mqtt extra",
            "",
        )) {
            assertNull(TogetherBrokerList.normalizeBroker(input), input)
        }
    }

    @Test
    fun `has no brokers until a list is fetched`() = runTest {
        assertEquals(emptyList(), Harness(testScheduler).list.entries.first())
    }

    @Test
    fun `fetches the list when connecting without one`() = runTest {
        val harness = Harness(testScheduler)
        harness.body = """{"brokers": ["wss://broker.emqx.io:8084/mqtt", "ws://plain.example.com/mqtt", " wss://mqtt.example.com/mqtt ", 3]}"""
        assertEquals(listOf("wss://broker.emqx.io:8084/mqtt", "wss://mqtt.example.com/mqtt"), harness.list.brokersToConnect())
        assertTrue(harness.requests > 0)
    }

    @Test
    fun `uses the stored list without fetching`() = runTest {
        val stored = listOf("wss://mqtt.example.com/mqtt")
        val harness = Harness(testScheduler, RepoHostedListCache(entries = stored, updatedAt = currentTimeMillis()))
        assertEquals(stored, harness.list.brokersToConnect())
        assertEquals(0, harness.requests)
    }

    @Test
    fun `nothing to connect when the list cannot be fetched`() = runTest {
        val harness = Harness(testScheduler)
        assertEquals(emptyList(), harness.list.brokersToConnect())
        assertTrue(harness.requests > 0)
    }

    @Test
    fun `connecting without brokers fails with its own reason`() = runTest {
        val factory = TogetherChannelFactory(client = { error("must not connect") }, brokerUrls = { emptyList() })
        assertFailsWith<TogetherNoBrokersException> {
            factory.connect(TogetherRoomId("12345678", "123456"), this, farewell = { null })
        }
    }
}
