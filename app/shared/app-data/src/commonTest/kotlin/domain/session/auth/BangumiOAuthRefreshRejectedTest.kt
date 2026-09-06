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
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.RepositoryAuthorizationException
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** bgm 的换 token 接口失败时回 400 + `{"error": …}`; 生产的客户端开着 expectSuccess, 与这里一样. */
class BangumiOAuthRefreshRejectedTest {
    private fun clientReplying(error: String) = BangumiOAuthClient(
        HttpClient(
            MockEngine {
                respond(
                    """{"error":"$error","error_description":"x"}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { expectSuccess = true }.asScopedHttpClient(),
        clientId = "id",
        clientSecret = "secret",
    )

    @Test
    fun `rejected refresh token means logged out`() = runTest {
        assertFailsWith<RepositoryAuthorizationException> { clientReplying("invalid_grant").refresh("rt") }
    }

    @Test
    fun `misconfigured client is not a logout`() = runTest {
        val e = assertFailsWith<RepositoryException> { clientReplying("invalid_client").refresh("rt") }
        assertFalse(e is RepositoryAuthorizationException)
    }

    @Test
    fun `expired authorization code is not a logout`() = runTest {
        val e = assertFailsWith<RepositoryException> { clientReplying("invalid_grant").exchangeCode("code") }
        assertFalse(e is RepositoryAuthorizationException)
    }
}
