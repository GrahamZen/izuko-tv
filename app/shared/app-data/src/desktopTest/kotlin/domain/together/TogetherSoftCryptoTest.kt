/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 拼出来的 AES-GCM 与 PBKDF2 (iOS 用, 见 [TogetherSoftCrypto]) 要与安卓、电脑上系统的实现逐字节相同:
 * 一起看房间里两端各走一条, 加密配置也是另一边 (Python) 加的密.
 * 这里系统的 AES 分组加密与 HMAC 用 JVM 的, 对照的是 JVM 的 `AES/GCM/NoPadding` 与 `PBKDF2WithHmacSHA256`.
 */
class TogetherSoftCryptoTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun ecb(key: ByteArray): (ByteArray) -> ByteArray = { blocks ->
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(blocks)
        }
    }

    private val hmacSha256: (ByteArray, ByteArray) -> ByteArray = { key, data ->
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
    }

    @Test
    fun `gcm matches the published test vector`() {
        // NIST GCM 规范的测试用例 16 (AES-256, 12 字节随机数, 带附加数据)
        val key = unhex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val nonce = unhex("cafebabefacedbaddecaf888")
        val plaintext = unhex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
        )
        val associatedData = unhex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        assertEquals(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b",
            hex(TogetherSoftCrypto.aesGcmSeal(ecb(key), nonce, plaintext, associatedData)),
        )
    }

    @Test
    fun `gcm agrees with the jvm byte for byte`() {
        val random = Random(20261122)
        repeat(200) {
            val key = random.nextBytes(if (random.nextBoolean()) 32 else 16)
            val nonce = random.nextBytes(12)
            // 0 与不满一块的、正好几块的长度都要有
            val plaintext = random.nextBytes(random.nextInt(0, 100))
            val associatedData = random.nextBytes(random.nextInt(0, 40))
            val sealed = TogetherSoftCrypto.aesGcmSeal(ecb(key), nonce, plaintext, associatedData)
            assertContentEquals(
                platformAesGcmSeal(key, nonce, plaintext, associatedData),
                sealed,
                "plaintext=${plaintext.size} associatedData=${associatedData.size}",
            )
            assertContentEquals(plaintext, TogetherSoftCrypto.aesGcmOpen(ecb(key), nonce, sealed, associatedData))
            assertContentEquals(plaintext, platformAesGcmOpen(key, nonce, sealed, associatedData))
        }
    }

    @Test
    fun `gcm open rejects tampering and malformed input`() {
        val random = Random(7)
        val key = random.nextBytes(32)
        val nonce = random.nextBytes(12)
        val plaintext = "一起看".encodeToByteArray()
        val associatedData = byteArrayOf(2)
        val sealed = TogetherSoftCrypto.aesGcmSeal(ecb(key), nonce, plaintext, associatedData)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(TogetherSoftCrypto.aesGcmOpen(ecb(key), nonce, tampered, associatedData), "byte $i")
        }
        assertNull(TogetherSoftCrypto.aesGcmOpen(ecb(key), nonce, sealed, byteArrayOf(3)))
        assertNull(TogetherSoftCrypto.aesGcmOpen(ecb(random.nextBytes(32)), nonce, sealed, associatedData))
        assertNull(TogetherSoftCrypto.aesGcmOpen(ecb(key), nonce.copyOf(8), sealed, associatedData))
        assertNull(TogetherSoftCrypto.aesGcmOpen(ecb(key), nonce, sealed.copyOf(15), associatedData))
    }

    @Test
    fun `pbkdf2 agrees with the jvm byte for byte`() {
        val random = Random(20261122)
        repeat(20) {
            val password = buildString { repeat(random.nextInt(1, 24)) { append("0123456789ABCDEFxyz 一起看"[random.nextInt(22)]) } }
            val salt = random.nextBytes(random.nextInt(1, 40))
            val iterations = random.nextInt(1, 300)
            val length = random.nextInt(1, 80)
            assertContentEquals(
                pbkdf2ByProvider(password, salt, iterations, length),
                TogetherSoftCrypto.pbkdf2HmacSha256(hmacSha256, password, salt, iterations, length),
                "password=$password iterations=$iterations length=$length",
            )
        }
    }

    @Test
    fun `pbkdf2 agrees at the real iteration count`() {
        val salt = "izuko-together/v2/key".encodeToByteArray()
        assertContentEquals(
            pbkdf2ByProvider("123456/abcd", salt, TogetherRoomKey.KDF_ITERATIONS, 32),
            TogetherSoftCrypto.pbkdf2HmacSha256(hmacSha256, "123456/abcd", salt, TogetherRoomKey.KDF_ITERATIONS, 32),
        )
    }
}
