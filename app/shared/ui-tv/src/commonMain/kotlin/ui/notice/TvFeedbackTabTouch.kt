/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.runtime.Composable
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.ui.feedback.TouchFeedback
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.touch_feedback_empty_description
import me.him188.ani.app.ui.lang.touch_feedback_new
import me.him188.ani.app.ui.lang.touch_feedback_reply_label
import org.jetbrains.compose.resources.stringResource

// 触屏 (平板 / iPad) 上写反馈、补充说明都在应用里做 (TouchFeedback 的弹窗), 不去手机控制台 (iPad 没有控制台):
// 「我的反馈」签 (TvFeedbackTab) 最上面那行「写新反馈」不放控制台的码, 点了在应用里写; 故障工单的「在手机上补充」换成打开应用里那张单的会话.
// 电视上这几处照旧.

/** 应用里就能写反馈 (触屏). */
@Composable
internal fun touchFeedbackInApp(): Boolean = LocalTvTouchInputEnabled.current

/** 触屏上「我的反馈」最上面那行: 点了在应用里写反馈; 电视上为 null (照旧扫码去控制台写). */
@Composable
internal fun touchFeedbackWriteItem(): TvNoticeColumnItem? {
    if (!touchFeedbackInApp()) return null
    val label = stringResource(Lang.touch_feedback_new)
    val description = stringResource(Lang.touch_feedback_empty_description)
    return TvNoticeColumnItem(
        key = TvFeedbackTab.NEW_KEY,
        icon = Icons.Rounded.EditNote,
        iconLabel = label,
        title = label,
        atMillis = null,
        summary = description,
        actions = listOf(TvNoticeAction(label, TvNoticeDestination.Run { openTouchFeedback(TouchFeedback.Screen.New) })),
        blocks = listOf(TvNoticeParagraph(description, tone = TvNoticeTone.Secondary)),
        bodyStartsAtEnd = false,
    )
}

/** 触屏上故障工单的「补充说明」: 打开应用里 [record] 的会话; 电视上为 null (照旧「在手机上补充」). */
@Composable
internal fun touchFeedbackSupplementAction(record: FeedbackRecord): TvNoticeAction? {
    if (!touchFeedbackInApp()) return null
    return TvNoticeAction(
        stringResource(Lang.touch_feedback_reply_label),
        TvNoticeDestination.Run { openTouchFeedback(TouchFeedback.Screen.Conversation(record.kind, record.issue)) },
    )
}

/** 收起通知中心再开应用里的反馈弹窗 (两个都是弹窗, 不叠着). */
private fun openTouchFeedback(screen: TouchFeedback.Screen) {
    TvNotices.close()
    TouchFeedback.open(screen)
}

