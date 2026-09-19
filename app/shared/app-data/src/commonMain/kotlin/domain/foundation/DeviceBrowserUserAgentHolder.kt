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

/**
 * 本机浏览器 UA 的持有者. 数据源由工厂创建, 拿不到 `Context`, 所以在启动接线时把取值方式装进来.
 *
 * 取值是惰性的: Android 上读 UA 会初始化 WebView, 不放在启动路径上.
 */
object DeviceBrowserUserAgentHolder {
    private val provider = atomic<(() -> String?)?>(null)
    private val cached = atomic<String?>(null)
    private val resolved = atomic(false)

    fun install(provider: () -> String?) {
        this.provider.value = provider
        resolved.value = false
        cached.value = null
    }

    /**
     * 本机浏览器的真实 UA, 取不到时为 `null` (调用方退回 client 自带的 UA).
     */
    val current: String?
        get() {
            if (!resolved.value) {
                cached.value = runCatching { provider.value?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() }
                resolved.value = true
            }
            return cached.value
        }
}
