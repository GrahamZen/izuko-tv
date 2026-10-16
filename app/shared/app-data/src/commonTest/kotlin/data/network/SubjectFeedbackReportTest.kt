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
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
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
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.foundation.CloudflareServiceTransport
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 「反馈」的提交: 中转按顺序试, 挂着不回的到点换下一个; 都连不上与被拒收分开报. */
class SubjectFeedbackReportTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private class Harness(
        scheduler: TestCoroutineScheduler,
        relays: List<String>,
        ech: Boolean = false,
        preferEch: Boolean = false,
    ) {
        /** 按地址给 (状态码, 响应体); 返回 `null` = 连不上. */
        var replies: (url: String) -> Pair<Int, String>? = { null }

        /** 这些地址连接一直挂着, 不回也不断. */
        var hanging: (url: String) -> Boolean = { false }
        val requested = mutableListOf<String>()

        /** 每个请求设的连接超时 (没设为 null). */
        val connectTimeouts = mutableListOf<Long?>()
        val records = MemorySettings(FeedbackRecords.Empty)

        /** 加密直连握手被拒时换不换得到新配置 (见 SubjectFeedbackService.recoverEch); 每换一次记一次. */
        var recoverable = false
        var recovered = 0

        // 引擎跑在测试调度器上: 请求与超时走同一个虚拟时钟 (默认的 IO 线程上回响应时, 测试调度器以为没事可做, 直接把时间拨过超时)
        private val client = HttpClient(MockEngine) {
            install(HttpTimeout)
            engine {
                dispatcher = StandardTestDispatcher(scheduler)
                addHandler { request ->
                    val url = request.url.toString()
                    requested += url
                    connectTimeouts += request.getCapabilityOrNull(HttpTimeoutCapability)?.connectTimeoutMillis
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
            transport = CloudflareServiceTransport(
                echUsable = { ech },
                preferEch = { preferEch },
                recoverEch = { if (recoverable) true.also { recoverable = false; recovered++ } else false },
            ),
            records = FeedbackRecordRepository(records),
            clock = { 1000L },
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

    @Test
    fun `直连不通 — 同一个中转再走加密直连`() = runTest {
        val harness = Harness(testScheduler, relays, ech = true)
        harness.replies = { url -> if (".ech.invalid" in url) 200 to """{"issue": 7}""" else null }
        assertEquals(SubjectFeedbackService.Result.Created(7), harness.report())
        assertEquals(
            listOf("https://a.example.com/entry-report", "https://a.example.com.ech.invalid/entry-report"),
            harness.requested,
        )
    }

    @Test
    fun `bangumi 已经在走加密直连 — 先试加密直连`() = runTest {
        val harness = Harness(testScheduler, relays, ech = true, preferEch = true)
        harness.replies = { 200 to """{"issue": 7}""" }
        assertEquals(SubjectFeedbackService.Result.Created(7), harness.report())
        assertEquals(listOf("https://a.example.com.ech.invalid/entry-report"), harness.requested)
    }

    @Test
    fun `加密直连握手被拒 — 换配置再试一次`() = runTest {
        val harness = Harness(testScheduler, listOf("https://a.example.com"), ech = true, preferEch = true)
        harness.recoverable = true
        harness.replies = { url -> if (".ech.invalid" in url && harness.recovered > 0) 200 to """{"issue": 7}""" else null }
        assertEquals(SubjectFeedbackService.Result.Created(7), harness.report())
        assertEquals(List(2) { "https://a.example.com.ech.invalid/entry-report" }, harness.requested)
    }

    @Test
    fun `上次加密直连通了 — 这个服务下次先走加密直连`() = runTest {
        val harness = Harness(testScheduler, listOf("https://a.example.com"), ech = true)
        harness.replies = { url -> if (".ech.invalid" in url) 200 to """{"issue": 7}""" else null }
        assertEquals(SubjectFeedbackService.Result.Created(7), harness.report())
        assertEquals(listOf("https://a.example.com/entry-report", "https://a.example.com.ech.invalid/entry-report"), harness.requested)
        harness.requested.clear()
        assertEquals(SubjectFeedbackService.Result.Created(7), harness.report())
        assertEquals(listOf("https://a.example.com.ech.invalid/entry-report"), harness.requested)
    }

    @Test
    fun `先试的那种 — 只把连接超时缩短，最后一种照常`() = runTest {
        val harness = Harness(testScheduler, listOf("https://a.example.com"), ech = true)
        harness.replies = { url -> if (".ech.invalid" in url) 200 to """{"issue": 7}""" else null }
        harness.report()
        assertEquals(listOf(CloudflareServiceTransport.PROBE_CONNECT_MILLIS, null), harness.connectTimeouts)
    }

    @Test
    fun `加密直连用不了 — 只试直连`() = runTest {
        val harness = Harness(testScheduler, relays, ech = false, preferEch = true)
        assertEquals(SubjectFeedbackService.Result.Unreachable, harness.report())
        assertEquals(listOf("https://a.example.com/entry-report", "https://b.example.com/entry-report"), harness.requested)
    }

    @Test
    fun `提交成功 — 记进本机的反馈记录`() = runTest {
        val harness = Harness(testScheduler, relays)
        harness.replies = { 200 to """{"status": "created", "issue": 42}""" }
        harness.service.reportLogo(7, "tv/1", "ja", null, subjectName = "作品")
        harness.replies = { 200 to """{"status": "pending", "issue": 9}""" }
        harness.service.reportEntry(8, null)
        assertEquals(
            listOf(
                FeedbackRecord(FeedbackKind.ENTRY, 9, 8, null, submittedAt = 1000L, joinedExisting = true, requested = FeedbackRecord.REQUESTED_NONE),
                FeedbackRecord(FeedbackKind.LOGO, 42, 7, "作品", language = "ja", submittedAt = 1000L, requested = FeedbackRecord.REQUESTED_NONE),
            ),
            harness.records.state.value.items,
        )
    }

    @Test
    fun `查处理结果 — 一批问一次，解析中转的回答`() = runTest {
        val harness = Harness(testScheduler, relays)
        harness.replies = { url ->
            if ("a.example.com" in url) null
            else 200 to """{"issues": [{"issue": 65, "state": "closed", "reason": "completed"},
                {"issue": 70, "state": "closed", "reason": "not_planned", "note": "另一季的", "note_at": "2026-10-16T06:20:00Z"},
                {"issue": 9, "error": "not_found"}]}"""
        }
        assertEquals(
            listOf(
                SubjectFeedbackService.IssueStatus(65, "closed", "completed"),
                SubjectFeedbackService.IssueStatus(70, "closed", "not_planned", "另一季的", "2026-10-16T06:20:00Z"),
                SubjectFeedbackService.IssueStatus(9, error = "not_found"),
            ),
            harness.service.status(listOf(65, 70, 9, 65)),
        )
        assertEquals(listOf("https://a.example.com/status", "https://b.example.com/status"), harness.requested)
    }

    @Test
    fun `查处理结果 — 中转还没有这个接口时当作没查成`() = runTest {
        val harness = Harness(testScheduler, relays)
        harness.replies = { 404 to """{"error": "not_found"}""" }
        assertNull(harness.service.status(listOf(1)))
        assertEquals(1, harness.requested.size)
    }

    @Test
    fun `没提交成功 — 不记`() = runTest {
        val harness = Harness(testScheduler, relays)
        harness.replies = { 429 to "" }
        assertEquals(SubjectFeedbackService.Result.RateLimited, harness.report())
        assertEquals(emptyList(), harness.records.state.value.items)
    }
}

