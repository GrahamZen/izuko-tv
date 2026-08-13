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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 反射兜底只在 ROM 自带 jsoup 遮蔽了 APK 内 jsoup 的设备上才会走到 (issue #12), 单测里跑不出那个环境,
 * 所以这里只保证兜底这条路本身是通的: 方法名/签名没被 jsoup 升级改掉, 异常也能原样还原.
 */
class QueryParserTest {
    private val html = """
        <html><body>
        <div class="list"><a href="/1">一</a><a href="/2">二</a></div>
        </body></html>
    """.trimIndent()

    @Test
    fun `reflective parse selects the same elements as direct parse`() {
        val document = Html.parse(html)
        val direct = document.select(QueryParser.parseSelector("div.list > a"))
        val reflective = document.select(QueryParser.parseSelectorReflectively("div.list > a"))
        assertEquals(2, reflective.size)
        assertEquals(direct.map { it.text() }, reflective.map { it.text() })
    }

    @Test
    fun `reflective parse rethrows jsoup exception instead of wrapping it`() {
        // jsoup 抛的 SelectorParseException 是 IllegalStateException 的子类, parseSelectorOrNull 靠这个接住.
        assertFailsWith<IllegalStateException> { QueryParser.parseSelectorReflectively("div[") }
        assertNull(QueryParser.parseSelectorOrNull("div["))
    }
}
