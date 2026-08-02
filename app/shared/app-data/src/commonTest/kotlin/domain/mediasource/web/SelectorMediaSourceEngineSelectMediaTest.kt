/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 测试 [SelectorMediaSourceEngine.selectMedia] 把剧集转成 media 时使用的集号, 以及
 * [SelectorSearchConfig.filterByEpisodeSort] 的过滤结果.
 *
 * 用例取自实际复现的条目: hanime1.me 的条目页并列两部单集作品 (Bangumi 554879 住在隔壁的她 /
 * 554880 被玷污的她, 两者都只有一集, sort 均为 01), 页面上每条的集号位置写的是作品名,
 * 于是集号解析不出来, 开着 filterByEpisodeSort 时两部作品都搜不到资源.
 */
class SelectorMediaSourceEngineSelectMediaTest {
    // selectMedia 不发请求, client 只是构造 engine 用
    private val engine = DefaultSelectorMediaSourceEngine(
        HttpClient(MockEngine { respond("") }).asScopedHttpClient(),
    )

    /** 站点把作品名写在集号位置 */
    private fun titleAsSort(title: String) = WebSearchEpisodeInfo(
        channel = "あんてきぬすっ",
        name = title,
        episodeSortOrEp = EpisodeSort(title),
        playUrl = "https://hanime1.me/watch?v=$title",
    )

    /** 站点只标画质/语言 */
    private fun labeled(label: String) = WebSearchEpisodeInfo(
        channel = "线路1",
        name = label,
        episodeSortOrEp = EpisodeSort(label),
        playUrl = "https://example.com/$label",
    )

    private fun numbered(sort: Int) = WebSearchEpisodeInfo(
        channel = "线路1",
        name = "第0${sort}集",
        episodeSortOrEp = EpisodeSort(sort),
        playUrl = "https://example.com/$sort",
    )

    private fun selectMedia(
        episodes: List<WebSearchEpisodeInfo>,
        episodeName: String?,
        episodeSort: EpisodeSort = EpisodeSort(1),
    ) = engine.selectMedia(
        episodes.asSequence(),
        SelectorSearchConfig.Empty,
        SelectorSearchQuery(
            subjectName = TONARI,
            allSubjectNames = setOf(TONARI),
            episodeSort = episodeSort,
            episodeEp = episodeSort,
            episodeName = episodeName,
        ),
        mediaSourceId = "test",
        subjectName = TONARI,
    )

    @Test
    fun `matches the episode whose name is written in the sort position`() {
        val page = listOf(titleAsSort(TONARI), titleAsSort(YOGORETA))

        val yogoreta = selectMedia(page, episodeName = YOGORETA)
        assertEquals(1, yogoreta.filteredList.size)
        assertEquals(YOGORETA, yogoreta.filteredList.single().properties.episodeName)
        assertEquals(EpisodeRange.single(EpisodeSort(1)), yogoreta.filteredList.single().episodeRange)

        val tonari = selectMedia(page, episodeName = TONARI)
        assertEquals(1, tonari.filteredList.size)
        assertEquals(TONARI, tonari.filteredList.single().properties.episodeName)
    }

    @Test
    fun `does not match another work listed on the same page`() {
        val page = listOf(titleAsSort(TONARI), titleAsSort(YOGORETA))
        val result = selectMedia(page, episodeName = "毫不相干的作品")
        assertEquals(2, result.originalList.size)
        assertEquals(emptyList(), result.filteredList)
    }

    @Test
    fun `parsed sorts are not affected by the episode name`() {
        val page = listOf(numbered(1), numbered(2), numbered(3))
        val result = selectMedia(page, episodeName = TONARI, episodeSort = EpisodeSort(2))
        assertEquals(1, result.filteredList.size)
        assertEquals("第02集", result.filteredList.single().properties.episodeName)
    }

    @Test
    fun `unparsed sorts without a name match are still dropped when the page has several`() {
        // 多条且都解析不出集号、又都对不上剧集名: 仍然全部丢掉 ("只有一条"那条判据不适用)
        val result = selectMedia(listOf(titleAsSort(TONARI), titleAsSort(YOGORETA)), episodeName = null)
        assertEquals(2, result.originalList.size)
        assertEquals(emptyList(), result.filteredList)
    }

    /**
     * 剧场版的条目页把同一部作品的不同配音列成多条, 名称只有画质与语言, 不含集号.
     * 实测 https://www.yinghua2.com 上的「铃芽之旅」: 9 条线路里 5 条是这种命名.
     */
    @Test
    fun `whole work labels are matched as episode 01`() {
        val page = listOf(labeled("HD高清国语版"), labeled("HD高清原声版"))
        val result = selectMedia(page, episodeName = null)
        assertEquals(2, result.filteredList.size)
        assertEquals(
            listOf(EpisodeRange.single(EpisodeSort(1)), EpisodeRange.single(EpisodeSort(1))),
            result.filteredList.map { it.episodeRange },
        )
    }

    @Test
    fun `whole work labels are not matched for other episodes`() {
        val result = selectMedia(listOf(labeled("HD中字")), episodeName = null, episodeSort = EpisodeSort(5))
        assertEquals(1, result.originalList.size)
        assertEquals(emptyList(), result.filteredList)
    }

    @Test
    fun `a label carrying more than quality and language is not a whole work`() {
        // "剧场版01" 的集号在字符串里 (要靠站点自己的集号正则), "全集" 是整季合集, 都不能当第 1 集
        val page = listOf(labeled("剧场版01"), labeled("全集"), labeled("铃芽之旅（普通话版）"))
        assertEquals(emptyList(), selectMedia(page, episodeName = null).filteredList)
    }

    /**
     * 条目页只有一条时它就是整部作品. 实测 https://dm1.xfdm.pro/bangumi/1978.html (铃芽之旅)
     * 只有一条名为「剧场版」的线路: 集号解析不出来, 名称也不是纯画质/语言标签.
     */
    @Test
    fun `the only episode on the page is the whole work`() {
        val result = selectMedia(listOf(labeled("剧场版")), episodeName = null)
        assertEquals(1, result.filteredList.size)
        assertEquals(EpisodeRange.single(EpisodeSort(1)), result.filteredList.single().episodeRange)
    }

    @Test
    fun `the only episode on the page is not matched for other episodes`() {
        val result = selectMedia(listOf(labeled("剧场版")), episodeName = null, episodeSort = EpisodeSort(5))
        assertEquals(emptyList(), result.filteredList)
    }

    @Test
    fun `an unparsed episode among many is still dropped`() {
        // 多条时不适用"只有一条"这条判据: 「剧场版01」要靠站点自己的集号正则
        val page = listOf(labeled("剧场版01"), labeled("剧场版02"), labeled("剧场版03"))
        assertEquals(emptyList(), selectMedia(page, episodeName = null).filteredList)
    }

    private companion object {
        private const val TONARI = "住在隔壁的她"
        private const val YOGORETA = "被玷污的她"
    }
}
