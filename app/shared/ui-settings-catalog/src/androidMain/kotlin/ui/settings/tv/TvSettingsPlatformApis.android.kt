/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import me.him188.ani.app.platform.AppLocales
import me.him188.ani.app.ui.settings.tabs.log.LogExportLauncher
import me.him188.ani.app.ui.settings.tabs.log.LogLanShareState
import me.him188.ani.app.ui.settings.tabs.log.hasCurrentLogFile
import me.him188.ani.app.ui.settings.tabs.log.runLogLanShare
import kotlin.math.roundToInt

internal actual fun appLanguageTag(context: Context): String? = AppLocales.get(context).toLanguageTags().substringBefore(',')

internal actual fun setAppLanguage(context: Context, tag: String) {
    val locales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    if (AppLocales.get(context).toLanguageTags() != locales.toLanguageTags()) {
        AppLocales.set(context, locales)
    }
}

/** Android 11 起才能取到; 更早为空. */
internal actual fun hasTouchscreen(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

internal actual fun displayModes(context: Context): List<TvDisplayMode> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.supportedModes.orEmpty().map { TvDisplayMode(it.modeId, it.refreshRate.roundToInt()) }
    } else {
        emptyList()
    }

internal actual val dynamicColorSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

internal actual fun openUrlInBrowser(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

internal actual fun crashOnMainThread(error: Throwable) {
    Handler(Looper.getMainLooper()).post { throw error }
}

internal actual val logShareSupported: Boolean get() = true

internal actual suspend fun runLogShare(context: Context, onState: (LogLanShareState) -> Unit) = runLogLanShare(context, onState)

internal actual val logExportSupported: Boolean get() = true

internal actual fun currentLogFileExists(context: Context): Boolean = context.hasCurrentLogFile()

@Composable
internal actual fun LogExport(onDone: () -> Unit) = LogExportLauncher(onDone)
