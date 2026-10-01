/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 连得上却不回 TLS 握手的节点: 按握手超时放弃, 不等平时的读超时.
 */
class HandshakeTimeoutSocketFactoryTest {
    @Test
    fun `a node that never answers the handshake times out at the handshake timeout`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching { server.accept().use { Thread.sleep(10_000) } }
            }
            val factory = HandshakeTimeoutSocketFactory(SSLContext.getDefault().socketFactory, timeoutMillis = 300)
            // 同 OkHttp: 先建好 TCP 连接、设上平时的读超时, 再交给 TLS 套接字工厂
            val raw = Socket(server.inetAddress, server.localPort).apply { soTimeout = 10_000 }
            val tls = factory.createSocket(raw, "localhost", server.localPort, true) as SSLSocket
            tls.use {
                val startedAt = System.nanoTime()
                assertFailsWith<SocketTimeoutException> { it.startHandshake() }
                val took = (System.nanoTime() - startedAt) / 1_000_000
                assertTrue(took < 3_000, "took ${took}ms")
            }
        }
    }
}
