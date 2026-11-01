/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** iOS 上没有多用户入口: 选人页永远不出现. */
actual object TvUserProfilePicker {
    actual val visible: MutableStateFlow<Boolean> = MutableStateFlow(false)

    actual fun show(held: Boolean) {}
}

internal actual val tvProfileSwitchLanding: StateFlow<Any?> = MutableStateFlow<Any?>(null)
