/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/** CommonCrypto 的 AES-CBC (PKCS#7 填充); 填充不对 (密钥错了) 时 CCCrypt 报错, 这里抛异常. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun platformAesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    require(data.isNotEmpty()) { "密文为空" }
    // 解密结果不会比密文长; 多留一块是 CCCrypt 对输出缓冲的要求
    val out = ByteArray(data.size + kCCBlockSizeAES128.toInt())
    val length = memScoped {
        val moved = alloc<size_tVar>()
        val status = CCCrypt(
            kCCDecrypt.convert(),
            kCCAlgorithmAES.convert(),
            kCCOptionPKCS7Padding.convert(),
            key.refTo(0),
            key.size.convert(),
            iv.refTo(0),
            data.refTo(0),
            data.size.convert(),
            out.refTo(0),
            out.size.convert(),
            moved.ptr,
        )
        require(status.toInt() == kCCSuccess.toInt()) { "AES 解密失败 (CCCrypt 返回 $status)" }
        moved.value.toInt()
    }
    return out.copyOf(length)
}
