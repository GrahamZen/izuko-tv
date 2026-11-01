/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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

/** 恢复后等一下再取东西: 刚连上时 DNS 可能还没好. */
val NETWORK_SETTLE: Duration = 1.seconds

/** 每次断网恢复发一个 (等 [NETWORK_SETTLE] 之后, 等的时候又断了就接着等). */
@OptIn(ExperimentalCoroutinesApi::class)
val NetworkStatus.recoveries: Flow<Unit>
    get() = flow {
        var wasDown = false
        emitAll(
            isAvailable.transformLatest { available ->
                if (!available) {
                    wasDown = true
                } else if (wasDown) {
                    delay(NETWORK_SETTLE)
                    wasDown = false
                    emit(Unit)
                }
            },
        )
    }
