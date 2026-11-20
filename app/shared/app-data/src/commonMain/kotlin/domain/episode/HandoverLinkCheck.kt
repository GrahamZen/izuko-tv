/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.platform.PlaybackRequestHints
import me.him188.ani.utils.ktor.ScopedHttpClient
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.time.Duration.Companion.seconds

/**
 * 后台换源换下来之前先试一下新的地址 (见 [PlaybackHandover]): 旧的还在放, 新的一上来就被拒、回的是网页、连不上的不换, 当场换下一条.
 * 换下来之后播放器才发现打不开的话, 旧的已经停了, 剩下的缓冲白丢.
 *
 * 只要第一个字节 (`Range: bytes=0-0`), 读完响应头就关; 请求头照播放器的带 (去掉只给播放器看的提示, 见 [PlaybackRequestHints]).
 * 不是 http 地址 (本地文件、BT 等) 不试.
 */
internal class HandoverLinkCheck(
    /** 发请求用的客户端; 没有时不试 */
    private val client: () -> ScopedHttpClient?,
) {
    /** @return 打不开的原因; 打得开或试不了时为 `null` */
    suspend fun failureOf(data: MediaData): Throwable? {
        val link = data as? UriMediaData ?: return null
        if (!link.uri.startsWith("http://") && !link.uri.startsWith("https://")) return null
        val client = client() ?: return null
        return try {
            withTimeout(TIMEOUT) {
                client.use {
                    prepareGet(link.uri) {
                        expectSuccess = false
                        headers {
                            for ((name, value) in PlaybackRequestHints.strip(link.headers)) set(name, value)
                            set(HttpHeaders.Range, "bytes=0-0")
                        }
                    }.execute { response ->
                        val status = response.status.value
                        val type = response.contentType()
                        when {
                            status >= 400 -> HandoverLinkCheckException("HTTP $status")
                            type != null && type.match(ContentType.Text.Html) -> HandoverLinkCheckException("not a video ($type)")
                            else -> null
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            HandoverLinkCheckException("no answer in $TIMEOUT", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
    }

    private companion object {
        /** 正常的服务器一两秒内就回; 等太久旧的缓冲也要放完了 */
        val TIMEOUT = 10.seconds
    }
}

/** 换下来之前试新地址没打开 (见 [HandoverLinkCheck]) */
class HandoverLinkCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)
