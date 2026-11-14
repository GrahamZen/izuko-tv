/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.hls

import io.ktor.client.plugins.ResponseException

/**
 * 本机 HLS 代理向源站取播放列表或分片失败时回给播放器的响应头.
 *
 * 源站回了 HTTP 错误 (地址过期时的 401 / 403 之类) 就把状态原样转给播放器: 播放器按状态码判断是地址被拒 (重新取一次地址)
 * 还是这条资源坏了 (换源), 见 `PlaybackFailureRecovery`; 一律回 502 的话过期会被当成资源坏了. 别的失败 (连不上、超时) 是 502.
 */
internal fun upstreamErrorResponseHeader(failure: Throwable): String {
    val status = generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH)
        .filterIsInstance<ResponseException>().firstOrNull()?.response?.status
    return buildString {
        append("HTTP/1.1 ").append(status?.value ?: 502).append(' ').append(status?.description ?: "Bad Gateway").append("\r\n")
        append("Content-Length: 0\r\n")
        append("Cache-Control: no-store\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }
}

private const val MAX_CAUSE_DEPTH = 8
