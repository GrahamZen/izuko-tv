/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.app.videoplayer.link.PlaybackLinks
import me.him188.ani.app.videoplayer.link.RefreshablePlaybackLink
import me.him188.ani.utils.coroutines.REJECTED_LINK_STATUSES
import java.io.InterruptedIOException

/**
 * 请求媒体地址被服务器拒绝 (签名过期) 时换新地址接着读的 HTTP 数据源, 见 [RefreshablePlaybackLink].
 *
 * 只管发给媒体原地址 ([originalUri], 播放器手上那个) 的请求: 换过地址之后把它们改发到新地址、带新的请求头.
 * 别的地址 (外挂字幕、m3u8 的分片) 原样交给上游. 换不了时把被拒的错原样抛出, 由播放器原来的出错处理接手 (重试、整个重新加载).
 *
 * 换来的地址得是同一个文件: 响应给出的文件总长与先前不一样时 (另一条线路或清晰度), 接着按原来的位置读会读出对不上的数据,
 * 这时把请求按被拒 ([MISMATCH_STATUS]) 报出去, 同样由播放器整个重新加载.
 */
@AndroidxOptIn(UnstableApi::class)
internal class RefreshingHttpDataSource(
    private val upstream: HttpDataSource,
    private val originalUri: String,
    private val link: RefreshablePlaybackLink,
) : HttpDataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.toString() != originalUri) return upstream.open(dataSpec)
        return try {
            link.requestBlocking({ (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode?.takeIf { code -> code in REJECTED_LINK_STATUSES } }) { lease ->
                val spec = if (lease.generation == 0) {
                    dataSpec
                } else {
                    dataSpec.buildUpon()
                        .setUri(lease.link.uri)
                        // 提示头只给播放器看; User-Agent 上游自己会带
                        .setHttpRequestHeaders(dataSpec.httpRequestHeaders + (PlaybackRequestHints.strip(lease.link.headers) - "User-Agent"))
                        .build()
                }
                if (link.mismatched) throw anotherFile(spec)
                val length = try {
                    upstream.open(spec)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    // 接下来可能换了地址再开一次: 先收掉这一次的
                    runCatching { upstream.close() }
                    throw e
                }
                val total = totalLengthOf(upstream.responseHeaders)
                if (total != null && !link.sameContent(total)) {
                    runCatching { upstream.close() }
                    throw anotherFile(spec)
                }
                length
            }
        } catch (e: InterruptedException) {
            // 换地址时这个请求不要了 (加载线程被打断): 按数据源的规矩报成被打断的读
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while refreshing the link")
        }
    }

    private fun anotherFile(spec: DataSpec) = HttpDataSource.InvalidResponseCodeException(
        MISMATCH_STATUS, "The fresh link serves another file", null, emptyMap(), spec, ByteArray(0),
    )

    /**
     * 包一层: 媒体 [originalUri] 登记了换地址的办法 ([PlaybackLinks]) 时, [upstream] 建出来的数据源被拒时会换地址再试; 没登记时就是 [upstream].
     */
    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val originalUri: String,
        private val link: RefreshablePlaybackLink,
    ) : HttpDataSource.Factory {
        override fun createDataSource(): HttpDataSource = RefreshingHttpDataSource(upstream.createDataSource(), originalUri, link)

        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
            upstream.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }
    }

    companion object {
        /** 换来的是另一个文件时报给播放器的状态码: 在「地址被拒」那一类里, 播放器按它整个重新加载 */
        private const val MISMATCH_STATUS = 412

        /** 见 [Factory] */
        fun wrap(upstream: HttpDataSource.Factory, originalUri: String): HttpDataSource.Factory {
            val link = PlaybackLinks.of(originalUri) ?: return upstream
            return Factory(upstream, originalUri, link)
        }

        /** 按范围请求的响应里写的文件总长 (`Content-Range: bytes 0-99/12345`); 没写时为 null */
        internal fun totalLengthOf(responseHeaders: Map<String, List<String>>): Long? {
            val range = responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
            return range?.substringAfterLast('/', "")?.toLongOrNull()
        }
    }
}
