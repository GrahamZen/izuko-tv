/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together.mqtt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 报文的字节形状对着 MQTT 3.1.1 规范写死, 不拿编码器自己的输出当标准 */
class MqttPacketsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun String.ascii() = encodeToByteArray()

    @Test
    fun `connect without will`() {
        assertContentEquals(
            bytes(0x10, 15, 0, 4) + "MQTT".ascii() + bytes(4, 0x02, 0, 30, 0, 3) + "abc".ascii(),
            MqttEncoder.connect("abc", keepAliveSeconds = 30),
        )
    }

    @Test
    fun `connect with will sets the will flag and appends topic and payload`() {
        assertContentEquals(
            bytes(0x10, 23, 0, 4) + "MQTT".ascii() + bytes(4, 0x06, 0, 30, 0, 3) + "abc".ascii() +
                    bytes(0, 2) + "t1".ascii() + bytes(0, 2, 0xAA, 0xBB),
            MqttEncoder.connect("abc", keepAliveSeconds = 30, will = MqttWill("t1", bytes(0xAA, 0xBB))),
        )
    }

    @Test
    fun `subscribe at qos 0`() {
        assertContentEquals(
            bytes(0x82, 8, 0, 7, 0, 3) + "a/b".ascii() + bytes(0),
            MqttEncoder.subscribe(packetId = 7, topic = "a/b"),
        )
    }

    @Test
    fun `publish at qos 0`() {
        assertContentEquals(
            bytes(0x30, 7, 0, 3) + "a/b".ascii() + bytes(1, 2),
            MqttEncoder.publish("a/b", bytes(1, 2)),
        )
        assertEquals(0x31, MqttEncoder.publish("a/b", bytes(1, 2), retain = true)[0].toInt())
    }

    @Test
    fun `remaining length above 127 takes two bytes`() {
        // 2 + 1 + 200 = 203 = 0xCB 0x01
        val packet = MqttEncoder.publish("t", ByteArray(200))
        assertContentEquals(bytes(0x30, 0xCB, 0x01), packet.copyOf(3))
        assertEquals(3 + 203, packet.size)
    }

    @Test
    fun `ping and disconnect are two bytes`() {
        assertContentEquals(bytes(0xC0, 0), MqttEncoder.pingReq())
        assertContentEquals(bytes(0xE0, 0), MqttEncoder.disconnect())
    }

    @Test
    fun `decodes connack`() {
        val accepted = assertIs<MqttPacket.ConnAck>(MqttDecoder().feed(bytes(0x20, 2, 0, 0)).single())
        assertTrue(accepted.accepted)
        val refused = assertIs<MqttPacket.ConnAck>(MqttDecoder().feed(bytes(0x20, 2, 0, 5)).single())
        assertFalse(refused.accepted)
        assertEquals(5, refused.returnCode)
    }

    @Test
    fun `decodes suback and tells a refused subscription`() {
        val granted = assertIs<MqttPacket.SubAck>(MqttDecoder().feed(bytes(0x90, 3, 0, 7, 0)).single())
        assertEquals(7, granted.packetId)
        assertTrue(granted.granted)
        val refused = assertIs<MqttPacket.SubAck>(MqttDecoder().feed(bytes(0x90, 3, 0, 7, 0x80)).single())
        assertFalse(refused.granted)
    }

    @Test
    fun `decodes publish and pingresp`() {
        val packets = MqttDecoder().feed(bytes(0x30, 7, 0, 3) + "a/b".ascii() + bytes(1, 2) + bytes(0xD0, 0))
        assertEquals(2, packets.size)
        val publish = assertIs<MqttPacket.Publish>(packets[0])
        assertEquals("a/b", publish.topic)
        assertContentEquals(bytes(1, 2), publish.payload)
        assertFalse(publish.retain)
        assertIs<MqttPacket.PingResp>(packets[1])
    }

    @Test
    fun `publish above qos 0 skips the packet id`() {
        // QoS 1: 话题后面多两个字节的报文标识
        val publish = assertIs<MqttPacket.Publish>(
            MqttDecoder().feed(bytes(0x32, 9, 0, 3) + "a/b".ascii() + bytes(0, 9, 1, 2)).single(),
        )
        assertContentEquals(bytes(1, 2), publish.payload)
    }

    @Test
    fun `a packet split across chunks is decoded once it is complete`() {
        val whole = MqttEncoder.publish("room", ByteArray(300) { it.toByte() })
        val decoder = MqttDecoder()
        val packets = whole.flatMap { decoder.feed(byteArrayOf(it)) }
        val publish = assertIs<MqttPacket.Publish>(packets.single())
        assertEquals("room", publish.topic)
        assertContentEquals(ByteArray(300) { it.toByte() }, publish.payload)
    }

    @Test
    fun `an oversized packet is skipped and the next one still decodes`() {
        val decoder = MqttDecoder(maxPacketBytes = 100)
        val big = MqttEncoder.publish("room", ByteArray(5000))
        val small = MqttEncoder.publish("room", bytes(7))
        // 大报文分三块到, 最后一块后面紧跟着小报文
        val stream = big + small
        val packets = decoder.feed(stream.copyOfRange(0, 50)) +
                decoder.feed(stream.copyOfRange(50, 3000)) +
                decoder.feed(stream.copyOfRange(3000, stream.size))
        assertContentEquals(bytes(7), assertIs<MqttPacket.Publish>(packets.single()).payload)
    }

    @Test
    fun `unknown packet types are passed over`() {
        val packets = MqttDecoder().feed(bytes(0x40, 2, 0, 1) + bytes(0xD0, 0))
        assertEquals(MqttPacket.Other(4), packets[0])
        assertIs<MqttPacket.PingResp>(packets[1])
    }

    @Test
    fun `a remaining length longer than four bytes is a protocol error`() {
        assertFailsWith<MqttProtocolException> { MqttDecoder().feed(bytes(0x30, 0xFF, 0xFF, 0xFF, 0xFF, 0x01)) }
    }
}
