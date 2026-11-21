/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

/** 安卓与桌面没有系统授权弹窗: 外部浏览器那条由回环监听在后台接住回调 (见 [OAuthLoopbackServer]). */
internal actual object SystemOAuthSession {
    actual fun open(url: String, onDismissedByUser: () -> Unit): Boolean = false

    actual fun close() {}
}
