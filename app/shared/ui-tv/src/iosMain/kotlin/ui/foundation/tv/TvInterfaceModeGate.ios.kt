/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import me.him188.ani.app.platform.Context
import platform.Foundation.NSUserDefaults

/** 存在 NSUserDefaults (同安卓存本机偏好: 启动时同步读). */
actual object TvInterfaceModeGate {
    private const val KEY_TV_MODE = "tv_interface_mode.tv_mode"

    actual fun isTvMode(context: Context): Boolean = NSUserDefaults.standardUserDefaults.boolForKey(KEY_TV_MODE)

    actual fun setTvMode(context: Context, tvMode: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(tvMode, forKey = KEY_TV_MODE)
    }
}
