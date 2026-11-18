/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_action_open
import me.him188.ani.app.ui.lang.tv_notice_feedback_done
import me.him188.ani.app.ui.lang.tv_notice_feedback_from
import me.him188.ani.app.ui.lang.tv_notice_feedback_reply
import me.him188.ani.app.ui.lang.tv_notice_feedback_result
import me.him188.ani.app.ui.settings.tv.TV_SETTINGS_FEEDBACK_GROUP
import me.him188.ani.app.ui.settings.tv.TvSettingsEntryGroup
import me.him188.ani.app.ui.settings.tv.feedbackPlainText
import me.him188.ani.app.ui.settings.tv.feedbackStateLabel
import org.koin.mp.KoinPlatform
import kotlin.time.Instant

/**
 * 反馈有新回复或处理结果 (见 FeedbackTickets): 一条反馈有未读就一条, 跟着未读走 —— 在电视的「我的反馈」或手机控制台的「反馈」里看过就消失.
 * 去处是设置里的「我的反馈」(直接进那一组, 进去就算看过).
 */
internal object TvFeedbackNotice : TvNoticeKind {
    override val id: String = "feedback.reply"
    override val category: TvNoticeCategory = TvNoticeCategory.Feedback

    override fun source(): Flow<List<Notice>> {
        val repository = runCatching { KoinPlatform.getKoin().get<FeedbackRecordRepository>() }.getOrNull() ?: return flowOf(emptyList())
        return repository.records.map { records -> records.filter { it.unread }.map(::noticeOf) }
    }

    private fun noticeOf(record: FeedbackRecord): Notice = Notice(
        kind = id,
        key = keyOf(record),
        atMillis = timeOf(record),
        args = mapOf(
            ARG_TITLE to (record.summary ?: record.subjectName ?: "#${record.issue}"),
            ARG_DONE to (record.state != FeedbackState.OPEN).toString(),
        ),
    )

    private fun keyOf(record: FeedbackRecord): String = "feedback:${record.kind.name}-${record.issue}"

    /** 维护者最近一条回复、工单服务记的更新时间里晚的那个; 都没有用提交的时刻. */
    private fun timeOf(record: FeedbackRecord): Long {
        val reply = record.replies.lastOrNull { it.fromMaintainer }?.at?.let(::parseMillis)
        val updated = record.remoteUpdated?.let(::parseMillis)
        return listOfNotNull(reply, updated).maxOrNull() ?: record.submittedAt
    }

    private fun parseMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

    override suspend fun title(notice: Notice): String =
        getStringOffMain(if (isDone(notice)) Lang.tv_notice_feedback_done else Lang.tv_notice_feedback_reply)

    /** 哪一条反馈 */
    override suspend fun summary(notice: Notice): String? = notice.args[ARG_TITLE]?.takeIf { it.isNotBlank() }

    override fun destination(notice: Notice): TvNoticeDestination =
        TvNoticeDestination.Page { TvSettingsEntryGroup.open(it, SettingsTab.PROFILE, TV_SETTINGS_FEEDBACK_GROUP) }

    /** 维护者最近一条回复的文字 (太长截断, 全文与附件在「我的反馈」里)、处理完了的写处理结果 */
    override suspend fun detail(notice: Notice): TvNoticeDetail {
        val record = recordOf(notice)
        val reply = record?.replies?.lastOrNull { it.fromMaintainer }?.let { feedbackPlainText(it.body, record.apks) }
        return TvNoticeDetail(
            body = buildList {
                if (!reply.isNullOrEmpty()) {
                    add(TvNoticeParagraph(reply, title = getStringOffMain(Lang.tv_notice_feedback_from), maxLines = REPLY_MAX_LINES))
                }
                if (record != null && isDone(notice)) {
                    add(TvNoticeParagraph(getStringOffMain(feedbackStateLabel(record)), title = getStringOffMain(Lang.tv_notice_feedback_result)))
                }
            },
            actions = listOf(TvNoticeAction(getStringOffMain(Lang.tv_notice_action_open), destination(notice))),
            subject = summary(notice),
        )
    }

    private fun isDone(notice: Notice): Boolean = notice.args[ARG_DONE] == "true"

    private suspend fun recordOf(notice: Notice): FeedbackRecord? {
        val repository = runCatching { KoinPlatform.getKoin().get<FeedbackRecordRepository>() }.getOrNull() ?: return null
        return repository.current().items.firstOrNull { keyOf(it) == notice.key }
    }

    override fun consoleHref(notice: Notice): String = "#settings/feedback"

    private const val ARG_TITLE = "title"
    private const val ARG_DONE = "done"

    /** 右栏里维护者的回复至多几行 */
    private const val REPLY_MAX_LINES = 8
}
