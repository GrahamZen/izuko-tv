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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 弹「连不上官方」之前的复查 ([BangumiOriginProbe]).
 */
class BangumiOriginProbeTest {
    /** 装着只剩镜像的路由: 复查必须绕过它, 直接问官方. */
    private fun probe(hosts: MutableList<String>, origin: HttpStatusCode?): BangumiOriginProbe {
        val engine = MockEngine { req ->
            hosts += req.url.host
            val status = if (req.url.host.endsWith("bgm.tv")) origin ?: throw IOException("blocked") else HttpStatusCode.OK
            respond("", status)
        }
        val client = HttpClient(engine) { expectSuccess = true }
        BangumiRoutingFeatureHandler(flowOf(BangumiRouting(listOf(BangumiRoute.Mirror("bangumi.vip")))))
            .applyToClient(client, true)
        val scoped = client.asScopedHttpClient()
        return BangumiOriginProbe { scoped }
    }

    @Test
    fun `官方回应了 — 4xx 也算，不经镜像`() = runTest {
        val hosts = mutableListOf<String>()
        assertTrue(probe(hosts, HttpStatusCode.NotFound).answers())
        assertTrue(probe(hosts, HttpStatusCode.OK).answers())
        assertEquals(listOf("next.bgm.tv", "next.bgm.tv"), hosts)
    }

    @Test
    fun `官方连不上 — 没回应`() = runTest {
        val hosts = mutableListOf<String>()
        assertFalse(probe(hosts, null).answers())
        assertEquals(listOf("next.bgm.tv"), hosts)
    }
}
