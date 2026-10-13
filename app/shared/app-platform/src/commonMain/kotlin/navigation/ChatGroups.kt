/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.navigation

import me.him188.ani.app.platform.currentAniBuildConfig

/**
 * 交流群 (QQ 群与 Telegram). 只给装了应用的人看: 链接不写进源码, 构建时注入 (见 `AniBuildConfig.chatQqGroupId`);
 * 没配置的构建里各项都是空串, 界面上相应的入口与二维码不显示.
 */
object ChatGroups {
    val qqGroupId: String get() = currentAniBuildConfig.chatQqGroupId
    val qqJoinLink: String get() = currentAniBuildConfig.chatQqGroupLink
    val telegramLink: String get() = currentAniBuildConfig.chatTelegramLink

    /** QQ 入群问题的答案: 仓库主人的用户名. */
    val qqJoinAnswer: String get() = currentAniBuildConfig.projectRepository.substringBefore('/')

    val hasQq: Boolean get() = qqJoinLink.isNotBlank()
    val hasTelegram: Boolean get() = telegramLink.isNotBlank()
    val isConfigured: Boolean get() = hasQq || hasTelegram
}
