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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.BangumiEndpointSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 粘在哪个目标上什么时候作废, 以及数据里存着的镜像地址怎么走.
 */
class BangumiMirrorRoutingTest {
    private val auto = BangumiRouting(listOf("bangumi.vip"), trusted = false, preferDirect = true)
    private val mirror = BangumiRouting(listOf("bangumi.vip"), trusted = false, preferDirect = false)

    private var nowMillis = 0L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }

    /** 官方的响应由 [origin] 决定 (`null` = 连不上), 其他域名一律 200. */
    private fun client(
        routing: Flow<BangumiRouting>,
        hosts: MutableList<String>,
        origin: () -> HttpStatusCode? = { HttpStatusCode.OK },
    ): HttpClient {
        val engine = MockEngine { req ->
            hosts += req.url.host
            val status = if (req.url.host.endsWith("bgm.tv")) origin() else HttpStatusCode.OK
            respond("", status ?: throw IOException("blocked"))
        }
        val client = HttpClient(engine) { expectSuccess = false }
        BangumiMirrorFeatureHandler(routing, clock = clock).applyToClient(client, true)
        return client
    }

    @Test
    fun `从用镜像手动改回官方连不上时用镜像，下一个请求先试官方`() = runTest {
        val routing = MutableStateFlow(mirror)
        val hosts = mutableListOf<String>()
        val client = client(routing, hosts)

        client.get("https://api.bgm.tv/v0/subjects/1")
        routing.value = auto
        client.get("https://api.bgm.tv/v0/subjects/2")

        assertEquals(listOf("api.bangumi.vip", "api.bgm.tv"), hosts)
    }

    @Test
    fun `镜像上的粘性从换过去那一刻算，一直有请求也会到期`() = runTest {
        var originStatus: HttpStatusCode? = HttpStatusCode.BadGateway
        val hosts = mutableListOf<String>()
        val client = client(flowOf(auto), hosts) { originStatus }

        client.get("https://api.bgm.tv/v0/subjects/1")
        nowMillis = 20.minutes.inWholeMilliseconds
        client.get("https://api.bgm.tv/v0/subjects/2")
        originStatus = HttpStatusCode.OK
        nowMillis = 31.minutes.inWholeMilliseconds
        client.get("https://api.bgm.tv/v0/subjects/3")

        assertEquals(listOf("api.bgm.tv", "api.bangumi.vip", "api.bangumi.vip", "api.bgm.tv"), hosts)
    }

    @Test
    fun `到期后官方还是 5xx 就重新计时，不会每个请求都先打一遍官方`() = runTest {
        val hosts = mutableListOf<String>()
        val client = client(flowOf(auto), hosts) { HttpStatusCode.BadGateway }

        client.get("https://api.bgm.tv/v0/subjects/1")
        nowMillis = 31.minutes.inWholeMilliseconds
        client.get("https://api.bgm.tv/v0/subjects/2")
        nowMillis = 32.minutes.inWholeMilliseconds
        client.get("https://api.bgm.tv/v0/subjects/3")

        assertEquals(listOf("api.bgm.tv", "api.bangumi.vip", "api.bgm.tv", "api.bangumi.vip", "api.bangumi.vip"), hosts)
    }

    @Test
    fun `数据里存着的镜像地址，官方能通时换回官方`() = runTest {
        val hosts = mutableListOf<String>()
        client(flowOf(auto), hosts).get("https://lain.bangumi.vip/pic/cover/l/c4/ca/1.jpg")
        assertEquals(listOf("lain.bgm.tv"), hosts)
    }

    @Test
    fun `只连官方时也换回官方`() = runTest {
        val hosts = mutableListOf<String>()
        val direct = BangumiRouting.Direct.copy(knownMirrors = listOf("bangumi.vip"))
        client(flowOf(direct), hosts).get("https://next.bangumi.vip/p1/subjects/1")
        assertEquals(listOf("next.bgm.tv"), hosts)
    }

    @Test
    fun `用镜像时照样走镜像`() = runTest {
        val hosts = mutableListOf<String>()
        client(flowOf(mirror), hosts).get("https://lain.bangumi.vip/pic/cover/l/c4/ca/1.jpg")
        assertEquals(listOf("lain.bangumi.vip"), hosts)
    }

    @Test
    fun `网页控制台转发图片前先把镜像地址换回原站`() = runTest {
        val provider = BangumiEndpointProvider(
            settings = flowOf(BangumiEndpointSettings(mode = BangumiEndpointMode.MIRROR)),
            mirrors = flowOf(listOf("bangumi.vip")),
            scope = backgroundScope,
        )
        runCurrent()
        assertEquals(
            "https://lain.bgm.tv/pic/cover/l/c4/ca/1.jpg",
            provider.canonicalUrl("https://lain.bangumi.vip/pic/cover/l/c4/ca/1.jpg"),
        )
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", provider.canonicalUrl("https://image.tmdb.org/t/p/w500/a.jpg"))
    }

    @Test
    fun `不相干的域名不碰`() = runTest {
        val hosts = mutableListOf<String>()
        val client = client(flowOf(auto), hosts)
        client.get("https://example.com/a")
        client.get("https://lain.notbangumi.vip/a")
        assertEquals(listOf("example.com", "lain.notbangumi.vip"), hosts)
    }
}
