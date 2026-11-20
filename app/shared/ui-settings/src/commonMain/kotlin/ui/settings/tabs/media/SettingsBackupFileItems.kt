/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_danmaku_cancel
import me.him188.ani.app.ui.lang.settings_danmaku_confirm
import me.him188.ani.app.ui.lang.settings_storage_backup_op_backup_error
import me.him188.ani.app.ui.lang.settings_storage_backup_op_export_file
import me.him188.ani.app.ui.lang.settings_storage_backup_op_export_file_description
import me.him188.ani.app.ui.lang.settings_storage_backup_op_export_file_done
import me.him188.ani.app.ui.lang.settings_storage_backup_op_import_file
import me.him188.ani.app.ui.lang.settings_storage_backup_op_import_file_description
import me.him188.ani.app.ui.lang.settings_storage_backup_op_import_file_invalid
import me.him188.ani.app.ui.lang.settings_storage_backup_op_restore_succees
import me.him188.ani.app.ui.lang.settings_storage_backup_op_restore_warning
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.TextItem
import me.him188.ani.utils.coroutines.IO_
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * 设置备份的文件那两行 (接在剪贴板那两行后面, 见 [BackupSettings]): 导出成文件存在设备上, 重装或换设备后从文件导入。
 * 触屏设备上不用扫码去 Web 控制台; 内容同剪贴板那份, 剪贴板里的备份存成文本文件、电视上控制台导出的文件也能导入。
 */
@Composable
internal fun SettingsScope.SettingsBackupFileItems(state: CacheDirectoryGroupState) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var confirmImport by remember { mutableStateOf(false) }

    val picker = rememberFilePickerLauncher(type = FileKitType.File("json", "txt")) { file ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            val restored = try {
                val content = withContext(Dispatchers.IO_) { file.readBytes().decodeToString() }
                content.isNotBlank() && state.onRestoreSettings(content)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            toaster.toast(
                getStringOffMain(
                    if (restored) Lang.settings_storage_backup_op_restore_succees else Lang.settings_storage_backup_op_import_file_invalid,
                ),
            )
        }
    }

    TextItem(
        onClick = {
            scope.launch {
                val saved = try {
                    val data = state.onGetBackupData()
                    val target = FileKit.openFileSaver(suggestedName = backupFileName(), extension = "json")
                    if (target != null) withContext(Dispatchers.IO_) { target.write(data.encodeToByteArray()) }
                    target != null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    toaster.toast(getStringOffMain(Lang.settings_storage_backup_op_backup_error))
                    return@launch
                }
                if (saved) toaster.toast(getStringOffMain(Lang.settings_storage_backup_op_export_file_done))
            }
        },
        title = { Text(stringResource(Lang.settings_storage_backup_op_export_file)) },
        description = { Text(stringResource(Lang.settings_storage_backup_op_export_file_description)) },
    )
    TextItem(
        onClick = { confirmImport = true },
        title = { Text(stringResource(Lang.settings_storage_backup_op_import_file)) },
        description = { Text(stringResource(Lang.settings_storage_backup_op_import_file_description)) },
    )

    if (confirmImport) {
        AniAlertDialog(
            { confirmImport = false },
            icon = { Icon(Icons.Rounded.FileOpen, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Lang.settings_storage_backup_op_import_file)) },
            text = { Text(stringResource(Lang.settings_storage_backup_op_restore_warning)) },
            confirmButton = {
                AniTextButton({
                    confirmImport = false
                    picker.launch()
                }) {
                    Text(stringResource(Lang.settings_danmaku_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                AniTextButton({ confirmImport = false }) { Text(stringResource(Lang.settings_danmaku_cancel)) }
            },
        )
    }
}

/** 导出的文件名: `izuko-settings-20261126` (扩展名另给). */
private fun backupFileName(): String {
    val date = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "izuko-settings-" + date.toString().replace("-", "")
}
