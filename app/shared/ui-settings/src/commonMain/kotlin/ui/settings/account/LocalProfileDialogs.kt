/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.profile.SelfCollectionRecords
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.profile_clear_records_confirm
import me.him188.ani.app.ui.lang.profile_clear_records_text
import me.him188.ani.app.ui.lang.profile_clear_records_title
import me.him188.ani.app.ui.lang.profile_convert_local_clear
import me.him188.ani.app.ui.lang.profile_convert_local_confirm
import me.him188.ani.app.ui.lang.profile_convert_local_keep
import me.him188.ani.app.ui.lang.profile_convert_local_leftover
import me.him188.ani.app.ui.lang.profile_convert_local_text
import me.him188.ani.app.ui.lang.profile_convert_local_title
import me.him188.ani.app.ui.lang.settings_account_popup_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * 改成本地用户前的确认 (见 [me.him188.ani.app.domain.profile.LocalProfileConversion]).
 * 库里还留着之前登录时的收藏记录 ([leftover]) 就让人选留下 (当作本地收藏) 还是清除, 默认焦点在不丢数据的「留下」; 没有就只确认一下.
 */
@Composable
fun ConvertToLocalProfileDialog(
    leftover: SelfCollectionRecords.Counts,
    onConvert: (clearRecords: Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val defaultFocus = rememberDialogDefaultFocus()
    AniAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.profile_convert_local_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Lang.profile_convert_local_text))
                if (!leftover.isEmpty) {
                    Text(stringResource(Lang.profile_convert_local_leftover, leftover.collections, leftover.watchedEpisodes))
                }
            }
        },
        confirmButton = {
            if (leftover.isEmpty) {
                AniTextButton({ onConvert(false) }, Modifier.focusRequester(defaultFocus)) {
                    Text(stringResource(Lang.profile_convert_local_confirm))
                }
            } else {
                AniTextButton(
                    { onConvert(true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(Lang.profile_convert_local_clear))
                }
                AniTextButton({ onConvert(false) }, Modifier.focusRequester(defaultFocus)) {
                    Text(stringResource(Lang.profile_convert_local_keep))
                }
            }
        },
        dismissButton = {
            AniTextButton(onDismissRequest) { Text(stringResource(Lang.settings_account_popup_cancel)) }
        },
    )
}

/** 本地用户清除自己的收藏记录前的确认. 清了找不回来, 默认焦点在「取消」. */
@Composable
fun ClearSelfRecordsDialog(
    counts: SelfCollectionRecords.Counts,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val cancelFocus = rememberDialogDefaultFocus()
    AniAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.profile_clear_records_title)) },
        text = { Text(stringResource(Lang.profile_clear_records_text, counts.collections, counts.watchedEpisodes)) },
        confirmButton = {
            AniTextButton(
                onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(Lang.profile_clear_records_confirm))
            }
        },
        dismissButton = {
            AniTextButton(onDismissRequest, Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(Lang.settings_account_popup_cancel))
            }
        },
    )
}

/** 焦点驱动的界面上对话框窗口不会自动给初始焦点, 要显式请求 (否则整个对话框按不动); 挂在想默认聚焦的按钮上. */
@Composable
private fun rememberDialogDefaultFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    if (LocalAniUiBehavior.current.focusDrivenNavigation) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    return requester
}
