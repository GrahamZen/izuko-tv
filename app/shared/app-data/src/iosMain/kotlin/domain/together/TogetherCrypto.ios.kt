/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.together

// iOS 上没有接一起看的加密 (系统公开的 CommonCrypto 没有 AES-GCM), 所以 iOS 上进不了房间。

private fun unsupported(): Nothing = throw UnsupportedOperationException("iOS 上没有接一起看的加密")

internal actual fun platformPbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray =
    unsupported()

internal actual fun platformAesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
    unsupported()

internal actual fun platformAesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray? =
    unsupported()

internal actual fun platformSecureRandomBytes(size: Int): ByteArray = unsupported()
