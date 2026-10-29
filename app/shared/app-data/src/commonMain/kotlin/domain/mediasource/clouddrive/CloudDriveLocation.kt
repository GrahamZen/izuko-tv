/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.datasources.api.source.MediaSourceLocation

/**
 * 网盘引擎的数据源与资源放在哪: 本地文件夹是本机的文件 ([MediaSourceLocation.Local]); WebDAV 服务器在局域网 (见 [isLanHost]) 时是
 * [MediaSourceLocation.Lan] (NAS); 公网上的 WebDAV (网盘式的服务) 与各家网盘是 [MediaSourceLocation.Online].
 *
 * 选源排序 (MediaSelectorFilterSortAlgorithm) 按它把本机、局域网的排前面; 自动选源在缓存之后先选它们 (见 LocalFileAutoSelect);
 * 电视选源面板把它们与「下载」放进「本地」那一组.
 */
val CloudDriveProtocol.mediaLocation: MediaSourceLocation
    get() = when {
        isLocalFolder -> MediaSourceLocation.Local
        isWebDav && WebDav.serverUrl(this)?.host?.let(::isLanHost) == true -> MediaSourceLocation.Lan
        else -> MediaSourceLocation.Online
    }

/**
 * [host] 在局域网: 回环、私有 (10/8、172.16/12、192.168/16)、链路本地的 IPv4 / IPv6 地址 (含 IPv6 唯一本地地址 fc00::/7),
 * 或局域网里用的主机名 (没有点的, `.local` `.lan` `.home` `.internal` `.localdomain` `.home.arpa` 结尾). 经 VPN 或公网域名连回家的不算.
 */
internal fun isLanHost(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    if (h.isEmpty()) return false
    if (h == "localhost") return true
    ipv4(h)?.let { (a, b) ->
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
    if (':' in h) {
        if (h == "::1") return true
        val first = h.substringBefore(':').toIntOrNull(16) ?: return false
        return first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80
    }
    return '.' !in h || LAN_SUFFIXES.any { h.endsWith(it) }
}

private val LAN_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")

/** 点分十进制的 IPv4 地址: 前两段; 不是 IPv4 时为 null. */
private fun ipv4(host: String): Pair<Int, Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val nums = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.isNotEmpty() && p.all(Char::isDigit) } ?: return null }
    return nums[0] to nums[1]
}
