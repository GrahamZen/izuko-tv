/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.mediasource.web.SelectorSearchConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuleEngineTest {
    /** 按地址返回页面的假站点; 记下每个请求. */
    private class FakeSite(private val pages: Map<String, String>) : RuleHttp {
        val requests = mutableListOf<RuleHttpRequest>()
        override suspend fun request(request: RuleHttpRequest): RuleHttpResponse {
            requests += request
            val page = pages[request.url] ?: return RuleHttpResponse(request.url, 404, "not found")
            return RuleHttpResponse(request.url, 200, page)
        }
    }

    private fun engine(config: RuleConfig, site: FakeSite) = RuleEngine(config, site)

    @Test
    fun `search lists cards and drops duplicate links`() = runTest {
        val site = FakeSite(
            mapOf(
                "https://www.example.com/search?q=%E7%A4%BA%E4%BE%8B" to """
                    <div class="list">
                      <div class="item"><a class="p" href="/play/h1/"><img alt="海报"></a><a class="t" href="/play/h1/">【10月】示例动画</a></div>
                      <div class="item"><a class="t" href="/play/h1/">【10月】示例动画 02</a></div>
                      <div class="item"><a class="t" href="/play/h2/">示例动画 剧场版</a></div>
                    </div>
                """.trimIndent(),
            ),
        )
        val config = RuleConfig(
            baseUrl = "https://www.example.com",
            search = listOf(
                RuleStep.Fetch(url = "/search?q={keyword}"),
                RuleStep.Subjects(list = listOf(".nothing", ".list .item"), link = "a.t", name = "a.t"),
            ),
        )
        val subjects = engine(config, site).search("示例")
        assertEquals(
            listOf(
                RuleSubject("【10月】示例动画", "https://www.example.com/play/h1/"),
                RuleSubject("示例动画 剧场版", "https://www.example.com/play/h2/"),
            ),
            subjects,
        )
    }

    @Test
    fun `subject name falls back to link title then image alt`() = runTest {
        val site = FakeSite(
            mapOf(
                "https://www.example.com/s" to """
                    <ul>
                      <li class="card"><a href="/detail/1" title="标题甲"><img alt="图甲"></a></li>
                      <li class="card"><a href="/detail/2"><img alt="图乙"></a></li>
                      <li class="card"><a href="javascript:void(0)">无效</a><a href="/other/3">其他</a></li>
                    </ul>
                """.trimIndent(),
            ),
        )
        val config = RuleConfig(
            baseUrl = "https://www.example.com",
            search = listOf(
                RuleStep.Fetch(url = "/s"),
                RuleStep.Subjects(list = listOf("li.card"), linkPattern = "/detail/"),
            ),
        )
        assertEquals(
            listOf(
                RuleSubject("标题甲", "https://www.example.com/detail/1"),
                RuleSubject("图乙", "https://www.example.com/detail/2"),
            ),
            engine(config, site).search("x"),
        )
    }

    @Test
    fun `episodes split from page text by regex`() = runTest {
        val site = FakeSite(
            mapOf(
                "https://www.example.com/play/h1/" to """
                    <ul id="player_code"><li>type=video&amp;file=https://cdn.example.com/新番/示例 动画/01.mp4|01**type=video&amp;file=https://cdn.example.com/新番/示例 动画/02.mp4|**</li><li>10-1-1</li></ul>
                """.trimIndent(),
            ),
        )
        val config = RuleConfig(
            detail = listOf(
                RuleStep.Fetch(),
                RuleStep.Select(css = "#player_code li"),
                RuleStep.RegexEpisodes(pattern = """type=video&file=(?<url>[^|]+)\|(?<name>[^*]*)"""),
            ),
        )
        val channels = engine(config, site).detail("https://www.example.com/play/h1/")
        assertEquals(1, channels.size)
        assertEquals(null, channels.single().name)
        assertEquals(
            listOf(
                RuleEpisode("01", "https://cdn.example.com/新番/示例 动画/01.mp4"),
                // 没有名字时按位置起名
                RuleEpisode("第2集", "https://cdn.example.com/新番/示例 动画/02.mp4"),
            ),
            channels.single().episodes,
        )
    }

    @Test
    fun `empty play stage plays the episode url itself, encoded`() = runTest {
        val result = engine(RuleConfig(), FakeSite(emptyMap())).play("https://cdn.example.com/新番/示例 动画/01.mp4")
        assertIs<RulePlayResult.Direct>(result)
        assertEquals(
            "https://cdn.example.com/%E6%96%B0%E7%95%AA/%E7%A4%BA%E4%BE%8B%20%E5%8A%A8%E7%94%BB/01.mp4",
            result.url,
        )
    }

    @Test
    fun `episodes pair lists with tab names`() = runTest {
        val site = FakeSite(
            mapOf(
                "https://www.example.com/detail/1" to """
                    <div class="tabs"><a>线路A</a><a>线路B</a></div>
                    <ul class="eps"><li><a href="/play/1-1-1">第01集</a></li><li><a href="/play/1-1-2">第02集</a></li></ul>
                    <ul class="eps"><li><a href="/play/1-2-1">第01集</a></li></ul>
                """.trimIndent(),
            ),
        )
        val config = RuleConfig(
            detail = listOf(
                RuleStep.Fetch(),
                RuleStep.Episodes(lists = listOf(".eps"), tabs = listOf(".tabs a")),
            ),
        )
        val channels = engine(config, site).detail("https://www.example.com/detail/1")
        assertEquals(listOf("线路A", "线路B"), channels.map { it.name })
        assertEquals("https://www.example.com/play/1-1-2", channels[0].episodes[1].url)
    }

    @Test
    fun `unnamed channels get numbered names`() = runTest {
        val site = FakeSite(
            mapOf(
                "https://www.example.com/detail/1" to """
                    <ul class="eps"><li><a href="/p/1">1</a></li></ul>
                    <ul class="eps"><li><a href="/p/2">1</a></li></ul>
                """.trimIndent(),
            ),
        )
        val config = RuleConfig(detail = listOf(RuleStep.Fetch(), RuleStep.Episodes(lists = listOf(".eps"))))
        assertEquals(listOf("线路1", "线路2"), engine(config, site).detail("https://www.example.com/detail/1").map { it.name })
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `maccms player decodes base64 and unescape`() = runTest {
        val encoded = Base64.Default.encode("https%3A%2F%2Fv.example.com%2Fa%u4E2D.m3u8".encodeToByteArray())
        val site = FakeSite(
            mapOf(
                "https://www.example.com/play/1" to """
                    <script>var player_aaaa={"flag":"play","encrypt":2,"from":"line1","url":"$encoded","note":"{x}"}</script>
                """.trimIndent(),
            ),
        )
        val config = RuleConfig(play = listOf(RuleStep.Fetch(), RuleStep.MacCmsPlayer()))
        val result = engine(config, site).play("https://www.example.com/play/1")
        assertIs<RulePlayResult.Direct>(result)
        assertEquals("https://v.example.com/a%E4%B8%AD.m3u8", result.url)
    }

    @Test
    fun `first falls back to the next branch and media headers apply`() = runTest {
        val site = FakeSite(
            mapOf(
                "https://www.example.com/play/1" to """
                    <script id="player-config" type="application/json">{"url":"https:\/\/v.example.com\/1.m3u8"}</script>
                """.trimIndent(),
            ),
        )
        val config = RuleConfig(
            baseUrl = "https://www.example.com",
            play = listOf(
                RuleStep.Fetch(),
                RuleStep.First(
                    listOf(
                        listOf(RuleStep.MacCmsPlayer()),
                        listOf(RuleStep.Select(css = "script#player-config"), RuleStep.Json(path = "url")),
                    ),
                ),
                RuleStep.MediaHeaders(headers = mapOf("Referer" to "{baseUrl}/")),
            ),
        )
        val result = engine(config, site).play("https://www.example.com/play/1")
        assertIs<RulePlayResult.Direct>(result)
        assertEquals("https://v.example.com/1.m3u8", result.url)
        assertEquals("https://www.example.com/", result.headers["Referer"])
        // 默认的 UA 来自 matchVideo
        assertEquals(SelectorSearchConfig.VideoHeaders().userAgent, result.headers["User-Agent"])
    }

    @Test
    fun `video url is found in player page`() = runTest {
        val site = FakeSite(
            mapOf(
                "https://www.example.com/player?id=1" to """
                    <script>var dp = new DPlayer({video: {url: "https:\/\/v.example.com\/a\/index.m3u8?sign=1", pic: "https:\/\/img.example.com\/1.jpg"}});</script>
                """.trimIndent(),
            ),
        )
        val config = RuleConfig(play = listOf(RuleStep.Fetch(url = "https://www.example.com/player?id=1"), RuleStep.VideoUrl))
        val result = engine(config, site).play("https://www.example.com/play/1")
        assertIs<RulePlayResult.Direct>(result)
        assertEquals("https://v.example.com/a/index.m3u8?sign=1", result.url)
    }

    @Test
    fun `regex expands variables and accepts javascript style braces`() = runTest {
        val page = """var config = {"m2": {"parse": "https://jx.example.com/?url="}, "m3": {"parse": ""}}"""
        val config = RuleConfig(
            play = listOf(
                RuleStep.Template(value = "m2"),
                RuleStep.SetVar(name = "from"),
                RuleStep.Template(value = page),
                // 裸 `{` 在 Java 正则里不合法, 在 JavaScript 里是字面量
                RuleStep.Regex(pattern = """"{from}"\s*:\s*{[^}]*"parse"\s*:\s*"([^"]*)""""),
                RuleStep.Template(value = "{value}https://v.example.com/{from}.m3u8"),
            ),
        )
        val result = engine(config, FakeSite(emptyMap())).play("https://www.example.com/play/1")
        assertIs<RulePlayResult.Direct>(result)
        assertEquals("https://jx.example.com/?url=https://v.example.com/m2.m3u8", result.url)
        // 合法的量词照旧
        assertEquals("12", RuleText.regex("""\d{2}""").find("a123")?.value)
    }

    @Test
    fun `sniff video asks for webview`() = runTest {
        val config = RuleConfig(play = listOf(RuleStep.Sniff()))
        assertEquals(RulePlayResult.Sniff, engine(config, FakeSite(emptyMap())).play("https://www.example.com/play/1"))
    }

    @Test
    fun `json subjects build urls from item fields`() = runTest {
        val site = FakeSite(
            mapOf(
                "https://www.example.com/index.php/ajax/suggest?mid=1&wd=a%20b" to
                        """{"code":1,"list":[{"id":12,"name":"甲"},{"id":"13","name":"乙"}]}""",
            ),
        )
        val config = RuleConfig(
            baseUrl = "https://www.example.com",
            search = listOf(
                RuleStep.Fetch(url = "/index.php/ajax/suggest?mid=1&wd={keyword}"),
                RuleStep.JsonSubjects(listPath = "list", urlTemplate = "/voddetail/{id}.html"),
            ),
        )
        assertEquals(
            listOf(
                RuleSubject("甲", "https://www.example.com/voddetail/12.html"),
                RuleSubject("乙", "https://www.example.com/voddetail/13.html"),
            ),
            engine(config, site).search("a b"),
        )
    }

    @Test
    fun `variables and query`() = runTest {
        val config = RuleConfig(
            play = listOf(
                RuleStep.Regex(pattern = """/play/(\d+)"""),
                RuleStep.SetVar(name = "id"),
                RuleStep.Template(value = "https://p.example.com/?url=https%3A%2F%2Fv.example.com%2F{id}.mp4&t=1"),
                RuleStep.Query(name = "url"),
            ),
        )
        val result = engine(config, FakeSite(emptyMap())).play("https://www.example.com/play/42")
        assertIs<RulePlayResult.Direct>(result)
        assertEquals("https://v.example.com/42.mp4", result.url)
    }

    @Test
    fun `failed page yields no results instead of throwing`() = runTest {
        val config = RuleConfig(
            baseUrl = "https://www.example.com",
            search = listOf(RuleStep.Fetch(url = "/missing"), RuleStep.Subjects(list = listOf("a"))),
        )
        assertEquals(emptyList(), engine(config, FakeSite(emptyMap())).search("x"))
    }

    @Test
    fun `network error surfaces when no branch succeeds`() = runTest {
        val config = RuleConfig(
            baseUrl = "https://www.example.com",
            search = listOf(RuleStep.Fetch(url = "/s"), RuleStep.Subjects(list = listOf("a"))),
        )
        val failing = RuleHttp { error("connection reset") }
        assertFailsWith<IllegalStateException> { RuleEngine(config, failing).search("x") }
    }

    @Test
    fun `template keeps unknown placeholders and encodes keyword`() {
        assertEquals(
            "/s?wd=%E7%A4%BA%E4%BE%8B&raw=示例&json={\"a\":1}&{missing}",
            RuleText.substitute("/s?wd={keyword}&raw={keyword:raw}&json={\"a\":1}&{missing}", mapOf("keyword" to "示例")),
        )
    }

    @Test
    fun `example config survives a json round trip with op names`() {
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(RuleMediaSourceArguments.serializer(), RuleMediaSourceArguments.Example)
        assertEquals(RuleMediaSourceArguments.Example, json.decodeFromString(RuleMediaSourceArguments.serializer(), text))
        assertTrue(text.contains("\"op\":\"maccmsPlayer\""), text)
        assertTrue(text.contains("\"op\":\"subjects\""), text)
    }
}
