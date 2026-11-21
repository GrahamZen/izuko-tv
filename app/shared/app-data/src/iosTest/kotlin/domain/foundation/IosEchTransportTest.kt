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
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 联网的几条只在设了 `IZUKO_ECH_NETWORK_TEST=1` 时跑 (模拟器里经 `SIMCTL_CHILD_IZUKO_ECH_NETWORK_TEST=1` 传进去): 要连 Cloudflare.
 */
@OptIn(ExperimentalEncodingApi::class)
class IosEchTransportTest {
    private val network = NSProcessInfo.processInfo.environment["IZUKO_ECH_NETWORK_TEST"] == "1"

    private fun transport(config: ByteArray = Base64.decode(BangumiEchConfig.BUNDLED)) = IosEchTransport(
        config = { config },
        addresses = { BangumiEchAddressList.BUNDLED },
        refreshConfig = { false },
        onFirstUse = {},
    )

    @Test
    fun `only ech addresses are taken over`() {
        fun request(url: String) = NSURLRequest(uRL = NSURL(string = url))
        assertTrue(EchUrlProtocol.canInitWithRequest(request("https://api.bgm-tv.ech.invalid/v0/me")))
        assertTrue(EchUrlProtocol.canInitWithRequest(request("https://relay.example.workers.dev.ech.invalid/x")))
        assertFalse(EchUrlProtocol.canInitWithRequest(request("https://api.bgm.tv/v0/me")))
        assertFalse(EchUrlProtocol.canInitWithRequest(request("https://example.com/")))
    }

    @Test
    fun `curl reaches bangumi with encrypted sni`() {
        if (!network) return
        val result = transport().selfCheck("api.bgm.tv")
        assertTrue("status=200" in result, result)
        assertTrue("h=api.bgm.tv" in result, result)
        assertTrue("sni=encrypted" in result, result)
        assertTrue("tls=TLSv1.3" in result, result)
    }

    @Test
    fun `stale config is replaced by the config the server sends back`() {
        if (!network) return
        // 公钥改掉: 服务器解不开, 拒掉 ECH 并给出现在的配置, curl 用它重试
        val stale = Base64.decode(BangumiEchConfig.BUNDLED).also { it[12] = (it[12].toInt() xor 0xff).toByte() }
        val result = transport(stale).selfCheck("api.bgm.tv")
        assertTrue("sni=encrypted" in result, result)
    }

    @Test
    fun `darwin client goes through the protocol`() = runTest {
        if (!network) return@runTest
        val transport = transport()
        HttpClient(Darwin) { transport.install(this) }.use { client ->
            val trace = client.get("https://api.bgm-tv.ech.invalid/cdn-cgi/trace").bodyAsText()
            assertTrue("h=api.bgm.tv" in trace, trace)
            assertTrue("sni=encrypted" in trace, trace)

            val subject = client.get("https://api.bgm-tv.ech.invalid/v0/subjects/12") {
                header(HttpHeaders.UserAgent, "izuko-test/1.0")
            }
            assertEquals(200, subject.status.value, subject.bodyAsText().take(300))
            assertTrue("\"id\":12" in subject.bodyAsText().replace(" ", ""))
        }
    }
}
