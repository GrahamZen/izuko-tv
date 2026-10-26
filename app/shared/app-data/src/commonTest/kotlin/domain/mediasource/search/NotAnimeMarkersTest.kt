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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotAnimeMarkersTest {
    @Test
    fun `category tags in brackets mark live action`() {
        assertTrue(NotAnimeMarkers.isLiveAction("【2019日影】辉夜大小姐想让我告白：天才们的恋爱头脑战【平野紫耀】"))
        assertTrue(NotAnimeMarkers.isLiveAction("【23年日剧】重启人生 another story 01-09"))
        assertTrue(NotAnimeMarkers.isLiveAction("【2024电影】怪物"))
        assertTrue(NotAnimeMarkers.isLiveAction("进击的巨人 真人版"))
        // 标题本身的字不算
        assertFalse(NotAnimeMarkers.isLiveAction("电影少女"))
        assertFalse(NotAnimeMarkers.isLiveAction("日影之下的约定"))
        assertFalse(NotAnimeMarkers.isLiveAction("辉夜大小姐想让我告白-初吻不会结束- 电影"))
        assertFalse(NotAnimeMarkers.isLiveAction("【剧场版】阿基拉（1988）"))
    }

    @Test
    fun `short dramas are marked anywhere`() {
        assertTrue(NotAnimeMarkers.isNotAnime("9月3日短剧更新汇总（17）180.新婚夜和夫君间谍过家家（66集）"))
        assertFalse(NotAnimeMarkers.isNotAnime("间谍过家家 第二季"))
    }
}
