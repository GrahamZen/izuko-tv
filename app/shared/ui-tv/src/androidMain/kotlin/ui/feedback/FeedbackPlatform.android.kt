/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.feedback

import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.ui.diagnostics.TvPerfDiagnostics
import me.him188.ani.app.ui.remote.RemoteAppUpdate
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.inSystem

internal actual object FeedbackPlatform {
    actual fun logsDir(): SystemPath? = TvRemoteControl.logsDir()?.let { Path(it.path).inSystem }

    actual fun filesDir(): SystemPath? = TvRemoteControl.filesDir()?.let { Path(it.path).inSystem }

    actual fun perfReports(limit: Int): List<FeedbackPerfReport> =
        TvPerfDiagnostics.reports().take(limit).mapNotNull { perfReportOf(it, text = "") }

    actual fun perfReport(id: String): FeedbackPerfReport? {
        val text = TvPerfDiagnostics.reportFile(id)?.let { runCatching { it.readText() }.getOrNull() } ?: return null
        val report = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return perfReportOf(report, text)
    }

    actual val apkInstaller: FeedbackApkInstaller? get() = RemoteAppUpdate.inApp

    private fun perfReportOf(report: JsonObject, text: String): FeedbackPerfReport? {
        val id = report["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return FeedbackPerfReport(
            id = id,
            kind = report["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            seconds = report["seconds"]?.jsonPrimitive?.intOrNull ?: 0,
            page = report["page"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
            time = report["time"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            headline = TvPerfDiagnostics.headlineOf(report),
            text = text,
        )
    }
}
