/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ios

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.him188.ani.app.platform.ManualRelaunchRestarter
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.profile_manual_relaunch
import org.jetbrains.compose.resources.stringResource

/**
 * 要重开应用时 (改成本地用户、改回 Bangumi 用户, 见 [ManualRelaunchRestarter]) 盖在最上面的提示: iOS 应用不能自己重开,
 * 请用户从后台关掉再打开。关不掉, 也点不到底下: 这时各仓库还按改之前的用户在跑。
 */
@Composable
internal fun ManualRelaunchPrompt() {
    val requested by ManualRelaunchRestarter.requested.collectAsState()
    if (!requested) return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 420.dp)) {
            Text(
                stringResource(Lang.profile_manual_relaunch),
                Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
