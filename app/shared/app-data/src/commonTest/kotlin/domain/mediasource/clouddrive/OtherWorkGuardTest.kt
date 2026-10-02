/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 同系列别的作品 ([OtherWorkGuard]): 标题、文件夹路径里含有系列关系图里一部更长的已知番名时不是这一部. 标题按分享站的真实写法.
 * 宿主单测不做繁简转换, 都用简体.
 */
class OtherWorkGuardTest {
    private val table = SeriesGraphTable.parse(
        listOf(
            "# bangumi-series-graph v1 max_id=600000 dump=dump-2026-10-13.210353Z.zip | 列: …",
            "50\t機動戦士ガンダム\t机动战士高达\t1979-04-07\t43\tTV\t\t\t4213",
            "1860\t機動戦士ガンダムSEED\t机动战士高达SEED\t2002-10-05\t50\tTV\t\t\t1861",
            "1861\t機動戦士ガンダムSEED DESTINY\t机动战士高达SEED DESTINY\t2004-10-09\t50\tTV\t\t1860\t",
            "4213\t機動戦士ガンダムΖΖ\t机动战士高达ZZ\t1986-03-01\t47\tTV\t\t50\t",
            "349441\t機動戦士ガンダム 水星の魔女\t机动战士高达 水星的魔女\t2022-10-02\t12\tTV\t\t\t403238",
            "403238\t機動戦士ガンダム 水星の魔女 Season2\t机动战士高达 水星的魔女 第二季\t2023-04-09\t12\tTV\t\t349441\t",
            "400602\t葬送のフリーレン\t葬送的芙莉莲\t2023-09-29\t28\tTV\t\t\t515759",
            "515759\t葬送のフリーレン 第2期\t葬送的芙莉莲 第二季\t2026-01-16\t10\tTV\t\t400602\t",
            "411427\tSPY×FAMILY Season 2\t间谍过家家 第二季\t2023-10-07\t12\tTV\t\t\t498378",
            "498378\tSPY×FAMILY Season 3\t间谍过家家 第三季\t2025-10-04\t13\tTV\t\t411427\t",
        ).joinToString("\n").encodeToByteArray(),
    )!!

    private fun request(subjectId: Int, vararg names: String) = MediaFetchRequest(
        subjectId = subjectId.toString(),
        episodeId = "1",
        subjectNames = names.toList(),
        episodeSort = EpisodeSort(1),
        episodeName = "",
    )

    private val gundam = OtherWorkGuard.of(request(50, "机动战士高达", "機動戦士ガンダム", "机动战士高达0079"), table)

    @Test
    fun `finds the entries whose names contain a fragment`() {
        assertEquals(listOf(50, 1860, 1861, 4213, 349441, 403238), table.nodesContaining("机动战士高达").map { it.id })
        assertEquals(listOf(1860, 1861), table.nodesContaining("ガンダムSEED").map { it.id })
        assertEquals(emptyList(), table.nodesContaining("不存在的番名").map { it.id })
        // 日期、id 列不算
        assertEquals(emptyList(), table.nodesContaining("1979").map { it.id })
    }

    @Test
    fun `other works of a franchise that starts with the subject name are rejected`() {
        assertTrue(gundam.rejectsTitle("机动战士高达SEED FREEDOM"))
        assertTrue(gundam.rejectsTitle("机动战士高达SEED DESTINY HD重制 (2013)"))
        assertTrue(gundam.rejectsTitle("机动战士高达 水星的魔女"))
        assertTrue(gundam.rejectsTitle("机动战士高达ZZ 機動戦士ガンダムΖΖ (1986)"))
        assertFalse(gundam.rejectsTitle("机动战士高达 TV版"))
        assertFalse(gundam.rejectsTitle("[网盘]机动战士高达："))
        assertFalse(gundam.rejectsTitle("机动战士高达0079 全43话"))
    }

    @Test
    fun `the first work of a series also rejects titles of other years`() {
        assertTrue(gundam.rejectsTitle("机动战士高达 [2025]  [更至12集] [动漫] [附相关全部]"))
        assertFalse(gundam.rejectsTitle("机动战士高达 (1979) 全43话"))
        assertFalse(gundam.rejectsTitle("机动战士高达 1980 BD"))
        // 重制版写的是重制那年
        assertFalse(gundam.rejectsTitle("机动战士高达 HD重制 2008"))
        // 路径只看名字, 不看年份
        assertFalse(gundam.isOtherWork("[2023 BDRip] 机动战士高达/01.mkv"))
    }

    @Test
    fun `a later season keeps collections named after the first season`() {
        val frieren2 = OtherWorkGuard.of(request(515759, "葬送的芙莉莲 第二季", "葬送のフリーレン 第2期"), table)
        assertFalse(frieren2.rejectsTitle("葬送的芙莉莲(2023)1080p 两季全"))
        assertFalse(frieren2.rejectsTitle("葬送的芙莉莲 第二季 (2026) 1080P CR 全10集"))
        assertFalse(frieren2.rejectsTitle("葬送的芙莉莲"))
    }

    @Test
    fun `another season is rejected unless the title also names this season`() {
        val spy3 = OtherWorkGuard.of(request(498378, "间谍过家家 第三季", "SPY×FAMILY Season 3"), table)
        assertTrue(spy3.rejectsTitle("间谍过家家第二季"))
        assertFalse(spy3.rejectsTitle("间谍过家家 第三季 附间谍过家家第二季"))
        assertFalse(spy3.rejectsTitle("间谍过家家 全3季.共50集+剧场版 简日特效字幕 2022-2025"))
        assertFalse(spy3.rejectsTitle("间谍过家家 第三季"))
    }

    @Test
    fun `a subject not in the table rejects nothing by name`() {
        val unknown = OtherWorkGuard.of(request(999, "某部番"), table)
        assertEquals(emptyList(), unknown.otherWorkNames)
        assertFalse(unknown.rejectsTitle("某部番SEED"))
    }

    @Test
    fun `files in folders of other works are dropped when matching episodes`() {
        val candidates = listOf(
            DriveSubjectMatcher.Candidate(
                video("seed01", "[机动战士高达SEED Destiny HD重制].Mobile.Suit.Gundam.SEED.Destiny.HD.Remaster.2013.S01E01.2160p.mp4"),
                listOf("机动战士高达SEED DESTINY HD重制 (2013)", "【动漫】机动战士高达SEED DESTINY HD重制 (2013)"),
            ),
            DriveSubjectMatcher.Candidate(video("tv01", "01.mkv"), listOf("机动战士高达 TV版")),
        )
        val request = request(50, "机动战士高达")
        val matched = DriveSubjectMatcher.matchEpisodes(request, candidates, otherWork = gundam)
        assertEquals(listOf("tv01"), matched.map { it.file.fid })
        // 不给 (用户指定的文件夹) 时照旧
        assertEquals(2, DriveSubjectMatcher.matchEpisodes(request, candidates).size)
    }
}
