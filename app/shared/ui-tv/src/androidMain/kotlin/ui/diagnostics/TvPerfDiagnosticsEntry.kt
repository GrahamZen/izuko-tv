/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_perf_diag_checking
import me.him188.ani.app.ui.lang.tv_perf_diag_description
import me.him188.ani.app.ui.lang.tv_perf_diag_dialog_hint
import me.him188.ani.app.ui.lang.tv_perf_diag_latest
import me.him188.ani.app.ui.lang.tv_perf_diag_none
import me.him188.ani.app.ui.lang.tv_perf_diag_recording
import me.him188.ani.app.ui.lang.tv_perf_diag_title
import me.him188.ani.app.ui.lang.tv_remote_control_close
import me.him188.ani.app.ui.remote.RemoteConnectionStatus
import me.him188.ani.app.ui.remote.RemoteQrCode
import me.him188.ani.app.ui.remote.TvRemoteControl
import org.jetbrains.compose.resources.stringResource

/**
 * 电视「设置 → 日志」里的「性能诊断」(经 ui-foundation 的 `TvRemoteSettingsBridge` 装进日志页): 一行状态,
 * 点开是直达 Web 控制台「设置 → 维护」的码. 体检与录制都在网页上点, 电视这边只负责被测.
 */
@Composable
internal fun TvPerfDiagnosticsSettingsItem(colors: ListItemColors) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(Lang.tv_perf_diag_title)) },
        supportingContent = { Text(statusText() ?: stringResource(Lang.tv_perf_diag_description)) },
        modifier = Modifier.clickable { open = true },
        colors = colors,
    )
    if (open) TvPerfDiagnosticsDialog(onDismissRequest = { open = false })
}

/** 录制 / 体检中的状态; 空闲时 null. */
@Composable
private fun statusText(): String? {
    val status by TvPerfDiagnostics.status.collectAsState()
    return when (val s = status) {
        is TvPerfDiagnostics.Status.Recording -> {
            val left by produceState(remainingSeconds(s), s) {
                while (true) {
                    value = remainingSeconds(s)
                    delay(500)
                }
            }
            stringResource(Lang.tv_perf_diag_recording, left)
        }

        TvPerfDiagnostics.Status.CheckingHealth -> stringResource(Lang.tv_perf_diag_checking)
        TvPerfDiagnostics.Status.Idle -> null
    }
}

private fun remainingSeconds(s: TvPerfDiagnostics.Status.Recording): Int =
    ((s.endsAtMillis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)

@Composable
private fun TvPerfDiagnosticsDialog(onDismissRequest: () -> Unit) {
    val url by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    val latest by TvPerfDiagnostics.latestHeadline.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    val scheme = MaterialTheme.colorScheme
    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        heightFraction = null,
        maxWidth = 720.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 网页按地址里的 #settings/maintain 直接停在「维护」那一组
            RemoteQrCode(url?.let { "$it#settings/maintain" }, 210.dp, 24.dp)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Lang.tv_perf_diag_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                RemoteConnectionStatus(url, hostChanged, phoneConnected, MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Lang.tv_perf_diag_dialog_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                val status = statusText()
                Text(
                    status ?: latest?.let { stringResource(Lang.tv_perf_diag_latest, it) } ?: stringResource(Lang.tv_perf_diag_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status != null) scheme.primary else scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                AniFocusActionButton(onDismissRequest, Modifier.tvWindowInitialFocus()) {
                    Text(stringResource(Lang.tv_remote_control_close), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
