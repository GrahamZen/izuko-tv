/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.migration

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_update_migration_uninstall_confirm
import me.him188.ani.app.ui.lang.settings_update_migration_uninstall_dismiss
import me.him188.ani.app.ui.lang.settings_update_migration_uninstall_message
import me.him188.ani.app.ui.lang.settings_update_migration_uninstall_message_with_caches
import me.him188.ani.app.ui.lang.settings_update_migration_uninstall_title
import org.jetbrains.compose.resources.stringResource

/**
 * 迁移完成后提示卸载旧包.
 *
 * 旧包 (跳板包) 不会被新包替换, 两个应用会一直同时装着, 而用户未必知道旧的已经没用了 —— 留着它,
 * 它的更新检查还会一直提示去装新应用. 这里在设置接管、**缓存也搬完**之后问一次, 确认就弹系统的卸载框.
 * 缓存没搬完 (空间不够、中断) 时不问: 这时卸载, 还在旧包里的缓存就没了.
 *
 * - 「保留旧版」: 以后不再问 ([SettingsMigration.dismissUninstallPrompt]).
 * - 返回键: 只是这次先不问, 下次冷启动还会出现.
 * - 从系统卸载框回来时重新看一眼旧包还在不在, 卸掉了提示自然消失.
 */
@Composable
fun LegacyAppUninstallPrompt() {
    val context = LocalContext.current
    val importedThisProcess by SettingsMigration.importSucceededThisProcess.collectAsStateWithLifecycle()
    val importedBefore = remember { SettingsMigration.wasImported(context) }
    var legacyInstalled by remember { mutableStateOf(SettingsMigration.isLegacyInstalled(context)) }
    var dismissed by remember { mutableStateOf(SettingsMigration.isUninstallPromptDismissed(context)) }
    var hiddenForNow by remember { mutableStateOf(false) }
    remember { CacheMigrationImport.phase(context) } // 读一次, 让 phaseFlow 有值
    val cachePhase by CacheMigrationImport.phaseFlow.collectAsStateWithLifecycle()
    val cacheUi by CacheMigrationImport.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        legacyInstalled = SettingsMigration.isLegacyInstalled(context)
        onPauseOrDispose { }
    }

    if (!(importedThisProcess || importedBefore) || !legacyInstalled || dismissed || hiddenForNow) return
    if (cachePhase != CacheMigrationImport.Phase.DONE || cacheUi !is CacheMigrationImport.UiState.Idle) return

    val legacyLabel = remember { SettingsMigration.legacyLabel(context) }
    val importedCaches = remember(cachePhase) { CacheMigrationImport.importedCount(context) }
    AlertDialog(
        onDismissRequest = { hiddenForNow = true },
        title = { Text(stringResource(Lang.settings_update_migration_uninstall_title)) },
        text = {
            Text(
                if (importedCaches > 0) {
                    stringResource(Lang.settings_update_migration_uninstall_message_with_caches, legacyLabel, importedCaches)
                } else {
                    stringResource(Lang.settings_update_migration_uninstall_message, legacyLabel)
                },
            )
        },
        confirmButton = {
            Button(
                onClick = { SettingsMigration.requestUninstallLegacy(context) },
                // 对话框是独立窗口, 遥控器上不指定的话焦点不在任何按钮上, 按确定键没反应
                modifier = Modifier.tvWindowInitialFocus(),
            ) {
                Text(stringResource(Lang.settings_update_migration_uninstall_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    SettingsMigration.dismissUninstallPrompt(context)
                    dismissed = true
                },
            ) {
                Text(stringResource(Lang.settings_update_migration_uninstall_dismiss))
            }
        },
    )
}
