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
import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.separation
import me.him188.ani.app.domain.mediasource.clouddrive.DriveSeriesTestData.table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「系列名 + 副标题」的条目在系列里的叫法 ([DriveSeriesParts]), 与条目名搜不全时按副标题搜分享站.
 */
class DriveSeriesPartsTest {
    private fun parts(subjectId: Int) = DriveSeriesParts.of(subjectId, table)

    // region 叫法

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
    fun `series names and subtitles of the other parts are known`() {
        val conflict = assertNotNull(parts(457326))
        assertEquals(DriveNameParser.normalize("死神 千年血战篇"), conflict.seriesNames.first())
        assertTrue("诀别谭" in conflict.otherParts && "祸进谭" in conflict.otherParts)
    }

    @Test
    fun `subtitle pieces skip season words and keep hyphenated tokens whole`() {
        assertEquals(listOf("r=1-sinθ"), DriveSeriesParts.piecesOf("[r=1-sinθ]♡"))
        assertEquals(listOf("日本代表队"), DriveSeriesParts.piecesOf("VS. U-20 日本代表队"))
        assertEquals(listOf("浅き夢の暁"), DriveSeriesParts.piecesOf("Final SEASON ‐浅き夢の暁‐"))
        assertEquals(listOf("Arise from the Shadow"), DriveSeriesParts.piecesOf("Season 2 -Arise from the Shadow-"))
        assertEquals(emptyList(), DriveSeriesParts.piecesOf(" -piece"))
        assertEquals(emptyList(), DriveSeriesParts.piecesOf("第2部分"))
        for (season in listOf("サードシーズン", "シーズン2", "2nd STAGE", "2ndクール", "The Final", "シリーズ", "시즌2")) {
            assertEquals(emptyList(), DriveSeriesParts.piecesOf(season), season)
        }
        // 「第几」后面没跟着季、期的是副标题的一部分
        assertEquals(listOf("第二个夏天", "迈向晴空"), DriveSeriesParts.piecesOf("第二季 ～第二个夏天，迈向晴空～"))
    }

    @Test
    fun `re-edits named after an arc of the main series have no subtitle`() {
        val table = SeriesGraphTable.parse(
            listOf(
                "# bangumi-series-graph v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
                "975\tONE PIECE\t航海王\t1999-10-20\t1155\tTV\t\t\t518603\t\t",
                "518603\tONE PIECE 魚人島編 特別編集版\t航海王：鱼人岛篇特别编辑版\t2024-04-07\t21\tTV\t\t975\t\t\t",
            ).joinToString("\n").encodeToByteArray(),
        )!!
        assertNull(DriveSeriesParts.of(518603, table))
    }

    @Test
    fun `season and half season names have no subtitle`() {
        assertNull(parts(373267)) // 间谍过家家 第2部分
        assertNull(parts(331752)) // 进击的巨人 最终季 Part.2
        assertNull(parts(376739)) // 进击的巨人 最终季 完结篇 前篇
        assertNull(parts(422759)) // 鬼灭之刃 游郭篇 特別编集版
    }

    @Test
    fun `the first work and subjects outside the table have no subtitle`() {
        assertNull(parts(1600)) // 名字就是系列名
        assertNull(parts(302286))
        assertNull(parts(999999))
        // 第二季与第一季只差一个「?」: 副标题第一季也有, 不能拿来认是哪一季
        assertNull(parts(293049))
    }

    @Test
    fun `common prefix only breaks at separators on both sides`() {
        assertEquals(8, DriveSeriesParts.commonPrefixEnd("死神 千年血战篇-诀别谭-", "死神 千年血战篇"))
        assertEquals(10, DriveSeriesParts.commonPrefixEnd("辉夜大小姐想让我告白-超级浪漫-", "辉夜大小姐想让我告白～天才们的恋爱头脑战～"))
        assertEquals(0, DriveSeriesParts.commonPrefixEnd("出包王女Darkness", "出包王女"))
        // 同一个名字后面接着写的季号, 往回退了一大段: 不是系列名加副标题
        assertEquals(0, DriveSeriesParts.commonPrefixEnd("響け！ユーフォニアム3", "響け！ユーフォニアム2"))
        assertEquals(6, DriveSeriesParts.commonPrefixEnd("间谍过家家 第2部分", "间谍过家家 第二季"))
    }

    @Test
    fun `share titles found by the subtitle need the series name and this part`() {
        val separation = assertNotNull(parts(412916))
        assertTrue(separation.acceptsTitle("死神 千年血战篇  诀别谭+相克谭+死神全集 4K"))
        assertFalse(separation.acceptsTitle("死神 千年血战篇 -祸进谭-(2026)【更48集】【1080p】【附1-3季】"))
        assertFalse(separation.acceptsTitle("诀别谭"))
        assertTrue(assertNotNull(parts(317613)).acceptsTitle("辉夜大小姐想让我告白：究极浪漫 かぐや様は告らせたい-ウルトラロマンティック- (2022)"))
        assertTrue(assertNotNull(parts(322102)).acceptsTitle("鬼灭之刃：柱众会议·蝶屋敷篇"))
    }

    // endregion

    // region 条目名搜不全时按副标题搜

    private val links = DriveShareLinks(TestDrive.protocol)
    private val config = DriveShareSearchConfig(
        searchUrl = "https://site.test/search?wd={keyword}",
        itemsPath = "list",
        titlePath = "vod_name",
        linkPaths = listOf("vod_down_url"),
    )

    /** 分享 mix: 三部合在一起; 分享 other: 别的部; 分享 full: 诀别谭全 13 集. */
    private val shares = FakeShares(
        mutableMapOf(
            "mix" to mapOf(
                TestDrive.SHARE_ROOT to listOf(dir("a", "诀别谭"), dir("b", "相克谭"), dir("c", "死神 千年血战篇")),
                "a" to listOf(video("a1", "01.mp4"), video("a2", "02.mp4")),
                "b" to listOf(video("b27", "27.mp4")),
                "c" to listOf(video("c2", "02.mp4"), video("c15", "15.mp4")),
            ),
            "other" to mapOf(TestDrive.SHARE_ROOT to listOf(video("o1", "01.mp4"))),
            "full" to mapOf(TestDrive.SHARE_ROOT to (1..13).map { video("f$it", "${it.toString().padStart(2, '0')}.mp4") }),
        ),
        defaultTitle = { "打乱的标题" },
    )

    /**
     * 搜「诀别谭」给三部合在一起的分享与别的部的分享, 别的词什么也搜不到. [primaryShare] 不为 null 时条目名搜得到这个分享
     * (`mix`: 只有诀别谭的几集; `full`: 诀别谭全 13 集).
     */
    private fun engine(urls: MutableList<String>, primaryShare: String? = null) =
        DriveShareSearchEngine(config, shares, links, identities = DriveSubjectIdentities { DriveSubjectIdentity.of(it, table) }) { url ->
            val keyword = url.substringAfter("wd=").decodeURLQueryComponent()
            urls += keyword
            when {
                keyword == "诀别谭" -> """{"list":[
                    {"vod_name":"死神 千年血战篇  诀别谭+相克谭+死神全集 4K","vod_down_url":"https://share.drive.test/s/mix"},
                    {"vod_name":"死神 千年血战篇 -祸进谭-","vod_down_url":"https://share.drive.test/s/other"}]}"""
                primaryShare != null -> """{"list":[{"vod_name":"死神 千年血战篇-诀别谭-","vod_down_url":"https://share.drive.test/s/$primaryShare"}]}"""
                else -> """{"list":[]}"""
            }.encodeToByteArray()
        }

    @Test
    fun `a subject name that finds nothing falls back to the subtitle and keeps only this part`() = runTest {
        val urls = mutableListOf<String>()
        val matched = engine(urls).search(separation)
        // 「诀别谭」文件夹里的两集, 与合在一起的文件夹里只可能是诀别谭的第 15 集; 相克谭与分不清的 02 不要
        assertEquals(listOf("a1", "a2", "c15"), matched.map { it.file.fid }.sorted())
        // 别的部的分享不打开
        assertEquals(listOf("mix"), shares.opened.map { it.first })
        assertTrue("诀别谭" in urls)
    }

    @Test
    fun `no subtitle search when the subject name finds every episode`() = runTest {
        val urls = mutableListOf<String>()
        val matched = engine(urls, primaryShare = "full").search(separation)
        assertEquals(13, matched.size)
        assertEquals(1, urls.size)
    }

    @Test
    fun `the subtitle is searched too when the subject name misses episodes`() = runTest {
        // 条目名搜到的分享只有几集: 再按副标题搜, 打开过的分享不再打开
        val urls = mutableListOf<String>()
        val matched = engine(urls, primaryShare = "mix").search(separation)
        assertTrue("诀别谭" in urls)
        assertEquals(matched.size, matched.distinctBy { it.share.shareId to it.file.fid }.size)
        assertEquals(1, shares.opened.count { it.first == "mix" })
    }

    // endregion
}
