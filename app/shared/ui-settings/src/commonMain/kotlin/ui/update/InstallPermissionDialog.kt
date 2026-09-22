/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_about_app_name
import me.him188.ani.app.ui.lang.settings_update_install_permission_message
import me.him188.ani.app.ui.lang.settings_update_install_permission_open_settings
import me.him188.ani.app.ui.lang.settings_update_install_permission_title
import me.him188.ani.app.ui.lang.settings_update_popup_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * 下载更新之前先要到「安装未知应用」的授权 (见 [AppUpdateViewModel.installPermissionRequest]).
 *
 * 讲清楚两件事: 要去系统设置里打开一个开关; 打开之后系统会把本应用关掉 (Android 11 的做法), 重新打开就会继续更新.
 * 不讲的话, 应用在设置页里突然没了, 用户会以为是崩了.
 *
 * 遥控器形态用居中大面板 (与其余面板同一形态), 指针设备用普通对话框.
 */
@Composable
fun InstallPermissionDialog(
    onOpenSettings: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val appName = stringResource(Lang.settings_about_app_name)
    val title = stringResource(Lang.settings_update_install_permission_title)
    val message = stringResource(Lang.settings_update_install_permission_message, appName)
    val openSettings = stringResource(Lang.settings_update_install_permission_open_settings)
    val cancel = stringResource(Lang.settings_update_popup_cancel)

    if (LocalAniUiBehavior.current.panelsAsCenteredDialogs) {
        AniCenteredPanelDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title) },
            widthFraction = 0.5f,
            heightFraction = 0.5f,
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismissRequest) { Text(cancel) }
                    Spacer(Modifier.width(8.dp))
                    // 弹窗是独立窗口, 不指定的话遥控器上焦点不在任何按钮上
                    Button(onClick = onOpenSettings, modifier = Modifier.tvWindowInitialFocus()) { Text(openSettings) }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { Button(onClick = onOpenSettings) { Text(openSettings) } },
            dismissButton = { TextButton(onClick = onDismissRequest) { Text(cancel) } },
        )
    }
}
