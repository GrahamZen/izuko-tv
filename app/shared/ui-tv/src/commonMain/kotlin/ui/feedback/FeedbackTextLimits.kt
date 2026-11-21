/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

/** 写反馈的字数限制, 同工单服务 (tools/ticket-worker) 的上下限: 提交前先在本机挡一道, 提示得具体些. 应用内与 Web 控制台共用. */
internal object FeedbackTextLimits {
    const val MIN_SUMMARY = 4
    const val MAX_SUMMARY = 80
    const val MIN_DESCRIPTION = 5
    const val MAX_DESCRIPTION = 4000

    /** 按字 (码点) 数: 表情这类占两个 char 的算一个. */
    fun length(text: String): Int = text.count { !it.isLowSurrogate() }
}
