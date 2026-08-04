/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.http.HttpStack
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.UnsafeScopedHttpClientApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull
import com.github.panpf.sketch.http.HttpHeaders as SketchHttpHeaders
import io.ktor.http.HttpHeaders as KtorHttpHeaders

class ScopedHttpClientHttpStackTest {
    @Test
    fun `forwards headers response metadata and body inside scoped lifetime`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals(listOf("first", "second"), request.headers.getAll("X-Added"))
                assertEquals("only", request.headers["X-Set"])
                respond(
                    content = "downstream failure",
                    status = HttpStatusCode.BadGateway,
                    headers = headersOf(
                        KtorHttpHeaders.ContentType to listOf("text/plain"),
                        KtorHttpHeaders.ContentLength to listOf("18"),
                        "X-Response" to listOf("present"),
                    ),
                )
            },
        )
        val scopedClient = TrackingScopedHttpClient(client)
        val stack = ScopedHttpClientHttpStack(scopedClient)

        try {
            val body = stack.request(
                url = "https://example.com/image",
                httpHeaders = SketchHttpHeaders {
                    add("X-Added", "first")
                    add("X-Added", "second")
                    set("X-Set", "only")
                },
                extras = null,
            ) { response ->
                assertEquals(1, scopedClient.activeCount)
                assertEquals(502, response.code)
                assertEquals("Bad Gateway", response.message)
                assertEquals(18L, response.contentLength)
                assertEquals("text/plain", response.contentType)
                assertEquals("present", response.getHeaderField("X-Response"))
                response.content().readUtf8AndClose()
            }

            assertEquals("downstream failure", body)
            assertEquals(1, scopedClient.borrowCount)
            assertEquals(1, scopedClient.returnCount)
            assertEquals(0, scopedClient.activeCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun `returns borrowed client exactly once when request fails`() = runTest {
        val failure = IOException("network failed")
        val client = HttpClient(MockEngine { throw failure })
        val scopedClient = TrackingScopedHttpClient(client)
        val stack = ScopedHttpClientHttpStack(scopedClient)

        try {
            val actual = assertFailsWith<IOException> {
                stack.request("https://example.com/failure", null, null) { Unit }
            }
            assertEquals(failure.message, actual.message)
            assertEquals(1, scopedClient.borrowCount)
            assertEquals(1, scopedClient.returnCount)
            assertEquals(0, scopedClient.activeCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancellation returns borrowed client`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine {
                requestStarted.complete(Unit)
                awaitCancellation()
            },
        )
        val scopedClient = TrackingScopedHttpClient(client)
        val stack = ScopedHttpClientHttpStack(scopedClient)

        try {
            val job = launch {
                stack.request("https://example.com/slow", null, null) { Unit }
            }
            requestStarted.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(1, scopedClient.borrowCount)
            assertEquals(1, scopedClient.returnCount)
            assertEquals(0, scopedClient.activeCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun `each request borrows the latest client`() = runTest {
        val first = responseClient("first")
        val second = responseClient("second")
        val scopedClient = TrackingScopedHttpClient(first)
        val stack = ScopedHttpClientHttpStack(scopedClient)

        try {
            assertEquals("first", stack.readBody())
            scopedClient.currentClient = second
            assertEquals("second", stack.readBody())

            assertEquals(listOf(first, second), scopedClient.borrowedClients)
            assertEquals(2, scopedClient.returnCount)
            assertEquals(0, scopedClient.activeCount)
        } finally {
            first.close()
            second.close()
        }
    }

    private fun responseClient(body: String): HttpClient = HttpClient(
        MockEngine {
            respond(body)
        },
    )

    private suspend fun ScopedHttpClientHttpStack.readBody(): String =
        request("https://example.com/image", null, null) { response ->
            response.content().readUtf8AndClose()
        }
}

private suspend fun HttpStack.Content.readUtf8AndClose(): String {
    val bytes = mutableListOf<Byte>()
    val buffer = ByteArray(4)
    try {
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            repeat(count) { index -> bytes += buffer[index] }
        }
    } finally {
        close()
    }
    return ByteArray(bytes.size) { bytes[it] }.decodeToString()
}

@OptIn(UnsafeScopedHttpClientApi::class)
private class TrackingScopedHttpClient(
    initialClient: HttpClient,
) : ScopedHttpClient() {
    var currentClient: HttpClient = initialClient
    var borrowCount: Int = 0
        private set
    var returnCount: Int = 0
        private set
    var activeCount: Int = 0
        private set
    val borrowedClients = mutableListOf<HttpClient>()

    override fun borrow(): Ticket {
        val borrowedClient = currentClient
        borrowCount++
        activeCount++
        borrowedClients += borrowedClient
        return object : Ticket {
            override val client: HttpClient = borrowedClient
        }
    }

    override fun returnClient(ticket: Ticket) {
        check(activeCount > 0) { "A client ticket was returned more than once" }
        returnCount++
        activeCount--
    }
}

/**
 * 钉住图片请求的两个超时.
 *
 * **读超时不能少**: 只设连接超时的话, "连上了但流假死"这一类会一路等到全局默认的 30 秒 ——
 * 这台电视上假死是常态 (实测背景图卡住 10.7s/10.2s、剧集图 5.18s, 同期其它请求 200~300ms).
 * 这两个数字都是行为的一部分, 不是随手填的, 所以在这里钉住 (改动请连带更新
 * `configureAniImageRequest` 里的说明).
 */
class AniImageRequestTimeoutTest {
    @Test
    fun `image requests carry both a connect and a socket timeout`() = runTest {
        var captured: HttpTimeoutConfig? = null
        val client = HttpClient(
            MockEngine { request ->
                captured = request.getCapabilityOrNull(HttpTimeoutCapability)
                respond("x", HttpStatusCode.OK)
            },
        )
        try {
            ScopedHttpClientHttpStack(TrackingScopedHttpClient(client))
                .request("https://example.com/a.jpg", null, null) { it.code }
        } finally {
            client.close()
        }

        val timeouts = requireNotNull(captured) { "图片请求没有带 HttpTimeout" }
        assertEquals(2_500L, timeouts.connectTimeoutMillis)
        assertEquals(3_000L, timeouts.socketTimeoutMillis, "流假死必须靠读超时兜住, 否则等到全局的 30 秒")
    }
}

/**
 * 评论里贴的常常是图床的**网页**地址而不是图片直链 (实测 `https://postimg.cc/Rq7Lt8Y4`:
 * 200 + text/html 51KB), 解码器解不出图, 界面上是"转很久然后图片已失效".
 *
 * 修法是通用的: 响应是 HTML 就顺着 `og:image` 再取一次, 不按图床逐个写正则
 * —— postimg 的网页 id 与直链 id 还不是一回事 (网页 id 只能换到 180×101 缩略图,
 * `og:image` 指的才是 1280×720 原图).
 */
class ImageHostPageResolutionTest {
    private fun page(ogImage: String) = """
        <html><head>
        <meta property="og:title" content="mpv-shot0001">
        <meta property="og:image" content="$ogImage">
        </head><body>x</body></html>
    """.trimIndent()

    @Test
    fun `extracts og image`() {
        assertEquals(
            "https://i.postimg.cc/J7g6fCcD/mpv-shot0001.png",
            extractOpenGraphImageUrl(page("https://i.postimg.cc/J7g6fCcD/mpv-shot0001.png")),
        )
    }

    @Test
    fun `extracts twitter image when og is absent`() {
        assertEquals(
            "https://example.com/a.png",
            extractOpenGraphImageUrl("""<meta name="twitter:image" content="https://example.com/a.png">"""),
        )
    }

    @Test
    fun `decodes entities and ignores relative urls`() {
        assertEquals(
            "https://example.com/a.png?w=1&h=2",
            extractOpenGraphImageUrl("""<meta property="og:image" content="https://example.com/a.png?w=1&amp;h=2">"""),
        )
        assertNull(extractOpenGraphImageUrl("""<meta property="og:image" content="/relative.png">"""))
        assertNull(extractOpenGraphImageUrl("<html><body>no meta</body></html>"))
    }

    @Test
    fun `html response is followed to the image`() = runTest {
        val requested = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requested += request.url.toString()
                when (request.url.encodedPath) {
                    "/Rq7Lt8Y4" -> respond(
                        content = page("https://i.postimg.cc/J7g6fCcD/a.png"),
                        headers = headersOf(KtorHttpHeaders.ContentType, "text/html; charset=UTF-8"),
                    )

                    else -> respond(
                        content = "image-bytes",
                        headers = headersOf(KtorHttpHeaders.ContentType, "image/png"),
                    )
                }
            },
        )
        val stack = ScopedHttpClientHttpStack(TrackingScopedHttpClient(client))

        val contentType = stack.request("https://postimg.cc/Rq7Lt8Y4", null, null) { it.contentType }

        assertEquals("image/png", contentType)
        assertEquals(
            listOf("https://postimg.cc/Rq7Lt8Y4", "https://i.postimg.cc/J7g6fCcD/a.png"),
            requested,
        )
    }

    /** 只跟一跳: 图片页指向另一个图片页时不再继续跟, 免得被兜圈子. */
    @Test
    fun `follows at most one hop`() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                respond(
                    content = page("https://example.com/next"),
                    headers = headersOf(KtorHttpHeaders.ContentType, "text/html"),
                )
            },
        )
        val stack = ScopedHttpClientHttpStack(TrackingScopedHttpClient(client))

        val contentType = stack.request("https://example.com/page", null, null) { it.contentType }

        assertEquals("text/html", contentType)
        assertEquals(2, requests)
    }

    /** 正常图片请求不进这条路: 一次请求, 不读 body. */
    @Test
    fun `image response is not touched`() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                respond(content = "png", headers = headersOf(KtorHttpHeaders.ContentType, "image/png"))
            },
        )
        val stack = ScopedHttpClientHttpStack(TrackingScopedHttpClient(client))

        assertEquals("image/png", stack.request("https://example.com/a.png", null, null) { it.contentType })
        assertEquals(1, requests)
    }
}
