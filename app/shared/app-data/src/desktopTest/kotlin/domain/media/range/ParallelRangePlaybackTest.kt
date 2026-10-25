/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.range

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.media.hls.KtorNetworkHlsProxyServer
import me.him188.ani.app.domain.settings.NoProxyProvider
import me.him188.ani.app.platform.PlaybackRequestHints
import org.openani.mediamp.source.UriMediaData
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 网盘原文件经本机代理多连接播放 (iOS 用的那套代理): 播放器要的那一段由几条连接同时拉、按顺序送; 请求头照带, 提示不外发;
 * 网盘不认范围请求时不走代理; 播放器断开后没送的块不再拉.
 */
class ParallelRangePlaybackTest {
    private val file = ByteArray(2 * 1024 * 1024) { (it * 31 % 251).toByte() }

    private fun playback(provider: DefaultHttpClientProvider, enabled: Boolean = true) =
        ParallelRangePlayback(provider, KtorNetworkHlsProxyServer.Factory, enabled)

    private fun hinted(url: String, connections: Int = 8, extra: Map<String, String> = emptyMap()) = UriMediaData(
        url,
        extra + mapOf(PlaybackRequestHints.PARALLEL_RANGE_HEADER to "$connections", PlaybackRequestHints.CACHE_KEY_HEADER to "file-1"),
    )

    /** 向代理要一段 (不带 Range 时要整个文件): 状态码、Content-Range 与正文. */
    private fun get(uri: String, range: String?): Triple<Int, String?, ByteArray> {
        val connection = URI(uri).toURL().openConnection() as HttpURLConnection
        if (range != null) connection.setRequestProperty("Range", range)
        try {
            val body = connection.inputStream.use { it.readBytes() }
            return Triple(connection.responseCode, connection.getHeaderField("Content-Range"), body)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a range is served in order through several connections at once`() = runTest {
        val origin = RangeOrigin(file, bytesPerSecond = 256 * 1024)
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        val result = withContext(Dispatchers.Default) { playback(provider).prepare(hinted("${origin.baseUrl}/v/file")) }
        try {
            assertNotNull(result)
            assertTrue(result.data.headers.isEmpty())
            // 每条通道一个独立的客户端 (iOS 上一个客户端只有一条连接)
            assertEquals(8, (result.session as ParallelRangeProxySession).laneClients)
            val (status, contentRange, body) = withContext(Dispatchers.IO) { get(result.data.uri, "bytes=1000-1500000") }
            assertEquals(206, status)
            assertEquals("bytes 1000-1500000/${file.size}", contentRange)
            assertContentEquals(file.copyOfRange(1000, 1500001), body)
            assertTrue(origin.maxActive.get() >= 4, "最多同时 ${origin.maxActive.get()} 个连接")
        } finally {
            result?.session?.close()
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `whole file and tiny ranges are answered`() = runTest {
        val origin = RangeOrigin(file)
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        val result = withContext(Dispatchers.Default) { playback(provider).prepare(hinted("${origin.baseUrl}/v/file")) }
        try {
            assertNotNull(result)
            val whole = withContext(Dispatchers.IO) { get(result.data.uri, null) }
            assertEquals(200, whole.first)
            assertContentEquals(file, whole.third)
            val tiny = withContext(Dispatchers.IO) { get(result.data.uri, "bytes=0-1") }
            assertEquals(206, tiny.first)
            assertContentEquals(file.copyOfRange(0, 2), tiny.third)
            val tail = withContext(Dispatchers.IO) { get(result.data.uri, "bytes=-100") }
            assertContentEquals(file.copyOfRange(file.size - 100, file.size), tail.third)
        } finally {
            result?.session?.close()
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `request headers are forwarded and playback hints stay local`() = runTest {
        // 网盘的登录 Cookie 里有 base64 与百分号编码的值, 要一个字节不差地到网盘
        val cookie = "__pus=AAQ+x/y0Z==; __kps=AAT%2Bz%3D; a=b"
        val origin = RangeOrigin(file, requiredHeader = "Cookie" to cookie)
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        val result = withContext(Dispatchers.Default) {
            playback(provider).prepare(hinted("${origin.baseUrl}/v/file", extra = mapOf("Cookie" to cookie)))
        }
        try {
            assertNotNull(result)
            val (status, _, body) = withContext(Dispatchers.IO) { get(result.data.uri, "bytes=0-300000") }
            assertEquals(206, status)
            assertContentEquals(file.copyOfRange(0, 300001), body)
            assertFalse(origin.seenHeaderNames.any { it.startsWith("x-izuko-") }, "发给网盘的请求头: ${origin.seenHeaderNames}")
        } finally {
            result?.session?.close()
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `only the resolver headers reach the origin`() = runTest {
        // 发给网盘的只有解析器给的请求头: 不追加 UA, 也不带 JSON 的 Accept
        val origin = RangeOrigin(file, strictUserAgent = "DriveClient/1.0")
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        val result = withContext(Dispatchers.Default) {
            playback(provider).prepare(hinted("${origin.baseUrl}/v/file", extra = mapOf("User-Agent" to "DriveClient/1.0")))
        }
        try {
            assertNotNull(result, "探测被网盘拒了")
            val (status, _, body) = withContext(Dispatchers.IO) { get(result.data.uri, "bytes=0-200000") }
            assertEquals(206, status)
            assertContentEquals(file.copyOfRange(0, 200001), body)
        } finally {
            result?.session?.close()
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `logged errors keep only the host of an address`() {
        val message = describe(IllegalStateException("GET https://dl.example.com/abc/def?sign=secret&t=1 failed"))
        assertFalse("secret" in message || "/abc" in message, message)
        assertTrue("https://dl.example.com" in message, message)
    }

    @Test
    fun `origin ignoring ranges is played directly`() = runTest {
        val origin = RangeOrigin(file, ignoreRange = true)
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        try {
            assertNull(withContext(Dispatchers.Default) { playback(provider).prepare(hinted("${origin.baseUrl}/v/file")) })
        } finally {
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `nothing happens without the hint or off iOS`() = runTest {
        val origin = RangeOrigin(file)
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        try {
            assertNull(withContext(Dispatchers.Default) { playback(provider).prepare(UriMediaData("${origin.baseUrl}/v/file")) })
            assertNull(withContext(Dispatchers.Default) { playback(provider, enabled = false).prepare(hinted("${origin.baseUrl}/v/file")) })
            assertEquals(0, origin.requests.get())
        } finally {
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `player disconnecting stops pulling the rest`() = runTest {
        val origin = RangeOrigin(file, bytesPerSecond = 64 * 1024)
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        val result = withContext(Dispatchers.Default) { playback(provider).prepare(hinted("${origin.baseUrl}/v/file")) }
        try {
            assertNotNull(result)
            withContext(Dispatchers.IO) {
                val connection = URI(result.data.uri).toURL().openConnection() as HttpURLConnection
                connection.inputStream.use { input -> input.readNBytes(1024) }
                connection.disconnect()
                // 断开后在拉的块随之取消, 网盘那边的连接都断掉
                val started = TimeSource.Monotonic.markNow()
                while (origin.active.get() > 0 && started.elapsedNow() < 5.seconds) Thread.sleep(50)
            }
            assertEquals(0, origin.active.get(), "断开后还有 ${origin.active.get()} 个连接在拉")
        } finally {
            result?.session?.close()
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `chunks cover the range and grow round by round`() {
        val chunks = planChunks(100, 100 + 10L * 1024 * 1024, connections = 4, firstChunkSize = 64 * 1024, chunkSize = 1024 * 1024).toList()
        assertEquals(100, chunks.first().first)
        assertEquals(100 + 10L * 1024 * 1024, chunks.last().last)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        val sizes = chunks.dropLast(1).map { it.last - it.first + 1 }
        assertEquals(List(4) { 64L * 1024 } + List(4) { 128L * 1024 } + List(4) { 256L * 1024 } + List(4) { 512L * 1024 }, sizes.take(16))
        assertTrue(sizes.drop(16).all { it == 1024L * 1024 })
    }

    @Test
    fun `range headers are read like a server would`() {
        assertEquals(0L..999L, parseByteRange(null, 1000))
        assertEquals(10L..999L, parseByteRange("bytes=10-", 1000))
        assertEquals(10L..20L, parseByteRange("bytes=10-20", 1000))
        assertEquals(10L..999L, parseByteRange("bytes=10-5000", 1000))
        assertEquals(900L..999L, parseByteRange("bytes=-100", 1000))
        assertNull(parseByteRange("bytes=1000-", 1000))
        assertNull(parseByteRange("bytes=20-10", 1000))
        assertNull(parseByteRange("items=0-1", 1000))
    }

    @Test
    fun `format comes from the first bytes`() {
        val mp4 = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".encodeToByteArray()
        assertEquals("video/mp4", MediaFormat.sniff(mp4, "https://x/a", null).mimeType)
        val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        assertEquals("mkv", MediaFormat.sniff(mkv, "https://x/a", "application/octet-stream").extension)
        assertEquals("video/webm", MediaFormat.sniff(ByteArray(4), "https://x/a.webm?t=1", "application/octet-stream").mimeType)
    }

    /**
     * 假网盘: 每个连接一个线程, 认 Range (除非 [ignoreRange]), 每个连接限速 [bytesPerSecond] (0 = 不限), 记下同时最多几个连接.
     * 要求带 [requiredHeader] 时没带的回 403.
     */
    private class RangeOrigin(
        private val content: ByteArray,
        private val bytesPerSecond: Int = 0,
        private val ignoreRange: Boolean = false,
        private val requiredHeader: Pair<String, String>? = null,
        /** 只认这一个 UA (出现两个或不同都不行), 且不能带 JSON 的 Accept, 不然回 412. */
        private val strictUserAgent: String? = null,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val serverSocket = ServerSocket(0, 200, InetAddress.getByName("127.0.0.1"))
        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val requests = AtomicInteger(0)
        val seenHeaderNames: MutableSet<String> = Collections.synchronizedSet(HashSet())

        init {
            thread(name = "RangeOrigin-${serverSocket.localPort}", isDaemon = true) {
                while (!closed.get()) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    thread(isDaemon = true) { serve(socket) }
                }
            }
        }

        private fun serve(socket: Socket) {
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                socket.use {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    reader.readLine() ?: return
                    requests.incrementAndGet()
                    val headers = HashMap<String, String>()
                    val userAgents = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val name = line.substringBefore(':').trim().lowercase()
                        val value = line.substringAfter(':').trim()
                        headers[name] = value
                        if (name == "user-agent") userAgents += value
                    }
                    seenHeaderNames += headers.keys
                    val output = socket.getOutputStream()
                    if (strictUserAgent != null && (userAgents != listOf(strictUserAgent) || headers["accept"]?.contains("json") == true)) {
                        output.write("HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return
                    }
                    if (requiredHeader != null && headers[requiredHeader.first.lowercase()] != requiredHeader.second) {
                        output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return
                    }
                    val range = headers["range"]?.takeIf { !ignoreRange }?.let { parseByteRange(it, content.size.toLong()) }
                    val (start, end) = range?.let { it.first.toInt() to it.last.toInt() } ?: (0 to content.size - 1)
                    val head = buildString {
                        if (range != null) {
                            append("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${content.size}\r\n")
                        } else {
                            append("HTTP/1.1 200 OK\r\n")
                        }
                        append("Content-Type: application/octet-stream\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
                    }
                    output.write(head.toByteArray(StandardCharsets.US_ASCII))
                    var position = start
                    while (position <= end) {
                        val slice = minOf(SLICE, end - position + 1)
                        output.write(content, position, slice)
                        output.flush()
                        position += slice
                        if (bytesPerSecond > 0) Thread.sleep(slice * 1000L / bytesPerSecond)
                    }
                }
            } catch (_: SocketException) {
                // 对方中途断开
            } finally {
                active.decrementAndGet()
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) serverSocket.close()
        }

        private companion object {
            const val SLICE = 8 * 1024
        }
    }
}
