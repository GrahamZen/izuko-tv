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
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.session.BangumiSessionRefresher
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BangumiOAuthRelayTest {
    private val nonce = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `只认内网 IPv4 加端口`() {
        for (host in listOf("10.0.0.203:41891", "172.16.0.1:80", "172.31.255.255:65535", "192.168.1.5:8080", "100.64.0.1:1")) {
            assertTrue(BangumiOAuthRelay.isPrivateLanHost(host), host)
        }
        for (host in listOf(
            "8.8.8.8:80", "172.32.0.1:80", "192.169.0.1:80", "100.128.0.1:80", // 公网
            "10.0.0.300:80", "10.0.0.3:0", "10.0.0.3:70000", // 不成立的地址 / 端口
            "10.0.0.3", "izuko.local:8080", "[fd00::1]:8080", "10.0.0.1:80@evil.com", "",
        )) {
            assertFalse(BangumiOAuthRelay.isPrivateLanHost(host), host)
        }
    }

    @Test
    fun `经 bgm 与 Worker 转手后的 state 解码后与发起时一致`() {
        val state = BangumiOAuthRelay.state(nonce, "10.0.0.203:41891")
        // Worker 原样转发 bgm 给的查询串; `~` 与 `:` 可能被转义, 也可能没有
        for (url in listOf(
            "http://10.0.0.203:41891/bgm-oauth?code=abc&state=$nonce%7E10.0.0.203%3A41891",
            "http://10.0.0.203:41891/bgm-oauth?code=abc&state=$nonce~10.0.0.203:41891",
        )) {
            assertTrue(BangumiOAuthConstants.stateMatches(state, BangumiOAuthConstants.extractState(url)), url)
            assertEquals("abc", BangumiOAuthConstants.extractCode(url))
        }
    }

    @Test
    fun `粘回来的中转地址认得出`() {
        assertTrue(BangumiOAuthRelay.isRelayUrl("${BangumiOAuthRelay.CALLBACK_URL}?code=x&state=y"))
        assertTrue(BangumiOAuthRelay.isRelayUrl("http://10.0.0.203:41891/bgm-oauth?code=x&state=y"))
        assertTrue(BangumiOAuthRelay.isRelayUrl("http://10.0.0.203:41891/bgm-oauth/?code=x"))
        assertFalse(BangumiOAuthRelay.isRelayUrl("https://bangumi-tmdb-map-report.grahamzen.workers.dev/logo-report?code=x"))
        assertFalse(BangumiOAuthRelay.isRelayUrl("http://10.0.0.203:41891/abcdef/?code=x"))
        assertFalse(BangumiOAuthRelay.isRelayUrl("https://bgm.tv/oauth/authorize?client_id=x"))
    }

    @Test
    fun `refresh token 的前缀来回不丢`() {
        val stored = BangumiOAuthRelay.encodeRefreshToken("rt123")
        assertTrue(BangumiOAuthRelay.isRelayRefreshToken(stored))
        assertEquals("rt123", BangumiOAuthRelay.decodeRefreshToken(stored))
        // 主应用的 refresh token 是 40 位十六进制, 不会被当成中转应用的
        assertFalse(BangumiOAuthRelay.isRelayRefreshToken("0123456789abcdef0123456789abcdef01234567"))
    }

    /** 记下每次发给 bgm 的表单, 回一对新 token. */
    private class TokenEndpoint {
        val forms = mutableListOf<Parameters>()
        val http = HttpClient(
            MockEngine { req ->
                forms += (req.body as FormDataContent).formData
                respond(
                    """{"access_token":"at","expires_in":604800,"refresh_token":"rt-new"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { expectSuccess = false }.asScopedHttpClient()
    }

    @Test
    fun `中转应用的授权地址带 Worker 回调`() {
        val url = BangumiOAuthRelayClient(TokenEndpoint().http, clientId = "bgm999", clientSecret = "s")
            .authorizeUrl(BangumiOAuthRelay.state(nonce, "10.0.0.203:41891"), mirrorRoot = null)
        assertTrue("client_id=bgm999" in url, url)
        assertTrue(
            "redirect_uri=https%3A%2F%2Fbangumi-tmdb-map-report.grahamzen.workers.dev%2Fbgm%2Fcallback" in url,
            url,
        )
    }

    @Test
    fun `中转应用换 token 与续期用自己的凭据和回调，存的 refresh token 带前缀`() = runTest {
        val endpoint = TokenEndpoint()
        val relay = BangumiOAuthRelayClient(endpoint.http, clientId = "bgm999", clientSecret = "s")

        val login = relay.exchangeCode("code1")
        assertTrue(BangumiOAuthRelay.isRelayRefreshToken(login.refreshToken), login.refreshToken)
        assertEquals("bgm999", endpoint.forms[0]["client_id"])
        assertEquals(BangumiOAuthRelay.CALLBACK_URL, endpoint.forms[0]["redirect_uri"])

        relay.refresh(login.refreshToken)
        // 发给 bgm 的是去掉前缀的原样 refresh token
        assertEquals("rt-new", endpoint.forms[1]["refresh_token"])
        assertEquals(BangumiOAuthRelay.CALLBACK_URL, endpoint.forms[1]["redirect_uri"])
    }

    @Test
    fun `续期按 refresh token 选应用`() = runTest {
        val main = TokenEndpoint()
        val relay = TokenEndpoint()
        val refresher = BangumiSessionRefresher(
            getClient = { BangumiOAuthClient(main.http, clientId = "bgm-main", clientSecret = "s") },
            getRelayClient = { BangumiOAuthRelayClient(relay.http, clientId = "bgm999", clientSecret = "s") },
        )

        val relayed = refresher.refresh(BangumiOAuthRelay.encodeRefreshToken("rt-old"))
        assertTrue(BangumiOAuthRelay.isRelayRefreshToken(relayed.refreshToken))
        assertEquals(1, relay.forms.size)

        val direct = refresher.refresh("rt-old")
        assertEquals("rt-new", direct.refreshToken)
        assertEquals(1, main.forms.size)
        assertEquals("bgm-main", main.forms[0]["client_id"])
    }
}
