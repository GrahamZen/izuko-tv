/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackReply
import me.him188.ani.app.data.models.feedback.FeedbackState
import kotlin.test.Test
import kotlin.test.assertEquals

/** 「我的反馈」签: 排序、取最近一次给的测试包、控制台里这张单的地址, 回复的 Markdown 换成纯文字. */
class TvFeedbackTabTest {
    private val apk = "https://github.com/a/b/releases/download/t/app.apk"

    private fun record(issue: Int, state: FeedbackState, vararg replies: FeedbackReply) = FeedbackRecord(
        kind = FeedbackKind.TICKET,
        issue = issue,
        summary = "ticket $issue",
        submittedAt = issue * 1000L,
        state = state,
        replies = replies.toList(),
    )

    private fun maintainer(at: String, body: String) = FeedbackReply(fromMaintainer = true, at = at, body = body)

    @Test
    fun `open tickets come first and newer activity first within each section`() {
        val oldOpen = record(1, FeedbackState.OPEN)
        val newOpen = record(2, FeedbackState.OPEN, maintainer("2026-11-22T10:00:00Z", "ok"))
        val closed = record(3, FeedbackState.ACCEPTED, maintainer("2026-11-23T10:00:00Z", "done"))
        assertEquals(listOf(2, 1, 3), feedbackOrder(listOf(closed, oldOpen, newOpen)).map { it.issue })
    }

    @Test
    fun `the latest test packages are the ones in the last reply that gave any`() {
        val first = "https://github.com/a/b/releases/download/t1/izuko-1-arm64-v8a.apk"
        val second = listOf(
            "https://github.com/a/b/releases/download/t2/izuko-2-arm64-v8a.apk",
            "https://github.com/a/b/releases/download/t2/izuko-2-universal.apk",
        )
        val r = record(
            5, FeedbackState.OPEN,
            maintainer("2026-11-20T10:00:00Z", "try $first"),
            maintainer("2026-11-21T10:00:00Z", "try again: ${second.joinToString(" ")}"),
            maintainer("2026-11-22T10:00:00Z", "any luck?"),
        ).copy(apks = listOf(first) + second)
        assertEquals(second, latestTestPackages(r))
        assertEquals(emptyList(), latestTestPackages(record(6, FeedbackState.OPEN, maintainer("2026-11-22T10:00:00Z", "no package"))))
    }

    @Test
    fun `the console link opens this ticket`() {
        assertEquals("#settings/feedback/TICKET-61", feedbackConsoleFragment(record(61, FeedbackState.OPEN)))
    }

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
}
