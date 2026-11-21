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
import me.him188.ani.app.ui.lang.tv_notice_feedback_done
import me.him188.ani.app.ui.lang.tv_notice_feedback_reply
import me.him188.ani.app.ui.settings.tv.TV_SETTINGS_FEEDBACK_GROUP
import me.him188.ani.app.ui.settings.tv.TvSettingsEntryGroup
import org.koin.mp.KoinPlatform
import kotlin.time.Instant

/**
 * 反馈有新回复或处理结果 (见 FeedbackTickets): 一条反馈有未读就一条, 跟着未读走 —— 在电视的「我的反馈」或手机控制台的「反馈」里看过就消失.
 * 去处是设置里的「我的反馈」(直接进那一组, 进去就算看过).
 */
internal object TvFeedbackNotice : TvNoticeKind {
    override val id: String = "feedback.reply"

    override fun source(): Flow<List<Notice>> {
        val repository = runCatching { KoinPlatform.getKoin().get<FeedbackRecordRepository>() }.getOrNull() ?: return flowOf(emptyList())
        return repository.records.map { records -> records.filter { it.unread }.map(::noticeOf) }
    }

    private fun noticeOf(record: FeedbackRecord): Notice = Notice(
        kind = id,
        key = "feedback:${record.kind.name}-${record.issue}",
        atMillis = timeOf(record),
        args = mapOf(
            ARG_TITLE to (record.summary ?: record.subjectName ?: "#${record.issue}"),
            ARG_DONE to (record.state != FeedbackState.OPEN).toString(),
        ),
    )

    /** 维护者最近一条回复、工单服务记的更新时间里晚的那个; 都没有用提交的时刻. */
    private fun timeOf(record: FeedbackRecord): Long {
        val reply = record.replies.lastOrNull { it.fromMaintainer }?.at?.let(::parseMillis)
        val updated = record.remoteUpdated?.let(::parseMillis)
        return listOfNotNull(reply, updated).maxOrNull() ?: record.submittedAt
    }

    private fun parseMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

    override suspend fun text(notice: Notice): String {
        val title = notice.args[ARG_TITLE].orEmpty()
        return if (notice.args[ARG_DONE] == "true") {
            getStringOffMain(Lang.tv_notice_feedback_done, title)
        } else {
            getStringOffMain(Lang.tv_notice_feedback_reply, title)
        }
    }

    override fun destination(notice: Notice): TvNoticeDestination =
        TvNoticeDestination.Page { TvSettingsEntryGroup.open(it, SettingsTab.PROFILE, TV_SETTINGS_FEEDBACK_GROUP) }

    override fun consoleHref(notice: Notice): String = "#settings/feedback"

    private const val ARG_TITLE = "title"
    private const val ARG_DONE = "done"
}
