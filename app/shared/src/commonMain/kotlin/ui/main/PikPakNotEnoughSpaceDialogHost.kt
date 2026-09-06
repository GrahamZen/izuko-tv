/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.DismissDialogButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.pikpak_not_enough_space_message
import me.him188.ani.app.ui.lang.pikpak_not_enough_space_ok
import me.him188.ani.app.ui.lang.pikpak_not_enough_space_title
import me.him188.ani.torrent.pikpak.PikPakNotEnoughSpaceException
import org.jetbrains.compose.resources.stringResource

/**
 * 云盘剩余空间放不下要秒传的文件时弹出. 每个账号只报一次, 见 PikPakAccount.requireRoomFor.
 */
@Composable
internal fun PikPakNotEnoughSpaceDialogHost(events: Flow<PikPakNotEnoughSpaceException>) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(events) {
        events.collect { shown = true }
    }
    if (shown) {
        AniAlertDialog(
            onDismissRequest = { shown = false },
            // 可能在播放中弹出: 独立窗口里把遥控器全局键接回主窗口 (见 tvOverlayWindowKeys)
            modifier = Modifier.tvOverlayWindowKeys { shown = false },
            title = { Text(stringResource(Lang.pikpak_not_enough_space_title)) },
            text = { Text(stringResource(Lang.pikpak_not_enough_space_message)) },
            // 纯提示, 唯一的按钮就是"知道了"
            confirmButton = {
                DismissDialogButton(stringResource(Lang.pikpak_not_enough_space_ok)) { shown = false }
            },
        )
    }
}
