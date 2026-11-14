/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal actual fun platformPbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray =
    try {
        pbkdf2ByProvider(password, salt, iterations, lengthBytes)
    } catch (_: NoSuchAlgorithmException) {
        // Android 8.0 (API 26) 之前系统没有这个算法, 兼容包 (Android 7.1) 走这里
        pbkdf2ByMac(password, salt, iterations, lengthBytes)
    }

internal fun pbkdf2ByProvider(password: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, lengthBytes * 8)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

/** RFC 8018 的 PBKDF2, 自己用 HMAC 一轮轮算; 结果与 [pbkdf2ByProvider] 逐字节相同 */
internal fun pbkdf2ByMac(password: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val blockBytes = mac.macLength
    val output = ByteArray(lengthBytes)
    var written = 0
    var blockIndex = 1
    while (written < lengthBytes) {
        mac.update(salt)
        mac.update(byteArrayOf((blockIndex ushr 24).toByte(), (blockIndex ushr 16).toByte(), (blockIndex ushr 8).toByte(), blockIndex.toByte()))
        var round = mac.doFinal()
        val block = round.copyOf()
        repeat(iterations - 1) {
            round = mac.doFinal(round)
            for (i in block.indices) block[i] = (block[i].toInt() xor round[i].toInt()).toByte()
        }
        val take = minOf(blockBytes, lengthBytes - written)
        block.copyInto(output, written, 0, take)
        written += take
        blockIndex++
    }
    return output
}

private const val GCM_TAG_BITS = 128

internal actual fun platformAesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    cipher.updateAAD(associatedData)
    return cipher.doFinal(plaintext)
}

internal actual fun platformAesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray? =
    try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        cipher.doFinal(sealed)
    } catch (_: GeneralSecurityException) {
        null
    }

private val secureRandom by lazy { SecureRandom() }

internal actual fun platformSecureRandomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }
