/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.richtext

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageUrlsTest {
    @Test
    fun `imgur page url becomes a direct link`() {
        // 用户贴的这种地址 GET 回来是 text_html, 直链才是 image_jpeg
        assertEquals("https://i.imgur.com/aHiG1wL.jpg", normalizeImageUrl("https://imgur.com/aHiG1wL"))
        assertEquals("https://i.imgur.com/aHiG1wL.jpg", normalizeImageUrl("http://imgur.com/aHiG1wL"))
        assertEquals("https://i.imgur.com/aHiG1wL.jpg", normalizeImageUrl("https://www.imgur.com/aHiG1wL"))
        assertEquals("https://i.imgur.com/aHiG1wL.jpg", normalizeImageUrl("https://m.imgur.com/aHiG1wL/"))
        assertEquals("https://i.imgur.com/aHiG1wL.jpg", normalizeImageUrl("https://imgur.com/gallery/aHiG1wL"))
        assertEquals("https://i.imgur.com/aHiG1wL.jpg", normalizeImageUrl("https://imgur.com/a/aHiG1wL"))
        // 前后的空白也要吃掉, 否则 URL 直接不合法
        assertEquals("https://i.imgur.com/aHiG1wL.jpg", normalizeImageUrl("  https://imgur.com/aHiG1wL\n"))
    }

    @Test
    fun `imgur direct link missing the extension gets one`() {
        assertEquals("https://i.imgur.com/aHiG1wL.jpg", normalizeImageUrl("https://i.imgur.com/aHiG1wL"))
    }

    @Test
    fun `already a direct link is left alone`() {
        for (url in
        listOf(
            "https://i.imgur.com/aHiG1wL.jpg",
            "https://i.imgur.com/aHiG1wL.png",
            "https://i.imgur.com/aHiG1wLh.gif",
            "https://lain.bgm.tv/pic/cover/l/12/34/5678_abc.jpg",
            "https://example.com/a.png",
        )) {
            assertEquals(url, normalizeImageUrl(url))
        }
    }

    @Test
    fun `unrecognized urls are left alone`() {
        // 不是 imgur 的、或者 id 明显不对的, 不要乱改 —— 改坏了本来能显示的图更糟
        assertEquals("https://imgur.com/", normalizeImageUrl("https://imgur.com/"))
        assertEquals("https://imgur.com/user/foo/avatar", normalizeImageUrl("https://imgur.com/user/foo/avatar"))
        assertEquals("", normalizeImageUrl(""))
    }
}
