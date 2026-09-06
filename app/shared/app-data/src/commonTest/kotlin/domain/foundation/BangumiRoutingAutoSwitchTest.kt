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
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import me.him188.ani.app.data.models.preference.BangumiRouteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 确定官方连不上后自动关掉「官方直连」, 之后不再先试官方.
 *
 * 判据是**同一个请求**在官方那一跳连接失败、后面某条线路成功: 官方回 5xx 是它在回答 (临时故障, 过一阵还会试官方),
 * 都连不上是本机断网 —— 这两种都不能改用户的设置.
 */
class BangumiRoutingAutoSwitchTest {
    private val vip = BangumiRoute.Mirror("bangumi.vip")
    private val backup = BangumiRoute.Mirror("backup.example")
    private val autoRouting = BangumiRouting(listOf(BangumiRoute.Origin, vip))

    /** 默认的组合 (官方、加密直连、镜像都开着) 关掉官方. */
    private val originOff = BangumiEndpointSettings().withRoute(BangumiRouteKind.ORIGIN, false)

    /** 只剩镜像. */
    private val mirrorOnly = originOff.withRoute(BangumiRouteKind.ECH, false)

    /**
     * @param origin 官方的响应; `null` = 连不上
     * @param ech 加密直连的响应; `null` = 连不上
     * @param mirror 镜像的响应; `null` = 连不上
     */
    private fun client(
        routing: Flow<BangumiRouting>,
        hosts: MutableList<String>,
        origin: HttpStatusCode?,
        ech: HttpStatusCode? = HttpStatusCode.OK,
        mirror: HttpStatusCode? = HttpStatusCode.OK,
        onOriginUnreachable: (BangumiRouting, BangumiRoute) -> Unit,
    ): HttpClient {
        val engine = MockEngine { req ->
            hosts += req.url.host
            val status = when {
                req.url.host.endsWith("bgm.tv") -> origin
                req.url.host.endsWith(BangumiRoute.Ech.HOST_ROOT) -> ech
                else -> mirror
            } ?: throw IOException("blocked")
            respond("", status)
        }
        val client = HttpClient(engine) { expectSuccess = false }
        BangumiRoutingFeatureHandler(routing, onOriginUnreachable = onOriginUnreachable).applyToClient(client, true)
        return client
    }

    @Test
    fun `官方连不上、镜像通 — 回报一次，之后粘在镜像上不再回报`() = runTest {
        val hosts = mutableListOf<String>()
        val reported = mutableListOf<BangumiRoute>()
        val client = client(flowOf(autoRouting), hosts, origin = null) { _, route -> reported += route }

        client.get("https://api.bgm.tv/v0/subjects/1")
        client.get("https://api.bgm.tv/v0/subjects/2")

        assertEquals(listOf<BangumiRoute>(vip), reported)
        assertEquals(listOf("api.bgm.tv", "api.bangumi.vip", "api.bangumi.vip"), hosts)
    }

    @Test
    fun `官方回 5xx 换到镜像不算连不上`() = runTest {
        val hosts = mutableListOf<String>()
        var reported = 0
        val client = client(flowOf(autoRouting), hosts, origin = HttpStatusCode.BadGateway) { _, _ -> reported++ }

        assertEquals(HttpStatusCode.OK, client.get("https://api.bgm.tv/v0/subjects/1").status)
        assertEquals(listOf("api.bgm.tv", "api.bangumi.vip"), hosts)
        assertEquals(0, reported)
    }

    @Test
    fun `都连不上是本机断网，不回报`() = runTest {
        val hosts = mutableListOf<String>()
        var reported = 0
        val client = client(flowOf(autoRouting), hosts, origin = null, mirror = null) { _, _ -> reported++ }

        assertFailsWith<IOException> { client.get("https://api.bgm.tv/v0/subjects/1") }
        assertEquals(0, reported)
    }

    /** 2026-09-25 Shield 实测: 官方刚成功过, 启动高峰里一个请求在官方那一跳超时换到镜像, 就弹了「连不上官方」. */
    @Test
    fun `官方在这个进程里回应过 — 之后一次连不上是网络卡顿，不回报`() = runTest {
        val hosts = mutableListOf<String>()
        var reported = 0
        var originUp = true
        val engine = MockEngine { req ->
            hosts += req.url.host
            if (req.url.host.endsWith("bgm.tv") && !originUp) throw IOException("stalled")
            respond("", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) { expectSuccess = false }
        BangumiRoutingFeatureHandler(flowOf(autoRouting), onOriginUnreachable = { _, _ -> reported++ })
            .applyToClient(client, true)

        client.get("https://next.bgm.tv/p1/me")
        originUp = false
        assertEquals(HttpStatusCode.OK, client.get("https://api.bgm.tv/").status)

        assertEquals(listOf("next.bgm.tv", "api.bgm.tv", "api.bangumi.vip"), hosts)
        assertEquals(0, reported)
    }

    @Test
    fun `关掉了官方就不试官方`() = runTest {
        val hosts = mutableListOf<String>()
        var reported = 0
        val client = client(flowOf(BangumiRouting(listOf(vip))), hosts, origin = HttpStatusCode.OK) { _, _ -> reported++ }

        client.get("https://api.bgm.tv/v0/subjects/1")

        assertEquals(listOf("api.bangumi.vip"), hosts)
        assertEquals(0, reported)
    }

    @Test
    fun `加密直连排在镜像前面 — 官方连不上时先换它，回报的是它`() = runTest {
        val hosts = mutableListOf<String>()
        val reported = mutableListOf<BangumiRoute>()
        val routing = BangumiRouting(listOf(BangumiRoute.Origin, BangumiRoute.Ech, vip))
        val client = client(flowOf(routing), hosts, origin = null) { _, route -> reported += route }

        client.get("https://api.bgm.tv/v0/subjects/1")
        client.get("https://next.bgm.tv/p1/subjects/2")

        assertEquals(listOf("api.bgm.tv", "api.bgm-tv.ech.invalid", "next.bgm-tv.ech.invalid"), hosts)
        assertEquals(listOf<BangumiRoute>(BangumiRoute.Ech), reported)
    }

    @Test
    fun `加密直连也连不上 — 落到镜像上，回报的是镜像`() = runTest {
        val hosts = mutableListOf<String>()
        val reported = mutableListOf<BangumiRoute>()
        val routing = BangumiRouting(listOf(BangumiRoute.Origin, BangumiRoute.Ech, vip))
        val client = client(flowOf(routing), hosts, origin = null, ech = null) { _, route -> reported += route }

        client.get("https://api.bgm.tv/v0/subjects/1")

        assertEquals(listOf("api.bgm.tv", "api.bgm-tv.ech.invalid", "api.bangumi.vip"), hosts)
        assertEquals(listOf<BangumiRoute>(vip), reported)
    }

    @Test
    fun `加密直连连不上时补救一次 — 换了配置就再试它，没换就去下一条`() = runTest {
        for (fixed in listOf(true, false)) {
            val hosts = mutableListOf<String>()
            var echAttempts = 0
            val engine = MockEngine { req ->
                hosts += req.url.host
                if (req.url.host.endsWith(BangumiRoute.Ech.HOST_ROOT) && echAttempts++ == 0) throw IOException("ech rejected")
                respond("", HttpStatusCode.OK)
            }
            val client = HttpClient(engine) { expectSuccess = false }
            val recovered = mutableListOf<BangumiRoute>()
            BangumiRoutingFeatureHandler(
                flowOf(BangumiRouting(listOf(BangumiRoute.Ech, vip))),
                recover = { route, _ -> recovered += route; fixed },
            ).applyToClient(client, true)

            client.get("https://api.bgm.tv/v0/subjects/1")
            client.get("https://api.bgm.tv/v0/subjects/2")

            assertEquals(listOf<BangumiRoute>(BangumiRoute.Ech), recovered)
            if (fixed) {
                assertEquals(List(3) { "api.bgm-tv.ech.invalid" }, hosts)
            } else {
                assertEquals(listOf("api.bgm-tv.ech.invalid", "api.bangumi.vip", "api.bangumi.vip"), hosts)
            }
        }
    }

    private fun TestScope.provider(
        settings: Flow<BangumiEndpointSettings>,
        echAvailable: Boolean = false,
        proxyInUse: Flow<Boolean> = flowOf(false),
        onOriginUnreachable: suspend (BangumiRoute) -> Unit = {},
    ): BangumiEndpointProvider = BangumiEndpointProvider(
        settings = settings,
        mirrors = flowOf(listOf("bangumi.vip", "backup.example")),
        scope = backgroundScope,
        echAvailable = echAvailable,
        proxyInUse = proxyInUse,
        onOriginUnreachable = onOriginUnreachable,
    ).also { runCurrent() }

    @Test
    fun `线路按固定顺序排，平台用不了或开着代理时没有加密直连`() = runTest {
        val custom = BangumiEndpointSettings(customBaseUrl = "https://my.example/").withRoute(BangumiRouteKind.CUSTOM, true)
        assertEquals(
            listOf(BangumiRoute.Origin, BangumiRoute.Custom("my.example"), BangumiRoute.Ech, vip, backup),
            provider(flowOf(custom), echAvailable = true).routing.first().routes,
        )
        assertEquals(
            listOf(BangumiRoute.Origin, BangumiRoute.Custom("my.example"), vip, backup),
            provider(flowOf(custom), echAvailable = false).routing.first().routes,
        )
        assertEquals(
            listOf(BangumiRoute.Origin, BangumiRoute.Custom("my.example"), vip, backup),
            provider(flowOf(custom), echAvailable = true, proxyInUse = flowOf(true)).routing.first().routes,
        )
    }

    @Test
    fun `全都关了等于只连官方`() = runTest {
        val none = BangumiRouteKind.entries.fold(BangumiEndpointSettings()) { s, kind -> s.withRoute(kind, false) }
        assertEquals(listOf<BangumiRoute>(BangumiRoute.Origin), provider(flowOf(none)).routing.first().routes)
    }

    @Test
    fun `只剩镜像 — 不先试官方，带 token 的请求照用户是否允许`() = runTest {
        val denied = provider(flowOf(mirrorOnly)).routing.first()
        assertEquals(listOf<BangumiRoute>(vip, backup), denied.routes)
        assertFalse(denied.allowCredentialsViaThirdParty)
        val allowed = provider(flowOf(mirrorOnly.copy(allowCredentialsViaMirror = true))).routing.first()
        assertEquals(listOf<BangumiRoute>(vip, backup), allowed.routes)
        assertTrue(allowed.allowCredentialsViaThirdParty)
    }

    @Test
    fun `只剩镜像 — 授权页用正在用的那个镜像，没发过请求时用清单第一个`() = runTest {
        val provider = provider(flowOf(mirrorOnly.copy(allowCredentialsViaMirror = true)))
        assertEquals("bangumi.vip", provider.trustedMirrorRoot.value)

        provider.reportSettled(provider.currentRouting!!, backup)
        runCurrent()
        assertEquals("backup.example", provider.trustedMirrorRoot.value)
    }

    @Test
    fun `只剩镜像 — 用户没允许时授权页仍用官方`() = runTest {
        val provider = provider(flowOf(mirrorOnly))
        provider.reportSettled(provider.currentRouting!!, vip)
        runCurrent()
        assertNull(provider.trustedMirrorRoot.value)
    }

    @Test
    fun `落在加密直连上 — 浏览器做不了加密直连，授权页在用户允许时用镜像`() = runTest {
        val provider = provider(flowOf(originOff.copy(allowCredentialsViaMirror = true)), echAvailable = true)
        provider.reportSettled(provider.currentRouting!!, BangumiRoute.Ech)
        runCurrent()
        assertEquals("bangumi.vip", provider.trustedMirrorRoot.value)
        assertFalse(provider.viaThirdPartyMirror.value)

        val denied = provider(flowOf(originOff), echAvailable = true)
        denied.reportSettled(denied.currentRouting!!, BangumiRoute.Ech)
        runCurrent()
        assertNull(denied.trustedMirrorRoot.value)
    }

    @Test
    fun `经镜像 — 只剩镜像时一直算，开着官方时只在落到镜像上时算`() = runTest {
        val mirror = provider(flowOf(mirrorOnly))
        assertTrue(mirror.viaThirdPartyMirror.value)

        val auto = provider(flowOf(BangumiEndpointSettings()))
        assertFalse(auto.viaThirdPartyMirror.value)
        auto.reportSettled(auto.currentRouting!!, vip)
        runCurrent()
        assertTrue(auto.viaThirdPartyMirror.value)
        auto.reportSettled(auto.currentRouting!!, BangumiRoute.Origin)
        runCurrent()
        assertFalse(auto.viaThirdPartyMirror.value)
    }

    @Test
    fun `经镜像 — 关着官方而加密直连排在镜像前面，不算`() = runTest {
        val provider = provider(flowOf(originOff), echAvailable = true)
        assertFalse(provider.viaThirdPartyMirror.value)
        // 加密直连连不上, 落到了镜像上
        provider.reportSettled(provider.currentRouting!!, vip)
        runCurrent()
        assertTrue(provider.viaThirdPartyMirror.value)
    }

    @Test
    fun `经镜像 — 从只剩镜像改回默认，旧的落点不算数`() = runTest {
        val settings = MutableStateFlow(mirrorOnly)
        val provider = provider(settings)
        provider.reportSettled(provider.currentRouting!!, vip)
        runCurrent()
        assertTrue(provider.viaThirdPartyMirror.value)

        // 改过去之后还没有请求落地: 按官方算, 授权登录照常给
        settings.value = BangumiEndpointSettings()
        runCurrent()
        assertFalse(provider.viaThirdPartyMirror.value)

        // 新路由下的请求落到了镜像上 (官方连不上)
        provider.reportSettled(provider.currentRouting!!, vip)
        runCurrent()
        assertTrue(provider.viaThirdPartyMirror.value)
    }

    @Test
    fun `设置读出来之前的请求先等着，不会只打官方`() = runTest {
        val settings = MutableSharedFlow<BangumiEndpointSettings>(replay = 1)
        val provider = provider(settings)
        val hosts = mutableListOf<String>()
        val client = client(provider.routing, hosts, origin = null) { _, _ -> }

        val call = async { client.get("https://api.bgm.tv/v0/subjects/1") }
        runCurrent()
        assertEquals(emptyList<String>(), hosts)

        settings.emit(mirrorOnly)
        assertEquals(HttpStatusCode.OK, call.await().status)
        assertEquals(listOf("api.bangumi.vip"), hosts)
    }

    @Test
    fun `启动时并发的请求一起报官方连不上，只处理一次`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var switched = 0
        val provider = provider(flowOf(BangumiEndpointSettings())) {
            switched++
            gate.await()
        }

        repeat(3) { provider.reportOriginUnreachable(provider.currentRouting!!, vip) }
        runCurrent()
        assertEquals(1, switched)

        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `官方连不上后关掉官方，下一个请求不再先试官方`() = runTest {
        val settings = MutableStateFlow(BangumiEndpointSettings())
        val provider = provider(settings) { settings.update { it.afterOriginUnreachable() } }
        val hosts = mutableListOf<String>()
        val client = client(provider.routing, hosts, origin = null, onOriginUnreachable = provider::reportOriginUnreachable)

        client.get("https://api.bgm.tv/v0/subjects/1")
        runCurrent()

        assertFalse(settings.value.isEnabled(BangumiRouteKind.ORIGIN))
        assertFalse(BangumiRoute.Origin in provider.routing.first().routes)

        // 新的处理器没有粘性 (相当于重启 app 之后), 靠的是存下来的设置
        val restartedHosts = mutableListOf<String>()
        val restarted = client(provider.routing, restartedHosts, origin = null) { _, _ -> }
        restarted.get("https://api.bgm.tv/v0/subjects/2")
        assertEquals(listOf("api.bangumi.vip"), restartedHosts)
    }

    @Test
    fun `只开着官方 — 不会被关掉`() = runTest {
        val direct = BangumiEndpointSettings()
            .withRoute(BangumiRouteKind.ECH, false)
            .withRoute(BangumiRouteKind.MIRRORS, false)
        val settings = MutableStateFlow(direct)
        val provider = provider(settings) { settings.update { it.afterOriginUnreachable() } }
        provider.reportOriginUnreachable(provider.currentRouting!!, vip)
        runCurrent()
        assertTrue(settings.value.isEnabled(BangumiRouteKind.ORIGIN))
    }

    @Test
    fun `旧路由下在途的请求报官方连不上 — 用户刚重新打开的官方不被关掉`() = runTest {
        val settings = MutableStateFlow(BangumiEndpointSettings())
        val provider = provider(settings) { settings.update { it.afterOriginUnreachable() } }
        val old = provider.currentRouting!!
        settings.value = mirrorOnly
        runCurrent()
        settings.value = BangumiEndpointSettings()
        runCurrent()
        // 内容一样, 但不是同一个: 改回来之后的新路由
        assertEquals(old, provider.currentRouting)
        assertNotSame(old, provider.currentRouting)

        provider.reportOriginUnreachable(old, vip)
        runCurrent()
        assertTrue(settings.value.isEnabled(BangumiRouteKind.ORIGIN))

        provider.reportOriginUnreachable(provider.currentRouting!!, vip)
        runCurrent()
        assertFalse(settings.value.isEnabled(BangumiRouteKind.ORIGIN))
    }

    @Test
    fun `旧路由下在途的请求落到镜像上 — 改回来的新路由不算经镜像`() = runTest {
        val settings = MutableStateFlow(BangumiEndpointSettings())
        val provider = provider(settings)
        val old = provider.currentRouting!!
        settings.value = mirrorOnly
        runCurrent()
        settings.value = BangumiEndpointSettings()
        runCurrent()

        provider.reportSettled(old, vip)
        runCurrent()
        assertFalse(provider.viaThirdPartyMirror.value)

        // 新路由下的请求落地才算
        provider.reportSettled(provider.currentRouting!!, vip)
        runCurrent()
        assertTrue(provider.viaThirdPartyMirror.value)
    }
}
