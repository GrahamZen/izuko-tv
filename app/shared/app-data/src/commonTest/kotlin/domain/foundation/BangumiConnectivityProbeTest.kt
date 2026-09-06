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
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.BangumiConnectionPreset
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 首次启动引导的网络检测: 官方、加密直连、镜像各自直接连一次, 按结果给建议的组合.
 */
class BangumiConnectivityProbeTest {
    /**
     * @param status 按域名给响应码; 返回 `null` = 连不上
     */
    /**
     * 在真实调度器上跑: 检测的封顶是 [kotlinx.coroutines.withTimeoutOrNull], 放在 runTest 的虚拟时间里,
     * 调度器一空闲就瞬间到点 —— 而 MockEngine 在别的线程上回应, 能连上的也会被判成超时.
     */
    private fun realTimeTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    private fun probe(
        mirrors: List<String> = listOf("bangumi.vip"),
        hosts: MutableList<String> = mutableListOf(),
        timeoutMillis: Long = 8_000,
        echAvailable: Boolean = false,
        status: suspend (host: String) -> HttpStatusCode?,
    ): BangumiConnectivityProbe {
        val engine = MockEngine { req ->
            hosts += req.url.host
            respond("", status(req.url.host) ?: throw IOException("blocked"))
        }
        // 与应用的客户端一样开着 expectSuccess: 4xx / 5xx 会以异常抛出来
        val client = HttpClient(engine) {
            install(HttpTimeout)
            expectSuccess = true
        }
        return BangumiConnectivityProbe({ client.asScopedHttpClient() }, flowOf(mirrors), echAvailable, timeoutMillis)
    }

    @Test
    fun `官方通 — 建议自动`() = realTimeTest {
        val hosts = mutableListOf<String>()
        val result = probe(hosts = hosts) { HttpStatusCode.OK }.run().last()

        assertIs<Reachability.Reachable>(result.origin)
        assertIs<Reachability.Reachable>(result.mirrorReachability)
        assertEquals(BangumiConnectionPreset.AUTO, result.recommended)
        // 镜像那一路打的是镜像自己的域名 (没被换回原站)
        assertEquals(setOf("api.bgm.tv", "api.bangumi.vip"), hosts.toSet())
    }

    @Test
    fun `官方连不上、镜像通 — 建议不连官方`() = realTimeTest {
        val result = probe { host -> if (host == "api.bgm.tv") null else HttpStatusCode.OK }.run().last()

        assertEquals(Reachability.Unreachable, result.origin)
        assertIs<Reachability.Reachable>(result.mirrorReachability)
        assertEquals("bangumi.vip", result.mirror)
        assertEquals(BangumiConnectionPreset.SKIP_ORIGIN, result.recommended)
    }

    @Test
    fun `都连不上 — 不给建议`() = realTimeTest {
        val result = probe { null }.run().last()

        assertTrue(result.completed)
        assertEquals(Reachability.Unreachable, result.origin)
        assertEquals(Reachability.Unreachable, result.mirrorReachability)
        assertNull(result.recommended)
    }

    @Test
    fun `4xx 是对方在回答算连得上，5xx 不算`() = realTimeTest {
        val result = probe { host ->
            if (host == "api.bgm.tv") HttpStatusCode.NotFound else HttpStatusCode.BadGateway
        }.run().last()

        assertIs<Reachability.Reachable>(result.origin)
        assertEquals(Reachability.Unreachable, result.mirrorReachability)
    }

    @Test
    fun `按清单顺序取第一个连得上的镜像`() = realTimeTest {
        val result = probe(mirrors = listOf("dead.example", "bangumi.vip", "other.example")) { host ->
            if (host == "api.dead.example") null else HttpStatusCode.OK
        }.run().last()

        assertEquals("bangumi.vip", result.mirror)
        assertIs<Reachability.Reachable>(result.mirrorReachability)
    }

    @Test
    fun `镜像都连不上时展示清单第一个`() = realTimeTest {
        val result = probe(mirrors = listOf("a.example", "b.example")) { host ->
            if (host == "api.bgm.tv") HttpStatusCode.OK else null
        }.run().last()

        assertEquals("a.example", result.mirror)
        assertEquals(Reachability.Unreachable, result.mirrorReachability)
        assertEquals(BangumiConnectionPreset.AUTO, result.recommended)
    }

    @Test
    fun `清单为空 — 镜像那一路直接算连不上`() = realTimeTest {
        val hosts = mutableListOf<String>()
        val result = probe(mirrors = emptyList(), hosts = hosts) { null }.run().last()

        assertNull(result.mirror)
        assertEquals(Reachability.Unreachable, result.mirrorReachability)
        assertEquals(listOf("api.bgm.tv"), hosts)
    }

    @Test
    fun `一直没有回应的算连不上`() = realTimeTest {
        val result = probe(timeoutMillis = 300) { host ->
            if (host == "api.bgm.tv") awaitCancellation() else HttpStatusCode.OK
        }.run().last()

        assertEquals(Reachability.Unreachable, result.origin)
        assertEquals(BangumiConnectionPreset.SKIP_ORIGIN, result.recommended)
    }

    @Test
    fun `设备用不了加密直连 — 不测这一路`() = realTimeTest {
        val hosts = mutableListOf<String>()
        val result = probe(hosts = hosts) { HttpStatusCode.OK }.run().last()

        assertNull(result.ech)
        assertTrue(hosts.none { it.endsWith(BangumiRoute.Ech.HOST_ROOT) })
    }

    @Test
    fun `官方连不上、加密直连通 — 建议不连官方，请求不会落到镜像上`() = realTimeTest {
        val hosts = mutableListOf<String>()
        val result = probe(hosts = hosts, echAvailable = true) { host ->
            if (host == "api.bgm.tv") null else HttpStatusCode.OK
        }.run().last()

        assertIs<Reachability.Reachable>(result.ech)
        assertEquals(BangumiConnectionPreset.SKIP_ORIGIN, result.recommended)
        assertFalse(result.landsOnMirror(BangumiConnectionPreset.SKIP_ORIGIN))
        // 加密直连那一路打的是本机才认得的域名, 由引擎按 ECH 连
        assertTrue("api.${BangumiRoute.Ech.HOST_ROOT}" in hosts)
    }

    @Test
    fun `只有镜像通 — 请求会落到镜像上`() = realTimeTest {
        val result = probe(echAvailable = true) { host ->
            if (host == "api.bangumi.vip") HttpStatusCode.OK else null
        }.run().last()

        assertEquals(Reachability.Unreachable, result.ech)
        assertEquals(BangumiConnectionPreset.SKIP_ORIGIN, result.recommended)
        assertTrue(result.landsOnMirror(BangumiConnectionPreset.SKIP_ORIGIN))
        assertFalse(result.landsOnMirror(BangumiConnectionPreset.DIRECT))
    }

    @Test
    fun `官方或加密直连还在测时不算会落到镜像上`() {
        val mirrorUp = BangumiConnectivityProbe.Result(mirror = "bangumi.vip", mirrorReachability = Reachability.Reachable(100))
        assertFalse(mirrorUp.copy(origin = Reachability.Checking).landsOnMirror(BangumiConnectionPreset.AUTO))
        assertFalse(
            mirrorUp.copy(origin = Reachability.Unreachable, ech = Reachability.Checking).landsOnMirror(BangumiConnectionPreset.AUTO),
        )
        assertTrue(mirrorUp.copy(origin = Reachability.Unreachable).landsOnMirror(BangumiConnectionPreset.AUTO))
    }

    @Test
    fun `出结论之前先发一次检测中`() = realTimeTest {
        val first = probe { HttpStatusCode.OK }.run().first()

        assertEquals(Reachability.Checking, first.origin)
        assertEquals(Reachability.Checking, first.mirrorReachability)
        assertFalse(first.completed)
        assertNull(first.recommended)
    }
}
