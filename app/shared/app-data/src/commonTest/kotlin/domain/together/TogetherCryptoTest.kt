/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TogetherCryptoTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `pbkdf2 matches the published test vectors`() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(platformPbkdf2HmacSha256("password", "salt".encodeToByteArray(), iterations = 1, lengthBytes = 32)),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(platformPbkdf2HmacSha256("password", "salt".encodeToByteArray(), iterations = 4096, lengthBytes = 32)),
        )
    }

    @Test
    fun `a room number and password read the same however they are typed`() {
        assertEquals(ROOM, TogetherRoomId.of("4827 1593", "123 456"))
        assertEquals(ROOM, TogetherRoomId.of("4827-1593", " 123456 "))
        assertNull(TogetherRoomId.of("4827159", "123456"))
        assertNull(TogetherRoomId.of("48271593", "12345"))
        assertNull(TogetherRoomId.of("4827159a", "123456"))
        assertNull(TogetherRoomId.of("", ""))
    }

    @Test
    fun `different room numbers give unrelated topics and keys`() = runTest {
        val a = TogetherRoomKey.derive(ROOM)
        val b = TogetherRoomKey.derive(TogetherRoomId("48271594", "123456"))
        assertNotEquals(a.topic, b.topic)
        assertFalse(a.encryptionKey.contentEquals(b.encryptionKey))
    }

    @Test
    fun `another password keeps the topic and changes the key`() = runTest {
        val a = TogetherRoomKey.derive(ROOM)
        val b = TogetherRoomKey.derive(TogetherRoomId("48271593", "654321"))
        assertEquals(a.topic, b.topic)
        assertFalse(a.encryptionKey.contentEquals(b.encryptionKey))
        // 密码不对的人解不开房间里的消息
        assertNull(TogetherEnvelope.open(b, TogetherEnvelope.seal(a, "pause".encodeToByteArray())))
    }

    @Test
    fun `a denial names the message it answers and nothing else reads as one`() = runTest {
        val envelope = TogetherEnvelope.seal(TogetherRoomKey.derive(ROOM), "hello".encodeToByteArray())
        val nonce = assertNotNull(TogetherEnvelope.nonceOf(envelope))
        assertContentEquals(nonce, TogetherEnvelope.deniedNonceOf(TogetherEnvelope.deny(nonce)))
        assertNull(TogetherEnvelope.deniedNonceOf(envelope))
        assertNull(TogetherEnvelope.deniedNonceOf(ByteArray(13)))
        assertNull(TogetherEnvelope.nonceOf(TogetherEnvelope.deny(nonce)))
    }

    @Test
    fun `the topic is 32 hex characters and does not contain the room number`() = runTest {
        val key = TogetherRoomKey.derive(ROOM)
        assertTrue(Regex("[0-9a-f]{32}").matches(key.topic), key.topic)
        assertFalse("48271593" in key.topic)
        assertEquals(32, key.encryptionKey.size)
    }

    @Test
    fun `a sealed message opens with the same room key`() = runTest {
        val key = TogetherRoomKey.derive(ROOM)
        val envelope = TogetherEnvelope.seal(key, "pause at 12:34".encodeToByteArray())
        val opened = assertNotNull(TogetherEnvelope.open(key, envelope))
        assertEquals("pause at 12:34", opened.plaintext.decodeToString())
        assertContentEquals(TogetherEnvelope.nonceOf(envelope), opened.nonce)
        // 线上看不到明文
        assertFalse("pause" in envelope.decodeToString())
    }

    @Test
    fun `sealing the same message twice gives different bytes`() = runTest {
        val key = TogetherRoomKey.derive(ROOM)
        val first = TogetherEnvelope.seal(key, "same".encodeToByteArray())
        val second = TogetherEnvelope.seal(key, "same".encodeToByteArray())
        assertFalse(first.contentEquals(second))
        assertFalse(TogetherEnvelope.nonceOf(first)!!.contentEquals(TogetherEnvelope.nonceOf(second)!!))
    }

    @Test
    fun `a message from another room does not open`() = runTest {
        val envelope = TogetherEnvelope.seal(TogetherRoomKey.derive(ROOM), "secret".encodeToByteArray())
        assertNull(TogetherEnvelope.open(TogetherRoomKey.derive(TogetherRoomId("48271594", "123456")), envelope))
    }

    @Test
    fun `a tampered or truncated message does not open`() = runTest {
        val key = TogetherRoomKey.derive(ROOM)
        val envelope = TogetherEnvelope.seal(key, "seek to 0".encodeToByteArray())

        for (index in envelope.indices) {
            val tampered = envelope.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull(TogetherEnvelope.open(key, tampered), "改了第 $index 个字节还能解开")
        }
        assertNull(TogetherEnvelope.open(key, envelope.copyOf(envelope.size - 1)))
        assertNull(TogetherEnvelope.open(key, ByteArray(5)))
        assertNull(TogetherEnvelope.open(key, ByteArray(0)))
        assertNull(TogetherEnvelope.nonceOf(ByteArray(5)))
    }

    @Test
    fun `an empty message survives the round trip`() = runTest {
        val key = TogetherRoomKey.derive(ROOM)
        val opened = assertNotNull(TogetherEnvelope.open(key, TogetherEnvelope.seal(key, ByteArray(0))))
        assertEquals(0, opened.plaintext.size)
    }

    private companion object {
        val ROOM = TogetherRoomId("48271593", "123456")
    }
}
