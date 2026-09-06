/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 裸 `ServerSocket` 实现, 只处理一个请求 —— 用 ktor-server 的话要给生产包多背一整套服务端,
 * 而这里要做的只是"读一行请求行, 回一页 HTML"。
 */
actual class OAuthLoopbackServer actual constructor(private val port: Int) {
    private val logger = logger<OAuthLoopbackServer>()

    @Volatile
    private var socket: ServerSocket? = null

    actual suspend fun start(onCallback: suspend (url: String) -> Unit): Boolean {
        val server = try {
            // 只绑回环: 不监听外部网络, 别的设备连不上
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            logger.warn(e) { "bgm-direct: oauth 回环监听起不来 (端口 $port 被占?), 退回自定义 scheme" }
            return false
        }
        socket = server
        logger.info { "bgm-direct: oauth 回环监听已启动 127.0.0.1:$port" }

        withContext(Dispatchers.IO) {
            try {
                server.accept().use { client ->
                    // 请求行形如 `GET /callback?code=…&state=… HTTP/1.1`
                    val requestLine = BufferedReader(InputStreamReader(client.getInputStream()))
                        .readLine().orEmpty()
                    val path = requestLine.split(' ').getOrNull(1).orEmpty()
                    client.getOutputStream().apply {
                        write(RESPONSE.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    if (path.isNotEmpty()) {
                        // 只记路径, 不记 query: 那里面是授权码
                        logger.info { "bgm-direct: oauth 回环收到回调 ${path.substringBefore('?')}" }
                        onCallback("http://127.0.0.1:$port$path")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // stop() 关掉 socket 时 accept 会抛, 属正常收尾
                logger.info { "bgm-direct: oauth 回环监听结束 (${e::class.simpleName})" }
            } finally {
                stop()
            }
        }
        return true
    }

    actual fun stop() {
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    private companion object {
        /** 浏览器停在这一页; 用户自己切回 App (电视上没有别的办法把它带回前台). */
        private val BODY = """
            <!doctype html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>授权完成</title></head>
            <body style="font-family:system-ui,sans-serif;text-align:center;padding:2em">
            <h2>授权完成</h2><p>可以回到 Animeko 了。</p>
            </body></html>
        """.trimIndent()

        val RESPONSE = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${BODY.toByteArray(Charsets.UTF_8).size}\r\n")
            append("Connection: close\r\n\r\n")
            append(BODY)
        }
    }
}
