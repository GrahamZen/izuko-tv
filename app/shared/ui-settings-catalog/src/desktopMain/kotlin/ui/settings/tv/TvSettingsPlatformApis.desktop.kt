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
import java.awt.Desktop
import java.awt.EventQueue
import java.net.URI

// 桌面不用这份清单 (只有编译与测试), 给最简单的做法.

internal actual fun appLanguageTag(context: Context): String? = null

internal actual fun setAppLanguage(context: Context, tag: String) {}

internal actual fun hasTouchscreen(context: Context): Boolean = false

internal actual fun displayModes(context: Context): List<TvDisplayMode> = emptyList()

internal actual val dynamicColorSupported: Boolean get() = false

internal actual fun openUrlInBrowser(context: Context, url: String): Boolean =
    runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess

internal actual fun crashOnMainThread(error: Throwable) {
    EventQueue.invokeLater { throw error }
}

internal actual val logShareSupported: Boolean get() = false

internal actual suspend fun runLogShare(context: Context, onState: (LogLanShareState) -> Unit) {}

internal actual val logExportSupported: Boolean get() = false

internal actual fun currentLogFileExists(context: Context): Boolean = false

@Composable
internal actual fun LogExport(onDone: () -> Unit) {
    LaunchedEffect(Unit) { onDone() }
}
