/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把电视上一个**已经下完**的视频文件发给手机 (电视在播本机文件时的全屏同步播放)。
 *
 * 只是按播放器要的范围 (`Range`) 读出那一段原样发出去: 不解码、不转码, 手机播到哪要到哪, 对电视的开销只有读存储与 Wi-Fi。
 * 每一路占着控制台服务的一个处理线程 (共 16 个), 所以同时最多 [MAX_STREAMS] 路, 余下的留给轮询与操作;
 * 写不出去的连接由服务自己关 (见 LanHttpServer 的 isStreaming)。
 */
internal object RemoteFileStream {
    private const val MAX_STREAMS = 4
    private const val BUFFER_BYTES = 64 * 1024

    private val active = AtomicInteger()

    fun serve(file: File, request: LanHttpRequest): LanHttpResponse {
        if (request.method != "GET" && request.method != "HEAD") return LanHttpResponse.status(405, "Method Not Allowed")
        if (!file.isFile) return LanHttpResponse.status(404, "Not Found")
        val length = file.length()
        val contentType = contentTypeOf(file.name)
        val range = request.range
        val span = if (range == null) 0L until length else parseRange(range, length)
            ?: return LanHttpResponse(
                status = 416, reason = "Range Not Satisfiable", contentType = "text/plain; charset=utf-8", contentLength = 0,
                extraHeaders = listOf("Content-Range: bytes */$length"),
            ) {}
        if (request.method == "GET" && active.get() >= MAX_STREAMS) {
            // 播放器会稍后再要; 多半是上一段还没断开时就要了下一段
            return LanHttpResponse(
                status = 503, reason = "Service Unavailable", contentType = "text/plain; charset=utf-8", contentLength = 0,
                extraHeaders = listOf("Retry-After: 1"),
            ) {}
        }
        val partial = range != null
        val headers = buildList {
            add("Accept-Ranges: bytes")
            if (partial) add("Content-Range: bytes ${span.first}-${span.last}/$length")
        }
        val count = span.last - span.first + 1
        return LanHttpResponse(
            status = if (partial) 206 else 200,
            reason = if (partial) "Partial Content" else "OK",
            contentType = contentType,
            contentLength = count,
            extraHeaders = headers,
        ) { out ->
            active.incrementAndGet()
            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(span.first)
                    val buf = ByteArray(BUFFER_BYTES)
                    var left = count
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            } finally {
                active.decrementAndGet()
            }
        }
    }

    /**
     * `bytes=a-b` / `bytes=a-` / `bytes=-n` (只认第一段); 越界或格式不对返回 null (回 416)。
     */
    internal fun parseRange(header: String, length: Long): LongRange? {
        if (length <= 0) return null
        val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (!header.trim().startsWith("bytes=") || dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                val n = b.toLongOrNull()?.takeIf { it > 0 } ?: return null
                (length - minOf(n, length)) until length
            }

            else -> {
                val start = a.toLongOrNull()?.takeIf { it in 0 until length } ?: return null
                val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(length - 1)
                if (end < start) null else start..end
            }
        }
    }

    internal fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        else -> "application/octet-stream"
    }
}
