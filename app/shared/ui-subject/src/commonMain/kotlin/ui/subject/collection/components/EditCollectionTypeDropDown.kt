/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.collection.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenu
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenuItem
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.*
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.*


@Composable
fun EditCollectionTypeDropDown(
    state: EditableSubjectCollectionTypeState,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation by state.presentationFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    EditCollectionTypeDropDown(
        currentType = presentation.selfCollectionType,
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        onClick = {
            scope.launch {
                val error = state.setSelfCollectionType(it.type)
                if (error != null) {
                    toaster.showLoadError(error)
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * A drop down menu to edit the collection type of a subject.
 * Also includes a dialog to set all episodes as watched when the user attempts to mark the subject as [UnifiedCollectionType.DONE].
 *
 * 当前收藏状态画成菜单的选中项 (TV 上选中色 + ✓, 手机上主题色文字, 见 [AniDropdownMenuItem]).
 */
@Composable
fun EditCollectionTypeDropDown(
    currentType: UnifiedCollectionType?,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onClick: (action: SubjectCollectionAction) -> Unit,
    modifier: Modifier = Modifier,
    actions: List<SubjectCollectionAction> = SubjectCollectionActionsForEdit,
    // bangumi 没有"取消收藏"的接口 (v0 与 p1 的 DELETE 都是 404), 这个入口整体去掉
    showDelete: Boolean = false,
) {
    var showConfirmDeleteDialog by rememberSaveable { mutableStateOf(false) }
    AniDropdownMenu(
        expanded,
        onDismissRequest = onDismissRequest,
        offset = DpOffset(x = 0.dp, y = 4.dp),
        // 菜单是独立窗口, 按键到不了播放页的根按键路由 —— 从播放器内嵌详情页点开时,
        // 画面还在后面放着, 遥控器播放暂停键仍该管用. 播放页之外为空操作
        modifier = modifier.tvOverlayWindowKeys(onDismissRequest),
    ) {
        for (action in actions) {
            if (!showDelete && action == SubjectCollectionActions.DeleteCollection) continue

            AniDropdownMenuItem(
                text = { action.title() },
                leadingIcon = { action.icon() },
                selected = currentType == action.type,
                onClick = {
                    if (action == SubjectCollectionActions.DeleteCollection) {
                        showConfirmDeleteDialog = true
                    } else {
                        onClick(action)
                        onDismissRequest()
                    }
                },
            )
        }

        if (showConfirmDeleteDialog) {
            AniAlertDialog(
                onDismissRequest = { showConfirmDeleteDialog = false },
                modifier = Modifier.tvOverlayWindowKeys { showConfirmDeleteDialog = false }, // 同上
                title = { Text(stringResource(Lang.subject_collection_delete_confirm_title)) },
                text = { Text(stringResource(Lang.subject_collection_delete_confirm_message)) },
                icon = { SubjectCollectionActions.DeleteCollection.icon() },
                confirmButton = {
                    AniTextButton(
                        onClick = {
                            onClick(SubjectCollectionActions.DeleteCollection)
                            onDismissRequest()
                            showConfirmDeleteDialog = false
                        },
                    ) {
                        Text(
                            stringResource(Lang.subject_collection_delete_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    AniTextButton(
                        onClick = { showConfirmDeleteDialog = false },
                    ) {
                        Text(stringResource(Lang.subject_collection_cancel))
                    }
                },
            )
        }
    }
}
