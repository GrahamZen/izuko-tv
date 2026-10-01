/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.ktor.installRawCookieHeader
import me.him188.ani.utils.ktor.rawCookieHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 默认 client 装了 [HttpCookies], 手写的 Cookie 头会被它改写 (夸克网盘直链因此 412, 缓存下载一直失败);
 * [rawCookieHeader] 让它原样发出去.
 */
class RawCookieHeaderTest {
    // 夸克登录 Cookie 的样子: 值里有 base64 的 + / =, 也有已经百分号编码过的
    private val cookie = "__pus=a1B+c2/D3e==; __puus=xY%2Bz9; __uid=AAQ1x/y+z"

    private fun client(onRequest: (String?) -> Unit) = HttpClient(
        MockEngine { request ->
            onRequest(request.headers[HttpHeaders.Cookie])
            respondOk()
        },
    ) {
        install(HttpCookies)
    }.also { it.installRawCookieHeader() }

    @Test
    fun `cookie plugin rewrites a hand written cookie header`() = runTest {
        var sent: String? = null
        client { sent = it }.get("https://dl-pc-sz.drive.quark.cn/a/b?auth_key=1") { header(HttpHeaders.Cookie, cookie) }
        assertNotEquals(cookie, sent)
    }

    @Test
    fun `raw cookie header is sent as is`() = runTest {
        var sent: String? = null
        client { sent = it }.get("https://dl-pc-sz.drive.quark.cn/a/b?auth_key=1") { rawCookieHeader(cookie) }
        assertEquals(cookie, sent)
    }
}
