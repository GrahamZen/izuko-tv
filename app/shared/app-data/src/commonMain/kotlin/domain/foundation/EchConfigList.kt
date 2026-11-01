/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

/**
 * `ECHConfigList` (draft-ietf-tls-esni 的线上格式) 的检查: 里面有没有 BoringSSL 会用的配置.
 *
 * BoringSSL 遇到合法但不支持的配置 (别的 KEM、别的套件、带必须理解的扩展) 会跳过; 全被跳过时它照常发出不带 ECH 的握手.
 * 要求 ECH 的连接因此要先查一遍, 查不过就不连 (iOS 的加密直连, 规则同 ech_http 的 `supports_ech`).
 */
object EchConfigList {
    /** [list] 里至少有一份 BoringSSL 能用的配置: 版本 0xfe0d、X25519 (KEM 0x20, 32 字节公钥)、HKDF-SHA256 配一种 AEAD、合法的公共名、没有必须理解的扩展. */
    fun hasUsableConfig(list: ByteArray): Boolean {
        val input = Reader(list)
        val configs = input.u16Prefixed() ?: return false
        if (!input.isEmpty()) return false
        var found = false
        while (!configs.isEmpty()) {
            val version = configs.u16() ?: return false
            val contents = configs.u16Prefixed() ?: return false
            if (version != VERSION) continue
            found = found or (parseConfig(contents) ?: return false)
        }
        return found
    }

    /** 一份配置能不能用; 格式不对为 `null`. */
    private fun parseConfig(contents: Reader): Boolean? {
        contents.u8() ?: return null // config_id
        val kem = contents.u16() ?: return null
        val key = contents.u16Prefixed() ?: return null
        val suites = contents.u16Prefixed() ?: return null
        contents.u8() ?: return null // maximum_name_length
        val name = contents.u8Prefixed() ?: return null
        val extensions = contents.u16Prefixed() ?: return null
        if (!contents.isEmpty()) return null

        var cipher = false
        while (!suites.isEmpty()) {
            val kdf = suites.u16() ?: return null
            val aead = suites.u16() ?: return null
            if (kdf == HKDF_SHA256 && aead in 1..3) cipher = true
        }
        var mandatory = false
        while (!extensions.isEmpty()) {
            val type = extensions.u16() ?: return null
            extensions.u16Prefixed() ?: return null
            if (type and 0x8000 != 0) mandatory = true
        }
        return kem == KEM_X25519 && key.remaining == X25519_KEY_LENGTH && cipher && isValidPublicName(name.rest()) && !mandatory
    }

    /** 公共名: 1~253 字节, 每段 1~63 个字母数字或中间的连字符, 段尾是字母数字, 不能全是数字 (像 IP 地址). */
    private fun isValidPublicName(name: ByteArray): Boolean {
        if (name.isEmpty() || name.size > 253) return false
        var valid = true
        var labelSize = 0
        var labelEndsAlnum = false
        var allNumeric = true
        for (b in name) {
            val c = b.toInt().toChar()
            val digit = c in '0'..'9'
            val alnum = digit || c in 'a'..'z' || c in 'A'..'Z'
            if (c == '.') {
                valid = valid && labelSize > 0 && labelEndsAlnum
                labelSize = 0
                labelEndsAlnum = false
            } else {
                valid = valid && (alnum || (c == '-' && labelSize > 0))
                labelSize++
                valid = valid && labelSize <= 63
                labelEndsAlnum = alnum
            }
            if (!digit && c != '.') allNumeric = false
        }
        return valid && labelSize > 0 && labelEndsAlnum && !allNumeric
    }

    private const val VERSION = 0xfe0d
    private const val KEM_X25519 = 0x20
    private const val X25519_KEY_LENGTH = 32
    private const val HKDF_SHA256 = 1

    /** 按 TLS 的写法 (大端、长度前缀) 读字节. 不够读时返回 `null`. */
    private class Reader(private val bytes: ByteArray, private var offset: Int = 0, private val end: Int = bytes.size) {
        val remaining: Int get() = end - offset

        fun isEmpty() = remaining == 0

        fun u8(): Int? = if (remaining < 1) null else bytes[offset++].toInt() and 0xff

        fun u16(): Int? {
            if (remaining < 2) return null
            val value = (bytes[offset].toInt() and 0xff shl 8) or (bytes[offset + 1].toInt() and 0xff)
            offset += 2
            return value
        }

        fun u8Prefixed(): Reader? = u8()?.let { take(it) }

        fun u16Prefixed(): Reader? = u16()?.let { take(it) }

        fun rest(): ByteArray = bytes.copyOfRange(offset, end)

        private fun take(length: Int): Reader? {
            if (remaining < length) return null
            return Reader(bytes, offset, offset + length).also { offset += length }
        }
    }
}
