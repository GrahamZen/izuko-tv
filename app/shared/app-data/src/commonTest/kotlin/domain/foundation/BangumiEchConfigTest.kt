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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 加密直连的配置 ([BangumiEchConfig]) 与地址清单 ([BangumiEchAddressList]).
 */
@OptIn(ExperimentalEncodingApi::class)
class BangumiEchConfigTest {
    /** 2026-10-13 阿里 DoH 的原样回答: 参数值带引号, 在 JSON 里转义成 `\"`. */
    private val aliAnswer = """{"Status":0,"Answer":[{"name":"crypto.cloudflare.com.","TTL":300,"type":65,""" +
            """"data":"1 . alpn=\"h2\" ipv4hint=\"162.159.135.79\" ech=\"AAEC\" ipv6hint=\"2606:4700:7::a29f:874f\""}]}"""

    /** Google / Cloudflare 的回答: 参数值不带引号. */
    private val googleAnswer = """{"Status":0,"Answer":[{"type":65,"data":"1 . alpn=h2 ech=AwQF ipv6hint=::1"}]}"""

    @Test
    fun `从 DoH 回答里取出 ECH 配置`() {
        assertContentEquals(byteArrayOf(0, 1, 2), BangumiEchConfig.parseDohAnswer(aliAnswer))
        assertContentEquals(byteArrayOf(3, 4, 5), BangumiEchConfig.parseDohAnswer(googleAnswer))
        assertNull(BangumiEchConfig.parseDohAnswer("""{"Status":0,"Answer":[{"type":65,"data":"1 . alpn=h2"}]}"""))
        assertNull(BangumiEchConfig.parseDohAnswer("not json"))
    }

    @Test
    fun `包里那份解得开`() {
        assertTrue(Base64.decode(BangumiEchConfig.BUNDLED).isNotEmpty())
    }

    /**
     * 在真实调度器上跑: 每个 DoH 入口有超时 ([kotlinx.coroutines.withTimeoutOrNull]), 放在 runTest 的虚拟时间里,
     * 调度器一空闲就瞬间到点 —— 而 MockEngine 在别的线程上回应, 答得上的也会被判成超时.
     */
    private fun realTimeTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    private fun config(
        answers: Map<String, String?>,
        requested: MutableList<String> = mutableListOf(),
        now: () -> Long = { 0L },
        scope: CoroutineScope,
    ): BangumiEchConfig {
        val engine = MockEngine { req ->
            requested += req.url.host
            val body = answers[req.url.host] ?: throw IOException("blocked")
            respond(body, HttpStatusCode.OK)
        }
        val client = HttpClient(engine).asScopedHttpClient()
        return BangumiEchConfig(
            { client },
            scope,
            dohEndpoints = listOf("https://dns.alidns.com/resolve", "https://dns.google/resolve"),
            now = now,
        )
    }

    @Test
    fun `按顺序问，第一个答得上的为准`() = realTimeTest {
        val requested = mutableListOf<String>()
        val config = config(mapOf("dns.alidns.com" to null, "dns.google" to googleAnswer), requested, scope = this)

        assertTrue(config.refresh())
        assertContentEquals(byteArrayOf(3, 4, 5), config.current)
        assertEquals(listOf("dns.alidns.com", "dns.google"), requested)
    }

    @Test
    fun `一分钟内不重复问，配置没变时报没换`() = realTimeTest {
        var now = 0L
        val requested = mutableListOf<String>()
        val config = config(mapOf("dns.alidns.com" to aliAnswer), requested, now = { now }, scope = this)

        assertTrue(config.refresh())
        assertFalse(config.refresh())
        assertEquals(1, requested.size)

        now = 61_000
        assertFalse(config.refresh())
        assertEquals(2, requested.size)
    }

    @Test
    fun `握手被拒后 — 配置刚被别的请求换过也算换了，换过很久就不算`() = realTimeTest {
        var now = 0L
        val config = config(mapOf("dns.alidns.com" to aliAnswer), now = { now }, scope = this)
        // 第一次用到时后台那次刷新换到了新配置
        assertTrue(config.refresh())
        // 同时进行的那次握手用的还是旧配置, 被拒: 不用再问, 用新配置再试
        now = 5_000
        assertTrue(config.refresh(acceptRecentChange = true))
        // 换过很久了 (又还在一分钟内): 不是配置的问题
        now = 40_000
        assertFalse(config.refresh(acceptRecentChange = true))
    }

    @Test
    fun `都问不到 — 留着原来的`() = realTimeTest {
        val config = config(emptyMap(), scope = this)
        val before = config.current
        assertFalse(config.refresh())
        assertContentEquals(before, config.current)
    }

    @Test
    fun `地址清单只认 IP`() {
        assertEquals("104.21.6.61", BangumiEchAddressList.normalizeAddress(" 104.21.6.61 "))
        assertEquals("2606:4700:20::681a:817", BangumiEchAddressList.normalizeAddress("2606:4700:20::681a:817"))
        assertNull(BangumiEchAddressList.normalizeAddress("256.1.1.1"))
        assertNull(BangumiEchAddressList.normalizeAddress("api.bgm.tv"))
        assertNull(BangumiEchAddressList.normalizeAddress("1.2.3"))
        assertNull(BangumiEchAddressList.normalizeAddress(""))
    }
}
