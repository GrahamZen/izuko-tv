/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

/**
 * iOS 不实现: 那边有 `ASWebAuthenticationSession`, 自定义 scheme 由系统直接送回 App,
 * 用不着回环监听. [start] 返回 false, 调用方自然退回原有那条路.
 */
actual class OAuthLoopbackServer actual constructor(port: Int) {
    actual suspend fun start(onCallback: suspend (url: String) -> Unit): Boolean = false

    actual fun stop() {}
}
