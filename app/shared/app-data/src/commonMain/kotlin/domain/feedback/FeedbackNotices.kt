/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.feedback

import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.Notices
import kotlin.time.Instant

/**
 * 反馈有新回复或处理结果时发的通知 (消息, 见 docs/contributing/code/notices.md): 一张单一条 (键是这张单), 有了新的回复或结果,
 * 时间变晚、算新的; 看过沉到「之前的」, 不跟着未读消失 (未读只管「我的反馈」里那张单的红点). 文案与右栏在界面层 (TvFeedbackNotice).
 */
object FeedbackNotices {
    /** 种类 id, 同 [Notice.kind]. */
    const val KIND: String = "feedback.reply"

    /** 参数: 这张单的标题 (故障工单的一句话、修正请求的作品名) */
    const val ARG_TITLE: String = "title"

    /** 参数: `true` = 已经有了处理结果 (不再是处理中) */
    const val ARG_DONE: String = "done"

    /** 参数: 这一次的时刻 (毫秒). [Notices.post] 只按参数判内容变没变: 只多了一条回复时标题与结果都没变, 靠它算新的 */
    const val ARG_AT: String = "at"

    fun keyOf(kind: FeedbackKind, issue: Int): String = "feedback:${kind.name}-$issue"

    fun keyOf(record: FeedbackRecord): String = keyOf(record.kind, record.issue)

    /** [keyOf] 反过来: 这条通知说的是哪张单; 不是反馈通知的键为 null. */
    fun recordOf(key: String, records: List<FeedbackRecord>): FeedbackRecord? = records.firstOrNull { keyOf(it) == key }

    /** 维护者最近一条回复、工单服务记的更新时间里晚的那个; 都没有用提交的时刻. */
    fun timeOf(record: FeedbackRecord): Long {
        val reply = record.replies.lastOrNull { it.fromMaintainer }?.at?.let(::parseMillis)
        val updated = record.remoteUpdated?.let(::parseMillis)
        return listOfNotNull(reply, updated).maxOrNull() ?: record.submittedAt
    }

    fun noticeOf(record: FeedbackRecord): Notice {
        val at = timeOf(record)
        return Notice(
            kind = KIND,
            key = keyOf(record),
            atMillis = at,
            args = mapOf(
                ARG_TITLE to (record.summary ?: record.subjectName ?: "#${record.issue}"),
                ARG_DONE to (record.state != FeedbackState.OPEN).toString(),
                ARG_AT to at.toString(),
            ),
        )
    }

    /** 有未读的那几张各发一条 (查到新回复或结果时未读才变 true); 同一张内容没变的再发不动 (见 [Notices.post]), 所以可以每次都发. */
    suspend fun postUnread(notices: Notices, records: List<FeedbackRecord>) {
        for (record in records) {
            if (record.unread) notices.post(noticeOf(record))
        }
    }

    private fun parseMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
}
