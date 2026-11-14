/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together.mqtt

/**
 * MQTT 3.1.1 里一起看用到的那一小部分报文: 连接 (可带遗嘱)、订阅一个话题、QoS 0 的发布、心跳、断开。
 * 一起看只把公共代理当转发管道, 用不到 QoS 1 / 2、保留消息、会话保持, 所以不引整套客户端库。
 */
internal sealed interface MqttPacket {
    data class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : MqttPacket {
        val accepted: Boolean get() = returnCode == 0
    }

    class Publish(val topic: String, val payload: ByteArray, val retain: Boolean) : MqttPacket

    data class SubAck(val packetId: Int, val returnCodes: List<Int>) : MqttPacket {
        /** 代理拒绝订阅时回 0x80 */
        val granted: Boolean get() = returnCodes.isNotEmpty() && returnCodes.none { it == 0x80 }
    }

    data object PingResp : MqttPacket

    /** 用不到的报文类型, 读掉不处理 */
    data class Other(val type: Int) : MqttPacket
}

/** 连接异常断开时由代理代发的那条消息 */
internal class MqttWill(val topic: String, val payload: ByteArray)

internal object MqttEncoder {
    private const val PROTOCOL_LEVEL_3_1_1 = 4
    private const val FLAG_CLEAN_SESSION = 0x02
    private const val FLAG_WILL = 0x04

    fun connect(clientId: String, keepAliveSeconds: Int, will: MqttWill? = null): ByteArray {
        val body = ByteSink()
        body.string("MQTT")
        body.byte(PROTOCOL_LEVEL_3_1_1)
        body.byte(FLAG_CLEAN_SESSION or (if (will != null) FLAG_WILL else 0))
        body.short(keepAliveSeconds)
        body.string(clientId)
        if (will != null) {
            body.string(will.topic)
            body.short(will.payload.size)
            body.bytes(will.payload)
        }
        return packet(0x10, body)
    }

    /** 以 QoS 0 订阅一个话题 */
    fun subscribe(packetId: Int, topic: String): ByteArray {
        val body = ByteSink()
        body.short(packetId)
        body.string(topic)
        body.byte(0)
        return packet(0x82, body)
    }

    /** QoS 0 发布 */
    fun publish(topic: String, payload: ByteArray, retain: Boolean = false): ByteArray {
        val body = ByteSink()
        body.string(topic)
        body.bytes(payload)
        return packet(0x30 or (if (retain) 0x01 else 0), body)
    }

    fun pingReq(): ByteArray = byteArrayOf(0xC0.toByte(), 0)

    fun disconnect(): ByteArray = byteArrayOf(0xE0.toByte(), 0)

    private fun packet(header: Int, body: ByteSink): ByteArray {
        val out = ByteSink()
        out.byte(header)
        var remaining = body.size
        do {
            val digit = remaining % 128
            remaining /= 128
            out.byte(if (remaining > 0) digit or 0x80 else digit)
        } while (remaining > 0)
        out.bytes(body.toByteArray())
        return out.toByteArray()
    }
}

/**
 * 把连接上收到的字节流切成报文。一块字节里可能有半个报文, 也可能有好几个 (MQTT over WebSocket 不保证一帧一个报文)。
 *
 * 超过 [maxPacketBytes] 的报文整个跳过、不进内存: 话题是公开代理上的, 谁都能往里发东西。
 */
internal class MqttDecoder(private val maxPacketBytes: Int = 64 * 1024) {
    private var buffer = ByteArray(0)
    private var skipping = 0

    fun feed(bytes: ByteArray): List<MqttPacket> {
        var input = bytes
        if (skipping > 0) {
            val drop = minOf(skipping, input.size)
            skipping -= drop
            if (drop == input.size) return emptyList()
            input = input.copyOfRange(drop, input.size)
        }
        buffer = if (buffer.isEmpty()) input else buffer + input

        val packets = ArrayList<MqttPacket>(1)
        var offset = 0
        while (true) {
            val available = buffer.size - offset
            if (available < 2) break
            // 剩余长度: 每字节低 7 位, 最高位表示后面还有, 最多 4 字节
            var length = 0
            var shift = 0
            var index = 1
            var complete = false
            while (index < available && index <= 4) {
                val digit = buffer[offset + index].toInt() and 0xFF
                length = length or ((digit and 0x7F) shl shift)
                shift += 7
                index++
                if (digit and 0x80 == 0) {
                    complete = true
                    break
                }
            }
            if (!complete) {
                if (index > 4) throw MqttProtocolException("剩余长度超过 4 字节")
                break
            }
            if (length > maxPacketBytes) {
                val have = available - index
                val drop = minOf(length, have)
                offset += index + drop
                skipping = length - drop
                continue
            }
            if (available < index + length) break
            val header = buffer[offset].toInt() and 0xFF
            packets += decode(header, buffer, offset + index, length)
            offset += index + length
        }
        buffer = if (offset >= buffer.size) ByteArray(0) else buffer.copyOfRange(offset, buffer.size)
        return packets
    }

    private fun decode(header: Int, data: ByteArray, start: Int, length: Int): MqttPacket {
        val end = start + length
        return when (val type = header shr 4) {
            2 -> {
                if (length < 2) throw MqttProtocolException("CONNACK 太短")
                MqttPacket.ConnAck(
                    sessionPresent = data[start].toInt() and 0x01 != 0,
                    returnCode = data[start + 1].toInt() and 0xFF,
                )
            }

            3 -> {
                if (length < 2) throw MqttProtocolException("PUBLISH 太短")
                val topicLength = readShort(data, start)
                var cursor = start + 2 + topicLength
                if (cursor > end) throw MqttProtocolException("PUBLISH 话题越界")
                val topic = data.decodeToString(start + 2, cursor)
                // 订阅的是 QoS 0, 代理不该发更高的; 真发了也把报文标识跳过去, 当 QoS 0 收
                val qos = (header shr 1) and 0x03
                if (qos > 0) cursor += 2
                if (cursor > end) throw MqttProtocolException("PUBLISH 标识越界")
                MqttPacket.Publish(topic, data.copyOfRange(cursor, end), retain = header and 0x01 != 0)
            }

            9 -> {
                if (length < 2) throw MqttProtocolException("SUBACK 太短")
                MqttPacket.SubAck(
                    packetId = readShort(data, start),
                    returnCodes = (start + 2 until end).map { data[it].toInt() and 0xFF },
                )
            }

            13 -> MqttPacket.PingResp
            else -> MqttPacket.Other(type)
        }
    }

    private fun readShort(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
}

internal class MqttProtocolException(message: String) : Exception(message)

private class ByteSink {
    private var data = ByteArray(64)
    var size = 0
        private set

    fun byte(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
    }

    fun short(value: Int) {
        require(value in 0..0xFFFF) { "超出两字节范围: $value" }
        byte(value shr 8)
        byte(value and 0xFF)
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(data, size)
        size += value.size
    }

    fun string(value: String) {
        val encoded = value.encodeToByteArray()
        short(encoded.size)
        bytes(encoded)
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }
}
