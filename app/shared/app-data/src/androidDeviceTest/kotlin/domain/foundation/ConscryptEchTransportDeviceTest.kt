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
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 在真机 / 模拟器上走一遍加密直连, 要联网. 判据是 Cloudflare 的 `/cdn-cgi/trace`: 握手用上 ECH 时回 `sni=encrypted`,
 * 没用上 (比如 Conscrypt 没认到要求 ECH 的策略, 退回明文的域名) 时回 `sni=plaintext` —— 后者请求照样成功, 只看成功与否测不出来.
 */
class ConscryptEchTransportDeviceTest {
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private object MemoryCache : Settings<RepoHostedListCache> {
        override val flow = MutableStateFlow(RepoHostedListCache.Default)
        override suspend fun set(value: RepoHostedListCache) {
            flow.value = value
        }
    }

    /** 用包里带的配置与地址: 用例里不拉远程清单, 也不问 DoH. */
    private fun transport() = ConscryptEchTransport(
        BangumiEchConfig({ error("not used in this test") }, scope),
        lazy { BangumiEchAddressList(MemoryCache, { error("not used in this test") }, scope) },
    )

    @Test
    fun `the handshake hides the bangumi host name`() = runBlocking {
        val client = HttpClient(OkHttp) { transport().install(this) }
        val host = checkNotNull(BangumiRoute.Ech.hostFor("api.bgm.tv"))
        val trace = client.use { it.get("https://$host/cdn-cgi/trace").bodyAsText() }
        // 转过去时换回了真实域名, Cloudflare 才交给 bangumi
        assertTrue("h=api.bgm.tv" in trace, trace)
        assertTrue("sni=encrypted" in trace, trace)
    }

    @Test
    fun `the subdomains are served over http 2`() = runBlocking {
        val client = HttpClient(OkHttp) { transport().install(this) }
        client.use {
            for (sub in listOf("api", "next", "lain")) {
                val host = checkNotNull(BangumiRoute.Ech.hostFor("$sub.bgm.tv"))
                val trace = it.get("https://$host/cdn-cgi/trace").bodyAsText()
                assertTrue("http=http/2" in trace, trace)
            }
        }
    }

    @Test
    fun `other hosts keep the system tls`() = runBlocking {
        val client = HttpClient(OkHttp) { transport().install(this) }
        val trace = client.use { it.get("https://api.bgm.tv/cdn-cgi/trace").bodyAsText() }
        assertTrue("sni=plaintext" in trace, trace)
    }
}
