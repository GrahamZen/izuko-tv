/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryParserTest {
    private val html = """
        <html><body>
        <div class="list"><a href="/1">一</a><a href="/2">二</a></div>
        </body></html>
    """.trimIndent()

    /**
     * ROM 自带的 org.jsoup 会遮蔽 APK 里的那份 (issue #12), 只有换了包名的 jsoup 才躲得开.
     * 有人把 libs.jsoup 直接加回来时这里会挂.
     */
    @Test
    fun `jsoup is the relocated copy`() {
        assertTrue(Document::class.java.name.startsWith("me.him188.ani.shaded.jsoup."), Document::class.java.name)
    }

    @Test
    fun `selector parses and matches`() {
        val selected = Html.parse(html).select(QueryParser.parseSelector("div.list > a"))
        assertEquals(listOf("一", "二"), selected.map { it.text() })
    }

    @Test
    fun `invalid selector yields null`() {
        assertNull(QueryParser.parseSelectorOrNull("div["))
    }
}
