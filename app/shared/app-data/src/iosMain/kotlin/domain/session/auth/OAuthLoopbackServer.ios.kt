/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.foundation.ForegroundListenerRefresh
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration.Companion.seconds

/**
 * ktor-network 实现 (同 HLS 本地代理), 配合系统授权弹窗 ([SystemOAuthSession]) 用: 弹窗开着时应用在前台,
 * 弹窗里的 Safari 跳到 `127.0.0.1:port/callback` 时请求落进这里. 只绑回环, 别的设备连不上.
 *
 * - 浏览器可能先建好空连接再发请求、也可能顺带要 favicon: 每个连接各自处理, 只认 `/callback` 那一个.
 * - 应用退到后台时系统会收回监听 socket: 回到前台在原端口重新监听 (见 [ForegroundListenerRefresh]).
 */
actual class OAuthLoopbackServer actual constructor(private val port: Int) {
    private val logger = logger<OAuthLoopbackServer>()

    private var selector: SelectorManager? = null
    private var server: ServerSocket? = null
    private var stopped = false

    actual suspend fun start(onCallback: suspend (url: String) -> Unit): Boolean {
        val selector = SelectorManager(Dispatchers.IO_)
        server = try {
            bind(selector)
        } catch (e: CancellationException) {
            selector.close()
            throw e
        } catch (e: Exception) {
            selector.close()
            logger.warn(e) { "bgm-direct: oauth 回环监听起不来 (端口 $port 被占?)" }
            return false
        }
        this.selector = selector
        val refresh = ForegroundListenerRefresh.register { server?.let { runCatching { it.close() } } }
        logger.info { "bgm-direct: oauth 回环监听已启动 127.0.0.1:$port" }
        try {
            val callbackUrl = coroutineScope {
                val received = CompletableDeferred<String>()
                val acceptor = launch {
                    try {
                        acceptLoop(selector) { client -> launch { serve(client, received) } }
                    } finally {
                        if (!received.isCompleted) received.cancel()
                    }
                }
                received.await().also { acceptor.cancel() }
            }
            onCallback(callbackUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "bgm-direct: oauth 回环监听结束 (${e::class.simpleName})" }
        } finally {
            refresh.close()
            stop()
        }
        return true
    }

    actual fun stop() {
        stopped = true
        server?.let { runCatching { it.close() } }
        server = null
        selector?.let { runCatching { it.close() } }
        selector = null
    }

    private suspend fun bind(selector: SelectorManager): ServerSocket =
        aSocket(selector).tcp().bind("127.0.0.1", port) { reuseAddress = true }

    /** 一直接连接, 交给 [onClient]; 监听被收回或关掉 (回到前台时) 就在原端口重新监听. [stop] 之后结束. */
    private suspend fun acceptLoop(selector: SelectorManager, onClient: (Socket) -> Unit) = coroutineScope {
        while (isActive && !stopped) {
            val listener = server
            if (listener == null) {
                relisten(selector)
                continue
            }
            val client = try {
                listener.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stopped) break
                relisten(selector)
                continue
            }
            onClient(client)
        }
    }

    private suspend fun relisten(selector: SelectorManager) {
        server?.let { runCatching { it.close() } }
        server = try {
            bind(selector)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "bgm-direct: oauth 回环在 $port 上重新监听失败, 稍后再试" }
            delay(RELISTEN_RETRY)
            null
        }
    }

    /** 读请求行, 回一页; 是 `/callback` 就把完整地址交给 [received]. */
    private suspend fun serve(client: Socket, received: CompletableDeferred<String>) {
        try {
            val input = client.openReadChannel()
            val output = client.openWriteChannel(autoFlush = false)
            try {
                // 请求行形如 `GET /callback?code=…&state=… HTTP/1.1`; 只建连接不发请求的不一直等
                val requestLine = withTimeoutOrNull(REQUEST_TIMEOUT) { input.readUTF8Line(MAX_LINE_LENGTH) } ?: return
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                val isCallback = path.startsWith(CALLBACK_PATH)
                output.writeFully(if (isCallback) RESPONSE_OK else RESPONSE_NOT_FOUND)
                output.flush()
                if (isCallback) {
                    // 只记路径, 不记 query: 那里面是授权码
                    logger.info { "bgm-direct: oauth 回环收到回调 ${path.substringBefore('?')}" }
                    received.complete("http://127.0.0.1:$port$path")
                }
            } finally {
                runCatching { output.flushAndClose() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "bgm-direct: oauth 回环连接出错 (${e::class.simpleName})" }
        } finally {
            runCatching { client.close() }
        }
    }

    private companion object {
        /** [BangumiOAuthConstants.CALLBACK_URL] 的路径. */
        private const val CALLBACK_PATH = "/callback"
        private val REQUEST_TIMEOUT = 10.seconds
        private val RELISTEN_RETRY = 1.seconds
        private const val MAX_LINE_LENGTH = 16 * 1024

        /** 弹窗随即由 [SystemOAuthSession.close] 收起, 这一页多半只闪一下. */
        private val BODY = """
            <!doctype html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>授权完成</title></head>
            <body style="font-family:-apple-system,system-ui,sans-serif;text-align:center;padding:2em">
            <h2>授权完成</h2><p>正在回到应用…</p>
            </body></html>
        """.trimIndent()

        private val RESPONSE_OK = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${BODY.encodeToByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(BODY)
        }.encodeToByteArray()

        private val RESPONSE_NOT_FOUND =
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray()
    }
}
