/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource

actual fun Context.createNetworkStatus(): NetworkStatus = AndroidNetworkStatus(this)

@SuppressLint("MissingPermission")
private class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val available = MutableStateFlow(connectivity?.activeNetwork != null)

    override val isAvailable: StateFlow<Boolean> = available.asStateFlow()

    @Volatile
    override var lastLostAt: TimeMark? = null
        private set

    init {
        // 注册失败 (同一个应用的回调数量有上限) 时永远算有网, 只是少了等网这一步
        runCatching { connectivity?.registerDefaultNetworkCallback(Callback()) }
            .onFailure { logger.warn(it) { "Failed to watch the default network" } }
    }

    private fun update(nowAvailable: Boolean, why: String) {
        if (!nowAvailable) lastLostAt = TimeSource.Monotonic.markNow()
        if (available.value != nowAvailable) logger.info { "Network available: $nowAvailable ($why)" }
        available.value = nowAvailable
    }

    private inner class Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(true, "default network available")

        override fun onLost(network: Network) = update(false, "default network lost")

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) =
            update(!blocked, if (blocked) "blocked by the system" else "unblocked")
    }

    private companion object {
        val logger = logger<AndroidNetworkStatus>()
    }
}
