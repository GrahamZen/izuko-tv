/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.android.migration.CacheMigrationImport.UiState
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_update_migration_cache_failed
import me.him188.ani.app.ui.lang.settings_update_migration_cache_failed_title
import me.him188.ani.app.ui.lang.settings_update_migration_cache_later
import me.him188.ani.app.ui.lang.settings_update_migration_cache_no_space
import me.him188.ani.app.ui.lang.settings_update_migration_cache_no_space_title
import me.him188.ani.app.ui.lang.settings_update_migration_cache_preparing
import me.him188.ani.app.ui.lang.settings_update_migration_cache_progress
import me.him188.ani.app.ui.lang.settings_update_migration_cache_restarting
import me.him188.ani.app.ui.lang.settings_update_migration_cache_retry
import me.him188.ani.app.ui.lang.settings_update_migration_cache_title
import me.him188.ani.app.ui.lang.settings_update_migration_cache_warning
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.stringResource

/**
 * 从旧包搬缓存时盖在整个界面上的进度页 (见 [CacheMigrationImport]).
 *
 * 搬运期间不让关、也不让操作下层界面: 这时新建的缓存会与搬来的抢同一个位置, 在播放的也可能正好是
 * 还没搬到的那一集. 缓存不多时几秒就过去; 搬完自动重启.
 *
 * 空间不够或中断时给「重试」与「以后再说」, 后者下次启动接着搬.
 */
@Composable
fun CacheMigrationOverlay() {
    val state by CacheMigrationImport.uiState.collectAsStateWithLifecycle()
    val current = state
    if (current is UiState.Idle) return
    val context = LocalContext.current
    val settled = current is UiState.NeedSpace || current is UiState.Failed

    Dialog(
        onDismissRequest = { if (settled) CacheMigrationImport.postpone() },
        properties = DialogProperties(
            dismissOnBackPress = settled,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 64.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (current) {
                        is UiState.NeedSpace -> {
                            Text(
                                stringResource(Lang.settings_update_migration_cache_no_space_title),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                stringResource(Lang.settings_update_migration_cache_no_space, current.neededBytes.bytes.toString()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        is UiState.Failed -> {
                            Text(
                                stringResource(Lang.settings_update_migration_cache_failed_title),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                stringResource(Lang.settings_update_migration_cache_failed, current.message),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        else -> {
                            Text(
                                stringResource(Lang.settings_update_migration_cache_title),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            ProgressBody(current)
                        }
                    }

                    if (settled) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { CacheMigrationImport.postpone() }) {
                                Text(stringResource(Lang.settings_update_migration_cache_later))
                            }
                            Spacer(Modifier.width(8.dp))
                            // 独立窗口, 不指定的话遥控器上焦点不在任何按钮上
                            Button(
                                onClick = { CacheMigrationImport.retry(context) },
                                modifier = Modifier.tvWindowInitialFocus(),
                            ) {
                                Text(stringResource(Lang.settings_update_migration_cache_retry))
                            }
                        }
                    } else {
                        // 搬运期间最要紧的一句: 这时卸载旧版, 没搬完的就没了
                        Text(
                            stringResource(Lang.settings_update_migration_cache_warning),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBody(state: UiState) {
    when (state) {
        is UiState.Transferring -> {
            Text(
                stringResource(
                    Lang.settings_update_migration_cache_progress,
                    state.doneFiles, state.totalFiles,
                    state.doneBytes.bytes.toString(), state.totalBytes.bytes.toString(),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(
                progress = { if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 1f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is UiState.Restarting -> {
            Text(stringResource(Lang.settings_update_migration_cache_restarting), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
        }

        else -> {
            Text(stringResource(Lang.settings_update_migration_cache_preparing), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
