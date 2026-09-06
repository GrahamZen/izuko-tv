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
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 带凭证的请求什么时候可以经过镜像: 默认只走原站; 用户在弹窗里确认风险、打开
 * [BangumiEndpointSettings.allowCredentialsViaMirror] 后, 与匿名请求一样在原站不通时回落到镜像.
 * 换 token 与续期例外: 只有自建地址代理得了 bgm.tv 主站, 第三方镜像一律不给.
 */
class BangumiMirrorCredentialsTest {
    private class Sent(val host: String, val authorized: Boolean)

    /** 原站一律连不上 (大陆的情形), 镜像一律 200. */
    private fun blockedOriginClient(
        routing: BangumiRouting,
        sent: MutableList<Sent>,
        onSettled: (BangumiRouting, String?) -> Unit = { _, _ -> },
    ): HttpClient {
        val engine = MockEngine { req ->
            sent += Sent(req.url.host, req.headers.contains(HttpHeaders.Authorization))
            if (req.url.host.endsWith("bgm.tv")) throw IOException("blocked")
            respond("", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) { expectSuccess = false }
        BangumiMirrorFeatureHandler(flowOf(routing), onSettled).applyToClient(client, true)
        return client
    }

    private fun autoRouting(allowCredentials: Boolean) = BangumiRouting(
        mirrors = listOf("bangumi.pro"),
        trusted = allowCredentials,
        preferDirect = true,
    )

    @Test
    fun `默认不允许 - 带 token 的请求只打原站`() = runTest {
        val sent = mutableListOf<Sent>()
        val client = blockedOriginClient(autoRouting(allowCredentials = false), sent)
        assertFailsWith<IOException> {
            client.get("https://api.bgm.tv/v0/me") { header(HttpHeaders.Authorization, "Bearer t") }
        }
        assertEquals(listOf("api.bgm.tv"), sent.map { it.host })
    }

    @Test
    fun `默认不允许 - 换 token 的表单也不给镜像`() = runTest {
        val sent = mutableListOf<Sent>()
        val client = blockedOriginClient(autoRouting(allowCredentials = false), sent)
        assertFailsWith<IOException> {
            client.submitForm("https://bgm.tv/oauth/access_token", parameters { append("code", "c") })
        }
        assertEquals(listOf("bgm.tv"), sent.map { it.host })
    }

    @Test
    fun `用户允许后 - 带 token 的请求回落到镜像，token 跟着过去`() = runTest {
        val sent = mutableListOf<Sent>()
        val client = blockedOriginClient(autoRouting(allowCredentials = true), sent)
        val status = client.get("https://api.bgm.tv/v0/me") { header(HttpHeaders.Authorization, "Bearer t") }.status
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(listOf("api.bgm.tv", "api.bangumi.pro"), sent.map { it.host })
        assertEquals(true, sent.last().authorized)
    }

    @Test
    fun `用户允许后 - 换 token 与续期仍只打原站`() = runTest {
        // 第三方镜像把主站挡在反爬验证页后面, 发过去只会拿回验证网页, 还把 client_secret 交了出去
        val sent = mutableListOf<Sent>()
        val client = blockedOriginClient(autoRouting(allowCredentials = true), sent)
        assertFailsWith<IOException> {
            client.submitForm("https://bgm.tv/oauth/access_token", parameters { append("grant_type", "refresh_token") })
        }
        assertEquals(listOf("bgm.tv"), sent.map { it.host })
    }

    @Test
    fun `自建地址 - 换 token 走自建地址`() = runTest {
        val sent = mutableListOf<Sent>()
        val routing = BangumiRouting(listOf("my.example"), trusted = true, preferDirect = false, servesMainSite = true)
        val client = blockedOriginClient(routing, sent)
        val status = client.submitForm("https://bgm.tv/oauth/access_token", parameters { append("code", "c") }).status
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(listOf("my.example"), sent.map { it.host })
    }

    @Test
    fun `落到哪个镜像会回报出去`() = runTest {
        val sent = mutableListOf<Sent>()
        val settled = mutableListOf<String?>()
        val client = blockedOriginClient(autoRouting(allowCredentials = false), sent) { _, root -> settled += root }
        client.get("https://api.bgm.tv/v0/subjects/1")
        client.get("https://api.bgm.tv/v0/subjects/2")
        // 只在目标变化时回报一次
        assertEquals(listOf<String?>("bangumi.pro"), settled)
    }

    @Test
    fun `清单换了同一个下标也要重新回报`() = runTest {
        val routing = MutableStateFlow(BangumiRouting(listOf("a.example"), trusted = false, preferDirect = true))
        val settled = mutableListOf<String?>()
        val engine = MockEngine { req ->
            if (req.url.host.endsWith("bgm.tv")) throw IOException("blocked")
            respond("", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) { expectSuccess = false }
        BangumiMirrorFeatureHandler(routing, onSettled = { _, root -> settled += root }).applyToClient(client, true)

        client.get("https://api.bgm.tv/v0/subjects/1")
        routing.value = BangumiRouting(listOf("b.example"), trusted = false, preferDirect = true)
        client.get("https://api.bgm.tv/v0/subjects/1")

        assertEquals(listOf<String?>("a.example", "b.example"), settled)
    }

    private fun TestScope.provider(settings: BangumiEndpointSettings): BangumiEndpointProvider =
        BangumiEndpointProvider(
            settings = flowOf(settings),
            mirrors = flowOf(listOf("bangumi.pro")),
            scope = backgroundScope,
        ).also { runCurrent() }

    @Test
    fun `授权页 - 默认不允许时即使落在镜像上也用原站`() = runTest {
        val provider = provider(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO))
        provider.reportSettled(provider.currentRouting!!, "bangumi.pro")
        runCurrent()
        assertNull(provider.trustedMirrorRoot.value)
    }

    @Test
    fun `授权页 - 用户允许后只在已经落到镜像上时才用镜像`() = runTest {
        val provider = provider(
            BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO, allowCredentialsViaMirror = true),
        )
        // 还没发过请求 / 原站能连: 用原站
        assertNull(provider.trustedMirrorRoot.value)

        provider.reportSettled(provider.currentRouting!!, "bangumi.pro")
        runCurrent()
        assertEquals("bangumi.pro", provider.trustedMirrorRoot.value)

        provider.reportSettled(provider.currentRouting!!, null)
        runCurrent()
        assertNull(provider.trustedMirrorRoot.value)
    }

    @Test
    fun `授权页 - 已不在清单里的镜像不用`() = runTest {
        val provider = provider(
            BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO, allowCredentialsViaMirror = true),
        )
        provider.reportSettled(provider.currentRouting!!, "gone.example")
        runCurrent()
        assertNull(provider.trustedMirrorRoot.value)
    }

    @Test
    fun `授权页 - 自建地址直接用`() = runTest {
        val provider = provider(
            BangumiEndpointSettings(mode = BangumiEndpointMode.CUSTOM, customBaseUrl = "https://my.example/"),
        )
        assertEquals("my.example", provider.trustedMirrorRoot.value)
    }
}
