/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.DriveRememberedShare
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「系列名 + 副标题」的条目按副标题补搜 ([DriveSharePartSearch]). 系列关系图与分享标题取自真实数据 (2026-10).
 */
class DriveSharePartSearchTest {
    private val table = SeriesGraphTable.parse(
        listOf(
            "# bangumi-series-graph v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
            "1600\tBLEACH\t死神\t2004-10-05\t366\tTV\t\t\t302286\t\t",
            "302286\tBLEACH 千年血戦篇\t死神 千年血战篇\t2022-10-10\t13\tTV\t\t1600\t412916\t\t",
            "412916\tBLEACH 千年血戦篇-訣別譚-\t死神 千年血战篇-诀别谭-\t2023-07-08\t13\tTV\t\t302286\t457326\t14\t1",
            "457326\tBLEACH 千年血戦篇-相剋譚-\t死神 千年血战篇-相克谭-\t2024-10-05\t14\tTV\t\t412916\t530725\t27\t",
            "530725\tBLEACH 千年血戦篇-禍進譚-\t死神 千年血战篇-祸进谭-\t2026-07-25\t10\tTV\t\t457326\t\t41\t",
            "248175\tかぐや様は告らせたい～天才たちの恋愛頭脳戦～\t辉夜大小姐想让我告白～天才们的恋爱头脑战～\t2019-01-12\t12\tTV\t\t\t293049\t\t",
            "293049\tかぐや様は告らせたい?～天才たちの恋愛頭脳戦～\t辉夜大小姐想让我告白?～天才们的恋爱头脑战～\t2020-04-11\t12\tTV\t\t248175\t317613\t\t",
            "317613\tかぐや様は告らせたい-ウルトラロマンティック-\t辉夜大小姐想让我告白-超级浪漫-\t2022-04-08\t13\tTV\t\t293049\t389474,425211\t\t",
            "389474\tかぐや様は告らせたい-ファーストキッスは終わらない-\t辉夜大小姐想让我告白-初吻不会结束-\t2022-12-17\t1\t剧场版\t\t317613\t\t\t",
            "425211\tかぐや様は告らせたい-ファーストキッスは終わらない-\t辉夜大小姐想让我告白-初吻不会结束-\t2023-03-31\t4\tTV\t\t317613\t\t\t",
            "245665\t鬼滅の刃\t鬼灭之刃\t2019-04-06\t26\tTV\t\t\t350764\t\t",
            "317002\t鬼滅の刃 那田蜘蛛山編\t鬼灭之刃 那田蜘蛛山篇\t2020-10-17\t1\tTV\t\t\t322102\t\t",
            "322102\t鬼滅の刃 柱合会議・蝶屋敷編\t鬼灭之刃 柱合会议・蝶屋敷篇\t2020-12-20\t1\tTV\t\t317002\t350764\t\t",
            "350764\t鬼滅の刃 無限列車編\t鬼灭之刃 无限列车篇\t2021-10-10\t7\tTV\t\t245665,322102\t328195,422759\t\t",
            "328195\t鬼滅の刃 遊郭編\t鬼灭之刃 游郭篇\t2021-12-05\t11\tTV\t\t350764\t\t\t",
            "422759\t鬼滅の刃 遊郭編 特別編集版\t鬼灭之刃 游郭篇 特別编集版\t2023-04-01\t2\tTV\t\t350764\t\t\t",
            "390353\t俺だけレベルアップな件\t我独自升级\t2024-01-06\t12\tTV\t\t\t487630\t\t1",
            "487630\t俺だけレベルアップな件 Season 2 -Arise from the Shadow-\t我独自升级 第二季 -起于暗影-\t2025-01-04\t13\tTV\t\t390353\t\t13\t",
            "140001\tRe:ゼロから始める異世界生活\tRe：从零开始的异世界生活\t2016-04-03\t25\tTV\t\t\t278826\t\t",
            "278826\tRe:ゼロから始める異世界生活 2nd season\tRe：从零开始的异世界生活 第二季\t2020-07-08\t13\tTV\t\t140001\t425998\t26\t",
            "425998\tRe:ゼロから始める異世界生活 3rd season 襲擊編\tRe：从零开始的异世界生活 第三季 袭击篇\t2024-10-02\t8\tTV\t\t278826\t510728\t51\t",
            "510728\tRe:ゼロから始める異世界生活 3rd season 反擊編\tRe：从零开始的异世界生活 第三季 反击篇\t2025-02-05\t8\tTV\t\t425998\t\t59\t",
            "363102\tるろうに剣心 －明治剣客浪漫譚－\t浪客剑心 －明治剑客浪漫谭－\t2023-07-06\t24\tTV\t\t\t470599\t\t",
            "470599\tるろうに剣心 －明治剣客浪漫譚－ 京都動乱\t浪客剑心 －明治剑客浪漫谭－ 京都动乱\t2024-10-03\t23\tTV\t\t363102\t\t25\t",
            "329906\tSPY×FAMILY\t间谍过家家\t2022-04-09\t12\tTV\t\t\t373267\t\t",
            "373267\tSPY×FAMILY 第2クール\t间谍过家家 第2部分\t2022-10-01\t13\tTV\t\t329906\t\t13\t1",
            "285666\t進撃の巨人 The Final Season\t进击的巨人 最终季\t2020-12-06\t16\tTV\t\t\t331752\t60\t",
            "331752\t進撃の巨人 The Final Season Part.2\t进击的巨人 最终季 Part.2\t2022-01-09\t12\tTV\t\t285666\t376739\t76\t",
            "376739\t進撃の巨人 The Final Season 完結編 前編\t进击的巨人 最终季 完结篇 前篇\t2023-03-03\t1\tTV\t\t331752\t\t\t",
        ).joinToString("\n").encodeToByteArray(),
    )!!

    private fun parts(subjectId: Int) = DriveSharePartSearch.of(subjectId, table)

    /** 诀别谭 (sort 14..26, 本季序号 1..13) / 相克谭 (sort 27..40, 1..14) 的第 [ep] 集. */
    private fun request(subjectId: Int, names: List<String>, firstSort: Int, count: Int, ep: Int) = MediaFetchRequest(
        subjectId = subjectId.toString(),
        episodeId = "e$ep",
        subjectNames = names,
        episodeSort = EpisodeSort(firstSort + ep - 1),
        episodeEp = EpisodeSort(ep),
        episodeName = "",
        episodes = (1..count).map { MediaFetchRequest.Episode("e$it", EpisodeSort(firstSort + it - 1), EpisodeSort(it)) },
    )

    private val separation = request(412916, listOf("死神 千年血战篇-诀别谭-", "BLEACH 千年血戦篇-訣別譚-"), 14, 13, ep = 2)
    private val conflict = request(457326, listOf("死神 千年血战篇-相克谭-", "BLEACH 千年血戦篇-相剋譚-"), 27, 14, ep = 1)

    private fun match(siteTitle: String, folders: List<String>, fileName: String, episode: Int) =
        DriveShareMatch(FoundShare("s", "", siteTitle), video("f", fileName), folders, EpisodeSort(episode))

    // region 补搜什么

    @Test
    fun `the subtitle after the series name is searched alone`() {
        // 日文名的「訣別譚」(「別」不在繁简对照里) 当作另一个写法再搜: 繁体标题 (「千年血戰篇-訣別譚-」) 靠它
        assertEquals(listOf("诀别谭", "訣別譚"), parts(412916)?.keywords)
        // 中文名的副标题分享站上常换个译法 (「究极浪漫」), 再搜日文名的
        assertEquals(listOf("超级浪漫", "ウルトラロマンティック"), parts(317613)?.keywords)
        // 「・」隔开的两段分开搜: 分享站按整段找, 另一段换了译法 (「柱众会议」) 也搜得到
        assertEquals(listOf("柱合会议", "蝶屋敷篇"), parts(322102)?.keywords)
        // 同名的剧场版不算别的部, 副标题照样是这一部的
        assertEquals(listOf("初吻不会结束", "ファーストキッスは終わらない"), parts(425211)?.keywords)
    }

    @Test
    fun `subtitle pieces skip season words and keep hyphenated tokens whole`() {
        assertEquals(listOf("r=1-sinθ"), DriveSharePartSearch.piecesOf("[r=1-sinθ]♡"))
        assertEquals(listOf("日本代表队"), DriveSharePartSearch.piecesOf("VS. U-20 日本代表队"))
        assertEquals(listOf("浅き夢の暁"), DriveSharePartSearch.piecesOf("Final SEASON ‐浅き夢の暁‐"))
        assertEquals(listOf("Arise from the Shadow"), DriveSharePartSearch.piecesOf("Season 2 -Arise from the Shadow-"))
        assertEquals(emptyList(), DriveSharePartSearch.piecesOf(" -piece"))
        assertEquals(emptyList(), DriveSharePartSearch.piecesOf("第2部分"))
        for (season in listOf("サードシーズン", "シーズン2", "2nd STAGE", "2ndクール", "The Final", "シリーズ", "시즌2")) {
            assertEquals(emptyList(), DriveSharePartSearch.piecesOf(season), season)
        }
        // 「第几」后面没跟着季、期的是副标题的一部分
        assertEquals(listOf("第二个夏天", "迈向晴空"), DriveSharePartSearch.piecesOf("第二季 ～第二个夏天，迈向晴空～"))
    }

    @Test
    fun `re-edits named after an arc of the main series get no subtitle search`() {
        val table = SeriesGraphTable.parse(
            listOf(
                "# bangumi-series-graph v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "975\tONE PIECE\t航海王\t1999-10-20\t1155\tTV\t\t\t518603\t\t",
                "518603\tONE PIECE 魚人島編 特別編集版\t航海王：鱼人岛篇特别编辑版\t2024-04-07\t21\tTV\t\t975\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        )!!
        assertNull(DriveSharePartSearch.of(518603, table))
    }

    @Test
    fun `season and half season names are not searched by subtitle`() {
        assertNull(parts(373267)) // 间谍过家家 第2部分
        assertNull(parts(331752)) // 进击的巨人 最终季 Part.2
        assertNull(parts(376739)) // 进击的巨人 最终季 完结篇 前篇
        assertNull(parts(422759)) // 鬼灭之刃 游郭篇 特別编集版
    }

    @Test
    fun `the first work and subjects outside the table get no subtitle search`() {
        assertNull(parts(1600)) // 名字就是系列名
        assertNull(parts(302286))
        assertNull(parts(999999))
        // 第二季与第一季只差一个「?」: 副标题第一季也有, 不能拿来认是哪一季
        assertNull(parts(293049))
    }

    @Test
    fun `common prefix only breaks at separators on both sides`() {
        assertEquals(8, DriveSharePartSearch.commonPrefixEnd("死神 千年血战篇-诀别谭-", "死神 千年血战篇"))
        assertEquals(10, DriveSharePartSearch.commonPrefixEnd("辉夜大小姐想让我告白-超级浪漫-", "辉夜大小姐想让我告白～天才们的恋爱头脑战～"))
        assertEquals(0, DriveSharePartSearch.commonPrefixEnd("出包王女Darkness", "出包王女"))
        // 同一个名字后面接着写的季号, 往回退了一大段: 不是系列名加副标题
        assertEquals(0, DriveSharePartSearch.commonPrefixEnd("響け！ユーフォニアム3", "響け！ユーフォニアム2"))
        assertEquals(6, DriveSharePartSearch.commonPrefixEnd("间谍过家家 第2部分", "间谍过家家 第二季"))
    }

    // endregion

    // region 认分享标题

    @Test
    fun `share titles need the series name and this part`() {
        val separation = assertNotNull(parts(412916))
        assertTrue(separation.acceptsTitle("死神 千年血战篇  诀别谭+相克谭+死神全集 4K"))
        assertFalse(separation.acceptsTitle("死神 千年血战篇 -祸进谭-(2026)【更48集】【1080p】【附1-3季】"))
        assertFalse(separation.acceptsTitle("诀别谭"))
        assertTrue(assertNotNull(parts(317613)).acceptsTitle("辉夜大小姐想让我告白：究极浪漫 かぐや様は告らせたい-ウルトラロマンティック- (2022)"))
        assertTrue(assertNotNull(parts(322102)).acceptsTitle("鬼灭之刃：柱众会议·蝶屋敷篇"))
    }

    // endregion

    // region 认分享里的文件

    @Test
    fun `the nearest name that names a part decides`() {
        val parts = assertNotNull(parts(457326))
        val title = "死神 千年血战篇-相克谭-.更至28集【附全系列】"
        assertTrue(
            parts.keeps(
                match(title, listOf("S 死神 千年血战篇-相克谭-[2024][附系列]"), "[ANi] BLEACH 死神 千年血戰篇-相剋譚- - 27 [1080P].mp4", 27),
                conflict, null,
            ),
        )
        // 分享标题是这一部, 文件夹是别的部
        assertFalse(parts.keeps(match(title, listOf("S 死神 千年血战篇-祸进谭-"), "01.mp4", 1), conflict, null))
    }

    @Test
    fun `a share named after this part with earlier parts attached keeps only what is this part`() {
        val parts = assertNotNull(parts(530725))
        val calamity = request(530725, listOf("死神 千年血战篇-祸进谭-", "BLEACH 千年血戦篇-禍進譚-"), 41, 10, ep = 1)
        val title = "死神 千年血战篇 -祸进谭- BLEACH 千年血戦篇 -禍進譚- (2026) 4K/1080p 更新EP48【七月新番剧】"
        // 「1-40」里附的是前几部: 第 1 集是第一部分的
        assertFalse(parts.keeps(match(title, listOf("1-40"), "1080P H EP 01.mp4", 1), calamity, null))
        assertFalse(parts.keeps(match("死神千年血战篇-祸进谭-动漫", listOf("S01"), "S01E12.mp4", 1), calamity, null))
        // 文件名或没写编号的文件夹之上是这一部的标题: 照收
        assertTrue(parts.keeps(match(title, listOf("BLEACH 死神"), "[BeanSub&FZSD][BLEACH_Sennen_Kessen-hen][41][CHS][1080P].mp4", 41), calamity, null))
        assertTrue(parts.keeps(match(title, listOf("1-40"), "[ANi] BLEACH 死神 千年血戰篇-禍進譚- - 41 [1080P].mp4", 41), calamity, null))
        // 范围文件夹里跨部连续编号落在这一部的, 文件夹点了系列名才收: 附着的 2004 年 TV 版也从 1 编, 第 27 集与相克谭的连续编号对得上
        val conflictParts = assertNotNull(parts(457326))
        val conflictTitle = "死神 千年血战篇 -相克谭-"
        assertTrue(conflictParts.keeps(match(conflictTitle, listOf("死神 千年血战篇", "1-30"), "027.mkv", 27), conflict, null))
        assertFalse(
            conflictParts.keeps(match(conflictTitle, listOf(conflictTitle, "S.死神系列动漫 TV版和剧场版 国语 【完结】", "1-30"), "027.mkv", 27), conflict, null),
        )
    }

    @Test
    fun `a folder named after this part does not vouch for a season folder under it`() {
        val parts = assertNotNull(parts(470599))
        val kyoto = request(470599, listOf("浪客剑心 －明治剑客浪漫谭－ 京都动乱", "るろうに剣心 －明治剣客浪漫譚－ 京都動乱"), 25, 23, ep = 1)
        val title = "《浪客剑心 明治剑客浪漫谭 京都动乱第二季 更46》"
        val folder = "[内嵌简日][附第1季]（浪客剑心 明治剑客浪漫谭 京都动乱；第二季）るろうに剣心 －明治剣客浪漫譚－ 京都動乱(2024)[更至46／47集]"
        // 附着的第一季
        assertFalse(
            parts.keeps(
                match(title, listOf(folder, "[内嵌简日]第一季[24集全]"), "[orion origin] Rurouni Kenshin：Meiji Kenkaku Romantan [01] [1080p].mp4", 1),
                kyoto, null,
            ),
        )
        // 这一部自己的, 按跨部连续编号是第 25 集
        assertTrue(
            parts.keeps(match(title, listOf(folder), "[orion origin] Rurouni Kenshin：Meiji Kenkaku Romantan - Kyoto Douran [25] [1080p].mp4", 25), kyoto, null),
        )
    }

    @Test
    fun `a folder of the season named in this part alone does not separate numbering`() {
        val parts = assertNotNull(parts(487630))
        val second = request(487630, listOf("我独自升级 第二季 -起于暗影-", "俺だけレベルアップな件 Season 2 -Arise from the Shadow-"), 13, 13, ep = 1)
        val title = "我独自升级 第二季 -起于暗影- (2025) 1080P"
        assertTrue(parts.keeps(match(title, listOf("2025奇幻动画剧《我独自升级 第2季》"), "01.mp4", 1), second, null))
        // 别的季照样隔开
        assertFalse(parts.keeps(match(title, listOf("我独自升级 第1季"), "01.mp4", 1), second, null))
    }

    @Test
    fun `a season split into two parts keeps its season folder separating numbering`() {
        val parts = assertNotNull(parts(510728))
        val counterattack = request(510728, listOf("Re：从零开始的异世界生活 第三季 反击篇", "Re:ゼロから始める異世界生活 3rd season 反擊編"), 59, 8, ep = 1)
        // 「第3季」里袭击篇与反击篇都在, 01 是袭击篇的
        assertFalse(parts.keeps(match("Re：从零开始的异世界生活 第三季 反击篇", listOf("第3季"), "01.mp4", 1), counterattack, null))
        // 文件夹点了系列名、按跨部连续编号是反击篇的照收
        assertTrue(parts.keeps(match("Re：从零开始的异世界生活 第三季 反击篇", listOf("Re：从零开始的异世界生活 第三季"), "59.mp4", 59), counterattack, null))
    }

    @Test
    fun `in a share of several parts only numbers this part alone has are kept`() {
        val parts = assertNotNull(parts(412916))
        val title = "死神 千年血战篇  诀别谭+相克谭+死神全集 4K"
        // 跨部连续编号的第 15 集只可能是诀别谭的
        assertTrue(parts.keeps(match(title, listOf("死神 千年血战篇"), "15.mp4", 15), separation, null))
        // 「死神全集」是 2004 年 TV 版, 它的第 15 集不是
        assertFalse(parts.keeps(match(title, listOf("死神全集"), "15.mp4", 15), separation, null))
        // 从 01 编起的分不清是哪一部 (第一部分的第 2 集也叫 02)
        assertFalse(parts.keeps(match(title, listOf("死神血战千年篇 第二季"), "02.mp4", 2), separation, null))
        // TMDB 对应表对上的 S几E几 照用
        assertTrue(parts.keeps(match(title, listOf("Season 2"), "S02E03.mkv", 16), separation, mapOf((2 to 3) to EpisodeSort(16))))
    }

    // endregion

    // region 搜索时

    private val links = DriveShareLinks(TestDrive.protocol)
    private val config = DriveShareSearchConfig(
        searchUrl = "https://site.test/search?wd={keyword}",
        itemsPath = "list",
        titlePath = "vod_name",
        linkPaths = listOf("vod_down_url"),
    )

    /** 分享 mix: 三部合在一起; 分享 other: 别的部. */
    private val shares = FakeShares(
        mutableMapOf(
            "mix" to mapOf(
                TestDrive.SHARE_ROOT to listOf(dir("a", "诀别谭"), dir("b", "相克谭"), dir("c", "死神 千年血战篇")),
                "a" to listOf(video("a1", "01.mp4"), video("a2", "02.mp4")),
                "b" to listOf(video("b27", "27.mp4")),
                "c" to listOf(video("c2", "02.mp4"), video("c15", "15.mp4")),
            ),
            "other" to mapOf(TestDrive.SHARE_ROOT to listOf(video("o1", "01.mp4"))),
        ),
        defaultTitle = { "打乱的标题" },
    )

    /** 整个条目名搜不到, 搜「诀别谭」给三部合在一起的分享与别的部的分享. */
    private fun engine(urls: MutableList<String>, primaryFinds: Boolean = false) =
        DriveShareSearchEngine(config, shares, links, otherWorks = OtherWorks { OtherWorkGuard.of(it, table) }) { url ->
            val keyword = url.substringAfter("wd=").decodeURLQueryComponent()
            urls += keyword
            when {
                keyword == "诀别谭" -> """{"list":[
                    {"vod_name":"死神 千年血战篇  诀别谭+相克谭+死神全集 4K","vod_down_url":"https://share.drive.test/s/mix"},
                    {"vod_name":"死神 千年血战篇 -祸进谭-","vod_down_url":"https://share.drive.test/s/other"}]}"""
                primaryFinds -> """{"list":[{"vod_name":"死神 千年血战篇-诀别谭-","vod_down_url":"https://share.drive.test/s/mix"}]}"""
                else -> """{"list":[]}"""
            }.encodeToByteArray()
        }

    @Test
    fun `a subject name that finds nothing falls back to the subtitle and keeps only this part`() = runTest {
        val urls = mutableListOf<String>()
        val matched = engine(urls).search(separation)
        // 「诀别谭」文件夹里的两集, 与合在一起的文件夹里只可能是诀别谭的第 15 集; 相克谭与分不清的 02 不要
        assertEquals(listOf("a1", "a2", "c15"), matched.map { it.file.fid }.sorted())
        assertTrue(matched.all { it.partSearch })
        // 别的部的分享不打开
        assertEquals(listOf("mix"), shares.opened.map { it.first })
        assertEquals("诀别谭", urls.last())
    }

    @Test
    fun `no subtitle search when the subject name already finds episodes`() = runTest {
        val urls = mutableListOf<String>()
        val matched = engine(urls, primaryFinds = true).search(separation)
        assertTrue(matched.isNotEmpty())
        assertTrue(matched.none { it.partSearch })
        assertTrue("诀别谭" !in urls)
    }

    @Test
    fun `a remembered folder from the subtitle search still keeps only this part`() = runTest {
        val engine = engine(mutableListOf())
        val folder = DriveRememberedShare("mix", "", "死神 千年血战篇  诀别谭+相克谭+死神全集 4K", "c", listOf("死神 千年血战篇"), partSearch = true)
        assertEquals(listOf("c15"), engine.matchRemembered(separation, folder)?.map { it.file.fid })
        // 不是补搜来的照旧全认
        assertEquals(listOf("c15", "c2"), engine.matchRemembered(separation, folder.copy(partSearch = false))?.map { it.file.fid }?.sorted())
    }

    // endregion
}
