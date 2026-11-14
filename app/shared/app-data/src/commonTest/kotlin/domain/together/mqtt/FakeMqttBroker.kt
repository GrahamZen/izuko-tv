/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together.mqtt

import kotlinx.coroutines.channels.Channel
import kotlinx.io.IOException

/**
 * 内存里的假代理, 行为照 MQTT 3.1.1: 回 CONNACK / SUBACK / PINGRESP, 把发布转给订阅了那个话题的全部连接 (发的人自己也收到),
 * 连接没发 DISCONNECT 就断时替它发遗嘱。客户端发来的报文在这里另写了一份解析, 不借被测的编码器。
 */
internal class FakeMqttBroker {
    /** `false` 时连不上 */
    var accepting = true
    var connAckReturnCode = 0
    var subAckReturnCode = 0
    var answerPings = true

    /** 握手一完成代理就把连接关掉 */
    var closeAfterHandshake = false

    var connectAttempts = 0
        private set

    val sessions = mutableListOf<Session>()
    val liveSessions: List<Session> get() = sessions.filter { !it.closed }

    /** 经过代理的全部发布 (含遗嘱), 按先后 */
    val published = mutableListOf<Pair<String, ByteArray>>()

    val connector = MqttConnector { _ ->
        connectAttempts++
        if (!accepting) throw IOException("connection refused")
        Session().also { sessions += it }
    }

    /** 像断网那样掐掉全部连接: 客户端读到错误, 代理替它们发遗嘱 */
    fun killAll() {
        liveSessions.forEach { it.kill() }
    }

    /** 不经客户端, 直接往话题里发一条 */
    fun inject(topic: String, payload: ByteArray) = route(topic, payload)

    private fun route(topic: String, payload: ByteArray) {
        published += topic to payload
        val packet = publishPacket(topic, payload)
        for (session in liveSessions) {
            if (topic in session.subscriptions) session.toClient.trySend(packet)
        }
    }

    inner class Session : MqttTransport {
        val toClient = Channel<ByteArray>(Channel.UNLIMITED)
        val subscriptions = mutableSetOf<String>()
        var clientId: String? = null
        var keepAliveSeconds = 0
        var will: Pair<String, ByteArray>? = null
        var gotDisconnect = false
        var closed = false
        var pingsReceived = 0

        override suspend fun send(bytes: ByteArray) {
            if (closed) throw IOException("connection closed")
            val type = (bytes[0].toInt() and 0xFF) shr 4
            var index = 1
            var length = 0
            var shift = 0
            while (true) {
                val digit = bytes[index++].toInt() and 0xFF
                length = length or ((digit and 0x7F) shl shift)
                shift += 7
                if (digit and 0x80 == 0) break
            }
            check(bytes.size == index + length) { "客户端一次发一个完整报文" }
            val body = bytes.copyOfRange(index, bytes.size)
            when (type) {
                1 -> onConnect(body)
                8 -> {
                    val topicLength = short(body, 2)
                    subscriptions += body.decodeToString(4, 4 + topicLength)
                    toClient.trySend(byteArrayOf(0x90.toByte(), 3, body[0], body[1], subAckReturnCode.toByte()))
                    if (closeAfterHandshake) closeByBroker()
                }

                3 -> {
                    val topicLength = short(body, 0)
                    route(body.decodeToString(2, 2 + topicLength), body.copyOfRange(2 + topicLength, body.size))
                }

                12 -> {
                    pingsReceived++
                    if (answerPings) toClient.trySend(byteArrayOf(0xD0.toByte(), 0))
                }

                14 -> gotDisconnect = true
                else -> error("假代理不认识的报文类型 $type")
            }
        }

        private fun onConnect(body: ByteArray) {
            check(body.decodeToString(2, 6) == "MQTT" && body[6].toInt() == 4) { "不是 MQTT 3.1.1 的 CONNECT" }
            val flags = body[7].toInt()
            keepAliveSeconds = short(body, 8)
            var cursor = 10
            val idLength = short(body, cursor)
            clientId = body.decodeToString(cursor + 2, cursor + 2 + idLength)
            cursor += 2 + idLength
            if (flags and 0x04 != 0) {
                val topicLength = short(body, cursor)
                val topic = body.decodeToString(cursor + 2, cursor + 2 + topicLength)
                cursor += 2 + topicLength
                val payloadLength = short(body, cursor)
                will = topic to body.copyOfRange(cursor + 2, cursor + 2 + payloadLength)
            }
            toClient.trySend(byteArrayOf(0x20, 2, 0, connAckReturnCode.toByte()))
        }

        override suspend fun receive(): ByteArray? {
            val result = toClient.receiveCatching()
            result.exceptionOrNull()?.let { throw it }
            return result.getOrNull()
        }

        /** 客户端关连接: 之前没发 DISCONNECT 就算异常断开 */
        override suspend fun close() {
            if (closed) return
            closed = true
            toClient.close()
            if (!gotDisconnect) publishWill()
        }

        /** 断网: 客户端读到错误 */
        fun kill() {
            if (closed) return
            closed = true
            toClient.close(IOException("connection reset"))
            publishWill()
        }

        private fun closeByBroker() {
            closed = true
            toClient.close()
            publishWill()
        }

        private fun publishWill() {
            will?.let { (topic, payload) -> route(topic, payload) }
        }
    }

    private fun short(data: ByteArray, at: Int): Int = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun publishPacket(topic: String, payload: ByteArray): ByteArray {
        val topicBytes = topic.encodeToByteArray()
        var remaining = 2 + topicBytes.size + payload.size
        val lengthBytes = ArrayList<Byte>()
        do {
            val digit = remaining % 128
            remaining /= 128
            lengthBytes += (if (remaining > 0) digit or 0x80 else digit).toByte()
        } while (remaining > 0)
        return byteArrayOf(0x30) + lengthBytes.toByteArray() +
                byteArrayOf((topicBytes.size shr 8).toByte(), topicBytes.size.toByte()) + topicBytes + payload
    }
}
