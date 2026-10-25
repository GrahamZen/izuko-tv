/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders

/**
 * 视频直链写进日志的样子: 只到域名 (`https://dl.example.com/…`). 网盘与 CDN 的直链在参数 (有的在路径) 里带着签名, 拿到就能下载.
 * 不是 http(s) 地址 (本地文件等) 原样返回.
 */
fun directLinkForLog(url: String): String {
    val scheme = url.substringBefore("://", missingDelimiterValue = "")
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return url
    val rest = url.substringAfter("://")
    val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }
    return if (host.length == rest.length) url else "$scheme://$host/…"
}

/** 请求日志里这个请求的地址: 取视频字节的 (带 Range) 只到域名 (见 [directLinkForLog]), 其余照写全. */
internal fun HttpRequestBuilder.urlForLog(): String {
    val url = this.url.toString()
    return if (headers.contains(HttpHeaders.Range)) directLinkForLog(url) else url
}
