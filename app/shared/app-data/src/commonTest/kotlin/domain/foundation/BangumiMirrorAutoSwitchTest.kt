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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「官方连不上时用镜像」确定官方连不上后自动改成「用镜像」, 之后不再先试官方.
 *
 * 判据是**同一个请求**在官方那一跳连接失败、换到镜像成功: 官方回 5xx 是它在回答 (临时故障, 过一阵还会试官方),
 * 两边都连不上是本机断网 —— 这两种都不能改用户的设置.
 */
class BangumiMirrorAutoSwitchTest {
    private val autoRouting = BangumiRouting(listOf("bangumi.vip"), trusted = false, preferDirect = true)

    /**
     * @param origin 官方的响应; `null` = 连不上
     * @param mirror 镜像的响应; `null` = 连不上
     */
    private fun client(
        routing: Flow<BangumiRouting>,
        hosts: MutableList<String>,
        origin: HttpStatusCode?,
        mirror: HttpStatusCode? = HttpStatusCode.OK,
        onOriginUnreachable: () -> Unit,
    ): HttpClient {
        val engine = MockEngine { req ->
            hosts += req.url.host
            val status = (if (req.url.host.endsWith("bgm.tv")) origin else mirror) ?: throw IOException("blocked")
            respond("", status)
        }
        val client = HttpClient(engine) { expectSuccess = false }
        BangumiMirrorFeatureHandler(routing, onOriginUnreachable = onOriginUnreachable).applyToClient(client, true)
        return client
    }

    @Test
    fun `官方连不上、镜像通 — 回报一次，之后粘在镜像上不再回报`() = runTest {
        val hosts = mutableListOf<String>()
        var reported = 0
        val client = client(flowOf(autoRouting), hosts, origin = null) { reported++ }

        client.get("https://api.bgm.tv/v0/subjects/1")
        client.get("https://api.bgm.tv/v0/subjects/2")

        assertEquals(1, reported)
        assertEquals(listOf("api.bgm.tv", "api.bangumi.vip", "api.bangumi.vip"), hosts)
    }

    @Test
    fun `官方回 5xx 换到镜像不算连不上`() = runTest {
        val hosts = mutableListOf<String>()
        var reported = 0
        val client = client(flowOf(autoRouting), hosts, origin = HttpStatusCode.BadGateway) { reported++ }

        assertEquals(HttpStatusCode.OK, client.get("https://api.bgm.tv/v0/subjects/1").status)
        assertEquals(listOf("api.bgm.tv", "api.bangumi.vip"), hosts)
        assertEquals(0, reported)
    }

    @Test
    fun `官方和镜像都连不上是本机断网，不回报`() = runTest {
        val hosts = mutableListOf<String>()
        var reported = 0
        val client = client(flowOf(autoRouting), hosts, origin = null, mirror = null) { reported++ }

        assertFailsWith<IOException> { client.get("https://api.bgm.tv/v0/subjects/1") }
        assertEquals(0, reported)
    }

    @Test
    fun `用镜像这一档不试官方`() = runTest {
        val hosts = mutableListOf<String>()
        var reported = 0
        val routing = BangumiRouting(listOf("bangumi.vip"), trusted = false, preferDirect = false)
        val client = client(flowOf(routing), hosts, origin = HttpStatusCode.OK) { reported++ }

        client.get("https://api.bgm.tv/v0/subjects/1")

        assertEquals(listOf("api.bangumi.vip"), hosts)
        assertEquals(0, reported)
    }

    private fun TestScope.provider(
        settings: Flow<BangumiEndpointSettings>,
        switchToMirror: suspend () -> Unit = {},
    ): BangumiEndpointProvider = BangumiEndpointProvider(
        settings = settings,
        mirrors = flowOf(listOf("bangumi.vip", "backup.example")),
        scope = backgroundScope,
        switchToMirror = switchToMirror,
    ).also { runCurrent() }

    @Test
    fun `用镜像 — 不先试官方，带 token 的请求照用户是否允许`() = runTest {
        val denied = provider(flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR)))
        assertEquals(
            BangumiRouting(listOf("bangumi.vip", "backup.example"), trusted = false, preferDirect = false),
            denied.routing.first(),
        )
        val allowed = provider(
            flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR, allowCredentialsViaMirror = true)),
        )
        assertEquals(
            BangumiRouting(listOf("bangumi.vip", "backup.example"), trusted = true, preferDirect = false),
            allowed.routing.first(),
        )
    }

    @Test
    fun `用镜像 — 授权页用正在用的那个镜像，没发过请求时用清单第一个`() = runTest {
        val provider = provider(
            flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR, allowCredentialsViaMirror = true)),
        )
        assertEquals("bangumi.vip", provider.trustedMirrorRoot.value)

        provider.reportSettled("backup.example")
        runCurrent()
        assertEquals("backup.example", provider.trustedMirrorRoot.value)
    }

    @Test
    fun `用镜像 — 用户没允许时授权页仍用官方`() = runTest {
        val provider = provider(flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR)))
        provider.reportSettled("bangumi.vip")
        runCurrent()
        assertNull(provider.trustedMirrorRoot.value)
    }

    @Test
    fun `经镜像 — 用镜像这一档一直算，官方连不上时用镜像只在落到镜像上时算`() = runTest {
        val mirror = provider(flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR)))
        assertTrue(mirror.viaThirdPartyMirror.value)

        val auto = provider(flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO)))
        assertFalse(auto.viaThirdPartyMirror.value)
        auto.reportSettled("bangumi.vip")
        runCurrent()
        assertTrue(auto.viaThirdPartyMirror.value)
        auto.reportSettled(null)
        runCurrent()
        assertFalse(auto.viaThirdPartyMirror.value)
    }

    @Test
    fun `经镜像 — 从用镜像切回官方连不上时用镜像，旧的落点不算数`() = runTest {
        val settings = MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR))
        val provider = provider(settings)
        provider.reportSettled("bangumi.vip")
        runCurrent()
        assertTrue(provider.viaThirdPartyMirror.value)

        // 切过去之后还没有请求落地: 按原站算, 授权登录照常给
        settings.value = BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO)
        runCurrent()
        assertFalse(provider.viaThirdPartyMirror.value)

        // 新路由下的请求落到了镜像上 (官方连不上)
        provider.reportSettled("bangumi.vip")
        runCurrent()
        assertTrue(provider.viaThirdPartyMirror.value)
    }

    @Test
    fun `设置读出来之前的请求先等着，不会只打官方`() = runTest {
        val settings = MutableSharedFlow<BangumiEndpointSettings>(replay = 1)
        val provider = provider(settings)
        val hosts = mutableListOf<String>()
        val client = client(provider.routing, hosts, origin = null) {}

        val call = async { client.get("https://api.bgm.tv/v0/subjects/1") }
        runCurrent()
        assertEquals(emptyList<String>(), hosts)

        settings.emit(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR))
        assertEquals(HttpStatusCode.OK, call.await().status)
        assertEquals(listOf("api.bangumi.vip"), hosts)
    }

    @Test
    fun `启动时并发的请求一起报官方连不上，只改一次设置`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var switched = 0
        val provider = provider(flowOf(BangumiEndpointSettings())) {
            switched++
            gate.await()
        }

        repeat(3) { provider.reportOriginUnreachable() }
        runCurrent()
        assertEquals(1, switched)

        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `官方连不上后设置改成用镜像，下一个请求不再先试官方`() = runTest {
        val settings = MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.AUTO))
        val provider = provider(settings) { settings.update { it.afterOriginUnreachable() } }
        val hosts = mutableListOf<String>()
        val client = client(provider.routing, hosts, origin = null, onOriginUnreachable = provider::reportOriginUnreachable)

        client.get("https://api.bgm.tv/v0/subjects/1")
        runCurrent()

        assertEquals(BangumiEndpointMode.MIRROR, settings.value.mode)
        assertEquals(false, provider.routing.first().preferDirect)

        // 新的处理器没有粘性 (相当于重启 app 之后), 靠的是存下来的设置
        val restartedHosts = mutableListOf<String>()
        val restarted = client(provider.routing, restartedHosts, origin = null) {}
        restarted.get("https://api.bgm.tv/v0/subjects/2")
        assertEquals(listOf("api.bangumi.vip"), restartedHosts)
    }

    @Test
    fun `只改「官方连不上时用镜像」这一档 — 用户选了只连官方就不动`() = runTest {
        val settings = MutableStateFlow(BangumiEndpointSettings(mode = BangumiEndpointMode.DIRECT))
        val provider = provider(settings) { settings.update { it.afterOriginUnreachable() } }
        provider.reportOriginUnreachable()
        runCurrent()
        assertEquals(BangumiEndpointMode.DIRECT, settings.value.mode)
    }
}
