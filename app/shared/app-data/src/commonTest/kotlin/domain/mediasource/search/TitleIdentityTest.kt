/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSubjectMatcher
import me.him188.ani.app.domain.mediasource.clouddrive.video
import me.him188.ani.app.domain.mediasource.search.TitleIdentity.Naming
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 网盘上的名字点名了哪一部 ([TitleIdentity]). 标题按分享站的真实写法. 宿主单测不做繁简转换, 都用简体.
 */
class TitleIdentityTest {
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

    private fun request(subjectId: Int, vararg names: String, hints: MediaFetchNameHints = MediaFetchNameHints.Empty) = MediaFetchRequest(
        subjectId = subjectId.toString(),
        episodeId = "1",
        subjectNames = names.toList(),
        episodeSort = EpisodeSort(1),
        episodeName = "",
        nameHints = hints,
    )

    private val gundam = TitleIdentity.of(request(50, "机动战士高达", "機動戦士ガンダム", "机动战士高达0079"), table)

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
        // 发行年份不是开播年份
        assertFalse(gundam.namesOtherWork(listOf("[2023 BDRip] 机动战士高达")))
    }

    @Test
    fun `a later season keeps collections named after the first season`() {
        val frieren2 = TitleIdentity.of(request(515759, "葬送的芙莉莲 第二季", "葬送のフリーレン 第2期"), table)
        assertFalse(frieren2.rejectsTitle("葬送的芙莉莲(2023)1080p 两季全"))
        assertFalse(frieren2.rejectsTitle("葬送的芙莉莲 第二季 (2026) 1080P CR 全10集"))
        assertFalse(frieren2.rejectsTitle("葬送的芙莉莲"))
        assertTrue(frieren2.laterWork)
        assertFalse(gundam.laterWork)
    }

    @Test
    fun `titles of short dramas are rejected even when a name in them contains the subject name`() {
        val spy = TitleIdentity.of(request(329906, "间谍过家家", "SPY×FAMILY"), table)
        val digest = "9月3日短剧更新汇总（17）180.新婚夜和夫君间谍过家家（66集）181.骨哨声起（80集）"
        assertEquals(Naming.OWN, spy.namingOf(digest))
        assertTrue(spy.rejectsTitle(digest))
        assertFalse(spy.rejectsTitle("间谍过家家 TV+剧场版"))
    }

    @Test
    fun `another season is rejected unless the title also names this season`() {
        val spy3 = TitleIdentity.of(request(498378, "间谍过家家 第三季", "SPY×FAMILY Season 3"), table)
        assertTrue(spy3.rejectsTitle("间谍过家家第二季"))
        assertFalse(spy3.rejectsTitle("间谍过家家 第三季 附间谍过家家第二季"))
        assertFalse(spy3.rejectsTitle("间谍过家家 全3季.共50集+剧场版 简日特效字幕 2022-2025"))
        assertFalse(spy3.rejectsTitle("间谍过家家 第三季"))
    }

    @Test
    fun `the nearest folder that names a work decides`() {
        // 系列合集里按作品分的文件夹
        assertTrue(gundam.namesOtherWork(listOf("机动战士高达 全系列", "机动战士高达SEED")))
        assertFalse(gundam.namesOtherWork(listOf("机动战士高达SEED 附初代", "机动战士高达 TV版")))
    }

    @Test
    fun `a name written apart from a longer name of another work still names this work`() {
        // 最长的名字只压住同一处的短名字: 别处单独写着的本条目名字照样算
        assertEquals(Naming.SEVERAL, gundam.namingOf("机动战士高达 TV版 + 机动战士高达SEED"))
        assertFalse(gundam.rejectsTitle("机动战士高达 TV版 + 机动战士高达SEED"))
        assertEquals(Naming.OTHER, gundam.namingOf("机动战士高达SEED 全50集"))
    }

    @Test
    fun `names from the request hints count for a subject outside the table`() {
        val hints = MediaFetchNameHints(
            ownNames = listOf("某部番 第二季", "某部番2"),
            seriesNames = listOf("某部番"),
            otherWorkNames = listOf("某部番", "某部番 剧场版"),
        )
        val sequel = TitleIdentity.of(request(999, "某部番 第二季", hints = hints), table)
        assertEquals(Naming.OWN, sequel.namingOf("某部番2 更新至05"))
        assertEquals(Naming.OTHER, sequel.namingOf("某部番 剧场版"))
        // 系列名谁也不点名
        assertEquals(Naming.NONE, sequel.namingOf("某部番"))
        assertTrue(sequel.laterWork)
        assertEquals(true, sequel.mentionOf("某部番 全集"))
    }

    @Test
    fun `a subject not in the table rejects nothing by name`() {
        val unknown = TitleIdentity.of(request(999, "某部番"), table)
        assertEquals(emptyList(), unknown.otherWorkNames)
        assertFalse(unknown.rejectsTitle("某部番SEED"))
        assertFalse(unknown.laterWork)
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
        val matched = DriveSubjectMatcher.matchEpisodes(request, candidates, identity = gundam)
        assertEquals(listOf("tv01"), matched.map { it.file.fid })
        // 不给 (用户指定的文件夹) 时照旧
        assertEquals(2, DriveSubjectMatcher.matchEpisodes(request, candidates).size)
    }

    /** 全部动画的表: 名字短的条目, 与系列以外的同名、近名作品 (表里都没有前传续集) */
    private val animeTable = SeriesGraphTable.parse(
        listOf(
            "# bangumi-anime v1 max_id=600000 dump=dump-2026-10-20.210353Z.zip | 列: …",
            "234\tAIR\tAIR\t2005-01-06\t12\tTV\t\t\t\t\t\t",
            "2471\tエア・ギア\t飞轮少年\t2006-04-04\t25\tTV\t\t\t\t\t\tAir Gear",
            "9001\tBIRDIE WING -Golf Girls' Story-\t一杆青空\t2022-04-02\t13\tTV\t\t\t\t\t\t",
            "1229\tKanon\tKanon\t2002-01-31\t13\tTV\t\t\t\t\t\t雪之少女",
            "1230\tKanon\tKanon\t2006-10-05\t24\tTV\t\t\t\t\t\t雪之少女",
            "1111\tMONSTER\t怪物\t2004-04-07\t74\tTV\t\t\t\t\t\t",
            "9002\t怪物事変\t怪物事变\t2021-01-10\t12\tTV\t\t\t\t\t\tKemono Jihen",
            "9005\tモンスターハンター ストーリーズ RIDE ON\t怪物猎人物语\t2016-10-02\t75\tTV\t\t\t\t\t\tMonster Hunter Stories",
            "140001\tRe:ゼロから始める異世界生活\tRe：从零开始的异世界生活\t2016-04-04\t25\tTV\t\t\t\t\t\t",
            "9006\tRe:ゼロから始める異世界生活 新編集版\tRe：从零开始的异世界生活 新编集版\t2020-01-01\t13\tTV\t\t\t\t\t\t",
            "294993\t呪術廻戦\t咒术回战\t2020-10-03\t24\tTV\t\t\t\t\t\t",
            "9007\tヴァイオレット・エヴァーガーデン\t紫罗兰永恒花园\t2018-01-11\t13\tTV\t\t\t\t\t\t",
            "841\tパプリカ\t红辣椒\t2006-11-25\t1\t剧场版\t\t\t\t\t\t",
        ).joinToString("\n").encodeToByteArray(),
    )!!
    private val names = AnimeNameIndex.of(animeTable)

    @Test
    fun `a short name names this work only standing alone`() {
        val air = TitleIdentity.of(request(234, "AIR"), animeTable, names)
        assertEquals(Naming.OWN, air.namingOf("AIR 2005 TV全集"))
        assertEquals(Naming.OWN, air.namingOf("[KTXP][AIR][01][BIG5].mkv"))
        assertEquals(Naming.NONE, air.namingOf("杀死Air（91集）"))
        val monster = TitleIdentity.of(request(1111, "怪物", "MONSTER"), animeTable, names)
        assertEquals(Naming.OWN, monster.namingOf("2004怪物 全74集"))
        assertEquals(Naming.NONE, monster.namingOf("谷江怪物 写真"))
    }

    @Test
    fun `share titles found by a short name must name this work`() {
        val air = TitleIdentity.of(request(234, "AIR"), animeTable, names)
        assertTrue(air.rejectsTitle("杀死Air（91集）", keyword = "AIR"))
        assertFalse(air.rejectsTitle("AIR 2005 TV全集", keyword = "AIR"))
        assertFalse(air.rejectsTitle("杀死Air（91集）"))
    }

    @Test
    fun `known works outside the series are other works`() {
        val air = TitleIdentity.of(request(234, "AIR"), animeTable, names)
        assertEquals(Naming.OTHER, air.namingOf("一杆青空 全13集 1080P"))
        assertEquals(Naming.OTHER, air.namingOf("AIR GEAR 飞轮少年 全25集"))
        val monster = TitleIdentity.of(request(1111, "怪物", "MONSTER"), animeTable, names)
        assertEquals(Naming.OTHER, monster.namingOf("怪物事变 第一季"))
        assertEquals(Naming.OTHER, monster.namingOf("Monster Hunter Stories"))
        // 本条目的名字只出现在别的作品的名字里
        val reZero = TitleIdentity.of(request(140001, "Re：从零开始的异世界生活"), animeTable, names)
        assertEquals(Naming.OTHER, reZero.namingOf("Re：从零开始的异世界生活 新编集版 第01集.mp4"))
        assertEquals(Naming.OWN, reZero.namingOf("Re：从零开始的异世界生活 第01集.mp4"))
    }

    @Test
    fun `known works written with this work are collections of several`() {
        val jujutsu = TitleIdentity.of(request(294993, "咒术回战"), animeTable, names)
        assertEquals(Naming.SEVERAL, jujutsu.namingOf("咒术回战1-2季全+影版.8.8分 紫罗兰永恒花园·全集+剧场版"))
        assertEquals(Naming.OTHER, jujutsu.namingOf("紫罗兰永恒花园·全集+剧场版"))
        // 本条目也叫这个名字 (表里的别名) 的不算别的作品
        val kanon = TitleIdentity.of(request(1230, "Kanon"), animeTable, names)
        assertEquals(Naming.NONE, kanon.namingOf("雪之少女"))
        assertEquals(Naming.OWN, kanon.namingOf("Kanon（雪之少女）全24集"))
    }

    @Test
    fun `versions with the same name are told apart by year`() {
        val kanon = TitleIdentity.of(request(1230, "Kanon"), animeTable, names)
        assertEquals(Naming.OTHER, kanon.namingOf("Kanon [2002][伊藤尚往_东映动画][540p][DVDrip]"))
        assertTrue(kanon.namesOtherWork(listOf("Kanon（雪之少女）全集", "Kanon [2002][东映动画]", "[ReinForce] Kanon 06")))
        assertFalse(kanon.namesOtherWork(listOf("Kanon（雪之少女）全集", "Kanon [2006][京都动画]", "Kanon 2006 EP06")))
        assertFalse(kanon.namesOtherWork(listOf("Kanon（雪之少女）全集", "[ReinForce] Kanon 06")))
    }

    @Test
    fun `the first work is not named by its name with years far from its own`() {
        val paprika = TitleIdentity.of(request(841, "红辣椒"), animeTable, names)
        assertEquals(Naming.OTHER, paprika.namingOf("红辣椒俱乐部 (2019) 4K"))
        assertEquals(Naming.OWN, paprika.namingOf("红辣椒 (2006) 1080P"))
        // 上传、更新的日期不是年份
        assertEquals(Naming.OWN, paprika.namingOf("红辣椒【2025-04-02】"))
        // 点名这一部而没写年份的, 外层第一个点名这一部又写了年份的说了算
        assertTrue(paprika.namesOtherWork(listOf("红辣椒", "G 红辣椒 2024", "红辣椒.E01.HD1080P")))
        assertFalse(paprika.namesOtherWork(listOf("红辣椒 (2006)", "红辣椒.E01.HD1080P")))
        assertFalse(paprika.namesOtherWork(listOf("2024年新番", "红辣椒.E01.HD1080P")))
        assertFalse(paprika.namesOtherWork(listOf("G 红辣椒 2024", "红辣椒 2006 1080P")))
    }

    @Test
    fun `a folder of the first work dated like another work of the series is that work`() {
        assertTrue(gundam.datesOtherSeriesWork("04 机动战士高达ZZ（1986）全47集"))
        assertTrue(gundam.datesOtherSeriesWork("第4部 (1986)"))
        assertFalse(gundam.datesOtherSeriesWork("01 第一季（1979）全43集"))
        assertFalse(gundam.datesOtherSeriesWork("全系列 1979-2023"))
        assertFalse(gundam.datesOtherSeriesWork("更新于2002-10-05"))
    }

    @Test
    fun `without a table only the checks needing the table are skipped`() {
        val air = TitleIdentity.namesOnly(request(234, "青空", "AIR"))
        // 短名字要单独成词照查
        assertTrue(air.rejectsPageTitle("一杆青空"))
        assertTrue(air.rejectsPageTitle("Fairy Pawg Mother Episode 2"))
        assertFalse(air.rejectsPageTitle("【POPGO】AIR 全12话"))
        val monster = TitleIdentity.namesOnly(request(1111, "怪物", "MONSTER"))
        assertTrue(monster.rejectsPageTitle("怪物事变"))
        assertFalse(monster.rejectsPageTitle("怪物 MONSTER"))
        // 认得的别的作品要名字表: 有表时是几部的合集, 没表时不认
        val jujutsu = TitleIdentity.namesOnly(request(294993, "咒术回战"))
        assertFalse(jujutsu.rejectsPageTitle("紫罗兰永恒花园·全集+剧场版"))
    }

    @Test
    fun `page titles of known works or with a short name inside a longer word are not this work`() {
        val air = TitleIdentity.of(request(234, "AIR"), animeTable, names)
        assertTrue(air.rejectsPageTitle("Fairy Pawg Mother Episode 2"))
        assertTrue(air.rejectsPageTitle("【Dymy】飞轮少年 AIR GEAR【繁體】"))
        assertTrue(air.rejectsPageTitle("一杆青空"))
        assertFalse(air.rejectsPageTitle("AIR"))
        assertFalse(air.rejectsPageTitle("【POPGO】AIR 全12话"))
        val monster = TitleIdentity.of(request(1111, "怪物", "MONSTER"), animeTable, names)
        assertTrue(monster.rejectsPageTitle("怪物事变"))
        assertFalse(monster.rejectsPageTitle("怪物 MONSTER"))
        // 站点标的年份常不对, 网页不按年份认
        val paprika = TitleIdentity.of(request(841, "红辣椒"), animeTable, names)
        assertFalse(paprika.rejectsPageTitle("【2000新番】红辣椒"))
    }

    @Test
    fun `page titles of other works in the series are left to the season rules`() {
        // 季与拆分季的段由网页自己的认法判 (站点常把几段放在一页)
        assertFalse(gundam.rejectsPageTitle("机动战士高达SEED"))
    }
}
