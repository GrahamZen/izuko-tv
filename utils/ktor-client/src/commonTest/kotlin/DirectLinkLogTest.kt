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
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectLinkLogTest {
    @Test
    fun `signed direct link keeps only the host`() {
        assertEquals("https://dl.example.com/…", directLinkForLog("https://dl.example.com/a/b.mp4?auth_key=1-2-3&token=abc"))
        assertEquals("http://127.0.0.1:8080/…", directLinkForLog("http://127.0.0.1:8080/v/0"))
        assertEquals("https://dl.example.com/…", directLinkForLog("https://dl.example.com?sign=abc"))
    }

    @Test
    fun `bare host and non http addresses stay as they are`() {
        assertEquals("https://dl.example.com", directLinkForLog("https://dl.example.com"))
        assertEquals("file:///cache/a.mp4", directLinkForLog("file:///cache/a.mp4"))
        assertEquals("/storage/a.mkv", directLinkForLog("/storage/a.mkv"))
    }

    @Test
    fun `only byte range requests are shortened in the request log`() {
        val media = HttpRequestBuilder().apply {
            url("https://dl.example.com/a.mp4?auth_key=secret")
            header(HttpHeaders.Range, "bytes=0-65535")
        }
        assertEquals("https://dl.example.com/…", media.urlForLog())

        val api = HttpRequestBuilder().apply { url("https://api.example.com/search?q=abc") }
        assertEquals("https://api.example.com/search?q=abc", api.urlForLog())
    }
}
