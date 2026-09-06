/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import me.him188.ani.app.platform.Context
import me.him188.ani.app.ui.settings.tabs.log.LogLanShareState
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** iOS 的应用语言在系统设置里换. */
internal actual fun appLanguageTag(context: Context): String? = null

internal actual fun setAppLanguage(context: Context, tag: String) {}

internal actual fun hasTouchscreen(context: Context): Boolean = true

internal actual fun displayModes(context: Context): List<TvDisplayMode> = emptyList()

internal actual val dynamicColorSupported: Boolean get() = false

internal actual fun openUrlInBrowser(context: Context, url: String): Boolean {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
    return true
}

internal actual fun crashOnMainThread(error: Throwable) {
    dispatch_async(dispatch_get_main_queue()) { throw error }
}

internal actual val logShareSupported: Boolean get() = false

internal actual suspend fun runLogShare(context: Context, onState: (LogLanShareState) -> Unit) {}

internal actual val logExportSupported: Boolean get() = false

internal actual fun currentLogFileExists(context: Context): Boolean = false

@Composable
internal actual fun LogExport(onDone: () -> Unit) {
    LaunchedEffect(Unit) { onDone() }
}
