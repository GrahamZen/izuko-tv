/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 一段名字里写的作品名 ([TitleParts]). 标题按分享站、网盘的真实写法. 宿主单测不做繁简转换, 都用简体.
 */
class TitlePartsTest {
    @Test
    fun `tags around the title are stripped`() {
        assertEquals(setOf("进击的巨人"), TitleParts.namesIn("进击的巨人 第一季 全25集 1080P 简繁内封"))
        assertEquals(setOf("虫师"), TitleParts.namesIn("虫师 (2005) S01-S02 BDRip x265 10bit"))
        assertEquals(setOf("间谍过家家"), TitleParts.namesIn("日漫 间谍过家家 全季 4K 典藏版"))
    }

    @Test
    fun `bracketed names and names in another script are separate`() {
        assertEquals(setOf("雪之少女", "kanon"), TitleParts.namesIn("Kanon（雪之少女）"))
        assertEquals(setOf("怪物", "monster"), TitleParts.namesIn("怪物 Monster 全74集"))
        assertEquals(setOf("水星领航员暮色", "ariathecrepuscolo"), TitleParts.namesIn("水星领航员 暮色 ARIA The CREPUSCOLO"))
    }

    @Test
    fun `promotion text is dropped`() {
        assertEquals(setOf("怪物"), TitleParts.namesIn("资源名称：怪物 亮点：浦泽直树经典悬疑 #动漫#"))
    }

    @Test
    fun `category words inside a title are kept`() {
        assertEquals(setOf("电影少女"), TitleParts.namesIn("电影少女 全6集"))
        assertEquals(setOf("物语系列"), TitleParts.namesIn("物语系列"))
    }

    @Test
    fun `folder names that are only tags name no work`() {
        assertTrue(TitleParts.namesIn("Specials").isEmpty())
        assertTrue(TitleParts.namesIn("剧场版").isEmpty())
    }

    @Test
    fun `subtitle groups and episode numbers are not names`() {
        assertEquals(setOf("怪物"), TitleParts.namesIn("[动漫国字幕組] 怪物 第01话.mkv"))
        assertEquals(setOf("kanon"), TitleParts.namesIn("Kanon - 01v2 [1080p].mkv"))
        assertEquals(setOf("机动战士高达0079"), TitleParts.namesIn("机动战士高达0079 第三十集"))
    }
}
