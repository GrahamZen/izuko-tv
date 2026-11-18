/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.NoticeEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 通知气泡弹不弹、说哪几条 ([tvNoticesToBubble]; docs/contributing/code/notices.md 5.1). */
class TvNoticeBubbleTest {
    private object TestKind : TvNoticeKind {
        override val id: String = "test"
        override val category: TvNoticeCategory = TvNoticeCategory.Announcement
        override suspend fun title(notice: Notice): String = notice.key
        override fun destination(notice: Notice): TvNoticeDestination? = null
    }

    private fun shown(key: String, at: Long, seen: Boolean) =
        TvShownNotice(NoticeEntry(Notice(TestKind.id, key, at), pending = false, seen = seen), key, TestKind)

    @Test
    fun `nothing pops when every notice was seen`() {
        assertTrue(tvNoticesToBubble(listOf(shown("a", 1, seen = true)), emptySet()).isEmpty())
        assertTrue(tvNoticesToBubble(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun `unseen notices pop until this batch was handled`() {
        val list = listOf(shown("a", 2, seen = false), shown("b", 1, seen = false), shown("c", 0, seen = true))
        assertEquals(listOf("a", "b"), tvNoticesToBubble(list, emptySet()).map { it.key })
        val handled = list.filter { !it.entry.seen }.map(::tvNoticeBubbleKey).toSet()
        assertTrue(tvNoticesToBubble(list, handled).isEmpty())
    }

    @Test
    fun `a new notice or a later time brings back the whole unseen batch`() {
        val first = listOf(shown("a", 1, seen = false))
        val handled = first.map(::tvNoticeBubbleKey).toSet()
        val added = listOf(shown("b", 2, seen = false)) + first
        assertEquals(listOf("b", "a"), tvNoticesToBubble(added, handled).map { it.key })
        val later = listOf(shown("a", 5, seen = false))
        assertEquals(listOf("a"), tvNoticesToBubble(later, handled).map { it.key })
    }
}
