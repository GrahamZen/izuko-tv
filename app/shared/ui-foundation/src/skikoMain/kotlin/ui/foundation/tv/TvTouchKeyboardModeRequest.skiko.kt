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

/** 没有嵌着的原生视图, 照常请求. */
@Composable
internal actual fun rememberTvTouchKeyboardModeRequest(): () -> Unit {
    val inputModeManager = LocalInputModeManager.current
    return remember(inputModeManager) { { inputModeManager.requestInputMode(InputMode.Keyboard) } }
}
