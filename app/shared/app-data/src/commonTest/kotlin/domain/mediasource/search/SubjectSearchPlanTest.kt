/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.domain.media.fetch.computeFallbackSearchKeywords
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 搜索计划 ([SubjectSearchPlan]): 搜什么词、站点上的条目是不是这一部. 名字取自 Bangumi 与系列关系图 (「继 怪怪守护神」276792 与第一季 192428),
 * 「怪怪守护神 第2季」这类是系列关系推出的季名 (见 `seasonNamesOf`).
 */
class SubjectSearchPlanTest {
    private val firstWord = KeywordStyle(onlyFirstWord = true, removeSpecial = true, primaryCount = 1)
    private val fullName = KeywordStyle(onlyFirstWord = false, removeSpecial = true, primaryCount = 1)

    private fun request(
        names: List<String>,
        ownNames: List<String> = names,
        otherNames: List<String> = emptyList(),
        seriesNames: List<String> = emptyList(),
        sortOffset: Int = 0,
    ): MediaFetchRequest = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNameCN = names.first(),
        subjectNames = names,
        episodeSort = EpisodeSort(1 + sortOffset),
        episodeName = "",
        episodeEp = EpisodeSort(1),
        episodes = (1..12).map { MediaFetchRequest.Episode(it.toString(), EpisodeSort(it + sortOffset), EpisodeSort(it)) },
        fallbackSearchKeywords = computeFallbackSearchKeywords(names, otherNames),
        nameHints = MediaFetchNameHints(ownNames = ownNames, seriesNames = seriesNames, otherWorkNames = otherNames),
    )

    private val tsuguTsugumomo = request(
        names = listOf("继 怪怪守护神", "継つぐもも", "つぐもも 第2期", "怪怪守护神 第2期"),
        ownNames = listOf("继 怪怪守护神", "継つぐもも", "つぐもも 第2期", "怪怪守护神 第2期", "怪怪守护神 第2季"),
        otherNames = listOf("怪怪守护神", "つぐもも", "怪怪守护神 第1季"),
        seriesNames = listOf("怪怪守护神", "つぐもも"),
    )

    private val tsugumomo = request(
        names = listOf("怪怪守护神", "つぐもも"),
        ownNames = listOf("怪怪守护神", "つぐもも", "怪怪守护神 第1季"),
        otherNames = listOf("继 怪怪守护神", "継つぐもも", "怪怪守护神 第2季"),
    )

    private fun SearchKeywords.keywords() = primary.map { it.keyword } to fallback.map { it.keyword }

    @Test
    fun `first word sources search the series name instead of a one character first word`() {
        val keywords = SubjectSearchPlan.of(tsuguTsugumomo).keywords(firstWord)
        // 首词「继」搜什么都中; 系列名的首词才是要的. 去掉空格的全名最后才试 (站点写「继怪怪守护神」)
        assertEquals(listOf("怪怪守护神") to listOf("つぐもも", "继怪怪守护神"), keywords.keywords())
        // 搜索缓存按名字记: 主关键词记在系列名下
        assertEquals("怪怪守护神", keywords.primary.single().name)
    }

    @Test
    fun `full name sources keep the name and try it without spaces last`() {
        val keywords = SubjectSearchPlan.of(tsuguTsugumomo).keywords(fullName)
        assertEquals(
            listOf("继 怪怪守护神") to listOf("つぐもも 第2期", "怪怪守护神 第2期", "怪怪守护神", "つぐもも", "继怪怪守护神"),
            keywords.keywords(),
        )
    }

    @Test
    fun `without a series name a weak first word gives way to the whole name`() {
        val keywords = SubjectSearchPlan.of(request(listOf("继 怪怪守护神"))).keywords(firstWord)
        assertEquals(listOf("继 怪怪守护神") to listOf("继怪怪守护神"), keywords.keywords())
    }

    @Test
    fun `a short name that is the whole name is still searched`() {
        assertEquals(listOf("K") to emptyList<String>(), SubjectSearchPlan.of(request(listOf("K"))).keywords(firstWord).keywords())
    }

    @Test
    fun `an ordinary first word is kept`() {
        val keywords = SubjectSearchPlan.of(request(listOf("药屋少女的呢喃 第三季"))).keywords(firstWord)
        assertEquals(listOf("药屋少女的呢喃"), keywords.primary.map { it.keyword })
    }

    @Test
    fun `a longer first segment is replaced by the series name and kept as fallback`() {
        val keywords = SubjectSearchPlan.of(
            request(listOf("某科学的超电磁炮T"), otherNames = listOf("某科学的超电磁炮"), seriesNames = listOf("某科学的超电磁炮")),
        ).keywords(firstWord)
        assertEquals(listOf("某科学的超电磁炮") to listOf("某科学的超电磁炮T"), keywords.keywords())
    }

    @Test
    fun `fallback keywords that collapse to the same site keyword are searched once`() {
        // 取首词: 「出包王女 第二季」「出包王女」都是「出包王女」; 「出包王女第2季」没有空格, 整个是关键词
        val names = listOf("更多 出包王女", "出包王女 第二季", "出包王女第2季")
        val keywords = SubjectSearchPlan.of(
            request(names, otherNames = listOf("出包王女"), seriesNames = listOf("出包王女")),
        ).keywords(firstWord)
        assertEquals(listOf("出包王女") to listOf("出包王女第2季", "更多", "更多出包王女"), keywords.keywords())
    }

    @Test
    fun `pages of the second season are told apart from the first season`() {
        val plan = SubjectSearchPlan.of(tsuguTsugumomo)
        assertEquals(SubjectMatch.OWN, plan.identify("怪怪守护神第二季"))
        assertEquals(SubjectMatch.OWN, plan.identify("继怪怪守护神"))
        assertEquals(SubjectMatch.OTHER, plan.identify("怪怪守护神第一季"))
        // 第一季的页面多半就叫系列名; 本条目的集不按系列内序号编, 选择器认不了它
        assertEquals(SubjectMatch.OTHER, plan.identify("怪怪守护神"))
        assertEquals(SubjectMatch.OTHER, plan.identify("继母的拖油瓶是我的前女友"))
    }

    @Test
    fun `the first season excludes pages of the second season`() {
        val plan = SubjectSearchPlan.of(tsugumomo)
        assertEquals(SubjectMatch.OWN, plan.identify("怪怪守护神"))
        assertEquals(SubjectMatch.OWN, plan.identify("怪怪守护神第一季"))
        assertEquals(SubjectMatch.OTHER, plan.identify("怪怪守护神第二季"))
        assertEquals(SubjectMatch.OTHER, plan.identify("继 怪怪守护神"))
    }

    @Test
    fun `a page of the whole series is opened when episodes are numbered across the series`() {
        val names = listOf("凡人修仙传 第四季")
        val plan = SubjectSearchPlan.of(request(names, otherNames = listOf("凡人修仙传"), sortOffset = 124))
        assertEquals(SubjectMatch.MAYBE, plan.identify("凡人修仙传"))
    }

    @Test
    fun `only pages that may be this subject are opened and own ones come first`() {
        val plan = SubjectSearchPlan.of(request(listOf("机动战士高达", "機動戦士ガンダム", "Mobile Suit Gundam")))
        val pages = listOf(
            "高达创元宇宙【日语】",
            "机动战士高达（机动战士敢达、机动战士钢弹）【日语】",
            "机动战士高达ZZ【日语】",
            "战鼎 动态漫画",
        )
        assertEquals(
            listOf("机动战士高达ZZ【日语】", "机动战士高达（机动战士敢达、机动战士钢弹）【日语】"),
            plan.pagesToOpen(pages, preferShorterName = true) { it },
        )
        assertEquals(
            listOf("机动战士高达（机动战士敢达、机动战士钢弹）【日语】", "机动战士高达ZZ【日语】"),
            plan.pagesToOpen(pages, preferShorterName = false) { it },
        )
    }

    @Test
    fun `sites matching titles literally search main titles then other writings subtitles and the series`() {
        val separation = request(
            names = listOf("死神 千年血战篇-诀别谭-", "BLEACH 千年血戦篇-訣別譚-", "死神 千年血战篇 诀别谭"),
            seriesNames = listOf("死神 千年血战篇", "死神"),
        )
        val keywords = SubjectSearchPlan.of(separation).keywords(KeywordStyle.literal(3), subtitles = listOf("诀别谭", "訣別譚"))
        // 主标题的比较写法相同的只留一个
        assertEquals(listOf("死神 千年血战篇-诀别谭", "BLEACH 千年血戦篇-訣別譚"), keywords.primary.map { it.keyword })
        // 同一个名字的另外写法紧跟着它试
        assertEquals(listOf("死神 千年血战篇 诀别谭", "死神千年血战篇诀别谭"), keywords.writingsOf(keywords.primary.first()).map { it.keyword })
        // 第一个副标题、最长的系列名、其余副标题
        assertEquals(listOf("诀别谭", "死神 千年血战篇", "訣別譚"), keywords.wider.map { it.keyword })
        assertEquals(listOf(KeywordKind.SUBTITLE, KeywordKind.SERIES, KeywordKind.SUBTITLE), keywords.wider.map { it.kind })
    }

    @Test
    fun `literal keywords drop main titles that are too short or already searched`() {
        val frieren = request(names = listOf("葬送的芙莉莲 第二季", "葬送のフリーレン 第2期", "葬送的芙莉莲", "A"), seriesNames = listOf("葬送的芙莉莲"))
        val keywords = SubjectSearchPlan.of(frieren).keywords(KeywordStyle.literal(8))
        assertEquals(listOf("葬送的芙莉莲", "葬送のフリーレン"), keywords.primary.map { it.keyword })
        // 系列名就是主标题, 不再搜
        assertEquals(emptyList(), keywords.wider.map { it.keyword })
    }

    @Test
    fun `literal keywords beyond the names must be distinctive`() {
        // 港台译名「夜曲」也是别的番的名字: 按标题原样找的站点不拿它搜
        val night = request(names = listOf("彻夜之歌 第二季", "よふかしのうた 第2期"))
            .copy(fallbackSearchKeywords = listOf("夜曲", "夜曲 第二季", "彻夜之歌 第二季"))
        val keywords = SubjectSearchPlan.of(night).keywords(KeywordStyle.literal(2))
        assertEquals(listOf("彻夜之歌", "よふかしのうた"), keywords.primary.map { it.keyword })
        assertEquals(emptyList(), keywords.wider.map { it.keyword })
    }

    @Test
    fun `live action pages are not this subject unless the subject itself is`() {
        val plan = SubjectSearchPlan.of(request(names = listOf("辉夜大小姐想让我告白～天才们的恋爱头脑战～", "辉夜大小姐想让我告白：天才们的恋爱头脑战")))
        assertEquals(SubjectMatch.OTHER, plan.identify("【2019日影】辉夜大小姐想让我告白：天才们的恋爱头脑战【平野紫耀】"))
        assertEquals(SubjectMatch.OWN, plan.identify("辉夜大小姐想让我告白：天才们的恋爱头脑战"))
        val liveAction = SubjectSearchPlan.of(request(names = listOf("进击的巨人 真人版")))
        assertEquals(SubjectMatch.OWN, liveAction.identify("进击的巨人 真人版"))
    }
}
