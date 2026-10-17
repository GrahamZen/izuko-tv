/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.recommendation

import me.him188.ani.app.data.network.SubjectSearchFilters
import me.him188.ani.app.domain.search.SearchSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecommendationCatalogTest {
    private val manifest = RecommendationCatalogManifest(
        format = 1,
        generated = "2026-10-17T00:00:00Z",
        tags = listOf("恋爱", "战斗", "奇幻", "TV", "剧场版"),
        tagConfidence = 0.15,
        catalog = RecommendationCatalogManifest.Catalog(date = "2026-10-15", rows = 4, minVotes = 1000),
        static = listOf(file("static-0")),
        stats = file("stats"),
        recent = file("recent"),
        recentScope = RecommendationCatalogManifest.RecentScope(since = "2026-03-01", minCollection = 100, date = "2026-10-17"),
        recs = listOf(file("recs-0")),
    )

    private fun file(name: String) = RecommendationCatalogManifest.FileEntry("rec/$name.tsv", 0, "")

    // id, 中文名, 原名, 首播日, 集数, 公共标签, 封面
    private val static = listOf(
        "# bgm-rec-static v1",
        "10\t甲\tKou\t2008-04-06\t25\tTV,日本\taa/bb/10_x.jpg",
        "11\t乙\tOtsu\t2012-01-01\t12\tTV\t",
        "12\t丙\tHei\t2015-07-01\t1\t剧场版\tcc/dd/12_y.jpg",
        "13\t\t\t2019-10-01\t12\tTV\t",
    ).joinToString("\n")

    // id, 分数 x10, 评分人数, 排名, 想看/看过/在看/搁置/抛弃, 标签
    private val stats = listOf(
        "# bgm-rec-stats v1",
        "10\t83\t18000\t84\t100,20000,500,400,100\t0*,1",
        "11\t76\t5000\t900\t50,8000,100,50,30\t0*,2*",
        "12\t72\t1500\t1500\t10,3000,0,10,5\t1*,0",
        "13\t79\t1200\t400\t20,2500,30,20,10\t0*",
    ).joinToString("\n")

    // 同 static 各列, 再接 stats 除 id 外各列
    private val recent = listOf(
        "# bgm-rec-recent v1 since=2026-03-01",
        "20\t新番一\tShin1\t2026-07-02\t12\tTV\tee/ff/20_z.jpg\t0\t300\t0\t50,900,2000,10,40\t0*,1*",
        "21\t新番二\tShin2\t2026-10-01\t12\tTV\t\t0\t0\t0\t800,0,300,0,10\t2*",
        "22\t网络番\tWeb\t2026-10-03\t12\tWEB\t\t0\t0\t0\t100,0,50,0,0\t0*",
        "11\t乙\tOtsu\t2012-01-01\t12\tTV\t\t77\t5100\t880\t50,8100,100,50,30\t0*,2*",
    ).joinToString("\n")

    private val recs = """
        # bgm-rec-recs v1
        10	11,12
        11
        12	10,99
    """.trimIndent()

    private fun catalog(names: (Int) -> Pair<String, String>? = { if (it == 13) "Tei" to "丁" else null }) =
        RecommendationCatalog.build(manifest, listOf(static), stats, recent, listOf(recs), names)

    private fun RecommendationCatalog.ids(
        filters: SubjectSearchFilters,
        sort: SearchSort = SearchSort.RANK,
        offset: Int = 0,
        today: String = "2026-10-17",
    ): List<Int>? = search("", filters, sort, offset, 20, today)?.items?.map { it.subjectInfo.subjectId }

    private val ranked = SubjectSearchFilters(ranks = listOf(">=1"), ratingCounts = listOf(">=1000"), nsfw = false)

    @Test
    fun `ranked query filters by tag and score line and sorts by rank`() {
        val c = catalog()
        assertEquals(listOf(10, 13, 11, 12), c.ids(ranked.copy(tags = listOf("恋爱"))))
        assertEquals(listOf(10, 13, 11), c.ids(ranked.copy(tags = listOf("恋爱"), ratings = listOf(">=7.5"))))
        assertEquals(listOf(11, 12), c.ids(ranked.copy(ratings = listOf(">=7", "<7.8"))))
        assertEquals(listOf(10, 12), c.ids(ranked.copy(tags = listOf("恋爱", "战斗"))))
    }

    @Test
    fun `recent shard replaces catalog stats of the same subject`() {
        val details = catalog().search("", ranked.copy(ratings = listOf(">=7.7")), SearchSort.RANK, 0, 20, "2026-10-17")!!
        val otsu = details.items.single { it.subjectInfo.subjectId == 11 }
        assertEquals(880, otsu.subjectInfo.ratingInfo.rank)
        assertEquals(8100, otsu.subjectInfo.collectionStats.done)
    }

    @Test
    fun `names left empty come from the series graph and unknown ones are dropped`() {
        assertEquals("丁", catalog().search("", ranked.copy(tags = listOf("恋爱")), SearchSort.RANK, 0, 20, "2026-10-17")!!
            .items.single { it.subjectInfo.subjectId == 13 }.subjectInfo.nameCn)
        assertEquals(listOf(10, 11, 12), catalog(names = { null }).ids(ranked.copy(tags = listOf("恋爱"))))
    }

    @Test
    fun `confident tags keep their share against the top tag`() {
        val tags = catalog().search("", ranked, SearchSort.RANK, 0, 20, "2026-10-17")!!
            .items.single { it.subjectInfo.subjectId == 10 }.subjectInfo.tags
        val top = tags.maxOf { it.count }
        val confident = tags.filter { it.count.toDouble() / top >= 0.15 }.map { it.name }
        assertEquals(listOf("", "恋爱"), confident)
        assertEquals(setOf("", "恋爱", "战斗"), tags.mapTo(HashSet()) { it.name })
    }

    @Test
    fun `heat queries since a recent date sort by total collections`() {
        val c = catalog()
        val season = SubjectSearchFilters(airDates = listOf(">=2026-07-01"), metaTags = listOf("TV"), nsfw = false)
        assertEquals(listOf(20, 21), c.ids(season, SearchSort.COLLECTION))
        assertEquals(listOf(20, 21, 22), c.ids(season.copy(metaTags = null), SearchSort.COLLECTION))
    }

    @Test
    fun `classics heat query covers ranked works before a date`() {
        val classics = SubjectSearchFilters(ranks = listOf(">=1"), airDates = listOf("<2026-04-20"), nsfw = false)
        assertEquals(listOf(10, 11, 12, 13), catalog().ids(classics, SearchSort.COLLECTION))
    }

    @Test
    fun `queries outside the coverage or with stale data are not answered`() {
        val c = catalog()
        assertNull(c.search("keyword", ranked, SearchSort.RANK, 0, 20, "2026-10-17"))
        assertNull(c.ids(ranked.copy(tags = listOf("不在词表里"))))
        assertNull(c.ids(ranked.copy(ratingCounts = listOf(">=500"))))
        assertNull(c.ids(ranked.copy(nsfw = null)))
        assertNull(c.ids(ranked, SearchSort.MATCH))
        assertNull(c.ids(SubjectSearchFilters(airDates = listOf(">=2025-01-01"), nsfw = false), SearchSort.COLLECTION))
        assertNull(c.ids(SubjectSearchFilters(airDates = listOf(">=2026-07-01"), nsfw = false), SearchSort.RANK))
        assertNull(c.ids(ranked, today = "2026-11-01"))
        assertNull(c.ids(SubjectSearchFilters(airDates = listOf(">=2026-07-01"), nsfw = false), SearchSort.COLLECTION, today = "2026-10-25"))
    }

    @Test
    fun `paging stops at the hit cap like bangumi search`() {
        val rows = (1..1200).joinToString("\n") { "$it\tn$it\tn$it\t2010-01-01\t12\tTV\t" }
        val st = (1..1200).joinToString("\n") { "$it\t80\t2000\t$it\t1,1,1,1,1\t0*" }
        val c = RecommendationCatalog.build(manifest, listOf(rows), st, "", emptyList(), { null })
        val page = c.search("", ranked, SearchSort.RANK, 980, 20, "2026-10-17")!!
        assertEquals(RecommendationCatalog.HIT_CAP, page.total)
        assertEquals((981..1000).toList(), page.items.map { it.subjectInfo.subjectId })
        assertEquals(emptyList(), c.ids(ranked, offset = 1000))
    }

    @Test
    fun `related recommendations come from the catalog only when every target is known`() {
        val c = catalog()
        assertEquals(listOf(11, 12), c.recommendationItemsOf(10)!!.map { it.subjectId })
        assertEquals("https://lain.bgm.tv/pic/cover/l/cc/dd/12_y.jpg", c.recommendationItemsOf(10)!![1].imageUrl)
        assertEquals(emptyList(), c.recommendationItemsOf(11))
        assertNull(c.recommendationItemsOf(12))
        assertNull(c.recommendationItemsOf(20))
        assertEquals(listOf(11, 12), c.recsOf(10))
    }
}
