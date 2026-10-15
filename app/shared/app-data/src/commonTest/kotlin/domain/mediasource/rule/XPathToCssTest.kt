/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import me.him188.ani.utils.xml.Html
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * AniBaka 规则里实际出现的 XPath (取自规则库) 换成 CSS, 以及换出来的选择器在页面上选中的元素.
 */
class XPathToCssTest {
    @Test
    fun `xpaths from the rule library become css`() {
        val cases = mapOf(
            """//div[contains(@class, "entry-content")]""" to """div[class*="entry-content"]""",
            """.//div[contains(@class, "wp-block-columns") and contains(@class, "is-not-stacked-on-mobile")]//a[contains(@class, "pbb-") and contains(text(), "集")]""" to
                """div[class*="wp-block-columns"][class*="is-not-stacked-on-mobile"] a[class*="pbb-"]:containsOwn(集)""",
            "//section" to "section",
            """.//a[contains(@href, "/play/")]""" to """a[href*="/play/"]""",
            """//a[contains(@class,"topic-list-item")]""" to """a[class*="topic-list-item"]""",
            "//*[@id='some_drama']/div" to """*[id="some_drama"] > div""",
            ".//p/span" to "p > span",
            ".//a" to "a",
            "/html/body/div[2]" to "html > body > div:nth-of-type(2)",
            "./a[@href][starts-with(@href, 'http')]" to """> a[href][href^="http"]""",
            "//li[contains(., '第1集')]" to "li:contains(第1集)",
        )
        for ((xpath, css) in cases) assertEquals(css, XPathToCss.convert(xpath), xpath)
    }

    @Test
    fun `other xpath forms are not converted`() {
        for (xpath in listOf("//a/@href", "//a | //b", "//div/following-sibling::a", "//div[last()]", "//a[@id='x' or @id='y']", "//div/", "//a[contains(text(), 'a(b')]", "")) {
            assertNull(XPathToCss.convert(xpath), xpath)
        }
    }

    @Test
    fun `converted selectors pick the same elements`() {
        val document = Html.parse(
            """
            <div class="entry-content">
              <div class="wp-block-columns is-not-stacked-on-mobile">
                <a class="pbb-1" href="/ep/1">第1集</a><a class="pbb-2" href="/ep/2">第2集</a><a class="pbb-3" href="/pv">预告</a>
              </div>
              <div class="wp-block-columns"><a class="pbb-4" href="/ep/9">第9集</a></div>
            </div>
            """.trimIndent(),
            "https://www.example.com/",
        )
        val road = document.select(XPathToCss.convert("""//div[contains(@class, "entry-content")]""")!!).single()
        val items = road.select(
            XPathToCss.convert(
                """.//div[contains(@class, "wp-block-columns") and contains(@class, "is-not-stacked-on-mobile")]//a[contains(@class, "pbb-") and contains(text(), "集")]""",
            )!!,
        )
        assertEquals(listOf("第1集", "第2集"), items.map { it.text() })
        // 相对的子元素 (`./`): 只认直接在里面的
        val columns = road.select("div.is-not-stacked-on-mobile").first()!!
        assertEquals(3, columns.select(XPathToCss.convert("./a")!!).size)
        assertEquals(0, road.select(XPathToCss.convert("./a")!!).size)
    }
}
