/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackReply
import me.him188.ani.app.data.models.feedback.FeedbackState
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @see feedbackItems
 */
class TvSettingsEntriesFeedbackTest {
    private val templates = mapOf(
        "tv_settings_feedback_header" to "%1\$s #%2\$d · %3\$s",
        "tv_settings_feedback_kind_ticket" to "故障工单",
        "tv_settings_feedback_kind_logo" to "标题 logo",
        "tv_settings_feedback_state_open" to "处理中",
        "tv_settings_feedback_state_resolved" to "已解决",
        "tv_settings_feedback_state_rejected" to "未采纳",
        "tv_settings_feedback_state_closed" to "已结束",
        "tv_settings_feedback_joined" to "同一处已有人反馈过",
        "tv_settings_feedback_from_maintainer" to "维护者 · %1\$s",
        "tv_settings_feedback_from_me" to "我 · %1\$s",
        "tv_settings_feedback_apk" to "（测试包）",
        "tv_settings_feedback_files_only" to "（只附了文件）",
        "tv_settings_feedback_new" to "新回复 · %1\$s",
        "tv_settings_feedback_unread_count" to "%1\$d 条新回复",
        "tv_settings_feedback_open_count" to "%1\$d 条处理中",
        "tv_settings_feedback_title_logo" to "《%1\$s》的标题 logo",
    )

    private fun resolve(text: TvText?): String? = text?.let { TvTextResolver { res -> templates[res.key] ?: res.key }.resolve(it) }

    /** 气泡写成一行字: 别人的 `<`、自己的 `>` 开头, 抬头与正文之间换行. */
    private fun bubbles(r: FeedbackRecord): List<String> = feedbackBubbles(r, ZoneOffset.UTC).map { b ->
        (if (b.mine) "> " else "< ") + resolve(b.meta) + (b.text?.let { "\n" + resolve(it) } ?: "")
    }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val apk = "https://github.com/a/b/releases/download/t/app.apk"

    @Test
    fun `reply markdown keeps only the words`() {
        val markdown = """
            ## 修好了
            ![截图](https://x/y.png)
            <!-- hidden -->
            **测试包**：[下载]($apk)
            详见 [说明](https://example.com/doc)
            <img src="a.png" width=300>

            > 引用一行



            `code` 结束
        """.trimIndent()
        assertEquals("修好了\n\n测试包：\n详见 说明\n\n引用一行\n\ncode 结束", feedbackPlainText(markdown, listOf(apk)))
    }

    @Test
    fun `ticket conversation lists the newest message first`() {
        val record = FeedbackRecord(
            FeedbackKind.TICKET, issue = 12, summary = "黑屏", description = "播放十分钟后黑屏", files = listOf("log.gz"),
            submittedAt = at("2026-10-14T13:30:00Z"), state = FeedbackState.ACCEPTED, apks = listOf(apk),
            replies = listOf(
                FeedbackReply(fromMaintainer = false, at = "2026-10-14T14:00:00Z", body = "", files = listOf("r1-photo-1.jpg")),
                FeedbackReply(fromMaintainer = true, at = "2026-10-15T01:12:00Z", body = "修好了，装这个试试：$apk"),
            ),
        )
        assertEquals("故障工单 #12 · 已解决", resolve(feedbackHeader(record)))
        assertEquals(
            listOf(
                "< 维护者 · 10-15 01:12\n修好了，装这个试试：\n（测试包）",
                "> 我 · 10-14 14:00\n（只附了文件）",
                "> 我 · 10-14 13:30\n播放十分钟后黑屏",
            ),
            bubbles(record),
        )
    }

    @Test
    fun `correction conversation shows the reason and what was sent`() {
        val record = FeedbackRecord(
            FeedbackKind.LOGO, issue = 5, subjectName = "某作", submittedAt = at("2026-10-14T13:30:00Z"),
            state = FeedbackState.REJECTED, joinedExisting = true,
            replies = listOf(FeedbackReply(fromMaintainer = true, at = "2026-10-15T01:00:00Z", body = "这张就是官方的")),
        )
        assertEquals("标题 logo #5 · 未采纳 · 同一处已有人反馈过", resolve(feedbackHeader(record)))
        assertEquals(
            listOf("< 维护者 · 10-15 01:00\n这张就是官方的", "> 我 · 10-14 13:30\n《某作》的标题 logo"),
            bubbles(record),
        )
    }

    @Test
    fun `row and group values count unread before open`() {
        val unread = FeedbackRecord(FeedbackKind.TICKET, issue = 1, submittedAt = 0, unread = true)
        val open = FeedbackRecord(FeedbackKind.LOGO, issue = 2, submittedAt = 0)
        val closed = FeedbackRecord(FeedbackKind.ENTRY, issue = 3, submittedAt = 0, state = FeedbackState.CLOSED)
        assertEquals("新回复 · 处理中", resolve(feedbackRowValue(unread, fresh = true)))
        assertEquals("已结束", resolve(feedbackRowValue(closed, fresh = false)))
        assertEquals("1 条新回复", resolve(feedbackGroupValue(listOf(unread, open))))
        assertEquals("1 条处理中", resolve(feedbackGroupValue(listOf(open, closed))))
        assertNull(feedbackGroupValue(listOf(closed)))
    }

    @Test
    fun `items read on opening stay marked new for this visit`() {
        val read = FeedbackRecord(FeedbackKind.TICKET, issue = 1, submittedAt = 0)
        assertTrue(TvFeedbackList(listOf(read), fresh = setOf(feedbackKey(read))).isFresh(read))
        assertFalse(TvFeedbackList(listOf(read)).isFresh(read))
    }

    @Test
    fun `unreadable reply time is shown as is`() {
        assertEquals("昨天", feedbackTime("昨天", ZoneOffset.UTC))
    }
}
