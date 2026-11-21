/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import me.him188.ani.app.domain.mediasource.search.SubjectMatch
import me.him188.ani.app.domain.mediasource.search.SubjectSearchPlan
import me.him188.ani.app.domain.mediasource.search.isHostPage
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchHostWork
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 接在本篇 (TV 13 集) 后面编号的 OVA (第 14 集) 在本篇的页上 */
class HostWorkPagesTest {
    private val host = MediaFetchHostWork(
        names = listOf("全金属狂潮 The Second Raid", "全金属狂潮3", "Full Metal Panic! The Second Raid"),
        firstSort = 1,
        episodeCount = 13,
    )

    private fun episode(name: String, channel: String? = null) =
        WebSearchEpisodeInfo(channel = channel, name = name, episodeSortOrEp = EpisodeSort(name), playUrl = "https://example.com/$channel/$name")

    private fun page(vararg names: String, channel: String? = null) = names.map { episode(it, channel) }
    private fun main(count: Int, channel: String? = null) = (1..count).map { episode(it.toString().padStart(2, '0'), channel) }

    private fun query(count: Int = 1, hostWork: MediaFetchHostWork? = host) = SelectorSearchQuery(
        subjectName = "全金属狂潮", allSubjectNames = setOf("全金属狂潮 The Second Raid OVA 战队长悠闲的一天"),
        episodeSort = EpisodeSort(14), episodeEp = EpisodeSort(1), episodeName = null,
        subjectEpisodeCount = count, hostWork = hostWork,
    )

    private fun List<WebSearchEpisodeInfo>.sorts() = map { it.name to it.episodeSortOrEp }

    @Test
    fun `host page names`() {
        assertTrue(host.isHostPage("全金属狂潮3"))
        // 繁体页名转简体再比 (繁转简只在安卓上有, 这里不测)
        assertTrue(host.isHostPage("Full Metal Panic!  The Second Raid"))
        assertFalse(host.isHostPage("全金属狂潮"))
        assertFalse(host.isHostPage("全金属狂潮4"))
    }

    @Test
    fun `only the OVA after the main episodes is kept and numbered as this subject`() {
        val page = main(13) + episode("OVA")
        assertEquals(listOf("OVA" to EpisodeSort(14)), page.asEpisodesOf(query(), "全金属狂潮3").sorts())
        // 已经换过集号的再换一次不变
        val converted = page.asEpisodesOf(query(), "全金属狂潮3")
        assertEquals(converted, converted.asEpisodesOf(query(), "全金属狂潮3"))
        // 不是本篇的页、或请求没有本篇时照旧
        assertEquals(page, page.asEpisodesOf(query(), "全金属狂潮"))
        assertEquals(page, page.asEpisodesOf(query(hostWork = null), "全金属狂潮3"))
    }

    @Test
    fun `an episode numbered after the main episodes is this subject`() {
        assertEquals(listOf("14" to EpisodeSort(14)), (main(13) + episode("14")).asEpisodesOf(query(), "全金属狂潮3").sorts())
    }

    @Test
    fun `pages numbered beyond this subject or with ambiguous OVAs give nothing`() {
        // 编到 24 集: 装着后面的季, 第 14 集不一定是本条目
        assertEquals(emptyList(), (main(24) + episode("OVA")).asEpisodesOf(query(), "全金属狂潮3"))
        // 两集 OVA 对一集的本条目: 分不清是哪一集
        assertEquals(emptyList(), (main(13) + page("OVA1", "OVA2")).asEpisodesOf(query(), "全金属狂潮3"))
        // 两集的本条目按先后对上
        assertEquals(
            listOf("OVA1" to EpisodeSort(14), "OVA2" to EpisodeSort(15)),
            (main(13) + page("OVA1", "OVA2")).asEpisodesOf(query(count = 2), "全金属狂潮3").sorts(),
        )
    }

    @Test
    fun `side stories written as OAD SP or in words count too`() {
        for (label in listOf("OAD", "SP", "特别篇", "番外篇", "OVA 战队长悠闲的一天")) {
            assertEquals(listOf(label to EpisodeSort(14)), (main(13) + episode(label)).asEpisodesOf(query(), "全金属狂潮3").sorts(), label)
        }
        // 预告、片头片尾不算
        assertEquals(emptyList(), (main(13) + page("PV", "NCOP")).asEpisodesOf(query(), "全金属狂潮3"))
    }

    @Test
    fun `with several side stories the one named as this episode is it`() {
        val page = main(13) + page("OAD 前夜祭", "OVA 战队长悠闲的一天")
        val query = query().copy(episodeName = "战队长悠闲的一天")
        assertEquals(listOf("OVA 战队长悠闲的一天" to EpisodeSort(14)), page.asEpisodesOf(query, "全金属狂潮3").sorts())
        // 名字也对不上时不要
        assertEquals(emptyList(), (main(13) + page("OAD", "OVA")).asEpisodesOf(query, "全金属狂潮3"))
        // SP 与 OVA 混在一起时 OVA 是它
        assertEquals(listOf("OVA" to EpisodeSort(14)), (main(13) + page("SP", "OVA")).asEpisodesOf(query, "全金属狂潮3").sorts())
    }

    @Test
    fun `episodes numbered with gaps follow the actual sorts of this subject`() {
        // 本篇 13 集, 本条目在 Bangumi 上登了 14 15 17 18 21 这几集, 站点接着编到 21
        val query = query().copy(
            episodeSort = EpisodeSort(14),
            subjectEpisodeSorts = listOf(14, 15, 17, 18, 21, 1, 2, 4, 5, 8).map { EpisodeSort(it) }.toSet(),
            subjectEpisodeCount = 5,
        )
        assertEquals(listOf("14", "15", "17", "18", "21"), main(21).asEpisodesOf(query, "全金属狂潮3").map { it.name.trimStart('0') })
    }

    @Test
    fun `the label that the subject name adds to the host name is this subject`() {
        val host = MediaFetchHostWork(names = listOf("露蒂的玩具", "アスタロッテのおもちゃ!"), firstSort = 1, episodeCount = 12)
        val query = query(hostWork = host).copy(
            allSubjectNames = setOf("露蒂的玩具 EX", "アスタロッテのおもちゃ! EX"),
            episodeSort = EpisodeSort(13),
        )
        assertEquals(listOf("EX" to EpisodeSort(13)), (main(12) + episode("EX")).asEpisodesOf(query, "露蒂的玩具").sorts())
    }

    @Test
    fun `SP extras give way to the OAD when there are too many side stories`() {
        val host = MediaFetchHostWork(names = listOf("龙与虎"), firstSort = 1, episodeCount = 25)
        val query = query(hostWork = host).copy(allSubjectNames = setOf("龙与虎OVA 便当的精髓"), episodeSort = EpisodeSort(26))
        val page = main(25) + page("OAD", "SP01", "SP02", "SP03", "SP04")
        assertEquals(listOf("OAD" to EpisodeSort(26)), page.asEpisodesOf(query, "龙与虎").sorts())
    }

    @Test
    fun `each line is judged on its own`() {
        val page = main(13, "A") + episode("OVA", "A") + main(24, "B")
        assertEquals(listOf("OVA" to EpisodeSort(14)), page.asEpisodesOf(query(), "全金属狂潮3").sorts())
    }

    @Test
    fun `the search plan opens host pages`() {
        val request = MediaFetchRequest(
            subjectId = "2689",
            episodeId = "16990",
            subjectNameCN = "全金属狂潮 The Second Raid OVA 战队长悠闲的一天",
            subjectNames = listOf("全金属狂潮 The Second Raid OVA 战队长悠闲的一天"),
            episodeSort = EpisodeSort(14),
            episodeName = "战队长悠闲的一天",
            episodeEp = EpisodeSort(1),
        )
        assertEquals(SubjectMatch.OTHER, SubjectSearchPlan.of(request).identify("全金属狂潮3"))
        val withHost = request.copy(nameHints = MediaFetchNameHints(hostWork = host))
        assertEquals(SubjectMatch.MAYBE, SubjectSearchPlan.of(withHost).identify("全金属狂潮3"))
        assertEquals(SubjectMatch.OTHER, SubjectSearchPlan.of(withHost).identify("全金属狂潮"))
    }
}
