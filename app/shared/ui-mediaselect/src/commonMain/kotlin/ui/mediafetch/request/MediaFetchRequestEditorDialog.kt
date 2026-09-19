/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediafetch.request

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.saveable.mutableStateSaver
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.dismissDialogButton
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.aniDialogContainerColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.mediafetch_request_editor_continue_editing
import me.him188.ani.app.ui.lang.mediafetch_request_editor_discard
import me.him188.ani.app.ui.lang.mediafetch_request_editor_discard_confirmation
import me.him188.ani.app.ui.lang.mediafetch_request_editor_invalid_request
import me.him188.ani.app.ui.lang.mediafetch_request_editor_restore_names
import me.him188.ani.app.ui.lang.mediafetch_request_editor_save_and_refresh
import me.him188.ani.app.ui.lang.mediafetch_request_editor_title
import me.him188.ani.app.ui.lang.settings_danmaku_cancel
import me.him188.ani.datasources.api.source.MediaFetchRequest
import org.jetbrains.compose.resources.stringResource

/**
 * @see MediaFetchRequestEditor
 */
@Composable
fun MediaFetchRequestEditorDialog(
    fetchRequest: MediaFetchRequest,
    onDismissRequest: () -> Unit,
    onFetchRequestChange: (MediaFetchRequest) -> Unit,
    /**
     * 未经用户修改、由 Bangumi 信息生成的请求. 提供时显示「恢复 Bangumi 名称」按钮.
     */
    defaultFetchRequest: MediaFetchRequest? = null,
) {
    var editingRequest by rememberSaveable(
        fetchRequest,
        saver = mutableStateSaver(EditingMediaFetchRequest.Saver),
    ) {
        mutableStateOf(fetchRequest.toEditingMediaFetchRequest())
    }
    var showConfirmDiscard by rememberSaveable { mutableStateOf(false) }
    val onDismissRequestWrapped = {
        val hasChange = editingRequest != fetchRequest.toEditingMediaFetchRequest()
        if (hasChange) {
            showConfirmDiscard = true
        } else {
            onDismissRequest()
        }
    }

    val toaster = LocalToaster.current
    val invalidRequestText = stringResource(Lang.mediafetch_request_editor_invalid_request)
    val saveAndRefreshText = stringResource(Lang.mediafetch_request_editor_save_and_refresh)
    val cancelText = stringResource(Lang.settings_danmaku_cancel)
    val editRequestTitle = stringResource(Lang.mediafetch_request_editor_title)
    val discardText = stringResource(Lang.mediafetch_request_editor_discard)
    val continueEditingText = stringResource(Lang.mediafetch_request_editor_continue_editing)
    val discardConfirmationText = stringResource(Lang.mediafetch_request_editor_discard_confirmation)
    val restoreNamesText = stringResource(Lang.mediafetch_request_editor_restore_names)

    AlertDialog(
        onDismissRequestWrapped,
        // 独立窗口: 遥控器全局键接回主窗口 (见 tvOverlayWindowKeys)
        modifier = Modifier.tvOverlayWindowKeys(onDismissRequestWrapped),
        confirmButton = {
            TextButton(
                {
                    editingRequest.toMediaFetchRequestOrNull(fetchRequest.episodes)?.let {
                        onDismissRequestWrapped()
                        onFetchRequestChange(it)
                    } ?: toaster.toast(invalidRequestText)
                },
                enabled = editingRequest.toMediaFetchRequestOrNull(fetchRequest.episodes) != null,
            ) {
                Text(saveAndRefreshText)
            }
        },
        dismissButton = {
            Row {
                if (defaultFetchRequest != null) {
                    // 把编辑区整体退回 Bangumi 的名字 (分集字段同样退回默认); 保存后记住的关键词随之清掉
                    val defaultEditing = defaultFetchRequest.toEditingMediaFetchRequest()
                    TextButton(
                        onClick = { editingRequest = defaultEditing },
                        enabled = editingRequest != defaultEditing,
                    ) {
                        Text(restoreNamesText)
                    }
                }
                dismissDialogButton(cancelText, onDismissRequestWrapped)?.invoke()
            }
        },
        title = {
            Text(editRequestTitle)
        },
        text = {
            MediaFetchRequestEditor(
                editingRequest,
                { editingRequest = it },
                Modifier.fillMaxWidth(),
            )
        },
        containerColor = aniDialogContainerColor(),
    )

    if (showConfirmDiscard) {
        AlertDialog(
            onDismissRequest = {
                showConfirmDiscard = false
            },
            modifier = Modifier.tvOverlayWindowKeys { showConfirmDiscard = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDiscard = false
                        onDismissRequest()
                    },
                ) {
                    Text(discardText, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = dismissDialogButton(continueEditingText) { showConfirmDiscard = false },
            icon = {
                Icon(
                    Icons.Rounded.Delete, null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(discardConfirmationText)
            },
            containerColor = aniDialogContainerColor(),
        )
    }
}
