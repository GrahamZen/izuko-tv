/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import io.ktor.client.HttpClientConfig

/**
 * [getPlatformKtorEngine] 引擎在系统 DNS 解析不出时改用最近一次解析出的地址或公共 DNS 的结果 (见 JVM 端的 `FallbackDns`).
 * 使用其他引擎时不做任何设置.
 */
expect fun HttpClientConfig<*>.engineFallbackDns()
