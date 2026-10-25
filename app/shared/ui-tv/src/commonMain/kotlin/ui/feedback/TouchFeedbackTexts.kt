/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import androidx.compose.runtime.Composable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.network.FeedbackTicketService.CreateResult
import me.him188.ani.app.data.network.FeedbackTicketService.Outcome
import me.him188.ani.app.domain.feedback.FeedbackTickets.SubmitResult
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.touch_feedback_closed
import me.him188.ani.app.ui.lang.touch_feedback_err_blocked
import me.him188.ani.app.ui.lang.touch_feedback_err_description_short
import me.him188.ani.app.ui.lang.touch_feedback_err_empty
import me.him188.ani.app.ui.lang.touch_feedback_err_failed
import me.him188.ani.app.ui.lang.touch_feedback_err_no_recording
import me.him188.ani.app.ui.lang.touch_feedback_err_rate_limited
import me.him188.ani.app.ui.lang.touch_feedback_err_rejected
import me.him188.ani.app.ui.lang.touch_feedback_err_still_recording
import me.him188.ani.app.ui.lang.touch_feedback_err_summary_short
import me.him188.ani.app.ui.lang.touch_feedback_err_too_large
import me.him188.ani.app.ui.lang.touch_feedback_err_too_many_open
import me.him188.ani.app.ui.lang.touch_feedback_err_unreachable
import me.him188.ani.app.ui.lang.tv_settings_feedback_header
import me.him188.ani.app.ui.lang.tv_settings_feedback_joined
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_entry
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_logo
import me.him188.ani.app.ui.lang.tv_settings_feedback_kind_ticket
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_accepted
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_closed
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_not_planned
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_open
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_rejected
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_resolved
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_entry
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_entry_id
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_logo
import me.him188.ani.app.ui.lang.tv_settings_feedback_title_logo_id
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** 弹窗底部那一行提示: [error] 用红字. */
internal class FeedbackNote(val text: String, val error: Boolean = false)

/** 提交 / 补充没成的原因 (同 Web 控制台的说法); null = 没等到结果 (超时). */
internal suspend fun submitFailureText(result: SubmitResult?): String = when (result) {
    null -> getString(Lang.touch_feedback_err_unreachable)
    is SubmitResult.Submitted -> ""
    SubmitResult.NoRecording -> getString(Lang.touch_feedback_err_no_recording)
    SubmitResult.StillRecording -> getString(Lang.touch_feedback_err_still_recording)
    SubmitResult.Empty -> getString(Lang.touch_feedback_err_empty)
    is SubmitResult.NotSent -> when (result.outcome) {
        Outcome.CONFLICT -> getString(Lang.touch_feedback_closed)
        Outcome.RATE_LIMITED -> getString(Lang.touch_feedback_err_rate_limited)
        Outcome.UNREACHABLE -> getString(Lang.touch_feedback_err_unreachable)
        Outcome.OK, Outcome.REJECTED, Outcome.FAILED -> getString(Lang.touch_feedback_err_failed)
    }

    is SubmitResult.NotCreated -> when (val reason = result.reason) {
        CreateResult.Blocked -> getString(Lang.touch_feedback_err_blocked)
        is CreateResult.TooManyOpen -> getString(Lang.touch_feedback_err_too_many_open, reason.issues.size)
        CreateResult.RateLimited -> getString(Lang.touch_feedback_err_rate_limited)
        is CreateResult.Rejected -> when (reason.error) {
            "summary_too_short" -> getString(Lang.touch_feedback_err_summary_short, FeedbackTextLimits.MIN_SUMMARY)
            "description_too_short" -> getString(Lang.touch_feedback_err_description_short, FeedbackTextLimits.MIN_DESCRIPTION)
            "file_too_large", "too_many_photos" -> getString(Lang.touch_feedback_err_too_large)
            else -> getString(Lang.touch_feedback_err_rejected, reason.error)
        }

        CreateResult.Unreachable -> getString(Lang.touch_feedback_err_unreachable)
        CreateResult.Failed, is CreateResult.Created -> getString(Lang.touch_feedback_err_failed)
    }
}

/** 一条反馈的标题: 故障工单是那句话, 修正请求是「《作品》的标题 logo」之类 (同设置里的「我的反馈」). */
@Composable
internal fun feedbackTitle(r: FeedbackRecord): String = when (r.kind) {
    FeedbackKind.TICKET -> r.summary?.takeIf { it.isNotBlank() } ?: stringResource(Lang.tv_settings_feedback_kind_ticket)
    FeedbackKind.LOGO -> r.subjectName?.takeIf { it.isNotBlank() }?.let { stringResource(Lang.tv_settings_feedback_title_logo, it) }
        ?: stringResource(Lang.tv_settings_feedback_title_logo_id, r.subjectId ?: 0)

    FeedbackKind.ENTRY -> r.subjectName?.takeIf { it.isNotBlank() }?.let { stringResource(Lang.tv_settings_feedback_title_entry, it) }
        ?: stringResource(Lang.tv_settings_feedback_title_entry_id, r.subjectId ?: 0)
}

/** 「故障工单 #12 · 处理中」(跟着别人提的修正再加一句). */
@Composable
internal fun feedbackHeader(r: FeedbackRecord): String {
    val kind = stringResource(
        when (r.kind) {
            FeedbackKind.TICKET -> Lang.tv_settings_feedback_kind_ticket
            FeedbackKind.LOGO -> Lang.tv_settings_feedback_kind_logo
            FeedbackKind.ENTRY -> Lang.tv_settings_feedback_kind_entry
        },
    )
    val state = stringResource(
        when (r.state) {
            FeedbackState.OPEN -> Lang.tv_settings_feedback_state_open
            FeedbackState.ACCEPTED -> if (r.kind == FeedbackKind.TICKET) Lang.tv_settings_feedback_state_resolved else Lang.tv_settings_feedback_state_accepted
            FeedbackState.REJECTED -> if (r.kind == FeedbackKind.TICKET) Lang.tv_settings_feedback_state_not_planned else Lang.tv_settings_feedback_state_rejected
            FeedbackState.CLOSED -> Lang.tv_settings_feedback_state_closed
        },
    )
    val header = stringResource(Lang.tv_settings_feedback_header, kind, r.issue, state)
    return if (r.joinedExisting) header + " · " + stringResource(Lang.tv_settings_feedback_joined) else header
}

/** `MM-dd HH:mm` (本地时间). */
internal fun feedbackTime(millis: Long): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${pad2(t.month.number)}-${pad2(t.day)} ${pad2(t.hour)}:${pad2(t.minute)}"
}

/** 工单服务给的 ISO 时间换成 `MM-dd HH:mm`; 认不出原样返回. */
internal fun feedbackTime(iso: String): String =
    runCatching { feedbackTime(Instant.parse(iso).toEpochMilliseconds()) }.getOrDefault(iso)

/** `HH:mm` (本地时间). */
internal fun feedbackClock(millis: Long): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${pad2(t.hour)}:${pad2(t.minute)}"
}

/** 时长 `m:ss`. */
internal fun feedbackDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${pad2((seconds % 60).toInt())}"
}

/** 文件大小 (`320 KB` / `1.5 MB`). */
internal fun feedbackSize(bytes: Long): String {
    if (bytes < 1024 * 1024) return "${(bytes + 1023) / 1024} KB"
    val tenths = bytes * 10 / (1024 * 1024)
    return "${tenths / 10}.${tenths % 10} MB"
}

private fun pad2(value: Int): String = value.toString().padStart(2, '0')
