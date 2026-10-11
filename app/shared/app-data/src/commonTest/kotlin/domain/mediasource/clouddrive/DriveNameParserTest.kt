/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveNameParserTest {
    private fun assertEpisode(fileName: String, episode: Int?, season: Int? = null) {
        val parsed = DriveNameParser.parseFile(fileName)
        assertEquals(episode?.let { EpisodeSort(it) }, parsed.episode, "episode of $fileName")
        assertEquals(season, parsed.season, "season of $fileName")
        assertEquals(false, parsed.isExtra, "isExtra of $fileName")
    }

    @Test
    fun `season and episode code`() {
        assertEpisode("Smoking.Behind.the.Supermarket.With.You.S01E03.mp4", 3, season = 1)
        assertEpisode("S01E01.2160p.WEB-DL.x264.AAC.mkv", 1, season = 1)
        assertEpisode("Title.S02E05.1080p.mkv", 5, season = 2)
        assertEpisode("title s1e12 [1080p].mp4", 12, season = 1)
        // SxxEyy 之后的是画质与集名, 不当小数
        assertEpisode("S06E01.4K.mp4", 1, season = 6)
        assertEpisode("Re.ZERO.Starting.Life.in.Another.World.S04E06.Julius.Juukulius.1080p.CR.WEB-DL.mkv", 6, season = 4)
        assertEpisode("Title.S01E12.5.mkv", 12, season = 1)
    }

    @Test
    fun `fansub style names`() {
        assertEpisode("[LoliHouse] Sousou no Frieren - 03 [WebRip 1080p HEVC-10bit AAC][简繁内封字幕].mkv", 3)
        assertEpisode("[Nekomoe kissaten][Sousou no Frieren][12][1080p][CHS].mp4", 12)
        assertEpisode("Frieren - 05v2.mkv", 5)
    }

    @Test
    fun `chinese and ep names`() {
        assertEpisode("第12集.mp4", 12)
        assertEpisode("葬送的芙莉莲 第十二话.mkv", 12)
        assertEpisode("EP05.mkv", 5)
        assertEpisode("Ep.07 1080p.mp4", 7)
        assertEpisode("EP01.4K.mp4", 1)
        assertEquals(EpisodeSort("12.5"), DriveNameParser.parseFile("EP12.5.mp4").episode)
    }

    @Test
    fun `bare numbers`() {
        assertEpisode("01.mp4", 1)
        assertEpisode("x264 01 AAC.mp4", 1)
        assertEpisode("Title 2023 03.mkv", 3)
    }

    @Test
    fun `copy suffix of duplicate files`() {
        assertEpisode("02(1).mp4", 2)
        assertEpisode("13 (2).mp4", 13)
        assertEpisode("第05集（1）.mp4", 5)
        assertEpisode("01(1).mp4", 1)
        // 括号前没有别的数字时, 括号里的就是集号
        assertEpisode("Title (03).mp4", 3)
    }

    @Test
    fun `episode at the start of a bracket`() {
        assertEpisode("[001冒険の終わり][B站沸羊羊都得叫我师傅][葬送のフリーレン][2160P][Crunchyroll][4K臻享级画质].mkv", 1)
        assertEpisode("[011北側諸国の冬][B站沸羊羊都得叫我师傅][葬送のフリーレン][2160P][Crunchyroll][4K臻享级画质].mkv", 11)
        assertEpisode("【03 冒险结束】【1080P】.mp4", 3)
        // 分辨率、日期、范围不是集号
        assertEpisode("[Crunchyroll][2160P][10bit].mkv", null)
        assertEpisode("[24年10月][1080P].mp4", null)
        assertEpisode("[01-12][1080P].mkv", null)
    }

    @Test
    fun `no episode number`() {
        assertEpisode("Title 1080p.mp4", null)
        assertEpisode("云缨-神骥·越千峰 闪卡.mp4", null)
    }

    @Test
    fun extras() {
        for (name in listOf("NCOP.mkv", "[SweetSub] Title - NCED [1080P].mkv", "Title PV1.mp4", "Title 预告.mp4", "OP2.mkv")) {
            assertTrue(DriveNameParser.parseFile(name).isExtra, name)
        }
    }

    @Test
    fun `single declared season`() {
        assertEquals(1, DriveNameParser.singleDeclaredSeason("S01"))
        assertEquals(2, DriveNameParser.singleDeclaredSeason("Season 2"))
        assertEquals(3, DriveNameParser.singleDeclaredSeason("第三季"))
        assertEquals(2, DriveNameParser.singleDeclaredSeason("2nd Season"))
        assertEquals(3, DriveNameParser.singleDeclaredSeason("[4K][内封简繁]Re：从零开始的异世界生活 第三季 袭击篇[8集全]"))
        // 写了几个季、季号旁边挨着别的数字、只写「第一部」或标题末尾带数字的都拿不准
        assertNull(DriveNameParser.singleDeclaredSeason("ZSD芙莉莲 1-2季（更1）"))
        assertNull(DriveNameParser.singleDeclaredSeason("S1-S3"))
        assertNull(DriveNameParser.singleDeclaredSeason("Season 1 & 2"))
        assertNull(DriveNameParser.singleDeclaredSeason("第一季+第二季"))
        assertNull(DriveNameParser.singleDeclaredSeason("第三季 01-12"))
        assertNull(DriveNameParser.singleDeclaredSeason("第一部"))
        assertNull(DriveNameParser.singleDeclaredSeason("葬送的芙莉莲2"))
        assertNull(DriveNameParser.singleDeclaredSeason("Specials"))
    }

    @Test
    fun `declared seasons`() {
        assertEquals(setOf(3), DriveNameParser.declaredSeasons("无职转生：到了异世界就拿出真本事 第三季（2026）CR 1080p 内封简中 S03E01-E07."))
        assertEquals(setOf(3), DriveNameParser.declaredSeasons("无职转生Ⅲ 到了异世界就拿出真本事（2026）1080p NF S03 内封简繁 HiveWeb"))
        assertEquals(setOf(3), DriveNameParser.declaredSeasons("[豌豆字幕组][药屋少女的呢喃 / Kusuriya no Hitorigoto S3][01(49)][简体][1080P][MP4]"))
        assertEquals(setOf(2), DriveNameParser.declaredSeasons("Kusuriya no Hitorigoto 2nd Season - 01 [1080p]"))
        assertEquals(setOf(0, 3), DriveNameParser.declaredSeasons("无职转生～到了异世界就拿出真本事～ S0-S03"))
        // 分辨率、编码、「第2部分」与标题末尾的数字都不是季
        assertEquals(emptySet(), DriveNameParser.declaredSeasons("[喵萌奶茶屋][药屋少女的呢喃 / Kusuriya no Hitorigoto][49][1080p][HEVC-10bit AAC]"))
        assertEquals(emptySet(), DriveNameParser.declaredSeasons("药屋少女的呢喃 第2部分"))
        assertEquals(emptySet(), DriveNameParser.declaredSeasons("无职转生/到了异世界就拿出真本事动画1-2季+小说"))
        assertEquals(emptySet(), DriveNameParser.declaredSeasons("葬送的芙莉莲2"))
    }

    @Test
    fun `folder season`() {
        assertEquals(2, DriveNameParser.parseFolderSeason("Season 2"))
        assertEquals(2, DriveNameParser.parseFolderSeason("S02"))
        assertEquals(2, DriveNameParser.parseFolderSeason("第二季"))
        assertEquals(2, DriveNameParser.parseFolderSeason("葬送的芙莉莲 第2季"))
        assertEquals(2, DriveNameParser.parseFolderSeason("2nd Season"))
        assertEquals(2, DriveNameParser.parseFolderSeason("葬送的芙莉莲2"))
        assertEquals(1, DriveNameParser.parseFolderSeason("Smoking.Behind.the.Supermarket.With.You.S01"))
        assertNull(DriveNameParser.parseFolderSeason("4K"))
        assertNull(DriveNameParser.parseFolderSeason("1080P"))
        assertNull(DriveNameParser.parseFolderSeason("动漫"))
    }

    @Test
    fun `subject season`() {
        assertEquals(2, DriveNameParser.parseSubjectSeason("葬送的芙莉莲 第二季"))
        assertEquals(2, DriveNameParser.parseSubjectSeason("无职转生Ⅱ ～到了异世界就拿出真本事～"))
        assertEquals(3, DriveNameParser.parseSubjectSeason("关于我转生变成史莱姆这档事 第三季"))
        assertEquals(3, DriveNameParser.parseSubjectSeason("Re:ゼロから始める異世界生活 3rd season"))
        assertNull(DriveNameParser.parseSubjectSeason("86 -エイティシックス-"))
        assertNull(DriveNameParser.parseSubjectSeason("在超市后门吸烟的二人"))
    }

    @Test
    fun `base title`() {
        assertEquals("无职转生", DriveNameParser.baseTitle("无职转生 第三季 ～到了异世界就拿出真本事～"))
        assertEquals("葬送的芙莉莲", DriveNameParser.baseTitle("葬送的芙莉莲 第二季"))
        assertEquals(
            "Smoking Behind the Supermarket with You",
            DriveNameParser.baseTitle("Smoking Behind the Supermarket with You"),
        )
        assertEquals("Re:ゼロから始める異世界生活", DriveNameParser.baseTitle("Re:ゼロから始める異世界生活 3rd season"))
        assertEquals("无职转生", DriveNameParser.baseTitle("无职转生Ⅱ ～到了异世界就拿出真本事～"))
    }

    @Test
    fun normalize() {
        assertEquals(
            DriveNameParser.normalize("Smoking Behind the Supermarket with You"),
            DriveNameParser.normalize("Smoking.Behind.the.Supermarket.With.You"),
        )
    }

    @Test
    fun `chinese numbers`() {
        assertEquals(3, DriveNameParser.parseNumber("3"))
        assertEquals(10, DriveNameParser.parseNumber("十"))
        assertEquals(12, DriveNameParser.parseNumber("十二"))
        assertEquals(20, DriveNameParser.parseNumber("二十"))
        assertEquals(23, DriveNameParser.parseNumber("二十三"))
        assertNull(DriveNameParser.parseNumber("abc"))
    }
}
