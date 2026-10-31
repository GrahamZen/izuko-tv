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
import kotlin.time.TimeMark

/**
 * 系统报的网络状态: 有没有可用的默认网络. 不看能不能上外网: 系统的联网检查在国内网络下不一定准, 路由器连着但外网不通时这里仍算有网.
 */
interface NetworkStatus {
    /** 有可用的默认网络, 而且这个应用没被系统拦着用它 (省电模式). */
    val isAvailable: StateFlow<Boolean>

    /** 最近一次没网的时刻; 这个对象活着期间没断过为 `null`. */
    val lastLostAt: TimeMark?

    /** 不知道网络状态的平台: 永远算有网. */
    object AlwaysAvailable : NetworkStatus {
        override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val lastLostAt: TimeMark? get() = null
    }
}

expect fun Context.createNetworkStatus(): NetworkStatus
