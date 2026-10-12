/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.settings

import me.him188.ani.app.data.models.preference.ProxyConfig
import me.him188.ani.app.data.repository.RepositoryNetworkException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnreachableProxyTest {
    private val proxy = ProxyConfig(url = "http://127.48.12.138:41987")

    @Test
    fun `connection to the proxy itself refused`() {
        // 同用户日志: 仓库异常包着连接错误, 报错里写的是代理的地址
        val error = RepositoryNetworkException(
            cause = IllegalStateException(
                "Failed to connect to /127.48.12.138:41987",
                IllegalStateException("failed to connect to /127.48.12.138 (port 41987) from /127.0.0.1 (port 33234) after 30000ms"),
            ),
        )
        assertEquals("127.48.12.138:41987", unreachableProxyOf(error, proxy))
    }

    @Test
    fun `no proxy or an unrelated failure is not blamed on the proxy`() {
        val refused = IllegalStateException("Failed to connect to /127.48.12.138:41987")
        assertNull(unreachableProxyOf(refused, null))
        assertNull(unreachableProxyOf(IllegalStateException("HTTP 502 Bad Gateway from bgm.tv"), proxy))
        assertNull(unreachableProxyOf(null, proxy))
    }

    @Test
    fun `proxy without a port uses the scheme default`() {
        val error = IllegalStateException("Failed to connect to proxy.lan/192.168.1.2:80")
        assertEquals("proxy.lan:80", unreachableProxyOf(error, ProxyConfig(url = "http://proxy.lan")))
    }
}
