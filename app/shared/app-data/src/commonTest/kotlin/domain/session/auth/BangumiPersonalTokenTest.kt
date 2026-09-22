/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.repository.RepositoryAuthorizationException
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 个人令牌登录的校验: 只用令牌问一次「我是谁」. 令牌被拒与连不上要分开 —— 界面上一个让用户重新生成令牌,
 * 一个让用户去改连接方式或设置代理.
 */
class BangumiPersonalTokenTest {
    private fun oauthClient(handler: MockRequestHandler) = BangumiOAuthClient(
        client = HttpClient(MockEngine(handler)) { expectSuccess = false }.asScopedHttpClient(),
        clientId = "id",
        clientSecret = "secret",
    )

    @Test
    fun `令牌能用 — 返回用户名，只打 API 子域`() = runTest {
        val sent = mutableListOf<HttpRequestData>()
        val client = oauthClient { req ->
            sent += req
            respond(
                """{"id":1,"username":"sai","nickname":"Sai"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        assertEquals("sai", client.verifyToken("tok"))
        assertEquals("next.bgm.tv", sent.single().url.host)
        assertEquals("Bearer tok", sent.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `令牌被拒是授权错误`() = runTest {
        val client = oauthClient { respond("""{"title":"Unauthorized"}""", HttpStatusCode.Unauthorized) }
        assertFailsWith<RepositoryAuthorizationException> { client.verifyToken("bad") }
    }

    @Test
    fun `连不上是网络错误，不是令牌无效`() = runTest {
        val client = oauthClient { throw IOException("blocked") }
        assertFailsWith<RepositoryNetworkException> { client.verifyToken("tok") }
    }
}
