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

    private data class Case(
        val page: List<WebSearchEpisodeInfo>,
        val episodeSort: EpisodeSort,
        val episodeEp: EpisodeSort?,
        val episodeName: String?,
        val config: SelectorSearchConfig = SelectorSearchConfig.Empty,
    )

    private fun Case.query() = SelectorSearchQuery(
        subjectName = TONARI,
        allSubjectNames = setOf(TONARI),
        episodeSort = episodeSort,
        episodeEp = episodeEp,
        episodeName = episodeName,
    )

    /**
     * [SelectorMediaSourceEngine.selectFilteredMedia] 只是不为注定被滤掉的剧集创建对象, 结果必须与
     * [SelectorMediaSourceEngine.selectMedia] 的 filteredList 完全一致.
     */
    @Test
    fun `selectFilteredMedia gives the same result as the filtered list of selectMedia`() {
        val longPage = (1..40).map { numbered(it) } +
                (1..40).map { numbered(it).copy(channel = "线路2") } +
                listOf(labeled("HD高清国语版"), labeled("剧场版"), titleAsSort(TONARI))
        val cases = listOf(
            Case(longPage, EpisodeSort(25), episodeEp = null, episodeName = null),
            Case(longPage, EpisodeSort(30), episodeEp = EpisodeSort(5), episodeName = null),
            Case(longPage, EpisodeSort(1), episodeEp = EpisodeSort(1), episodeName = TONARI),
            Case(longPage, EpisodeSort(99), episodeEp = null, episodeName = null),
            Case(longPage, EpisodeSort("SP1"), episodeEp = null, episodeName = "SP"),
            Case(
                longPage, EpisodeSort(3), episodeEp = null, episodeName = null,
                config = SelectorSearchConfig.Empty.copy(filterByEpisodeSort = false),
            ),
            Case(listOf(labeled("剧场版")), EpisodeSort(1), episodeEp = null, episodeName = null),
            Case(listOf(titleAsSort(TONARI), titleAsSort(YOGORETA)), EpisodeSort(1), EpisodeSort(1), YOGORETA),
        )
        for (case in cases) {
            val expected = engine.selectMedia(case.page.asSequence(), case.config, case.query(), "test", TONARI)
            val actual = engine.selectFilteredMedia(case.page, case.config, case.query(), "test", TONARI)
            assertEquals(
                expected.filteredList.map { it.mediaId },
                actual.map { it.mediaId },
                "episodeSort=${case.episodeSort}, episodeEp=${case.episodeEp}, episodeName=${case.episodeName}",
            )
        }

        // 不是两边都空才相等: 长页面上请求第 25 集, 两条线路各一个
        assertEquals(2, engine.selectFilteredMedia(longPage, cases[0].config, cases[0].query(), "test", TONARI).size)
    }

    /**
     * 实测异世界动漫 / MiFun / 樱之空动漫: 剧集列表里混进了 `javascript:` 占位链接, 构造 WebVideo 时抛异常, 整个源作废.
     */
    @Test
    fun `episodes with non-http links are skipped instead of failing the whole page`() {
        val fake = numbered(1).copy(channel = "线路2", playUrl = "javascript://ios.mifun.org/voddetail/void(0)")
        val page = listOf(numbered(1), fake, numbered(2))
        val case = Case(page, EpisodeSort(1), episodeEp = EpisodeSort(1), episodeName = null)

        assertEquals(listOf("https://example.com/1"), selectMedia(page, episodeName = null).filteredList.map { it.originalUrl })
        assertEquals(
            listOf("https://example.com/1"),
            engine.selectFilteredMedia(page, case.config, case.query(), "test", TONARI).map { it.originalUrl },
        )
    }

    // region 条目级查询: 一次查询的产出要覆盖整个条目

    private fun sorts(range: IntRange) = range.mapTo(mutableSetOf()) { EpisodeSort(it) }

    /**
     * 播放页的查询会话是**按条目**复用的 (上游 #3442 «数据源查询改为条目级»): 切集只重建选择器, 不重新查询.
     * 所以一次查询的产出必须覆盖整个条目 —— 只给当次那一集的话, 切到别的集就一条都匹配不上,
     * 界面上是"在线源全部没有结果, 连正在加载都不出现" (2026-09-22 真机复现: 第 1 集正常, 切到第 7 集全灭).
     */
    @Test
    fun `条目级查询产出整个条目，切集之后仍能从同一批结果里拿到资源`() {
        val page = (1..12).map { numbered(it) }
        val case = Case(page, EpisodeSort(1), episodeEp = EpisodeSort(1), episodeName = null)

        val produced = engine.selectFilteredMedia(
            page, case.config, case.query().copy(subjectEpisodeSorts = sorts(1..12)), "test", TONARI,
        )

        assertEquals(12, produced.size)
        // 切到第 7 集时选择器要能从这一批里找到它
        assertEquals(
            1,
            produced.count { it.episodeRange == EpisodeRange.single(EpisodeSort(7)) },
        )
    }

    /** 源站条目页比条目本身长 (多出来的集号不属于这个条目) 时, 多出来的不产出. */
    @Test
    fun `条目之外的集号不产出`() {
        val page = (1..40).map { numbered(it) }
        val case = Case(page, EpisodeSort(1), episodeEp = EpisodeSort(1), episodeName = null)

        val produced = engine.selectFilteredMedia(
            page, case.config, case.query().copy(subjectEpisodeSorts = sorts(1..12)), "test", TONARI,
        )

        assertEquals(12, produced.size)
    }

    /** 放宽到整个条目, 不等于把同一页上的另一部作品也放进来. */
    @Test
    fun `条目级查询仍然挡住同一页上的另一部作品`() {
        val page = listOf(titleAsSort(TONARI), titleAsSort(YOGORETA))
        val case = Case(page, EpisodeSort(1), episodeEp = EpisodeSort(1), episodeName = "毫不相干的作品")

        val produced = engine.selectFilteredMedia(
            page, case.config, case.query().copy(subjectEpisodeSorts = sorts(1..1)), "test", TONARI,
        )

        assertEquals(emptyList(), produced)
    }

    /** 没有条目集号 (长番, 见 SelectorMediaSource.MAX_WHOLE_SUBJECT_EPISODES) 时维持老行为: 只产出当前这一集. */
    @Test
    fun `没有条目集号时只产出当前这一集`() {
        val page = (1..12).map { numbered(it) }
        val case = Case(page, EpisodeSort(7), episodeEp = EpisodeSort(7), episodeName = null)

        val produced = engine.selectFilteredMedia(page, case.config, case.query(), "test", TONARI)

        assertEquals(1, produced.size)
        assertEquals(EpisodeRange.single(EpisodeSort(7)), produced.single().episodeRange)
    }

    // endregion

    private companion object {
        private const val TONARI = "住在隔壁的她"
        private const val YOGORETA = "被玷污的她"
    }
}
