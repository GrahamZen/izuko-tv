/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「有新版本」的提示卡 (见 [UpdateNotifier]) 在不在: 打开应用时它会抢焦点, TV 的通知弹窗等它走了 (关掉、开始下载、20 秒自己消失) 再出,
 * 每次进应用最多盖一层 (见 docs/contributing/code/notices.md).
 */
object UpdatePromptState {
    private val _cardShown = MutableStateFlow(false)
    val cardShown: StateFlow<Boolean> = _cardShown.asStateFlow()

    internal fun setCardShown(shown: Boolean) {
        _cardShown.value = shown
    }
}
