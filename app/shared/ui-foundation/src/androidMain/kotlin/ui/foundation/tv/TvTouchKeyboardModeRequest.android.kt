/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun rememberTvTouchKeyboardModeRequest(): () -> Unit {
    val view = LocalView.current
    val inputModeManager = LocalInputModeManager.current
    return remember(view, inputModeManager) {
        {
            val focused = view.findFocus()
            if (focused != null && focused !== view && view.isInTouchMode) {
                // 焦点在嵌着的原生视图上: 由它自己离开触摸模式, 焦点留在它身上 (Compose 随触摸模式变化把输入模式改成 Keyboard)
                focused.requestFocusFromTouch()
            } else {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
        }
    }
}
