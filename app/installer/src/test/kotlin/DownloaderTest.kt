/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 本机起几条「线路」(限速的 HTTP 服务, 认 Range), 看测速、按实测速度换线路、手动指定、断线续传是否接得上.
 */
class DownloaderTest {
    private val payload = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + Random(1).nextBytes(768 * 1024 - 4)
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val logs = mutableListOf<String>()

    /** 每条线路: 开头 [fastBytes] 字节不限速, 之后每 [chunkDelayMs] 毫秒给 [chunkBytes]; 给到 [breakAfter] 字节就断开. */
    private class Route(
        val fastBytes: Int = 0,
        val chunkBytes: Int = 64 * 1024,
        val chunkDelayMs: Long = 0,
        val breakAfter: Int = Int.MAX_VALUE,
        val html: Boolean = false,
    )

    private val tuning = Downloader.Tuning(
        probeBytes = 64 * 1024,
        minProbeSample = 8 * 1024,
        probeTimeoutMs = 3_000,
        probeGraceMs = 300,
        switchAfterMs = 1_500,
        switchFactor = 2.5,
        minSwitchGainBytesPerSecond = 50 * 1024,
        minSwitchRemainingBytes = 0,
        rateWindowMs = 1_500,
        stallMs = 1_500,
    )

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        dir = Files.createTempDirectory("downloader-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun route(name: String, route: Route): String {
        server.createContext("/$name") { exchange -> serve(exchange, route) }
        return "http://127.0.0.1:${server.address.port}/$name"
    }

    private fun serve(exchange: HttpExchange, route: Route) {
        try {
            if (route.html) {
                val body = "<html>error</html>".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return
            }
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            val start = range?.substringBefore('-')?.toInt() ?: 0
            val end = range?.substringAfter('-')?.takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(payload.size - 1) ?: (payload.size - 1)
            val length = end - start + 1
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                exchange.sendResponseHeaders(206, length.toLong())
            } else {
                exchange.sendResponseHeaders(200, length.toLong())
            }
            exchange.responseBody.use { out ->
                var position = start
                while (position <= end) {
                    if (position >= route.breakAfter) throw IOException("route breaks here")
                    val fast = position < route.fastBytes
                    val n = minOf(if (fast) route.fastBytes - position else route.chunkBytes, end - position + 1, route.breakAfter - position)
                    out.write(payload, position, n)
                    out.flush()
                    position += n
                    if (!fast && route.chunkDelayMs > 0) Thread.sleep(route.chunkDelayMs)
                }
            }
        } catch (e: IOException) {
            // 下载器换线路时断开连接
        } finally {
            exchange.close()
        }
    }

    private fun file(url: String) = RemoteFile("izuko.apk", url, payload.size.toLong(), sha256)

    private fun downloader() = Downloader({ synchronized(logs) { logs += it } }, tuning)

    @Test
    fun `switches to a faster route when the first one slows down`() {
        // 测速那一段很快, 之后约 40 KB/s
        val slow = route("slow", Route(fastBytes = 128 * 1024, chunkBytes = 8 * 1024, chunkDelayMs = 200))
        // 一直约 1 MB/s
        val steady = route("steady", Route(chunkBytes = 32 * 1024, chunkDelayMs = 30))
        val dest = File(dir, "izuko.apk")
        val downloader = downloader()
        downloader.download(file(slow), listOf(slow, steady), dest, onProgress = {})
        assertContentEquals(payload, dest.readBytes())
        assertEquals(steady, downloader.statuses().single { it.current }.url)
        assertTrue(logs.any { it.startsWith("Leaving 127.0.0.1") }, logs.joinToString("\n"))
    }

    @Test
    fun `manual choice takes over at once and resumes`() {
        val a = route("a", Route(chunkBytes = 16 * 1024, chunkDelayMs = 50))
        val b = route("b", Route(chunkBytes = 32 * 1024, chunkDelayMs = 50))
        val dest = File(dir, "izuko.apk")
        val downloader = Downloader({ synchronized(logs) { logs += it } }, Downloader.Tuning(probeBytes = 64 * 1024, minProbeSample = 8 * 1024, switchAfterMs = 60_000))
        var chosen = false
        downloader.download(file(a), listOf(a, b), dest, onProgress = { progress ->
            // 先从测速快的 b 开始, 下了一点就指定 a
            if (!chosen && progress.done > 32 * 1024 && progress.done < payload.size / 2) {
                chosen = true
                downloader.choose(a)
            }
        })
        assertContentEquals(payload, dest.readBytes())
        assertTrue(chosen)
        assertTrue(logs.any { it.contains("manual") }, logs.joinToString("\n"))
    }

    @Test
    fun `a broken or stalled route is left and the next one resumes`() {
        val broken = route("broken", Route(fastBytes = 256 * 1024, breakAfter = 300 * 1024))
        val steady = route("steady2", Route(chunkBytes = 32 * 1024, chunkDelayMs = 20))
        val dest = File(dir, "izuko.apk")
        val downloader = downloader()
        downloader.download(file(broken), listOf(broken, steady), dest, onProgress = {})
        assertContentEquals(payload, dest.readBytes())
        val statuses = downloader.statuses()
        // 断开的算出错; 卡住不动的 (这个测试服务器断开后客户端常常读不到结束) 排到最后
        val left = statuses.single { it.url == broken }
        assertTrue(left.error != null || left.measuredBytesPerSecond == 0L, "${left.error} ${left.measuredBytesPerSecond}")
        assertEquals(steady, statuses.single { it.current }.url)
    }

    @Test
    fun `a route answering with a web page is not used`() {
        val page = route("page", Route(html = true))
        val good = route("good", Route(chunkBytes = 64 * 1024, chunkDelayMs = 5))
        val dest = File(dir, "izuko.apk")
        val downloader = downloader()
        downloader.download(file(page), listOf(page, good), dest, onProgress = {})
        assertContentEquals(payload, dest.readBytes())
        assertEquals("IOException: not an APK", downloader.statuses().single { it.url == page }.error)
    }
}
