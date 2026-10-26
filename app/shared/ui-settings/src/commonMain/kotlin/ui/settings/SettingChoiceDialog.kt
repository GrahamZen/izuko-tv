/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniButton
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.AniTextButton

/** [SettingChoiceDialog] 里的一个动作 (取消不算). */
class SettingChoiceDialogAction(val text: String, val testTag: String? = null, val onClick: () -> Unit)

/**
 * 改设置之前问用户的弹窗: 说明 + 几个动作 + 取消.
 *
 * 默认焦点在「取消」上 —— 遥控器上顺手按一下确定, 不该就把账号交给第三方或改掉连接方式.
 * 遥控器形态用居中大面板 (与其余面板同一形态) 与弹窗动作按钮 ([AniFocusActionButton]: 聚焦才是主题色,
 * M3 的实底按钮一直是主题色, 电视上分不出焦点在哪颗), 指针设备用普通对话框. 按钮放不下一行时折行.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingChoiceDialog(
    title: String,
    message: String,
    actions: List<SettingChoiceDialogAction>,
    cancel: String,
    onDismissRequest: () -> Unit,
    cancelTestTag: String? = null,
    heightFraction: Float = 0.6f,
) {
    val centered = LocalAniUiBehavior.current.panelsAsCenteredDialogs
    val buttons = @Composable {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (action in actions) {
                val actionModifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier
                if (centered) {
                    AniFocusActionButton(onClick = action.onClick, modifier = actionModifier) {
                        Text(action.text, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    AniTextButton(onClick = action.onClick, modifier = actionModifier) {
                        Text(action.text)
                    }
                }
            }
            // 弹窗是独立窗口, 不指定的话遥控器上焦点不在任何按钮上
            val cancelModifier = Modifier.tvWindowInitialFocus().then(cancelTestTag?.let { Modifier.testTag(it) } ?: Modifier)
            if (centered) {
                AniFocusActionButton(onClick = onDismissRequest, modifier = cancelModifier) {
                    Text(cancel, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                AniButton(onClick = onDismissRequest, modifier = cancelModifier) {
                    Text(cancel)
                }
            }
        }
    }
    if (centered) {
        AniCenteredPanelDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title) },
            widthFraction = 0.6f,
            heightFraction = heightFraction,
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                buttons()
            }
        }
    } else {
        AniAlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { buttons() },
        )
    }
}
