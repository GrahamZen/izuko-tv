/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web.format

import me.him188.ani.utils.xml.Html
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 线路名正则与页面对不上时的回落, 见 [SelectorChannelFormatIndexGrouped.select].
 *
 * 实例: girigiri 常规季的线路名是 "简中12" (集数拼在后面), 订阅配置的正则因此要求结尾有数字;
 * 而剧场版页面只有 "简中", 整页一个都命不中, 原本会连剧集一起丢掉, 整个条目解析成 0 集.
 */
class SelectorChannelFormatIndexGroupedTest {
    private val config = SelectorChannelFormatIndexGrouped.Config(
        selectChannelNames = ".anthology-tab > .swiper-wrapper a",
        matchChannelName = """(?<ch>.+?)(\d+?)""", // 要求结尾有数字, 与 girigiri 订阅里的一致
        selectEpisodeLists = ".anthology-list-box",
        selectEpisodesFromList = "a",
        selectEpisodeLinksFromList = "",
        matchEpisodeSortFromName = """第\s*(?<ep>.+)\s*[话集]""",
    )

    private fun page(vararg tabsAndLists: Pair<String, List<String>>): String {
        val tabs = tabsAndLists.joinToString("") { (name, _) -> """<a href="#">$name</a>""" }
        val lists = tabsAndLists.joinToString("") { (_, episodes) ->
            val items = episodes.joinToString("") { """<a href="/play/$it/">$it</a>""" }
            """<div class="anthology-list-box">$items</div>"""
        }
        return """<html><body>
            <div class="anthology-tab"><div class="swiper-wrapper">$tabs</div></div>
            $lists
        </body></html>"""
    }

    private fun select(html: String) =
        SelectorChannelFormatIndexGrouped.select(Html.parse(html), "https://example.com", config)

    @Test
    fun `常规页面 线路名带集数时行为不变`() {
        val result = assertNotNull(select(page("简中12" to listOf("第01集", "第02集"))))
        assertEquals(listOf("简中"), result.channels)
        assertEquals(2, result.episodes.size)
        assertEquals("简中", result.episodes.first().channel)
    }

    @Test
    fun `剧场版页面 线路名不带集数时回落用原文本, 不再丢掉剧集`() {
        val result = assertNotNull(select(page("简中" to listOf("1080P"))))
        assertEquals(1, result.episodes.size, "线路名一个都没命中时应当回落, 而不是把剧集丢光")
        assertEquals("简中", result.episodes.single().channel)
        assertEquals("1080P", result.episodes.single().name)
    }

    @Test
    fun `部分命中时仍然筛掉不匹配的标签页`() {
        val result = assertNotNull(
            select(page("简中12" to listOf("第01集"), "预告" to listOf("花絮"))),
        )
        // "预告" 不匹配正则 ⇒ 维持原行为, 它那一条线路被筛掉
        assertEquals(1, result.episodes.size)
        assertEquals("简中", result.episodes.single().channel)
    }
}
