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
import me.him188.ani.app.domain.settings.NoProxyProvider
import org.openani.mediamp.source.UriMediaData
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 本机 HLS 代理向源站取东西被拒时, 把源站的状态原样回给播放器 ([upstreamErrorResponseHeader]):
 * 播放器靠它认出地址过期 (重新取一次地址), 而不是当成资源坏了去换源.
 */
class HlsProxyUpstreamErrorTest {
    @Test
    fun `an upstream rejection of a variant playlist reaches the player as is`() = runTest {
        val server = Site()
        val provider = DefaultHttpClientProvider(NoProxyProvider, backgroundScope)
        // 探测时限按真实时间计, 不能留在 runTest 的虚拟时间上
        val result = withContext(Dispatchers.Default) {
            PlatformHlsPlaybackPreparer(provider).prepare(
                UriMediaData("${server.baseUrl}/index.m3u8", emptyMap()),
                HlsPlaybackOptions(filterSegments = true),
            )
        }
        try {
            val localVariant = URI(result.data.uri).toURL().readText().lineSequence()
                .first { it.isNotBlank() && !it.startsWith("#") }
            // 暂停了很久: 子播放列表的签名过期, 源站回 403
            server.rejected.set(true)

            val connection = URI(localVariant).toURL().openConnection() as HttpURLConnection
            assertEquals(403, connection.responseCode)
        } finally {
            result.session?.close()
            provider.forceReleaseAll()
            server.close()
        }
    }

    /** 主播放列表一直给; 子播放列表在 [rejected] 之后回 403 */
    private class Site : AutoCloseable {
        val rejected = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${serverSocket.localPort}"

        private val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nmedia/low.m3u8?sign=1\n"
        private val variant = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nseg0.ts\n#EXT-X-ENDLIST\n"

        init {
            thread(name = "HlsProxyUpstreamErrorTest-${serverSocket.localPort}", isDaemon = true) {
                while (!closed.get()) {
                    try {
                        serverSocket.accept().use { socket ->
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                            val requestLine = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val path = requestLine.substringAfter(" ").substringBefore(" ")
                            val response = when {
                                path.startsWith("/index.m3u8") -> ok(master)
                                rejected.get() -> "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                else -> ok(variant)
                            }
                            socket.getOutputStream().use { it.write(response.toByteArray(StandardCharsets.UTF_8)) }
                        }
                    } catch (_: SocketException) {
                        // 客户端中途断开只影响这一个连接; 关闭后由循环条件退出
                    }
                }
            }
        }

        private fun ok(body: String) =
            "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

        override fun close() {
            if (closed.compareAndSet(false, true)) serverSocket.close()
        }
    }
}
