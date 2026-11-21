/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 系统通知发哪些、撤哪些 ([planSystemNotices]) 与记录的存取.
 */
class TvSystemNoticesTest {
    private val kinds = setOf("feedback.reply")
    private fun c(id: String, kind: String = "feedback.reply", seen: Boolean = false) = SystemNoticeCandidate(id, id.substringBefore('@'), kind, seen)

    @Test
    fun `new unseen notices of listed kinds are posted in the background`() {
        val plan = planSystemNotices(listOf(c("a@1"), c("b@2", kind = "update"), c("c@3", seen = true)), emptyList(), kinds, postNew = true)
        assertEquals(listOf("a@1"), plan.post)
        assertEquals(listOf(SystemNoticeRecord("a@1", delivered = true)), plan.records)
    }

    @Test
    fun `notices seen while in the foreground are recorded without posting`() {
        val plan = planSystemNotices(listOf(c("a@1")), emptyList(), kinds, postNew = false)
        assertEquals(emptyList(), plan.post)
        assertEquals(listOf(SystemNoticeRecord("a@1", delivered = false)), plan.records)
        // 进了后台也不再发
        val later = planSystemNotices(listOf(c("a@1")), plan.records, kinds, postNew = true)
        assertEquals(emptyList(), later.post)
    }

    @Test
    fun `a newer time of the same notice is posted again`() {
        val records = listOf(SystemNoticeRecord("a@1", delivered = false))
        val plan = planSystemNotices(listOf(c("a@2")), records, kinds, postNew = true)
        assertEquals(listOf("a@2"), plan.post)
    }

    @Test
    fun `delivered notices are removed once seen or gone`() {
        val records = listOf(SystemNoticeRecord("a@1", delivered = true), SystemNoticeRecord("b@1", delivered = true))
        val plan = planSystemNotices(listOf(c("a@1", seen = true)), records, kinds, postNew = true)
        assertEquals(listOf("a@1", "b@1"), plan.remove)
        assertEquals(listOf(SystemNoticeRecord("a@1", false), SystemNoticeRecord("b@1", false)), plan.records)
        // 撤过的不再撤
        assertEquals(emptyList(), planSystemNotices(listOf(c("a@1", seen = true)), plan.records, kinds, postNew = true).remove)
    }

    @Test
    fun `records survive encoding`() {
        val records = listOf(SystemNoticeRecord("feedback:12@1700000000000", true), SystemNoticeRecord("x@1", false))
        assertEquals(records, decodeSystemNoticeRecords(encodeSystemNoticeRecords(records)))
        assertEquals(emptyList(), decodeSystemNoticeRecords(""))
    }
}
