/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.mediasource.search.TitleForms
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriveTitleContinuationTest {
    private fun DriveTitleContinuation.continues(segment: String, vararg names: String) =
        continuesEvery(segment, names.map(TitleForms::compact))

    private val sao = DriveTitleContinuation(listOf("刀剑神域", "SAO", "Sword Art Online"))

    @Test
    fun `a name followed by another part of a title continues into that title`() {
        assertTrue(sao.continues("[Ygm&Kgm] Sword Art Online Alicization [01][Ma10p_2160p].mkv", "Sword Art Online"))
        assertTrue(sao.continues("刀剑神域 Extra Edition", "刀剑神域"))
        val titan = DriveTitleContinuation(listOf("进击的巨人", "Attack on Titan"))
        assertTrue(titan.continues("[SRENIX] Attack on Titan The Final Season - 01 [BD HEVC 2160P FLAC].mkv", "Attack on Titan"))
        assertTrue(titan.continues("Attack on Titan - The Final Chapters (Part 2).mkv", "Attack on Titan"))
        val bocchi = DriveTitleContinuation(listOf("孤独摇滚！", "Bocchi the Rock!"))
        assertTrue(bocchi.continues("[YE&Ygm] BOCCHI THE ROCK! Recap Part 1[Ma10p_2160p].mkv", "Bocchi the Rock!"))
        assertTrue(DriveTitleContinuation(listOf("CLANNAD")).continues("CLANNAD After Story", "CLANNAD"))
    }

    @Test
    fun `tags episode numbers and seasons end a title`() {
        assertFalse(sao.continues("[VCB-Studio] Sword Art Online [01][Ma10p_1080p].mkv", "Sword Art Online"))
        assertFalse(sao.continues("Sword Art Online II [01].mkv", "Sword Art Online"))
        assertFalse(sao.continues("Sword.Art.Online.S01E01.1080p.BluRay.mkv", "Sword Art Online"))
        assertFalse(sao.continues("Sword Art Online BDRip 1080p", "Sword Art Online"))
        assertFalse(sao.continues("刀剑神域S01.2012 (SAO+ALO)", "刀剑神域", "SAO"))
        // 写着季的按季认: 中文名后面常接着英文译名
        val oshi = DriveTitleContinuation(listOf("【我推的孩子】", "Oshi no Ko"))
        assertFalse(oshi.continues("我推的孩子.My.Favorite.Idol.s.Children.S01E04.2023.2160p.WEB-DL.mkv", "我推的孩子"))
        val nozaki = DriveTitleContinuation(listOf("Gekkan Shoujo Nozaki-kun"))
        assertFalse(nozaki.continues("[Kamigami] Gekkan Shoujo Nozaki-kun - 01 [BD 1920x1080].mkv", "Gekkan Shoujo Nozaki-kun"))
    }

    @Test
    fun `words of the own names do not continue a title`() {
        assertFalse(sao.continues("刀剑神域 Sword Art Online 1080P", "刀剑神域", "Sword Art Online"))
        assertFalse(sao.continues("刀剑神域 SAO", "刀剑神域", "SAO"))
        val frieren = DriveTitleContinuation(listOf("葬送的芙莉莲", "Sousou no Frieren"))
        assertFalse(frieren.continues("葬送的芙莉莲 Frieren 1080P", "葬送的芙莉莲"))
        // 太短、不拿来认名字的名字的词也算
        val flcl = DriveTitleContinuation(listOf("FLCL", "フリクリ", "特别的她"))
        assertFalse(flcl.continues("特别的她 FLCL (2000) 6集", "特别的她"))
    }

    @Test
    fun `one occurrence that ends the title is enough`() {
        val kaguya = DriveTitleContinuation(listOf("Kaguya-sama wa Kokurasetai", "Kaguya-sama wa Kokurasetai: Tensai-tachi no Ren`ai Zunousen"))
        val file = "[TUDO&Ygm] Kaguya-sama wa Kokurasetai Tensai-tachi no Renai Zunousen [01][Ma10p_2160p].mkv"
        assertFalse(kaguya.continues(file, "Kaguya-sama wa Kokurasetai", "Kaguya-sama wa Kokurasetai: Tensai-tachi no Ren`ai Zunousen"))
        // 原样找不到的名字不算接着
        assertFalse(sao.continues("刀劍神域 Alicization", "刀剑神域"))
    }

    @Test
    fun `a short name stands alone between digits tags and symbols`() {
        val air = DriveTitleContinuation(listOf("AIR"))
        assertTrue(air.standsAlone("AIR 2005 TV全集", "air"))
        assertTrue(air.standsAlone("[KTXP][AIR][01][BIG5].mkv", "air"))
        val monster = DriveTitleContinuation(listOf("怪物", "MONSTER"))
        assertTrue(monster.standsAlone("2004怪物 全74集", "怪物"))
        assertTrue(monster.standsAlone("怪物全集 国日双语", "怪物"))
        assertTrue(DriveTitleContinuation(listOf("虫师")).standsAlone("虫师第一季", "虫师"))
        assertTrue(DriveTitleContinuation(listOf("间谍过家家")).standsAlone("日漫间谍过家家", "间谍过家家"))
    }

    @Test
    fun `a short name inside a longer title does not stand alone`() {
        val air = DriveTitleContinuation(listOf("AIR"))
        assertFalse(air.standsAlone("AIR GEAR 飞轮少年", "air"))
        assertFalse(air.standsAlone("Fairy Tail", "air"))
        val monster = DriveTitleContinuation(listOf("怪物", "MONSTER"))
        assertFalse(monster.standsAlone("怪物事变 第一季", "怪物"))
        assertFalse(monster.standsAlone("怪物：丽兹·波顿的故事", "怪物"))
        assertFalse(monster.standsAlone("Monster Hunter Stories", "monster"))
        assertFalse(DriveTitleContinuation(listOf("玲音")).standsAlone("谷江玲音 写真", "玲音"))
    }

    @Test
    fun `a short name next to another name of the subject stands alone`() {
        assertTrue(DriveTitleContinuation(listOf("娜娜", "NANA－ナナ－")).standsAlone("NANA娜娜", "娜娜"))
        assertTrue(DriveTitleContinuation(listOf("物怪", "モノノ怪")).standsAlone("モノノ怪物怪", "物怪"))
        assertTrue(DriveTitleContinuation(listOf("人狼")).standsAlone("人狼一999", "人狼"))
        // 英文写的名字挨着汉字的不算
        assertFalse(DriveTitleContinuation(listOf("AIR")).standsAlone("杀死Air（91集）", "air"))
    }
}
