/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together.mqtt

import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.UnsafeScopedHttpClientApi
import kotlin.time.Duration.Companion.seconds

/** 连到一家代理的一条字节管道 */
internal interface MqttTransport {
    suspend fun send(bytes: ByteArray)

    /** 下一块字节 (不按报文切分); 对端正常关闭时为 `null`, 连接出错时抛异常 */
    suspend fun receive(): ByteArray?

    suspend fun close()
}

internal fun interface MqttConnector {
    /** 连到 [url]; 连不上时抛异常 */
    suspend fun connect(url: String): MqttTransport
}

/**
 * MQTT over WebSocket (子协议 `mqtt`, 二进制帧)。走应用的 HTTP 客户端, 所以跟着应用的代理设置;
 * 每次连接现借一个客户端, 代理设置变了之后重连就用上新的。
 *
 * 连接超时比客户端默认的短 ([CONNECT_TIMEOUT_MILLIS]): 代理的域名解析出好几个地址, IPv6 的排在前面, 而有的网络上
 * 它们的 IPv6 地址连不通; 连不通的地址要等满连接超时才换下一个, 照客户端默认的 30 秒一个, 一家代理要一两分钟才连上。
 *
 * @param client 要装了 WebSocket 插件 (见 `TogetherWebSocketsFeature`)
 */
internal class WebSocketMqttConnector(
    private val client: () -> ScopedHttpClient,
) : MqttConnector {
    @OptIn(UnsafeScopedHttpClientApi::class)
    override suspend fun connect(url: String): MqttTransport {
        val scoped = client()
        val ticket = scoped.borrow()
        try {
            val session = ticket.client.webSocketSession(url) {
                header(HttpHeaders.SecWebSocketProtocol, "mqtt")
                timeout { connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS }
            }
            return WebSocketMqttTransport(session) { scoped.returnClient(ticket) }
        } catch (e: Throwable) {
            scoped.returnClient(ticket)
            throw e
        }
    }

    private companion object {
        /** 同播放器与下载器: 连不通的地址早点换下一个 */
        const val CONNECT_TIMEOUT_MILLIS = 3_000L
    }
}

private class WebSocketMqttTransport(
    private val session: DefaultClientWebSocketSession,
    private val release: () -> Unit,
) : MqttTransport {
    private var released = false

    override suspend fun send(bytes: ByteArray) {
        session.send(Frame.Binary(true, bytes))
    }

    override suspend fun receive(): ByteArray? {
        while (true) {
            val result = session.incoming.receiveCatching()
            val frame = result.getOrNull()
            if (frame == null) {
                result.exceptionOrNull()?.let { throw it }
                return null
            }
            if (frame is Frame.Binary) return frame.data
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            withTimeoutOrNull(CLOSE_TIMEOUT) { runCatching { session.close() } }
            if (!released) {
                released = true
                release()
            }
        }
    }

    private companion object {
        val CLOSE_TIMEOUT = 2.seconds
    }
}
