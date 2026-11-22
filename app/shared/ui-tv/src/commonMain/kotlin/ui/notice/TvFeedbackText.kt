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
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_accepted
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_closed
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_not_planned
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_open
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_rejected
import me.him188.ani.app.ui.lang.tv_settings_feedback_state_resolved
import org.jetbrains.compose.resources.StringResource

/** 反馈的状态 (同控制台): 故障工单的采纳 / 没采纳叫「已解决 / 不处理」. */
internal fun feedbackStateLabel(r: FeedbackRecord): StringResource = when (r.state) {
    FeedbackState.OPEN -> Lang.tv_settings_feedback_state_open
    FeedbackState.ACCEPTED -> if (r.kind == FeedbackKind.TICKET) Lang.tv_settings_feedback_state_resolved else Lang.tv_settings_feedback_state_accepted
    FeedbackState.REJECTED -> if (r.kind == FeedbackKind.TICKET) Lang.tv_settings_feedback_state_not_planned else Lang.tv_settings_feedback_state_rejected
    FeedbackState.CLOSED -> Lang.tv_settings_feedback_state_closed
}

private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")
private val MD_IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val MD_LINK = Regex("""\[([^\]]*)]\(([^)\s]*)[^)]*\)""")
private val HTML_TAG = Regex("""</?[a-zA-Z][^>]*>""")
private val CODE_FENCE = Regex("""(?m)^\s*```.*$""")
private val LINE_MARK = Regex("""(?m)^[ \t]*(?:#{1,6}[ \t]+|>[ \t]?)""")
private val EMPHASIS = Regex("""\*\*|__|`""")
private val TRAILING_SPACE = Regex("""(?m)[ \t]+$""")
private val BLANK_LINES = Regex("""\n{3,}""")

/**
 * 回复的 Markdown 换成电视上能看的字: 去掉图片、注释与网页标签, 链接只留文字, 去掉粗体 / 代码 / 标题 / 引用的记号;
 * [drop] 里的链接 (测试包, 在测试包卡片里另写) 整个去掉.
 */
internal fun feedbackPlainText(markdown: String, drop: Collection<String> = emptyList()): String {
    var s = markdown.replace("\r\n", "\n")
    s = HTML_COMMENT.replace(s, "")
    s = MD_IMAGE.replace(s, "")
    s = MD_LINK.replace(s) { m -> if (m.groupValues[2] in drop) "" else m.groupValues[1] }
    for (url in drop) s = s.replace(url, "")
    s = HTML_TAG.replace(s, "")
    s = CODE_FENCE.replace(s, "")
    s = LINE_MARK.replace(s, "")
    s = EMPHASIS.replace(s, "")
    s = TRAILING_SPACE.replace(s, "")
    s = BLANK_LINES.replace(s, "\n\n")
    return s.trim()
}
