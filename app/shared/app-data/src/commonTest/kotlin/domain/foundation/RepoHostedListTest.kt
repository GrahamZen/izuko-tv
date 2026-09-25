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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.data.models.preference.RepoHostedListCache
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 仓库维护的清单: 按入口顺序拉, 远程内容逐条归一化, 拉不到用缓存或内置的.
 */
class RepoHostedListTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private val bundled = listOf("https://images.tmdb.org", "https://image.tmdb.org")

    private class Harness(bundled: List<String>, cached: RepoHostedListCache = RepoHostedListCache()) {
        /** 按地址给响应体; 返回 `null` = 连不上. */
        var bodies: (url: String) -> String? = { null }
        val requested = mutableListOf<String>()
        val cache = MemorySettings(cached)

        private val client = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                requested += url
                respond(bodies(url) ?: throw IOException("blocked"), HttpStatusCode.OK)
            },
        )

        // 构造时的自动刷新落在已取消的作用域里不会跑, 由用例自己调 refreshIfStale
        val list = RepoHostedList(
            RepoHostedList.Spec("tmdb-image-hosts.json", "hosts", bundled, EndpointUrls::normalizeBaseUrl),
            cache,
            client = { client.asScopedHttpClient() },
            scope = CoroutineScope(Job().apply { cancel() }),
            repository = { "owner/repo" },
        )
    }

    @Test
    fun `从没拉到过 — 用内置的`() = runTest {
        val harness = Harness(bundled)
        assertEquals(bundled, harness.list.entries.first())
    }

    @Test
    fun `拉到的逐条归一化，认不出的与重复的丢掉`() = runTest {
        val harness = Harness(bundled)
        harness.bodies = { """{"hosts": ["images.tmdb.org", "https://images.tmdb.org/", "not a url", 3, "img.example.com/tmdb"]}""" }
        harness.list.refreshIfStale()
        assertEquals(listOf("https://images.tmdb.org", "https://img.example.com/tmdb"), harness.list.entries.first())
    }

    @Test
    fun `入口按顺序试 — jsDelivr 在前，raw 兜底`() = runTest {
        val harness = Harness(bundled)
        harness.bodies = { url -> if ("raw.githubusercontent.com" in url) """{"hosts": ["img.example.com"]}""" else null }
        harness.list.refreshIfStale()
        assertEquals(
            listOf(
                "https://testingcf.jsdelivr.net/gh/owner/repo@main/tmdb-image-hosts.json",
                "https://gcore.jsdelivr.net/gh/owner/repo@main/tmdb-image-hosts.json",
                "https://cdn.jsdelivr.net/gh/owner/repo@main/tmdb-image-hosts.json",
                "https://raw.githubusercontent.com/owner/repo/main/tmdb-image-hosts.json",
            ),
            harness.requested,
        )
        assertEquals(listOf("https://img.example.com"), harness.list.entries.first())
    }

    @Test
    fun `内容不对的入口跳过，试下一个`() = runTest {
        val harness = Harness(bundled)
        harness.bodies = { url ->
            when {
                "testingcf" in url -> "<html>404</html>"
                "gcore" in url -> """{"mirrors": ["x.com"]}"""
                else -> """{"hosts": ["img.example.com"]}"""
            }
        }
        harness.list.refreshIfStale()
        assertEquals(listOf("https://img.example.com"), harness.list.entries.first())
    }

    @Test
    fun `都拉不到 — 留着上次的`() = runTest {
        val cached = RepoHostedListCache(listOf("https://img.example.com"), updatedAt = 1)
        val harness = Harness(bundled, cached)
        harness.list.refreshIfStale()
        assertEquals(4, harness.requested.size)
        assertEquals(listOf("https://img.example.com"), harness.list.entries.first())
    }

    @Test
    fun `缓存还新 — 不去拉`() = runTest {
        val harness = Harness(bundled, RepoHostedListCache(listOf("https://img.example.com"), updatedAt = currentTimeMillis()))
        harness.list.refreshIfStale()
        assertTrue(harness.requested.isEmpty())
    }

    @Test
    fun `缓存里没有条目 — 不管多新都去拉`() = runTest {
        // 旧格式的缓存解出来就是这样: 条目字段名对不上, 只剩时间
        val harness = Harness(bundled, RepoHostedListCache(emptyList(), updatedAt = currentTimeMillis()))
        harness.bodies = { """{"hosts": ["img.example.com"]}""" }
        harness.list.refreshIfStale()
        assertEquals(listOf("https://img.example.com"), harness.list.entries.first())
    }
}
