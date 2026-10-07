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
