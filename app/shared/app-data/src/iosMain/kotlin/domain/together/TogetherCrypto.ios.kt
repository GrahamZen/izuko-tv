/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalForeignApi::class)

package me.him188.ani.app.domain.together

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

// 系统公开的 CommonCrypto 没有 AES-GCM, 只有 AES 分组加密与 HMAC: GCM 与 PBKDF2 在它们上面拼 (见 TogetherSoftCrypto)

internal actual fun platformPbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray =
    TogetherSoftCrypto.pbkdf2HmacSha256(::hmacSha256, password, salt, iterations, lengthBytes)

internal actual fun platformAesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
    TogetherSoftCrypto.aesGcmSeal(aesEcbEncryptor(key), nonce, plaintext, associatedData)

internal actual fun platformAesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray? =
    TogetherSoftCrypto.aesGcmOpen(aesEcbEncryptor(key), nonce, sealed, associatedData)

internal actual fun platformSecureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), bytes.refTo(0))
    check(status == errSecSuccess) { "取随机数失败 (SecRandomCopyBytes 返回 $status)" }
    return bytes
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(SHA256_BYTES)
    // 空数组取不了地址: 给一个字节的缓冲, 长度仍按 0 传
    val keyBuffer = if (key.isEmpty()) ByteArray(1) else key
    val dataBuffer = if (data.isEmpty()) ByteArray(1) else data
    CCHmac(
        kCCHmacAlgSHA256.convert(),
        keyBuffer.refTo(0),
        key.size.convert(),
        dataBuffer.refTo(0),
        data.size.convert(),
        out.refTo(0),
    )
    return out
}

private const val SHA256_BYTES = 32

/** 用 [key] 把若干个 16 字节分组逐个 AES 加密 (ECB, 不填充) */
private fun aesEcbEncryptor(key: ByteArray): (ByteArray) -> ByteArray = { blocks ->
    val out = ByteArray(blocks.size)
    memScoped {
        val moved = alloc<size_tVar>()
        val status = CCCrypt(
            kCCEncrypt.convert(),
            kCCAlgorithmAES.convert(),
            kCCOptionECBMode.convert(),
            key.refTo(0),
            key.size.convert(),
            null,
            blocks.refTo(0),
            blocks.size.convert(),
            out.refTo(0),
            out.size.convert(),
            moved.ptr,
        )
        check(status == kCCSuccess) { "AES 加密失败 (CCCrypt 返回 $status)" }
        check(moved.value.toInt() == blocks.size) { "AES 输出长度不对: ${moved.value}, 应为 ${blocks.size}" }
    }
    out
}
