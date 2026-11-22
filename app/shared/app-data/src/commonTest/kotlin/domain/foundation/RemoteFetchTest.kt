/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [RemoteFetch.firstChanged] (带 ETag 问的取法) 与 [RemoteRefresh] 出错后的定期检查. */
class RemoteFetchTest {
    private val a = "https://a.example/file"
    private val b = "https://b.example/file"

    /** 引擎跑在测试调度器上: 请求与每个入口的限时走同一个虚拟时钟. */
    private fun TestScope.client(
        requests: MutableList<HttpRequestData>,
        handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ScopedHttpClient = HttpClient(MockEngine) {
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests += request
                handle(request)
            }
        }
    }.asScopedHttpClient()

    private fun etag(value: String) = headersOf(HttpHeaders.ETag, value)

    @Test
    fun `ETag goes only to the source of the local copy and 304 there means not modified`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(requests) { request ->
            if (request.url.host == "a.example") respond("old", HttpStatusCode.OK) else respond("", HttpStatusCode.NotModified)
        }
        val result = RemoteFetch.firstChanged("test", listOf(a, b), { client }, previous = RemoteFetch.Revision(b, "\"v1\"")) { _, bytes ->
            // a 给的是 CDN 上更早的一版
            if (bytes.decodeToString() == "old") RemoteFetch.reject("stale")
            bytes.decodeToString()
        }
        assertIs<RemoteFetch.Conditional.NotModified>(result)
        assertEquals(b, result.source)
        assertEquals(listOf(null, "\"v1\""), requests.map { it.headers[HttpHeaders.IfNoneMatch] })
    }

    @Test
    fun `failed or rejected source falls through and the result carries the ETag of its source`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(requests) { request ->
            if (request.url.host == "a.example") respond("error", HttpStatusCode.InternalServerError) else respond("new", HttpStatusCode.OK, etag("\"b2\""))
        }
        val result = RemoteFetch.firstChanged("test", listOf(a, b), { client }, previous = null) { _, bytes -> bytes.decodeToString() }
        assertIs<RemoteFetch.Conditional.Changed<String>>(result)
        assertEquals("new", result.value)
        assertEquals("\"b2\"", result.etag)
        assertEquals(b, result.source)
    }

    @Test
    fun `without a local copy no ETag is sent and a 304 does not count`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(requests) { request ->
            if (request.url.host == "a.example") respond("", HttpStatusCode.NotModified) else respond("full", HttpStatusCode.OK)
        }
        val result = RemoteFetch.firstChanged("test", listOf(a, b), { client }, previous = null) { _, bytes -> bytes.decodeToString() }
        assertIs<RemoteFetch.Conditional.Changed<String>>(result)
        assertEquals("full", result.value)
        assertEquals(listOf<String?>(null, null), requests.map { it.headers[HttpHeaders.IfNoneMatch] })
    }

    @Test
    fun `a source that never responds is given up after the timeout`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client(requests) { request ->
            if (request.url.host == "a.example") delay(1.hours)
            respond("ok", HttpStatusCode.OK)
        }
        val result = RemoteFetch.firstChanged("test", listOf(a, b), { client }, previous = null) { _, bytes -> bytes.decodeToString() }
        assertIs<RemoteFetch.Conditional.Changed<String>>(result)
        assertEquals(b, result.source)
        assertEquals(RemoteFetch.SOURCE_TIMEOUT.inWholeSeconds, testScheduler.currentTime / 1000)
    }

    @Test
    fun `all sources failing gives null`() = runTest {
        val client = client(mutableListOf()) { respond("<html>", HttpStatusCode.OK) }
        assertNull(RemoteFetch.firstChanged("test", listOf(a, b), { client }, previous = null) { _, _ -> RemoteFetch.reject("not a table") })
    }

    @Test
    fun `an exception while refreshing does not stop the periodic check`() = runTest {
        var calls = 0
        RemoteRefresh(
            name = "test",
            scope = backgroundScope,
            staleAfter = 1.hours,
            checkEvery = 1.hours,
            lastFetchedAt = { null },
        ) {
            calls++
            if (calls == 1) error("disk full")
            true
        }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1.hours + 1.seconds)
        assertEquals(2, calls)
    }

    @Test
    fun `refreshIfOlderThan fetches only past the given age`() = runTest {
        var calls = 0
        var last: Long? = null
        val refresh = RemoteRefresh(
            name = "test",
            scope = backgroundScope,
            staleAfter = 1.hours,
            checkEvery = 1.hours,
            lastFetchedAt = { last },
        ) {
            calls++
            last = currentTimeMillis()
            true
        }
        runCurrent()
        assertEquals(1, calls)
        // 刚取过: 退回首页不取
        assertFalse(refresh.refreshIfOlderThan(10.minutes))
        last = currentTimeMillis() - 11.minutes.inWholeMilliseconds
        // 没到一小时, 所以定期检查不取; 超过了这次给的间隔就取
        assertFalse(refresh.refreshIfOlderThan(1.hours))
        assertTrue(refresh.refreshIfOlderThan(10.minutes))
        assertEquals(2, calls)
    }
}
