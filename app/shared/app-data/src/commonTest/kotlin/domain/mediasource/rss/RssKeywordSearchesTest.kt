/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rss

import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.mediasource.directapi.Transform
import me.him188.ani.app.domain.mediasource.directapi.TransformOp
import kotlin.test.Test
import kotlin.test.assertEquals

class RssKeywordSearchesTest {
    /** 源配置里写的样子: 两边不都是字母数字的「-」换成空格, 再收拢空白 */
    private val dashRuleConfig = Json.decodeFromString<RssSearchConfig>(
        """
        {
          "searchUrl": "https://example.com/rss.xml?keyword={keyword}",
          "searchKeywordTransforms": [
            {"op": "regexReplace", "value": "(?<![A-Za-z0-9])-|-(?![A-Za-z0-9])", "replacement": " "},
            {"op": "regexReplace", "value": "\\s+", "replacement": " "},
            {"op": "trim"}
          ]
        }
        """,
    )

    @Test
    fun `keywords are sent as they are without configured transforms`() {
        val config = RssSearchConfig(searchUrl = "https://example.com/rss.xml?keyword={keyword}")
        assertEquals("BLEACH: Thousand-Year Blood War - The Calamity", config.searchKeywordOf("BLEACH: Thousand-Year Blood War - The Calamity"))
    }

    @Test
    fun `configured transforms rewrite the keyword`() {
        assertEquals(3, dashRuleConfig.searchKeywordTransforms.size)
        assertEquals("BLEACH: Thousand-Year Blood War The Calamity", dashRuleConfig.searchKeywordOf("BLEACH: Thousand-Year Blood War - The Calamity"))
        assertEquals("死神 千年血战篇 祸进谭", dashRuleConfig.searchKeywordOf("死神 千年血战篇-祸进谭-"))
        assertEquals("Re:ZERO Starting Life in Another World", dashRuleConfig.searchKeywordOf("Re:ZERO -Starting Life in Another World-"))
        assertEquals("孤独摇滚！", dashRuleConfig.searchKeywordOf("孤独摇滚！"))
    }

    @Test
    fun `a transform that fails keeps the original keyword`() {
        val broken = RssSearchConfig(searchKeywordTransforms = listOf(Transform(TransformOp.RegexReplace, value = "(")))
        assertEquals("a - b", broken.searchKeywordOf("a - b"))
        val emptied = RssSearchConfig(searchKeywordTransforms = listOf(Transform(TransformOp.RegexReplace, value = ".+")))
        assertEquals("a - b", emptied.searchKeywordOf("a - b"))
    }
}
