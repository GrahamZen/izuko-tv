/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 应用内的本机监听服务 (HLS 代理、分块播放代理、iOS 调试控制台) 从后台回到前台时在原端口重新监听.
 *
 * iOS 在应用挂起期间会收回监听 socket (Apple TN2277), 挂在上面的 accept 不一定因此报错醒来, 服务就一直收不到连接:
 * 播放器拿不到数据, 调试控制台连不上. 每个服务在这里登记「关掉当前的监听 socket」: 回到前台时逐个执行,
 * 各服务的 accept 循环随之报错, 走它自己的重新监听 (端口不变, 播放器手里带端口的地址照常可用).
 * 只有 iOS 有回到前台的事件; 其他平台登记了也不会执行.
 */
object ForegroundListenerRefresh {
    private val closers = MutableStateFlow<List<() -> Unit>>(emptyList())
    private val observing = atomic(false)

    /** 登记一个服务的「关掉当前监听」; 服务关闭时关掉返回值, 撤销登记. */
    fun register(closeListener: () -> Unit): AutoCloseable {
        if (observing.compareAndSet(expect = false, update = true)) {
            observeReturnToForeground { refreshAll() }
        }
        closers.update { it + closeListener }
        return AutoCloseable { closers.update { list -> list.filterNot { it === closeListener } } }
    }

    internal fun refreshAll() {
        val current = closers.value
        logger.info { "Back in foreground, re-listening ${current.size} local server(s)" }
        current.forEach { runCatching { it() } }
    }

    private val logger = logger<ForegroundListenerRefresh>()
}

/** 应用每次从后台回到前台时在主线程调 [action]; 没有这个事件的平台什么也不做. */
internal expect fun observeReturnToForeground(action: () -> Unit)
