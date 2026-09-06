/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.log

/** 扫码把日志传到手机的服务此刻的样子 (安卓上见 runLogLanShare). */
sealed interface LogLanShareState {
    data object Starting : LogLanShareState
    data object NoNetwork : LogLanShareState
    data object Failed : LogLanShareState
    data class Ready(val url: String) : LogLanShareState
}
