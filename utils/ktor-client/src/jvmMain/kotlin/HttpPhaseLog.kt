/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 共用客户端每个请求的各段耗时 (开播诊断用): 请求结束时记一行 DNS、建连、TLS、拿到连接、发完请求、到响应头、读完正文各在第几毫秒,
 * 复用连接的没有 DNS 与建连. [enabled] 为 false 时不挂监听 (由应用接上调试开关).
 */
object HttpPhaseLog : EventListener.Factory {
    private val logger = logger("HttpPhaseLog")

    /** 现在记不记; 应用启动时接上调试开关 */
    @Volatile
    var enabled: () -> Boolean = { false }

    override fun create(call: Call): EventListener = if (!enabled()) EventListener.NONE else object : EventListener() {
        private val start = System.nanoTime()
        private fun now() = (System.nanoTime() - start) / 1_000_000
        private var dnsStart = -1L
        private var dns = -1L
        private var connectStart = -1L
        private var tlsStart = -1L
        private var tls = -1L
        private var connect = -1L
        private var acquired = -1L
        private var sent = -1L
        private var ttfb = -1L
        private var bodyEnd = -1L
        private var protocol = "?"

        override fun dnsStart(call: Call, domainName: String) { dnsStart = now() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dns = now() - dnsStart }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connectStart = now() }
        override fun secureConnectStart(call: Call) { tlsStart = now() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) { tls = now() - tlsStart }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connect = now() - connectStart }
        override fun connectionAcquired(call: Call, connection: Connection) { acquired = now(); protocol = connection.protocol().toString() }
        override fun requestHeadersEnd(call: Call, request: okhttp3.Request) { sent = now() }
        override fun responseHeadersStart(call: Call) { ttfb = now() }
        override fun responseBodyEnd(call: Call, byteCount: Long) { bodyEnd = now() }
        override fun callEnd(call: Call) = log("done")
        override fun callFailed(call: Call, ioe: IOException) = log("failed ${ioe::class.simpleName}")

        private fun log(outcome: String) {
            val url = call.request().url
            logger.info {
                "HTTP ${url.host}${url.encodedPath.take(32)} $outcome $protocol reused=${connectStart < 0} " +
                    "dns@${dnsStart}+$dns connect@${connectStart}+$connect tls+$tls acquired@$acquired sent@$sent ttfb@$ttfb body@$bodyEnd total=${now()}"
            }
        }
    }
}
