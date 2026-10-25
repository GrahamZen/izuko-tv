/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import me.him188.ani.datasources.api.matcher.WebViewConfig
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 写进日志的媒体与网页解析配置: 请求头、Cookie 只有名字, 值不出现.
 */
class LogSafeTextTest {
    @Test
    fun `media keeps the host and header names - drops the signed address and header values`() {
        val data = UriMediaData(
            "https://drive.example.com/f/file.mkv?auth_key=signed-key&token=signed-token",
            mapOf("Cookie" to "session=secret-value", "User-Agent" to "Agent/1.0"),
            MediaExtraFiles(listOf(Subtitle("https://drive.example.com/file.ass"))),
        )
        val text = data.toLogText()
        assertEquals("UriMediaData(uri=https://drive.example.com/…, headers=[Cookie, User-Agent], subtitles=1)", text)
        assertFalse("secret-value" in text || "signed-" in text)
    }

    @Test
    fun `local media keeps the whole path`() {
        assertEquals("UriMediaData(uri=file:///cache/a.mp4, headers=[])", UriMediaData("file:///cache/a.mp4").toLogText())
    }

    @Test
    fun `web view config keeps cookie names only`() {
        val text = WebViewConfig(listOf("PHPSESSID=abc123; path=/", "quality=1080")).toLogText()
        assertEquals("WebViewConfig(cookies=[PHPSESSID, quality])", text)
    }
}
