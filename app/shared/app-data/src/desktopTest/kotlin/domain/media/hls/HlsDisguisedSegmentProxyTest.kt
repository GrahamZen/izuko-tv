/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.hls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.media.hls.HlsDisguisedSegmentsTest.Companion.PNG_1X1
import me.him188.ani.app.domain.media.hls.HlsDisguisedSegmentsTest.Companion.tsPackets
import me.him188.ani.app.domain.settings.NoProxyProvider
import org.openani.mediamp.source.UriMediaData
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分片伪装成图片的播放列表经本地代理时, 播放器拿到的分片去掉了前面的图片; 普通分片的播放列表不经代理;
 * 要转发请求头时播放列表与分片都经代理, 由代理带着请求头去取.
 */
class HlsDisguisedSegmentProxyTest {
    private val ts = tsPackets(16)

    @Test
    fun `serves disguised segments without the image prefix`() = runTest {
        val origin = BinaryOrigin(
            mapOf(
                "/v/index.m3u8" to playlist("000.png", "001.png").encodeToByteArray(),
                "/v/000.png" to PNG_1X1 + ts,
                "/v/001.png" to PNG_1X1 + ts,
            ),
        )
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        val result = withContext(Dispatchers.Default) {
            PlatformHlsPlaybackPreparer(provider).prepare(
                UriMediaData("${origin.baseUrl}/v/index.m3u8"),
                HlsPlaybackOptions(unwrapDisguisedSegments = true),
            )
        }
        try {
            assertIs<HlsPlaybackProxySession>(result.session)
            val local = URI(result.data.uri).toURL().readText()
            val segments = local.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
            assertEquals(2, segments.size)
            for (segment in segments) {
                assertContains(segment, "http://127.0.0.1:")
                assertContentEquals(ts, URI(segment).toURL().readBytes())
            }
        } finally {
            result.session?.close()
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `plain ts playlist is played from the origin directly`() = runTest {
        val origin = BinaryOrigin(mapOf("/v/index.m3u8" to playlist("000.ts", "001.ts").encodeToByteArray()))
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        val data = UriMediaData("${origin.baseUrl}/v/index.m3u8")
        val result = withContext(Dispatchers.Default) {
            PlatformHlsPlaybackPreparer(provider).prepare(data, HlsPlaybackOptions(unwrapDisguisedSegments = true))
        }
        try {
            assertNull(result.session)
            assertEquals(data.uri, result.data.uri)
        } finally {
            result.session?.close()
            provider.forceReleaseAll()
            origin.close()
        }
    }

    @Test
    fun `forwards the request headers for the playlist and every segment`() = runTest {
        val origin = BinaryOrigin(
            mapOf(
                "/v/play.php?f=index.m3u8" to playlist("000.ts", "001.ts").encodeToByteArray(),
                "/v/000.ts" to ts,
                "/v/001.ts" to ts,
            ),
            requiredHeader = "Referer" to "https://example.org/",
        )
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        val result = withContext(Dispatchers.Default) {
            PlatformHlsPlaybackPreparer(provider).prepare(
                UriMediaData("${origin.baseUrl}/v/play.php?f=index.m3u8", mapOf("Referer" to "https://example.org/")),
                HlsPlaybackOptions(forwardRequestHeaders = true),
            )
        }
        try {
            assertIs<HlsPlaybackProxySession>(result.session)
            assertTrue(result.data.uri.endsWith(".m3u8"))
            // 播放器这边不带请求头去取, 经代理照样拿得到
            val local = URI(result.data.uri).toURL().readText()
            val segments = local.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
            assertEquals(2, segments.size)
            for (segment in segments) {
                assertContains(segment, "http://127.0.0.1:")
                assertContentEquals(ts, URI(segment).toURL().readBytes())
            }
        } finally {
            result.session?.close()
            provider.forceReleaseAll()
            origin.close()
        }
    }

    private fun playlist(vararg segments: String) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n")
        for (segment in segments) append("#EXTINF:10.0,\n").append(segment).append('\n')
        append("#EXT-X-ENDLIST\n")
    }

    /** 按路径回固定字节的源站; 不认识的路径回 404, 给了 [requiredHeader] 时没带这个请求头 (名字不分大小写) 回 403. */
    private class BinaryOrigin(
        private val contentByPath: Map<String, ByteArray>,
        private val requiredHeader: Pair<String, String>? = null,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

        private val thread = thread(name = "HlsDisguisedSegmentProxyTest-${serverSocket.localPort}", isDaemon = true) {
            while (!closed.get()) {
                try {
                    serverSocket.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                        val requestLine = reader.readLine() ?: return@use
                        val headers = mutableListOf<Pair<String, String>>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers += line.substringBefore(':').trim() to line.substringAfter(':').trim()
                        }
                        val path = requestLine.substringAfter(" ").substringBefore(" ")
                        val allowed = requiredHeader == null ||
                            headers.any { (name, value) -> name.equals(requiredHeader.first, ignoreCase = true) && value == requiredHeader.second }
                        val body = contentByPath[path]?.takeIf { allowed }
                        socket.getOutputStream().use { output ->
                            val status = when {
                                !allowed -> "403 Forbidden"
                                body != null -> "200 OK"
                                else -> "404 Not Found"
                            }
                            val bytes = body ?: ByteArray(0)
                            val header = "HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray(StandardCharsets.US_ASCII))
                            output.write(bytes)
                            output.flush()
                        }
                    }
                } catch (_: SocketException) {
                    // 客户端中途断开只影响这一个连接; 关闭后由循环条件退出
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) serverSocket.close()
        }

        @Suppress("unused")
        private fun keepThreadReachable(): Thread = thread
    }
}
