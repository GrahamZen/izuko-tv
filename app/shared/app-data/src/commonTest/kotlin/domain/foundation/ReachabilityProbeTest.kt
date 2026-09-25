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
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.EndpointSelection
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 首次启动引导的 TMDB 图片那一页: 清单里的每个入口直接各连一次.
 */
class ReachabilityProbeTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    /**
     * 真实调度器上跑, 理由见 [BangumiConnectivityProbeTest]. 入口对象里的热流挂在 [TestScope.backgroundScope] 上:
     * 挂在 withContext 的作用域里的话, 它永不结束, withContext 也就等不到头.
     */
    private fun realTimeTest(block: suspend TestScope.() -> Unit) = runTest {
        val test = this
        withContext(Dispatchers.Default) { test.block() }
    }

    private val entries = listOf("https://images.tmdb.org", "https://image.tmdb.org")

    private fun endpoints(scope: CoroutineScope, entries: List<String> = this.entries) = AlternativeEndpoints(
        name = "TMDB image",
        canonicalBaseUrl = "https://image.tmdb.org",
        probePath = "/t/p/w92/probe.jpg",
        selection = MemorySettings(EndpointSelection()),
        list = RepoHostedList(
            RepoHostedList.Spec("tmdb-image-hosts.json", "hosts", entries, EndpointUrls::normalizeBaseUrl),
            MemorySettings(RepoHostedListCache(entries, updatedAt = currentTimeMillis())),
            client = { error("the list is fresh; nothing should be fetched") },
            scope,
        ),
        scope = scope,
    )

    private fun probe(requested: MutableList<String> = mutableListOf(), status: (host: String) -> HttpStatusCode?): ReachabilityProbe {
        // 各入口并行探测, 跑在多线程调度器上: 不加锁的话两条同时 add 会丢一条
        val requestedLock = Mutex()
        val client = HttpClient(
            MockEngine { request ->
                requestedLock.withLock { requested += request.url.toString() }
                respond("", status(request.url.host) ?: throw IOException("blocked"))
            },
        ) {
            install(HttpTimeout)
            expectSuccess = true
        }
        return ReachabilityProbe({ client.asScopedHttpClient() })
    }

    @Test
    fun `每个入口直接连 — 不经过改写`() = realTimeTest {
        val requested = mutableListOf<String>()
        val result = probe(requested) { HttpStatusCode.OK }.checkCandidates(endpoints(backgroundScope)).last()
        assertTrue(result.completed)
        assertEquals(
            setOf("https://images.tmdb.org/t/p/w92/probe.jpg", "https://image.tmdb.org/t/p/w92/probe.jpg"),
            requested.toSet(),
        )
        assertEquals("https://images.tmdb.org", result.firstReachable)
    }

    @Test
    fun `第一个连不上 — 自动会落到第二个`() = realTimeTest {
        val result = probe { if (it == "images.tmdb.org") null else HttpStatusCode.OK }
            .checkCandidates(endpoints(backgroundScope)).last()
        assertEquals(Reachability.Unreachable, result.candidates[0].second)
        assertIs<Reachability.Reachable>(result.candidates[1].second)
        assertEquals("https://image.tmdb.org", result.firstReachable)
    }

    @Test
    fun `回 404 也算连得上 — 探测图换掉了不影响判定`() = realTimeTest {
        val result = probe { HttpStatusCode.NotFound }.checkCandidates(endpoints(backgroundScope)).last()
        assertEquals("https://images.tmdb.org", result.firstReachable)
    }

    @Test
    fun `都连不上`() = realTimeTest {
        val result = probe { null }.checkCandidates(endpoints(backgroundScope)).last()
        assertTrue(result.completed)
        assertNull(result.firstReachable)
    }

    @Test
    fun `先发一次全是检测中的`() = realTimeTest {
        val first = probe { HttpStatusCode.OK }.checkCandidates(endpoints(backgroundScope)).first()
        assertTrue(first.loaded)
        assertFalse(first.completed)
        assertEquals(listOf(Reachability.Checking, Reachability.Checking), first.candidates.map { it.second })
    }

    @Test
    fun `模板入口 — 探测图换进参数`() = realTimeTest {
        val requested = mutableListOf<String>()
        probe(requested) { HttpStatusCode.OK }
            .checkCandidates(endpoints(backgroundScope, listOf("https://wsrv.nl/?url=image.tmdb.org{path}"))).last()
        assertEquals("image.tmdb.org/t/p/w92/probe.jpg", Url(requested.single()).parameters["url"])
    }

    @Test
    fun `排在前面的还没出结论 — 不下结论`() {
        val check = CandidatesCheck(
            listOf("https://a.example.com" to Reachability.Checking, "https://b.example.com" to Reachability.Reachable(10)),
            loaded = true,
        )
        assertNull(check.firstReachable)
        val decided = check.copy(
            candidates = listOf(
                "https://a.example.com" to Reachability.Unreachable,
                "https://b.example.com" to Reachability.Reachable(10),
            ),
        )
        assertEquals("https://b.example.com", decided.firstReachable)
    }
}
