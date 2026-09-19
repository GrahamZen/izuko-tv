/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SelectorMediaSourceEngine.resolveVideoDirectly]: 不开 WebView, 按配置请求站点自己的取流接口.
 *
 * 用例取自实际排查的稀饭动漫 Next (见 memory `project-old-webview-source-fail`): 播放页是 Next.js
 * 客户端渲染的, 地址来自一个无鉴权的 supabase edge function. 老电视的系统 WebView 跑不动它的 JS,
 * 配上这条就能绕开浏览器.
 */
class SelectorResolveVideoDirectlyTest {
    private class RecordingEngine(
        private val responseBody: String,
        client: HttpClient = HttpClient(MockEngine { respond("") }),
    ) : SelectorMediaSourceEngine() {
        var lastMethod: String? = null
        var lastUrl: String? = null
        var lastHeaders: List<Pair<String, String>> = emptyList()
        var lastBody: String? = null

        @Suppress("unused")
        private val scoped = client.asScopedHttpClient()

        override suspend fun searchImpl(finalUrl: io.ktor.http.Url): SearchSubjectResult =
            SearchSubjectResult(finalUrl, document = null)

        override suspend fun doHttpGet(uri: String) = throw UnsupportedOperationException()

        override suspend fun doHttpText(
            method: String,
            url: String,
            headers: List<Pair<String, String>>,
            body: String,
        ): String {
            lastMethod = method
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return responseBody
        }
    }

    /** 稀饭动漫 Next 实测抓到的响应 (已裁剪) */
    private val xfdmResponse = """
        {"ok":true,"action":"fallback","resolved_action":"fallback","episode_id":21094,"anime_id":1360,
         "url":"https://play.xfvod.pro:8088/R/R-ReLIFE/01.mp4",
         "candidates":[{"source_id":5,"source_code":"xfy2","url":"https://play.xfvod.pro:8088/R/R-ReLIFE/01.mp4"}]}
    """.trimIndent()

    private val xfdmConfig = SelectorSearchConfig.ResolveVideoConfig(
        requestUrl = "https://xxx.supabase.co/functions/v1/issue-web-playback",
        matchPageUrl = """/play/(?<episodeId>\d+)""",
        method = "POST",
        requestHeaders = "Content-Type: application/json",
        requestBody = """{"action":"fallback","episode_id":{episodeId}}""",
        selectUrlJsonPath = "$.url",
    )

    private val pageUrl = "https://next.xifanacg.com/anime/1360/play/21094?source=xfy2"

    @Test
    fun `xfdm - 从播放页地址抽出 episodeId 并取到直链`() = runTest {
        val engine = RecordingEngine(xfdmResponse)
        val video = engine.resolveVideoDirectly(
            pageUrl,
            xfdmConfig,
            SelectorSearchConfig.MatchVideoConfig(),
        )

        assertEquals("https://play.xfvod.pro:8088/R/R-ReLIFE/01.mp4", video?.m3u8Url)
        assertEquals("POST", engine.lastMethod)
        assertEquals("https://xxx.supabase.co/functions/v1/issue-web-playback", engine.lastUrl)
        // {episodeId} 必须被换掉, 且不能给数字加引号 (接口要的是 number)
        assertEquals("""{"action":"fallback","episode_id":21094}""", engine.lastBody)
        assertEquals(listOf("Content-Type" to "application/json"), engine.lastHeaders)
    }

    @Test
    fun `视频请求头沿用 matchVideo 那一份`() = runTest {
        val video = RecordingEngine(xfdmResponse).resolveVideoDirectly(
            pageUrl,
            xfdmConfig,
            SelectorSearchConfig.MatchVideoConfig(
                addHeadersToVideo = SelectorSearchConfig.VideoHeaders(
                    referer = "https://next.xifanacg.com/",
                    userAgent = "UA/1.0",
                ),
            ),
        )
        assertEquals("https://next.xifanacg.com/", video?.headers?.get("Referer"))
        assertEquals("UA/1.0", video?.headers?.get("User-Agent"))
    }

    @Test
    fun `没配置时返回 null，调用方退回 WebView`() = runTest {
        val engine = RecordingEngine(xfdmResponse)
        assertNull(
            engine.resolveVideoDirectly(
                pageUrl,
                SelectorSearchConfig.ResolveVideoConfig(), // 默认: requestUrl 为空
                SelectorSearchConfig.MatchVideoConfig(),
            ),
        )
        assertNull(engine.lastUrl, "没启用时不该发任何请求")
    }

    @Test
    fun `matchPageUrl 匹配不上时返回 null 而不是发一个缺变量的请求`() = runTest {
        val engine = RecordingEngine(xfdmResponse)
        assertNull(
            engine.resolveVideoDirectly(
                "https://next.xifanacg.com/anime/1360",
                xfdmConfig,
                SelectorSearchConfig.MatchVideoConfig(),
            ),
        )
        assertNull(engine.lastUrl)
    }

    @Test
    fun `请求抛异常时吞掉返回 null，不能把原本能用 WebView 的源拖下水`() = runTest {
        val engine = object : SelectorMediaSourceEngine() {
            override suspend fun searchImpl(finalUrl: io.ktor.http.Url) =
                SearchSubjectResult(finalUrl, document = null)

            override suspend fun doHttpGet(uri: String) = throw UnsupportedOperationException()
            override suspend fun doHttpText(
                method: String,
                url: String,
                headers: List<Pair<String, String>>,
                body: String,
            ): String = throw RuntimeException("boom")
        }
        assertNull(engine.resolveVideoDirectly(pageUrl, xfdmConfig, SelectorSearchConfig.MatchVideoConfig()))
    }

    @Test
    fun `响应不是 JSON 时退回正则取地址`() = runTest {
        val engine = RecordingEngine("var url = 'https://cdn.example.com/a/b.m3u8'; play(url)")
        val video = engine.resolveVideoDirectly(
            pageUrl,
            xfdmConfig.copy(
                selectUrlJsonPath = "",
                selectUrlRegex = """https?://[^'"\s]+\.m3u8""",
            ),
            SelectorSearchConfig.MatchVideoConfig(),
        )
        assertEquals("https://cdn.example.com/a/b.m3u8", video?.m3u8Url)
    }

    @Test
    fun `正则有命名分组 v 时取该分组`() = runTest {
        val engine = RecordingEngine("""{"data":{"playUrl":"https://cdn.example.com/x.mp4"}}""")
        val video = engine.resolveVideoDirectly(
            pageUrl,
            xfdmConfig.copy(
                selectUrlJsonPath = "",
                selectUrlRegex = """"playUrl":"(?<v>[^"]+)"""",
            ),
            SelectorSearchConfig.MatchVideoConfig(),
        )
        assertEquals("https://cdn.example.com/x.mp4", video?.m3u8Url)
    }

    @Test
    fun `GET 也能用，pageUrl 是内置变量`() = runTest {
        val engine = RecordingEngine("""{"url":"https://cdn.example.com/x.mp4"}""")
        engine.resolveVideoDirectly(
            pageUrl,
            SelectorSearchConfig.ResolveVideoConfig(
                requestUrl = "https://api.example.com/resolve?page={pageUrl}",
                selectUrlJsonPath = "$.url",
            ),
            SelectorSearchConfig.MatchVideoConfig(),
        )
        assertEquals("GET", engine.lastMethod)
        assertEquals("https://api.example.com/resolve?page=$pageUrl", engine.lastUrl)
        assertEquals("", engine.lastBody)
    }

    @Test
    fun `取不到地址时返回 null`() = runTest {
        val engine = RecordingEngine("""{"ok":false,"error":"not found"}""")
        assertNull(engine.resolveVideoDirectly(pageUrl, xfdmConfig, SelectorSearchConfig.MatchVideoConfig()))
    }

    @Test
    fun `JSON 里本来就有的花括号不被当成变量吃掉`() = runTest {
        val engine = RecordingEngine("""{"url":"https://a/b.mp4"}""")
        engine.resolveVideoDirectly(
            pageUrl,
            xfdmConfig.copy(requestBody = """{"filter":{"id":{episodeId}},"raw":"{unknownVar}"}"""),
            SelectorSearchConfig.MatchVideoConfig(),
        )
        assertEquals("""{"filter":{"id":21094},"raw":"{unknownVar}"}""", engine.lastBody)
    }

    @Test
    fun `多个命名分组都能当变量用`() = runTest {
        val engine = RecordingEngine("""{"url":"https://a/b.mp4"}""")
        engine.resolveVideoDirectly(
            pageUrl,
            xfdmConfig.copy(
                matchPageUrl = """/anime/(?<animeId>\d+)/play/(?<episodeId>\d+)""",
                requestBody = """{"a":{animeId},"e":{episodeId}}""",
            ),
            SelectorSearchConfig.MatchVideoConfig(),
        )
        assertEquals("""{"a":1360,"e":21094}""", engine.lastBody)
    }

    @Test
    fun `namedGroupNames 按出现顺序列出命名分组，跳过非捕获组与字符类`() {
        assertEquals(
            listOf("animeId", "episodeId"),
            namedGroupNames("""/anime/(?<animeId>\d+)(?:/x)?/play/(?<episodeId>[\d(?<no>)]+)/?"""),
        )
        assertTrue(namedGroupNames("""/play/(\d+)""").isEmpty())
    }

    @Test
    fun `DefaultSelectorMediaSourceEngine 真发请求`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("POST", request.method.value)
                // body 是 OutgoingContent 时 Content-Type 挂在 body 上, 不在 request.headers 里
                assertEquals("application/json", request.body.contentType?.toString())
                assertEquals(
                    """{"action":"fallback","episode_id":21094}""",
                    (request.body as TextContent).text,
                )
                respond(
                    """{"url":"https://play.xfvod.pro:8088/R/R-ReLIFE/01.mp4"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val video = DefaultSelectorMediaSourceEngine(client.asScopedHttpClient())
            .resolveVideoDirectly(pageUrl, xfdmConfig, SelectorSearchConfig.MatchVideoConfig())
        assertEquals("https://play.xfvod.pro:8088/R/R-ReLIFE/01.mp4", video?.m3u8Url)
    }
}
