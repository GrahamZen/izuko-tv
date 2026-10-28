/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 播放请求的各段耗时 (开播时看播放器在等哪一次往返): 每个请求结束时记一行 Range、DNS、建连 (含 TLS)、到响应头、整个请求各花了多少毫秒,
 * 复用连接的没有 DNS 与建连. 只在开了调试开关 [DevSwitches.playDiagnostics] 时记.
 */
internal object PlaybackRequestLog : EventListener.Factory {
    private val logger = logger("PlaybackRequestLog")

    override fun create(call: Call): EventListener = if (!DevSwitches.playDiagnostics.value) EventListener.NONE else object : EventListener() {
        private val start = System.nanoTime()
        private var dns = -1L
        private var connect = -1L
        private var headers = -1L
        private var connectStart = 0L
        private var dnsStart = 0L

        private fun sinceStart() = (System.nanoTime() - start) / 1_000_000

        override fun dnsStart(call: Call, domainName: String) {
            dnsStart = System.nanoTime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dns = (System.nanoTime() - dnsStart) / 1_000_000
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStart = System.nanoTime()
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connect = (System.nanoTime() - connectStart) / 1_000_000
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            headers = sinceStart()
        }

        override fun callEnd(call: Call) = log("done")

        override fun callFailed(call: Call, ioe: IOException) = log("failed ${ioe::class.simpleName}")

        override fun canceled(call: Call) = log("canceled")

        private fun log(outcome: String) {
            val request = call.request()
            logger.info {
                "Playback ${request.method} ${request.url.host} range=${request.header("Range") ?: "-"} $outcome: " +
                        "dns=${dns}ms connect=${connect}ms headers=${headers}ms total=${sinceStart()}ms"
            }
        }
    }
}
