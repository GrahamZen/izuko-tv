/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.onboarding

import me.him188.ani.app.platform.Context
import platform.Foundation.NSUserDefaults

/** 存在 NSUserDefaults (同安卓存本机偏好). */
actual object TvOnboardingGate {
    private const val KEY_DONE = "tv_onboarding.done"

    actual fun isPending(context: Context): Boolean = !NSUserDefaults.standardUserDefaults.boolForKey(KEY_DONE)

    actual fun markDone(context: Context) {
        NSUserDefaults.standardUserDefaults.setBool(true, forKey = KEY_DONE)
    }

    actual fun markDoneNow(context: Context) {
        NSUserDefaults.standardUserDefaults.setBool(true, forKey = KEY_DONE)
    }

    actual fun reset(context: Context) {
        NSUserDefaults.standardUserDefaults.setBool(false, forKey = KEY_DONE)
    }
}
