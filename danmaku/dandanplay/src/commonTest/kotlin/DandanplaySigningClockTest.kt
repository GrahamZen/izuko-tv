/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.dandanplay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.toHttpDate
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class DandanplaySigningClockTest {
    private val serverMillis = 1_792_000_000_000L
    private val serverSeconds = serverMillis / 1000
    private var localMillis = serverMillis + 30.minutes.inWholeMilliseconds
    private val timestamps = mutableListOf<Long>()
    private val lock = Mutex()

    @Test
    fun `rejected timestamp is resent with server time`() = runTest {
        val client = createClient { acceptServerTime(it) }

        client.getSeasonAnimeList(2024, 4)

        assertEquals(listOf(serverSeconds + 30 * 60, serverSeconds), timestamps)
    }

    @Test
    fun `requests rejected together are all resent`() = runTest {
        val bothArrived = CompletableDeferred<Unit>()
        var skewedArrivals = 0
        val client = createClient { request ->
            // 两个都到了再一起拒, 像同时发出的请求那样: 先回来的那个换掉偏差时, 另一个已经按旧偏差签过名
            if ((request.timestamp() - serverSeconds).absoluteValue > 600) {
                if (lock.withLock { ++skewedArrivals } < 2) bothArrived.await() else bothArrived.complete(Unit)
            }
            acceptServerTime(request)
        }

        listOf(async { client.getSeasonAnimeList(2024, 4) }, async { client.getSeasonAnimeList(2024, 7) }).awaitAll()

        assertEquals(listOf(serverSeconds + 30 * 60, serverSeconds + 30 * 60, serverSeconds, serverSeconds), timestamps)
    }

    @Test
    fun `correction is kept for later requests`() = runTest {
        val client = createClient { acceptServerTime(it) }

        client.getSeasonAnimeList(2024, 4)
        client.getSeasonAnimeList(2024, 7)

        assertEquals(listOf(serverSeconds + 30 * 60, serverSeconds, serverSeconds), timestamps)
    }

    @Test
    fun `clock fixed while running is picked up by the next rejection`() = runTest {
        val client = createClient { acceptServerTime(it) }
        client.getSeasonAnimeList(2024, 4)

        localMillis = serverMillis
        client.getSeasonAnimeList(2024, 7)

        assertEquals(listOf(serverSeconds + 30 * 60, serverSeconds, serverSeconds - 30 * 60, serverSeconds), timestamps)
    }

    @Test
    fun `other rejections are not resent and carry the reason`() = runTest {
        val client = createClient { reject("Invalid AppId") }

        val e = assertFailsWith<ClientRequestException> { client.getSeasonAnimeList(2024, 4) }

        assertEquals(1, timestamps.size)
        assertTrue("Invalid AppId" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `rejection without a Date header is not resent`() = runTest {
        val client = createClient { reject("Invalid Timestamp", date = false) }

        val e = assertFailsWith<ClientRequestException> { client.getSeasonAnimeList(2024, 4) }

        assertEquals(1, timestamps.size)
        assertTrue("Invalid Timestamp" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `rejection with the same skew is not resent again`() = runTest {
        // 服务器的时间也跟着本机走: 算出来的偏差是 0, 与现在用的一样
        val client = createClient { reject("Invalid Timestamp", serverTime = localMillis) }

        assertFailsWith<ClientRequestException> { client.getSeasonAnimeList(2024, 4) }

        assertEquals(1, timestamps.size)
    }

    private fun HttpRequestData.timestamp() = headers["X-Timestamp"]!!.toLong()

    /** 时间戳与服务器相差十分钟以内放行, 否则像弹弹 play 那样拒绝. */
    private fun MockRequestHandleScope.acceptServerTime(request: HttpRequestData): HttpResponseData =
        if ((request.timestamp() - serverSeconds).absoluteValue <= 600) {
            respond(
                """{"hasMore":false,"bangumiList":[],"errorCode":0,"success":true}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        } else {
            reject("Invalid Timestamp")
        }

    private fun MockRequestHandleScope.reject(
        reason: String,
        date: Boolean = true,
        serverTime: Long = serverMillis,
    ): HttpResponseData {
        val headers = buildList {
            add("X-Error-Message" to listOf(reason))
            if (date) add(HttpHeaders.Date to listOf(GMTDate(serverTime).toHttpDate()))
        }
        return respond("", HttpStatusCode.Forbidden, headersOf(*headers.toTypedArray()))
    }

    private fun createClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): DandanplayClient {
        val engine = MockEngine { request ->
            lock.withLock { timestamps += request.timestamp() }
            handler(request)
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return DandanplayClient(
            client.asScopedHttpClient(),
            appId = "app-id",
            appSecret = "app-secret",
            ioDispatcher = EmptyCoroutineContext,
            signingClock = DandanplaySigningClock { localMillis },
        )
    }
}
