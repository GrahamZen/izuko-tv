/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.IosLoggingConfigurator
import platform.Foundation.NSData
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard
import platform.posix.memcpy
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * debug 包起调试接口 (见 [DebugConsole]); 正式包什么都不做. 「debug 包」按 Kotlin/Native 产物的编译方式认
 * (iOS 的 AniBuildConfig.isDebug 恒为 false). 要在界面组合之前调, 设置页才列得出地址.
 */
@OptIn(ExperimentalNativeApi::class)
fun startIosDebugConsole(dataDir: SystemPath, logsDir: SystemPath) {
    if (!Platform.isDebugBinary) return
    DebugConsole.start(
        DebugConsoleConfig(
            stateFile = dataDir.resolve("debug-console.txt"),
            logsDir = logsDir,
            flushLogs = { IosLoggingConfigurator.flush() },
            screenshotPng = { keyWindowPng() },
            copyText = { UIPasteboard.generalPasteboard.string = it },
        ),
    )
}

/** 主窗口此刻的样子 (系统合成后的画面, 含 Compose 与原生视图). 主线程上调. */
@OptIn(ExperimentalForeignApi::class)
private fun keyWindowPng(): ByteArray? {
    val window = UIApplication.sharedApplication.keyWindow ?: return null
    val bounds = window.bounds
    val image = UIGraphicsImageRenderer(bounds = bounds).imageWithActions { _ ->
        window.drawViewHierarchyInRect(bounds, afterScreenUpdates = false)
    }
    return UIImagePNGRepresentation(image)?.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
    if (isNotEmpty()) usePinned { memcpy(it.addressOf(0), bytes, length) }
}
