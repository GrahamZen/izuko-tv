/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.hls

import me.him188.ani.app.domain.episode.ipadDiag
import kotlin.time.TimeSource

/*
 * iPad 测试包的诊断 (只在 CI 测试分支): 代理每回一个分片请求记一行: 播放器要的范围、回应状态、正文应发多少、实际发了多少、用时,
 * 以及怎么结束的 (发完 / 播放器先断开 / 其他错误). 日志里搜 `iPadDiag] hls seg`. 请求头只记名字.
 */
internal suspend fun diagServeSegment(
    index: Int,
    request: HlsProxyRequest,
    output: HlsProxyResponseSink,
    serve: suspend (HlsProxyResponseSink) -> Unit,
) {
    val sink = CountingSink(output)
    val started = TimeSource.Monotonic.markNow()
    var failure: Throwable? = null
    try {
        serve(sink)
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        val outcome = when {
            failure == null -> "done"
            sink.writeFailure != null -> "player closed (${sink.writeFailure!!::class.simpleName})"
            else -> "error ${failure::class.simpleName}: ${failure.message?.take(120)}"
        }
        val headerNames = if (index < 3) " headers=${request.headers.keys}" else ""
        ipadDiag(
            "hls seg #$index range=${request.headers["range"] ?: "-"} ${sink.status} sent=${sink.bodyBytes}/${sink.contentLength ?: "?"} " +
                "in ${started.elapsedNow().inWholeMilliseconds}ms: $outcome$headerNames",
        )
    }
}

private class CountingSink(private val delegate: HlsProxyResponseSink) : HlsProxyResponseSink {
    var status: String = "-"
    var contentLength: Long? = null
    var bodyBytes = 0L
    var writeFailure: Throwable? = null
    private var headerSeen = false

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (!headerSeen) {
            headerSeen = true
            val text = bytes.decodeToString(offset, offset + minOf(length, 2048))
            status = text.substringBefore("\r\n").substringAfter(' ').substringBefore(' ')
            contentLength = text.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toLongOrNull()
            val headerEnd = text.indexOf("\r\n\r\n")
            if (headerEnd >= 0) bodyBytes += length - (headerEnd + 4).coerceAtMost(length)
        } else {
            bodyBytes += length
        }
        try {
            delegate.write(bytes, offset, length)
        } catch (e: Throwable) {
            writeFailure = e
            throw e
        }
    }
}
