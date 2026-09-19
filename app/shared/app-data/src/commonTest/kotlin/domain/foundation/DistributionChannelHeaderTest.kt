/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.foundation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `X-Ani-Distro-Channel` 会暴露"这是 Ani", 只能发给自己的服务器: 数据源与第三方站点用的是同一批 client.
 */
class DistributionChannelHeaderTest {
    private val handler = DistributionChannelFeatureHandler { "default" }

    private suspend fun headerSentTo(url: String): String? {
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers[AbstractDistributionChannelHandler.HEADER_DISTRO_CHANNEL]
            respond("ok")
        }
        val client = HttpClient(engine) {
            handler.applyToConfig(this) { "default" }
        }
        client.get(url)
        return seen
    }

    @Test
    fun `sent to the ani server placeholder host`() = runTest {
        assertEquals(
            "default",
            headerSentTo("https://${ServerListFeatureConfig.MAGIC_ANI_SERVER_HOST}/v1/test"),
        )
    }

    @Test
    fun `sent to ani own servers`() = runTest {
        assertEquals("default", headerSentTo("https://danmaku-cn.myani.org/v1/danmaku/1"))
        assertEquals("default", headerSentTo("https://auth.myani.org/v1/subject-relations/1"))
        assertEquals("default", headerSentTo("https://s1.animeko.openani.org/x"))
    }

    @Test
    fun `not sent to data sources and other third parties`() = runTest {
        assertNull(headerSentTo("https://example.com/search?keyword=x"))
        assertNull(headerSentTo("https://api.themoviedb.org/3/search/tv"))
        assertNull(headerSentTo("https://mikanani.me/RSS/Search"))
        // 域名里带 myani.org 但不是子域, 不能放行
        assertNull(headerSentTo("https://myani.org.example.com/x"))
    }
}
