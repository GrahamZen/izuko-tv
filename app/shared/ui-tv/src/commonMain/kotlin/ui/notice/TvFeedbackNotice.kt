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
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.data.repository.feedback.FeedbackRecordRepository
import me.him188.ani.app.domain.feedback.FeedbackNotices
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_feedback_done
import me.him188.ani.app.ui.lang.tv_notice_feedback_reply
import org.koin.mp.KoinPlatform

/**
 * 反馈有新回复或处理结果 (消息, 由数据层的 [FeedbackNotices] 在查到时发): 一张单一条, 这张单在本机删掉了就不列.
 * 在「我的反馈」签里看 ([tab]): 不在「通知」签与手机控制台的通知列表里列, 签上的红点提醒, 焦点到这个签时算看过 (见 [TvFeedbackTab]);
 * 在电视或控制台看过这张单也算看过 (FeedbackTickets.markRead). 照样算侧边栏「通知」的红点与通知气泡, 气泡上按「查看」开到
 * 「我的反馈」里的这张单 (键相同).
 */
internal object TvFeedbackNotice : TvNoticeKind {
    override val id: String = FeedbackNotices.KIND
    override val category: TvNoticeCategory = TvNoticeCategory.Feedback
    override val tab: String = TvFeedbackTab.ID

    private val repository: FeedbackRecordRepository?
        get() = runCatching { KoinPlatform.getKoin().get<FeedbackRecordRepository>() }.getOrNull()

    /** 单子删掉、回复变了都要重算 */
    override fun textChanges(): Flow<*>? = repository?.records

    override suspend fun title(notice: Notice): String? {
        if (recordOf(notice) == null) return null
        val done = notice.args[FeedbackNotices.ARG_DONE] == "true"
        return getStringOffMain(if (done) Lang.tv_notice_feedback_done else Lang.tv_notice_feedback_reply)
    }

    /** 哪一条反馈 */
    override suspend fun summary(notice: Notice): String? = notice.args[FeedbackNotices.ARG_TITLE]?.takeIf { it.isNotBlank() }

    /** 「我的反馈」签里的这张单 (键相同) */
    override fun destination(notice: Notice): TvNoticeDestination = TvNoticeDestination.Run { TvFeedbackTab.open(notice.key) }

    private suspend fun recordOf(notice: Notice): FeedbackRecord? =
        repository?.current()?.items?.let { FeedbackNotices.recordOf(notice.key, it) }
}
