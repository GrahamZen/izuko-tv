/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.notice

import kotlinx.serialization.Serializable

/**
 * 一条通知 (见 docs/contributing/code/notices.md): 种类、键、时间与参数. 显示的那句话与去处由种类在界面层定,
 * 这里不存字 —— 换了语言照样对.
 *
 * @property kind 种类 id, 如 `feedback.reply`; 界面层认不出的种类不列
 * @property key 这件事的键: 同一个键只有一条
 * @property atMillis 时间; 同一个键的时间变晚 (事情有了变化) 算新的
 * @property args 文案与去处要用的参数. 都是字符串: 以后加取值, 旧版本照样读得出来
 */
@Serializable
data class Notice(
    val kind: String,
    val key: String,
    val atMillis: Long,
    val args: Map<String, String> = emptyMap(),
)

/**
 * 一个用户档存下的通知: 消息 ([messages], 新的在前)、看过的记录 ([seen]: 键 → 看过时那一条的时间), 与跟着状态而不知道时间的那几条
 * 第一次出现的时刻 ([firstSeen]: 键 → 时刻). 跟着状态的通知本身不存, 每次从各个来源算 (见 me.him188.ani.app.domain.notice.Notices).
 */
@Serializable
data class NoticeLog(
    val messages: List<Notice> = emptyList(),
    val seen: Map<String, Long> = emptyMap(),
    val firstSeen: Map<String, Long> = emptyMap(),
) {
    companion object {
        val Empty = NoticeLog()
    }
}
