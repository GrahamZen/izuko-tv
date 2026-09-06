/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** 「反馈」的提交: 中转按顺序试, 挂着不回的到点换下一个; 都连不上与被拒收分开报. */
class SubjectFeedbackReportTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private class Harness(scheduler: TestCoroutineScheduler, relays: List<String>) {
        /** 按地址给 (状态码, 响应体); 返回 `null` = 连不上. */
        var replies: (url: String) -> Pair<Int, String>? = { null }

        /** 这些地址连接一直挂着, 不回也不断. */
        var hanging: (url: String) -> Boolean = { false }
        val requested = mutableListOf<String>()

        // 引擎跑在测试调度器上: 请求与超时走同一个虚拟时钟 (默认的 IO 线程上回响应时, 测试调度器以为没事可做, 直接把时间拨过超时)
        private val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(scheduler)
                addHandler { request ->
                    val url = request.url.toString()
                    requested += url
                    if (hanging(url)) awaitCancellation()
                    val (status, body) = replies(url) ?: throw IOException("blocked")
                    respond(body, HttpStatusCode.fromValue(status))
                }
            }
        }

        // 构造时的自动刷新落在已取消的作用域里不会跑
        val service = SubjectFeedbackService(
            MemorySettings(RepoHostedListCache(relays, updatedAt = if (relays.isEmpty()) 0 else currentTimeMillis())),
            client = { client.asScopedHttpClient() },
            scope = CoroutineScope(Job().apply { cancel() }),
        )

        suspend fun report() = service.reportEntry(1, null)
    }

    private val relays = listOf("https://a.example.com", "https://b.example.com")

    @Test
    fun `中转挂着不回 — 到点换下一个`() = runTest {
        val harness = Harness(testScheduler, relays)
        harness.hanging = { "a.example.com" in it }
        harness.replies = { 200 to """{"issue": 42}""" }
        assertEquals(SubjectFeedbackService.Result.Created(42), harness.report())
        assertEquals(listOf("https://a.example.com/entry-report", "https://b.example.com/entry-report"), harness.requested)
    }

    @Test
    fun `中转都挂着或连不上 — 网络不通`() = runTest {
        val harness = Harness(testScheduler, relays)
        harness.hanging = { "a.example.com" in it }
        assertEquals(SubjectFeedbackService.Result.Unreachable, harness.report())
        assertEquals(2, harness.requested.size)
    }

    @Test
    fun `中转拒收 — 提交失败，不再试下一个`() = runTest {
        val harness = Harness(testScheduler, relays)
        harness.replies = { 400 to "bad request" }
        assertEquals(SubjectFeedbackService.Result.Failed, harness.report())
        assertEquals(1, harness.requested.size)
    }

    @Test
    fun `中转都出错 — 提交失败`() = runTest {
        val harness = Harness(testScheduler, relays)
        harness.replies = { url -> if ("a.example.com" in url) null else 502 to "bad gateway" }
        assertEquals(SubjectFeedbackService.Result.Failed, harness.report())
    }

    @Test
    fun `中转地址清单拉不到 — 网络不通`() = runTest {
        val harness = Harness(testScheduler, emptyList())
        harness.hanging = { "testingcf" in it }
        assertEquals(SubjectFeedbackService.Result.Unreachable, harness.report())
        // 清单的四个入口都试过, 没有可试的中转
        assertEquals(4, harness.requested.size)
    }
}
