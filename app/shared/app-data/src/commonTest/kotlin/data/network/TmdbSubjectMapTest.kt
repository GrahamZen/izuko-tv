/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientFeatureHandler
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.ServerListFeatureHandler
import me.him188.ani.app.domain.foundation.UserAgentFeature
import me.him188.ani.app.domain.foundation.UserAgentFeatureHandler
import me.him188.ani.app.domain.settings.NoProxyProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbSubjectMapTest {
    private val tsv = listOf(
        "# bangumi-tmdb-map v1. 列: bgm_id, backdrop, backdrop_path, stills, source",
        "237\tmovie/9323\t/jIpagj2Dud9g3ZVJFvaWikEWsYz.jpg\tmovie/9323\tauto",
        "140001\ttv/65942\t/7ZruEnSnHD6Jx5mF0hBt1E306Vt.jpg\ttv/65942\tauto",
        "516311\ttv/65942\t/7ZruEnSnHD6Jx5mF0hBt1E306Vt.jpg\ttv/65942/season/0\tauto",
        "296195\ttv/65942\t\t\tmanual",
        "311\t\t\t\tmanual",
        // 以下都认不出, 要跳过
        "100\ttv/1/season/1\t/a.jpg\t\tauto",
        "101\tbook/1\t/a.jpg\t\tauto",
        "102\ttv/2\t/a.jpg\ttv/2/season/x\tauto",
        "abc\ttv/3\t/a.jpg\t\tauto",
    ).joinToString("\n")

    @Test
    fun `解析 - 自动结果`() {
        val entry = parseTmdbSubjectMap(tsv).getValue(140001)
        assertEquals(TmdbSubjectMapRef("tv", 65942), entry.backdrop)
        assertEquals("/7ZruEnSnHD6Jx5mF0hBt1E306Vt.jpg", entry.backdropPath)
        assertEquals(listOf(TmdbSubjectMapRef("tv", 65942)), entry.stills)
        assertEquals(false, entry.manual)
        assertEquals("tv/65942", entry.stillsKey)
    }

    @Test
    fun `解析 - 剧照只取 S0`() {
        val entry = parseTmdbSubjectMap(tsv).getValue(516311)
        assertEquals(listOf(TmdbSubjectMapRef("tv", 65942, 0)), entry.stills)
        assertEquals("tv/65942/season/0", entry.stillsKey)
    }

    @Test
    fun `解析 - 人工修正只给条目不给图`() {
        val entry = parseTmdbSubjectMap(tsv).getValue(296195)
        assertEquals(TmdbSubjectMapRef("tv", 65942), entry.backdrop)
        assertNull(entry.backdropPath)
        assertTrue(entry.manual)
    }

    @Test
    fun `分集缓存标记 - 没给剧照出处时跟着背景图条目`() {
        val map = parseTmdbSubjectMap(tsv)
        assertEquals("tv/65942", map.getValue(140001).stillsBuildKey)
        assertEquals("follow:tv/65942", map.getValue(296195).stillsBuildKey)
        assertEquals("follow:none", map.getValue(311).stillsBuildKey)
    }

    @Test
    fun `解析 - 人工确认没有对应`() {
        val entry = parseTmdbSubjectMap(tsv).getValue(311)
        assertNull(entry.backdrop)
        assertNull(entry.backdropPath)
        assertEquals(emptyList(), entry.stills)
        assertTrue(entry.manual)
    }

    @Test
    fun `解析 - 认不出的行整行跳过`() {
        assertEquals(setOf(237, 140001, 516311, 296195, 311), parseTmdbSubjectMap(tsv).keys)
    }

    @Test
    fun `背景图 - 表里有路径就直接用，不发请求也不要 token`() = runTest {
        val service = serviceWithMap()
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/jIpagj2Dud9g3ZVJFvaWikEWsYz.jpg",
            service.getBackdropUrl(237, "GHOST IN THE SHELL / 攻殻機動隊"),
        )
        // 进程内热表也记上了, 页面首帧能同步读到
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/jIpagj2Dud9g3ZVJFvaWikEWsYz.jpg",
            service.peekBackdropUrl(237),
        )
    }

    @Test
    fun `背景图 - 人工确认没有对应就不出图，也不去搜`() = runTest {
        val service = serviceWithMap()
        assertNull(service.getBackdropUrl(311, "千と千尋の神隠し"))
        assertTrue(service.peekBackdropResolved(311))
    }

    private fun TestScope.serviceWithMap(): TmdbImageService {
        val map = TmdbSubjectMapRepository(
            cache = MemoryDataStore(TmdbSubjectMapCache(tsv = tsv, checkedAt = Long.MAX_VALUE)),
            client = { error("不应下载对应表") },
            enabled = flowOf(false),
            scope = backgroundScope,
        )
        return TmdbImageService(
            httpClientProvider = DefaultHttpClientProvider(
                NoProxyProvider, backgroundScope,
                featureHandlers = listOf(
                    FailOnRequestHandler,
                    ServerListFeatureHandler(flowOf(listOf(Url("https://auth.myani.org/")))),
                ),
            ),
            dataStore = MemoryDataStore(TmdbImageCache()),
            ioDispatcher = Dispatchers.Default,
            subjectMap = map,
        )
    }

    /** 顶替一定会被请求的 UA 处理器; 任何请求都让测试失败 (AssertionError 不是 Exception, 不会被业务代码吞掉). */
    private object FailOnRequestHandler : ScopedHttpClientFeatureHandler<ScopedHttpClientUserAgent>(UserAgentFeature) {
        override fun applyToConfig(config: HttpClientConfig<*>, value: ScopedHttpClientUserAgent) =
            UserAgentFeatureHandler.applyToConfig(config, value)

        override fun applyToClient(client: HttpClient, value: ScopedHttpClientUserAgent) {
            client.plugin(HttpSend).intercept { request ->
                throw AssertionError("不应发请求: ${request.url.buildString()}")
            }
        }
    }
}
