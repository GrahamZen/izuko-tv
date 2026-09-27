/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

/**
 * 结束当前进程并重新打开应用. 换用户时用: 一个进程只属于一个用户 (见 `UserProfiles`).
 *
 * 只有 Android 支持; 其他平台是 [Unsupported], 界面上不给换用户的入口.
 */
interface AppRestarter {
    val isSupported: Boolean

    /** 重开应用. 当前进程随后被结束, 调用方不要指望之后的代码还能跑完. */
    fun restart()

    object Unsupported : AppRestarter {
        override val isSupported: Boolean get() = false
        override fun restart() = throw UnsupportedOperationException("Restarting the app is not supported on this platform")
    }
}
