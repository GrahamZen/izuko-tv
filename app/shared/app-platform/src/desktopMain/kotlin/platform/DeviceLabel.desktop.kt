/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import java.security.MessageDigest

actual fun Context.deviceLabel(): String = System.getProperty("os.name").orEmpty().ifEmpty { "Desktop" }

actual fun Context.feedbackDeviceId(): String {
    val id = "${System.getProperty("user.name")}|${System.getProperty("os.name")}|${System.getProperty("user.home")}"
    return MessageDigest.getInstance("SHA-256").digest("izuko-feedback:$id".encodeToByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
}

actual fun Context.feedbackEnvironment(): Map<String, String> = mapOf(
    "系统" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
    "Java" to System.getProperty("java.version").orEmpty(),
)
