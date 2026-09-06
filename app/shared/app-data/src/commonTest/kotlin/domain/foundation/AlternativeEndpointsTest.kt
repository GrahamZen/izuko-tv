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
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.EndpointSelection
import me.him188.ani.app.data.models.preference.EndpointSelectionMode
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 可互换入口的改写与回落: 数据里存的是原站地址, 请求按选择与清单换到实际入口, 连不上换下一个并记住.
 */
class AlternativeEndpointsTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private class Harness(
        scope: CoroutineScope,
        selection: EndpointSelection = EndpointSelection(),
        entries: List<String> = listOf("https://images.tmdb.org", "https://image.tmdb.org"),
    ) {
        /** 连不上的域名. */
        val blocked = mutableSetOf<String>()

        /** 各域名的响应码, 默认 200. */
        val status = mutableMapOf<String, HttpStatusCode>()

        /** 依次打到的完整地址. */
        val requested = mutableListOf<String>()

        val listCache = MemorySettings(RepoHostedListCache(entries, updatedAt = currentTimeMillis()))
        val selection = MemorySettings(selection)

        private val endpoints = AlternativeEndpoints(
            name = "TMDB image",
            canonicalBaseUrl = "https://image.tmdb.org",
            probePath = "/t/p/w92/probe.jpg",
            selection = this.selection,
            // 缓存是新的, 不会去拉清单
            list = RepoHostedList(
                RepoHostedList.Spec("tmdb-image-hosts.json", "hosts", entries, EndpointUrls::normalizeBaseUrl),
                listCache,
                client = { error("the list is fresh; nothing should be fetched") },
                scope,
            ),
            scope = scope,
        )

        val client = HttpClient(
            MockEngine { request ->
                requested += request.url.toString()
                if (request.url.host in blocked) throw IOException("blocked")
                respond("", status[request.url.host] ?: HttpStatusCode.OK)
            },
        ) { expectSuccess = false }

        init {
            AlternativeEndpointsFeatureHandler(listOf(endpoints)).applyToClient(client, true)
        }

        suspend fun get(url: String = IMAGE) = client.get(url).status
    }

    @Test
    fun `自动 — 先用清单第一个`() = runTest {
        val harness = Harness(backgroundScope)
        assertEquals(HttpStatusCode.OK, harness.get())
        assertEquals(listOf("https://images.tmdb.org/t/p/w1280/a.jpg"), harness.requested)
    }

    @Test
    fun `第一个连不上换下一个，并记住通的那个`() = runTest {
        val harness = Harness(backgroundScope)
        harness.blocked += "images.tmdb.org"
        assertEquals(HttpStatusCode.OK, harness.get())
        assertEquals(HttpStatusCode.OK, harness.get())
        assertEquals(
            listOf(
                "https://images.tmdb.org/t/p/w1280/a.jpg",
                "https://image.tmdb.org/t/p/w1280/a.jpg",
                // 第二次直接从通的那个开始, 不再白等一次
                "https://image.tmdb.org/t/p/w1280/a.jpg",
            ),
            harness.requested,
        )
    }

    @Test
    fun `记住的那个也连不上了 — 接着试别的`() = runTest {
        val harness = Harness(backgroundScope)
        harness.blocked += "images.tmdb.org"
        harness.get()
        harness.blocked.clear()
        harness.blocked += "image.tmdb.org"
        assertEquals(HttpStatusCode.OK, harness.get())
        assertEquals("https://images.tmdb.org/t/p/w1280/a.jpg", harness.requested.last())
    }

    @Test
    fun `回 4xx 是对方在回答 — 不换入口`() = runTest {
        val harness = Harness(backgroundScope)
        harness.status["images.tmdb.org"] = HttpStatusCode.NotFound
        assertEquals(HttpStatusCode.NotFound, harness.get())
        assertEquals(listOf("https://images.tmdb.org/t/p/w1280/a.jpg"), harness.requested)
    }

    @Test
    fun `回 5xx 换下一个`() = runTest {
        val harness = Harness(backgroundScope)
        harness.status["images.tmdb.org"] = HttpStatusCode.BadGateway
        assertEquals(HttpStatusCode.OK, harness.get())
        assertEquals(2, harness.requested.size)
    }

    @Test
    fun `全都连不上 — 交出真实的错误`() = runTest {
        val harness = Harness(backgroundScope)
        harness.blocked += setOf("images.tmdb.org", "image.tmdb.org")
        assertFailsWith<IOException> { harness.get() }
        assertEquals(2, harness.requested.size)
    }

    @Test
    fun `选定一个就只用它 — 连不上也不换`() = runTest {
        val harness = Harness(
            backgroundScope,
            EndpointSelection(mode = EndpointSelectionMode.FIXED, fixedBaseUrl = "https://images.tmdb.org"),
        )
        harness.blocked += "images.tmdb.org"
        assertFailsWith<IOException> { harness.get() }
        assertEquals(listOf("https://images.tmdb.org/t/p/w1280/a.jpg"), harness.requested)
    }

    @Test
    fun `自定义入口 — 带端口与路径前缀`() = runTest {
        val harness = Harness(
            backgroundScope,
            EndpointSelection(mode = EndpointSelectionMode.CUSTOM, customBaseUrl = "http://10.0.0.2:8080/tmdb/"),
        )
        harness.get()
        assertEquals(listOf("http://10.0.0.2:8080/tmdb/t/p/w1280/a.jpg"), harness.requested)
    }

    @Test
    fun `模板入口 — 路径换进参数`() = runTest {
        val harness = Harness(backgroundScope, entries = listOf(WSRV, "https://image.tmdb.org"))
        harness.get()
        val url = Url(harness.requested.single())
        assertEquals("wsrv.nl", url.host)
        assertEquals("image.tmdb.org/t/p/w1280/a.jpg", url.parameters["url"])
    }

    @Test
    fun `模板入口连不上换下一个 — 不带上它的参数`() = runTest {
        val harness = Harness(backgroundScope, entries = listOf(WSRV, "https://image.tmdb.org"))
        harness.blocked += "wsrv.nl"
        assertEquals(HttpStatusCode.OK, harness.get())
        assertEquals("https://image.tmdb.org/t/p/w1280/a.jpg", harness.requested.last())
    }

    @Test
    fun `别的域名不归它管`() = runTest {
        val harness = Harness(backgroundScope)
        harness.get("https://lain.bgm.tv/pic/cover/l/a.jpg")
        assertEquals(listOf("https://lain.bgm.tv/pic/cover/l/a.jpg"), harness.requested)
    }

    @Test
    fun `清单一变就从头试`() = runTest {
        val harness = Harness(backgroundScope)
        harness.blocked += "images.tmdb.org"
        harness.get()
        harness.blocked.clear()
        harness.listCache.set(
            RepoHostedListCache(
                listOf("https://images.tmdb.org", "https://image.tmdb.org", "https://img.example.com"),
                updatedAt = currentTimeMillis(),
            ),
        )
        runCurrent() // 让入口列表那个热流收到新清单
        harness.get()
        assertEquals("https://images.tmdb.org/t/p/w1280/a.jpg", harness.requested.last())
    }

    @Test
    fun `改了选择立刻生效`() = runTest {
        val harness = Harness(backgroundScope)
        harness.get()
        harness.selection.set(EndpointSelection(mode = EndpointSelectionMode.FIXED, fixedBaseUrl = "https://image.tmdb.org"))
        runCurrent()
        harness.get()
        assertEquals("https://image.tmdb.org/t/p/w1280/a.jpg", harness.requested.last())
    }

    private companion object {
        const val IMAGE = "https://image.tmdb.org/t/p/w1280/a.jpg"
        const val WSRV = "https://wsrv.nl/?url=image.tmdb.org{path}"
    }
}
