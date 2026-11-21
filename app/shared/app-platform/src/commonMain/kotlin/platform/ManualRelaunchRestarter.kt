/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用不能自己重开的平台 (iOS) 用的 [AppRestarter]: 要重开时不结束进程, 只记下 [requested], 界面根部据此盖一层关不掉的提示,
 * 请用户从后台关掉应用再打开。改成本地用户 / 改回 Bangumi 用户在调 [restart] 之前就把要写的都写完了 (见 UserProfileManager),
 * 之后这个进程不再用也不丢东西。
 */
object ManualRelaunchRestarter : AppRestarter {
    private val _requested = MutableStateFlow(false)

    /** 已经要求重开 (提示一直盖着, 直到进程被关掉). */
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    override val isSupported: Boolean get() = true

    override fun restart() {
        _requested.value = true
    }
}
