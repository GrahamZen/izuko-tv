/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.settings

import io.ktor.http.Url
import me.him188.ani.app.data.models.preference.ProxyConfig

/**
 * 请求失败是不是因为连不上代理 [proxy] 本身 (提供代理的应用没在跑、地址填错): 设了代理时连接都先连到代理, 连接被拒、连不上、解析不出时
 * 报错里写的就是代理的地址; 经代理到不了目标站时代理会回 HTTP 错误, 不是这种连接失败. 是就返回给人看的代理地址 (`主机:端口`), 否则 null.
 */
fun unreachableProxyOf(error: Throwable?, proxy: ProxyConfig?): String? {
    val url = proxy?.url?.let { runCatching { Url(it) }.getOrNull() } ?: return null
    val host = url.host.takeIf { it.isNotBlank() } ?: return null
    var e = error
    var depth = 0
    while (e != null && depth++ < MAX_CAUSE_DEPTH) {
        if (e.message?.contains(host) == true) return "$host:${url.port}"
        e = e.cause
    }
    return null
}

/** 往 cause 里找几层 (仓库异常包着网络异常, 网络异常再包着系统的连接错误). */
private const val MAX_CAUSE_DEPTH = 8
