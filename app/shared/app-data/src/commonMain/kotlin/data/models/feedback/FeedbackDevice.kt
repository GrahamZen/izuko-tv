/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.feedback

/**
 * 这台电视的反馈设备号 ([id], 见平台的 `feedbackDeviceId`): 提交故障工单时带上, 维护者据此拉黑乱发的电视.
 * 给用户看时只显示 [shortId]. [environment] 是开单时写进 issue 的机型、系统等 (见平台的 `feedbackEnvironment`).
 */
class FeedbackDevice(val id: String, val environment: Map<String, String> = emptyMap()) {
    /** 显示用的前 8 位. */
    val shortId: String get() = id.take(8)
}
