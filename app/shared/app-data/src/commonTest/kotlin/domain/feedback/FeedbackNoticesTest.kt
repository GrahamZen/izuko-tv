/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.feedback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.feedback.FeedbackDevice
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.data.models.feedback.FeedbackReply
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.models.notice.NoticeLog
import me.him188.ani.app.data.network.FeedbackTicketService
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.notice.Notices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 反馈的通知: 有未读的单一张一条、新回复算新的、看过不消失; 「我的反馈」逐张标已读. */
class FeedbackNoticesTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private val notices = Notices(MemorySettings(NoticeLog.Empty), now = { NOW })

    private fun ticket(issue: Int, unread: Boolean, vararg replyTimes: String) = FeedbackRecord(
        kind = FeedbackKind.TICKET,
        issue = issue,
        summary = "ticket $issue",
        submittedAt = SUBMITTED,
        replies = replyTimes.map { FeedbackReply(fromMaintainer = true, at = it, body = "reply at $it") },
        unread = unread,
    )

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun `only unread tickets are posted and the time is the latest maintainer reply`() = runTest {
        FeedbackNotices.postUnread(notices, listOf(ticket(61, unread = true, REPLY_1), ticket(47, unread = false, REPLY_1)))
        val entry = notices.all.first().single()
        assertEquals("feedback:TICKET-61", entry.notice.key)
        assertEquals(millis(REPLY_1), entry.notice.atMillis)
        assertEquals("ticket 61", entry.notice.args[FeedbackNotices.ARG_TITLE])
        assertEquals("false", entry.notice.args[FeedbackNotices.ARG_DONE])
    }

    @Test
    fun `a seen notice stays after the ticket is read and a newer reply makes it new again`() = runTest {
        FeedbackNotices.postUnread(notices, listOf(ticket(61, unread = true, REPLY_1)))
        notices.markSeen(listOf(notices.all.first().single().notice))
        // 标成已读后不再发, 那条还在, 照旧是看过的
        FeedbackNotices.postUnread(notices, listOf(ticket(61, unread = false, REPLY_1)))
        val seen = notices.all.first().single()
        assertTrue(seen.seen)
        // 又来一条回复: 同一个键, 时间变晚, 算新的
        FeedbackNotices.postUnread(notices, listOf(ticket(61, unread = true, REPLY_1, REPLY_2)))
        val renewed = notices.all.first().single()
        assertEquals(millis(REPLY_2), renewed.notice.atMillis)
        assertFalse(renewed.seen)
    }

    @Test
    fun `a closed ticket is posted as done`() = runTest {
        FeedbackNotices.postUnread(notices, listOf(ticket(61, unread = true, REPLY_1).copy(state = FeedbackState.ACCEPTED)))
        assertEquals("true", notices.all.first().single().notice.args[FeedbackNotices.ARG_DONE])
    }

    private fun TestScope.tickets(records: List<FeedbackRecord>): Pair<FeedbackTickets, FeedbackRecordRepository> {
        val repository = FeedbackRecordRepository(MemorySettings(FeedbackRecords(items = records)))
        val tickets = FeedbackTickets(
            repository,
            FeedbackTicketService({ error("no network in this test") }, endpoints = { emptyList() }),
            FeedbackDevice("0123456789abcdef", emptyMap()),
            backgroundScope,
            notices = notices,
        )
        return tickets to repository
    }

    @Test
    fun `start posts the unread tickets`() = runTest {
        val (tickets, _) = tickets(listOf(ticket(61, unread = true, REPLY_1), ticket(47, unread = false, REPLY_1)))
        tickets.start()
        runCurrent()
        assertEquals(listOf("feedback:TICKET-61"), notices.all.first().map { it.notice.key })
    }

    @Test
    fun `marking one ticket read leaves the others unread`() = runTest {
        val (tickets, repository) = tickets(listOf(ticket(61, unread = true, REPLY_1), ticket(47, unread = true, REPLY_1)))
        tickets.markRead(FeedbackKind.TICKET, 61)
        val unread = repository.current().items.associate { it.issue to it.unread }
        assertEquals(mapOf(61 to false, 47 to true), unread)
    }

    @Test
    fun `marking a ticket read marks its notice seen`() = runTest {
        val (tickets, _) = tickets(listOf(ticket(61, unread = true, REPLY_1), ticket(47, unread = true, REPLY_1)))
        tickets.start()
        runCurrent()
        tickets.markRead(FeedbackKind.TICKET, 61)
        val seen = notices.all.first().associate { it.notice.key to it.seen }
        assertEquals(mapOf("feedback:TICKET-61" to true, "feedback:TICKET-47" to false), seen)
        tickets.markRead()
        assertTrue(notices.all.first().all { it.seen }, "控制台的「反馈」页看过后全部算看过")
    }

    private companion object {
        val NOW = Instant.parse("2026-11-22T12:00:00Z").toEpochMilliseconds()
        val SUBMITTED = Instant.parse("2026-11-20T06:02:00Z").toEpochMilliseconds()
        const val REPLY_1 = "2026-11-21T14:10:00Z"
        const val REPLY_2 = "2026-11-22T11:57:00Z"
    }
}
