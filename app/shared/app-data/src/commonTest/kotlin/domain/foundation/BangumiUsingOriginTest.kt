/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 是不是在用官方直连 (自家放在 Cloudflare 上的服务据此决定先直连还是先走加密直连):
 * 看请求实际落在哪条线路; 还没落地时只有「只开着官方」才算.
 */
class BangumiUsingOriginTest {
    @Test
    fun `follows the route requests settled on and ignores settles of an older routing`() = runTest {
        val settings = MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO))
        val provider = BangumiEndpointProvider(
            settings = settings,
            mirrors = flowOf(listOf("bangumi.vip")),
            scope = backgroundScope,
            echAvailable = true,
        ).also { runCurrent() }
        provider.reportSettled(provider.currentRouting!!, BangumiRoute.Mirror("bangumi.vip"))
        assertFalse(provider.usingOrigin)
        provider.reportSettled(provider.currentRouting!!, BangumiRoute.Origin)
        assertTrue(provider.usingOrigin)
        // 关掉官方 (加密直连 + 镜像): 官方那个旧落点不算数
        settings.value = BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR)
        runCurrent()
        assertFalse(provider.usingOrigin)
    }

    @Test
    fun `origin listed first does not count before it has answered`() = runTest {
        val provider = BangumiEndpointProvider(
            settings = flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO)),
            mirrors = flowOf(listOf("bangumi.vip")),
            scope = backgroundScope,
            echAvailable = true,
        ).also { runCurrent() }
        // 官方排第一, 后面还有加密直连与镜像: 一个请求都没落地时不当成在用官方
        assertFalse(provider.usingOrigin)
    }

    @Test
    fun `origin as the only route counts before any request settles`() = runTest {
        val provider = BangumiEndpointProvider(
            settings = flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.DIRECT)),
            mirrors = flowOf(listOf("bangumi.vip")),
            scope = backgroundScope,
            echAvailable = true,
        ).also { runCurrent() }
        assertTrue(provider.usingOrigin)
    }
}
