/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.datasources.api.matcher.WebVideo
import me.him188.ani.utils.ktor.rawCookieHeader
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/*
 * iOS 的嗅探在页面脚本里拦 (XHR / fetch / 视频的 src), 页面一用到一个地址就报上来, 不像安卓那样看到的是真正发出去的请求. 有的页面先用到的地址
 * 还不能放: 播放器参数里没签名的地址 (签名由播放器脚本再去取, 直接要回 401), 或者地址里带 m3u8 字样的播放器页 (回的是网页).
 * 所以嗅探到一个先试一下 ([sniffedVideoUsable]), 不能放的记下来、重新打开页面再嗅 (见 IosWebMediaResolver), 最多 [MAX_SNIFF_ROUNDS] 轮.
 */

/** 嗅探最多几轮 (头几轮抓到的不能放时再来). */
internal const val MAX_SNIFF_ROUNDS = 3

/**
 * 嗅探到的 [video] 能不能放: 要开头 [HEAD_BYTES] 个字节. 回 4xx, 或回的是网页 ([looksLikeHtml]) 就不能; 试不通 (网络错误、5xx) 照样当能放, 交给播放器.
 */
internal suspend fun sniffedVideoUsable(video: WebVideo, round: Int): Boolean {
    val host = runCatching { Url(video.m3u8Url).host }.getOrDefault("?")
    return try {
        GlobalKoin.get<HttpClientProvider>().get(ScopedHttpClientUserAgent.NONE).use {
            prepareGet(video.m3u8Url) {
                video.headers.forEach { (name, value) ->
                    if (name.equals(HttpHeaders.Cookie, ignoreCase = true)) rawCookieHeader(value) else header(name, value)
                }
                header(HttpHeaders.Range, "bytes=0-${HEAD_BYTES - 1}")
                expectSuccess = false
                timeout { requestTimeoutMillis = CHECK_TIMEOUT_MILLIS }
            }.execute { response ->
                val status = response.status.value
                val channel = response.bodyAsChannel()
                val head = ByteArray(HEAD_BYTES)
                var read = 0
                while (read < HEAD_BYTES) {
                    val n = channel.readAvailable(head, read, HEAD_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                val html = looksLikeHtml(head.copyOf(read))
                val usable = status !in 400..499 && !html
                logger.info {
                    "[Sniff] 第 ${round + 1} 轮抓到的 $host 试了一下: $status${if (html) " (网页)" else ""}, " +
                        if (usable) "能放" else "不能放, 接着找"
                }
                usable
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.info { "[Sniff] 第 ${round + 1} 轮抓到的 $host 没试通 (${e::class.simpleName}), 交给播放器" }
        true
    }
}

/**
 * 开头像网页: 去掉 BOM 与空白后以 `<` 开头 (播放器页常以换行或 BOM 开头). 播放列表以 `#EXTM3U` 开头, 视频是二进制;
 * `<?xml` 与 DASH 的 `<MPD` 不算网页.
 */
internal fun looksLikeHtml(head: ByteArray): Boolean {
    val text = head.decodeToString().trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (!text.startsWith("<")) return false
    return !text.startsWith("<?xml", ignoreCase = true) && !text.contains("<MPD", ignoreCase = true)
}

private const val HEAD_BYTES = 256
private const val CHECK_TIMEOUT_MILLIS = 8_000L
private val logger = logger("SniffedVideoCheck")
