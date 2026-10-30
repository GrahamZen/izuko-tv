/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormat
import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 名字里没有集号的一条只在条目只有一集或集数不明时算整部作品 (见 [matchingEpisodeSortFor]). */
class WebSearchWholeWorkEntryTest {
    private fun entry(name: String) = WebSearchEpisodeInfo(
        channel = null,
        name = name,
        episodeSortOrEp = SelectorChannelFormat.convertSpecialEpisodes(name, Regex("""第\s*(?<ep>.+)\s*[话集]""").find(name)?.groups?.get("ep")?.value),
        playUrl = "https://example.com/$name",
    )

    private fun List<WebSearchEpisodeInfo>.sortOf(name: String, subjectEpisodeCount: Int?) =
        matchingEpisodeSortOf(first { it.name == name }, EpisodeSort(1), EpisodeSort(1), "", subjectEpisodeCount)

    @Test
    fun `a page with a single quality label is the whole work only for single episode subjects`() {
        // 同名的 2019 年真人电影: 页面上只有一条「1080P」
        val movie = listOf(entry("1080P"))
        assertNull(movie.sortOf("1080P", subjectEpisodeCount = 12))
        assertEquals(EpisodeSort(1), movie.sortOf("1080P", subjectEpisodeCount = 1))
        assertEquals(EpisodeSort(1), movie.sortOf("1080P", subjectEpisodeCount = null))
        val titled = listOf(entry("辉夜大小姐想让我告白：天才们的恋爱头脑战 2019 1080P"))
        assertNull(titled.sortOf("辉夜大小姐想让我告白：天才们的恋爱头脑战 2019 1080P", subjectEpisodeCount = 12))
        val only = listOf(entry("正片"))
        assertNull(only.sortOf("正片", subjectEpisodeCount = 12))
        assertEquals(EpisodeSort(1), only.sortOf("正片", subjectEpisodeCount = 1))
    }

    @Test
    fun `the only OVA or SP on a page is the whole work for single episode subjects`() {
        // 「攻壳机动队SSS」页两条线路都只有一集「OVA」
        val sss = listOf(entry("OVA"), entry("OVA"))
        assertEquals(EpisodeSort(1), sss.sortOf("OVA", subjectEpisodeCount = 1))
        assertEquals(EpisodeSort(1), listOf(entry("SP")).sortOf("SP", subjectEpisodeCount = 1))
        assertEquals(EpisodeSort(1), listOf(entry("OAD1")).sortOf("OAD1", subjectEpisodeCount = null))
        // 多集的番, 或页上还有别的集: 仍是特别篇
        assertEquals(EpisodeSort("OVA"), sss.sortOf("OVA", subjectEpisodeCount = 12))
        assertEquals(EpisodeSort("OVA"), listOf(entry("OVA"), entry("SP")).sortOf("OVA", subjectEpisodeCount = 1))
    }

    @Test
    fun `trailers openings and a second OVA are not the whole work`() {
        for (name in listOf("PV", "OP", "ED", "MAD", "OVA2", "SP3")) {
            assertEquals(EpisodeSort(name), listOf(entry(name)).sortOf(name, subjectEpisodeCount = 1), name)
        }
    }

    @Test
    fun `the special being watched still matches itself`() {
        val page = listOf(entry("SP"))
        assertEquals(EpisodeSort("SP"), page.matchingEpisodeSortOf(page[0], EpisodeSort("SP"), null, "", subjectEpisodeCount = 1))
    }

    @Test
    fun `episodes with numbers keep their sorts in multi episode subjects`() {
        val page = listOf(entry("第01集 1080P"), entry("第一集 1080P"), entry("01"))
        assertEquals(EpisodeSort(1), page.sortOf("第01集 1080P", subjectEpisodeCount = 12))
        assertEquals(EpisodeSort(1), page.sortOf("第一集 1080P", subjectEpisodeCount = 12))
        assertEquals(EpisodeSort(1), page.sortOf("01", subjectEpisodeCount = 12))
    }
}
