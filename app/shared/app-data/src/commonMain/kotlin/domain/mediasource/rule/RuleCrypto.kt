/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlinx.io.Buffer
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.readAndDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 规则源 [RuleStep.Crypto] 的解密与摘要. 站点常把视频地址用固定密钥 AES 加密后放进页面或接口, 密钥与 IV 写在规则里.
 */
internal object RuleCrypto {
    /** [text] (UTF-8) 的 MD5, 小写十六进制. */
    fun md5Hex(text: String): String {
        val buffer = Buffer().apply { write(text.encodeToByteArray()) }
        return buffer.readAndDigest(DigestAlgorithm.MD5).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * AES-CBC 解密 (PKCS#7 填充), 结果按 UTF-8 解成文本. [key] 与 [iv] 按 UTF-8 取字节.
     * 密钥 / IV 长度不对、密文认不出时抛 [IllegalArgumentException].
     */
    fun aesCbcDecrypt(cipherText: String, key: String, iv: String): String {
        val keyBytes = key.encodeToByteArray()
        val ivBytes = iv.encodeToByteArray()
        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) { "密钥要 16 / 24 / 32 字节, 实际 ${keyBytes.size} 字节" }
        require(ivBytes.size == 16) { "IV 要 16 字节, 实际 ${ivBytes.size} 字节" }
        val data = decodeCipherText(cipherText) ?: throw IllegalArgumentException("密文不是 base64 或十六进制")
        return platformAesCbcDecrypt(data, keyBytes, ivBytes).decodeToString()
    }

    /**
     * 密文的字节: 全是十六进制字符且长度是 32 的倍数 (AES 一块 16 字节) 时按十六进制, 否则按 base64
     * (也认 URL 安全的 `-` `_`、缺填充). 都不是时为 `null`.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeCipherText(text: String): ByteArray? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        if (compact.length % 32 == 0 && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val standard = compact.replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
        return runCatching { Base64.Default.decode(padded) }.getOrNull()
    }
}

/** AES-CBC 解密 (PKCS#7 填充). 填充不对 (密钥错了) 时抛异常. */
internal expect fun platformAesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray
