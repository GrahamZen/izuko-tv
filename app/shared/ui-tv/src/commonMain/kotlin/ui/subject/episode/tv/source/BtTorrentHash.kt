/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation

/**
 * BT 资源是哪个种子 (infohash, 小写十六进制): 磁力链接里的 `btih` (十六进制或 Base32 两种写法统一成十六进制),
 * 或种子文件地址里的 40 位十六进制 (蜜柑的 `/Download/<日期>/<infohash>.torrent`). 认不出时为 null.
 */
internal fun torrentHashOf(media: Media): String? {
    val uri = media.download.uri
    BTIH.find(uri)?.let { return normalizeHash(it.groupValues[1]) }
    if (media.download is ResourceLocation.HttpTorrentFile) HEX_HASH.find(uri)?.let { return it.value.lowercase() }
    return null
}

private fun normalizeHash(raw: String): String? = when (raw.length) {
    40 -> raw.lowercase().takeIf { hash -> hash.all { it in '0'..'9' || it in 'a'..'f' } }
    32 -> base32ToHex(raw.uppercase())
    else -> null
}

private fun base32ToHex(text: String): String? {
    val out = StringBuilder(40)
    var buffer = 0
    var bits = 0
    for (char in text) {
        val value = BASE32_ALPHABET.indexOf(char)
        if (value < 0) return null
        buffer = ((buffer shl 5) or value) and 0xFFF
        bits += 5
        if (bits >= 8) {
            bits -= 8
            val byte = (buffer shr bits) and 0xFF
            out.append(HEX_DIGITS[byte shr 4]).append(HEX_DIGITS[byte and 0xF])
        }
    }
    return out.toString().takeIf { it.length == 40 }
}

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val HEX_DIGITS = "0123456789abcdef"
private val BTIH = Regex("""(?i)urn:btih:([0-9a-z]{32,40})""")
private val HEX_HASH = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{40}(?![0-9a-f])""")
