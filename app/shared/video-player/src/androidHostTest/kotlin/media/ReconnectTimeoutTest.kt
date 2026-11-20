/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 断了重新请求时连接超时用短的 ([ReconnectTimeout]), 标记不发给服务器.
 */
class ReconnectTimeoutTest {
    @Test
    fun `a request made again connects with the short timeout and the mark is not sent`() {
        val seen = mutableListOf<Pair<Int, String?>>()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(ReconnectTimeout)
            // 代替网络: 记下发出去时的连接超时与请求头
            .addInterceptor { chain ->
                seen += chain.connectTimeoutMillis() to chain.request().header(ReconnectTimeout.HEADER)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("".toResponseBody()).build()
            }
            .build()

        client.newCall(Request.Builder().url("http://example.invalid/v.mp4").header(ReconnectTimeout.HEADER, "1").build()).execute().close()
        client.newCall(Request.Builder().url("http://example.invalid/v.mp4").build()).execute().close()

        assertEquals(listOf<Pair<Int, String?>>(ReconnectTimeout.CONNECT_TIMEOUT_MILLIS to null, 30_000 to null), seen)
    }
}
