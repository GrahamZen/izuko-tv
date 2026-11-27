/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import me.him188.ani.app.domain.mediasource.search.TitleForms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 标题开头没有字幕组方括号的 BT 单集发布: 数据源把整个标题当字幕组, 集号要换掉 (见 TorrentReleaseGroup). */
class TorrentReleaseGroupTest {
    private val ramparts5 = "冰之城墙「氷の城壁」The Ramparts of Ice S02E05 1080p 日英双语-多国字幕"
    private val ramparts6 = "冰之城墙「氷の城壁」The Ramparts of Ice S02E06 1080p 日英双语-多国字幕"

    @Test
    fun `episode numbers in a group read from the whole title are masked`() {
        assertEquals("冰之城墙「氷の城壁」The Ramparts of Ice S02E* 1080p 日英双语-多国字幕", TorrentReleaseGroup.of(ramparts5))
        assertEquals(TorrentReleaseGroup.of(ramparts5), TorrentReleaseGroup.of(ramparts6))
        assertEquals("Title s02e* 1080p", TorrentReleaseGroup.of("Title s02e06v2 1080p"))
        assertEquals("Title EP* 1080p", TorrentReleaseGroup.of("Title EP06 1080p"))
        assertEquals("Title E* WEB-DL", TorrentReleaseGroup.of("Title E12 WEB-DL"))
        assertEquals("番 第*集 1080p", TorrentReleaseGroup.of("番 第06集 1080p"))
        assertEquals("番 第*話", TorrentReleaseGroup.of("番 第 6 話"))
        assertEquals("Title - * [1080p", TorrentReleaseGroup.of("Title - 06 [1080p"))
        assertEquals("番 [*", TorrentReleaseGroup.of("番 [06"))
        // 再换一遍不变
        assertEquals(TorrentReleaseGroup.of(ramparts5), TorrentReleaseGroup.of(TorrentReleaseGroup.of(ramparts5)))
    }

    @Test
    fun `group names and numbers without an episode marker stay as they are`() {
        for (group in listOf("SweetSub&LoliHouse", "桜都字幕组", "ANi", "EP1C", "Title 1080p 2025", "Title - 2nd Season", "Title S2 1080p", "日英双语-多国字幕")) {
            assertEquals(group, TorrentReleaseGroup.of(group))
        }
    }

    @Test
    fun `the label leaves the masked episode number out`() {
        assertEquals("冰之城墙「氷の城壁」The Ramparts of Ice S02 1080p 日英双语-多国字幕", TorrentReleaseGroup.label(TorrentReleaseGroup.of(ramparts5)))
        assertEquals("番 1080p", TorrentReleaseGroup.label(TorrentReleaseGroup.of("番 第06集 1080p")))
        assertEquals("Title 1080p", TorrentReleaseGroup.label(TorrentReleaseGroup.of("Title EP06 1080p")))
        assertEquals("SweetSub&LoliHouse", TorrentReleaseGroup.label("SweetSub&LoliHouse"))
    }

    @Test
    fun `a site-wide feed title of another episode is from the same release group`() {
        val group = TorrentReleaseGroup.of(ramparts5)
        assertTrue(TorrentReleaseGroup.mentionedIn(group, TitleForms.compact(ramparts6)))
        assertFalse(TorrentReleaseGroup.mentionedIn(group, TitleForms.compact("[桜都字幕组] 冰之城墙 / Koori no Jouheki [19][1080P][简繁内封]")))
        assertFalse(TorrentReleaseGroup.mentionedIn(group, TitleForms.compact("冰之城墙「氷の城壁」The Ramparts of Ice S01E06 1080p 日英双语-多国字幕")))
        assertTrue(TorrentReleaseGroup.mentionedIn("桜都字幕组", TitleForms.compact("[桜都字幕组] 冰之城墙 / Koori no Jouheki [19][1080P][简繁内封]")))
    }
}
