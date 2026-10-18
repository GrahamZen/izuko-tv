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
import kotlinx.serialization.json.JsonObject
import me.him188.ani.datasources.api.source.MediaSourceTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniBakaRuleImporterTest {
    private fun rule(text: String) = Json.parseToJsonElement(text) as JsonObject

    /** 一条覆盖常见步骤的规则 (站点是虚构的). */
    private val sample = """
        {
          "format": "anx-rule/2",
          "id": "example",
          "name": "示例站",
          "baseUrl": "https://www.example.com/",
          "description": "示例",
          "headers": {"User-Agent": "Mozilla/5.0", "Referer": "https://www.example.com/"},
          "search": [
            {"op": "first", "branches": [
              [{"op": "maccmsSuggest", "detailUrlTemplate": "/voddetail/{id}.html", "verify": true}],
              [{"op": "fetch", "url": "/search?wd={keyword}"},
               {"op": "searchList", "selectors": [".module-card-item", ".card"], "detailPattern": "/voddetail/"}]
            ]}
          ],
          "detail": [
            {"op": "follow"},
            {"op": "episodes", "listSelectors": [".module-play-list"], "tabSelectors": [".module-tab-item"]}
          ],
          "play": [
            {"op": "follow", "headers": {"Referer": "https://www.example.com/"}},
            {"op": "setVar", "name": "page", "value": "{url:raw}"},
            {"op": "template", "value": "{episodeId:raw}"},
            {"op": "regex", "pattern": "/vodplay/(\\d+)-", "group": 1},
            {"op": "setVar", "name": "vodId", "value": "{url:raw}"},
            {"op": "template", "value": "{page:raw}"},
            {"op": "regex", "pattern": "\"{vodId:raw}\"\\s*:", "group": 0},
            {"op": "first", "branches": [
              [{"op": "playerAaaa", "var": "player_aaaa", "key": "url"}],
              [{"op": "sniff", "goal": "video", "followEmbeddedPlayer": true, "timeoutMs": 45000}]
            ]},
            {"op": "replace", "pattern": "\\/", "replacement": "/"},
            {"op": "videoUrl"},
            {"op": "setMediaHeaders", "headers": {"Referer": "https://www.example.com/"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `converts common steps`() {
        val converted = AniBakaRuleImporter.convert(rule(sample))
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        val config = converted.arguments.rule
        assertEquals("示例站", converted.arguments.name)
        assertEquals("https://www.example.com", config.baseUrl)
        // UA 不转, 其余请求头保留
        assertEquals(mapOf("Referer" to "https://www.example.com/"), config.headers)

        val search = config.search.single()
        assertIs<RuleStep.First>(search)
        assertEquals(
            listOf(
                RuleStep.Fetch(url = "/index.php/ajax/suggest?mid=1&wd={keyword}"),
                RuleStep.JsonSubjects(listPath = "list", idPath = "id", namePath = "name", urlTemplate = "/voddetail/{id:url}.html"),
            ),
            search.branches[0],
        )
        assertEquals(
            RuleStep.Subjects(list = listOf(".module-card-item", ".card"), linkPattern = "/voddetail/"),
            search.branches[1][1],
        )
        // follow 打开当前值
        assertEquals(RuleStep.Fetch(url = "{value}"), config.detail[0])
        assertEquals(
            RuleStep.Episodes(lists = listOf(".module-play-list"), tabs = listOf(".module-tab-item")),
            config.detail[1],
        )

        // 变量换成规则源的名字
        assertEquals(
            listOf(
                RuleStep.Fetch(url = "{value}", headers = mapOf("Referer" to "https://www.example.com/")),
                RuleStep.SetVar(name = "page", value = "{value}"),
                RuleStep.Template(value = "{episodeUrl}"),
                RuleStep.Regex(pattern = "/vodplay/(\\d+)-", group = "1"),
                RuleStep.SetVar(name = "vodId", value = "{value}"),
                RuleStep.Template(value = "{page}"),
                // 正则里的变量也换成规则源的写法
                RuleStep.Regex(pattern = "\"{vodId}\"\\s*:", group = "0"),
                RuleStep.First(
                    listOf(
                        listOf(RuleStep.MacCmsPlayer()),
                        // 嗅探的等待时长照录
                        listOf(RuleStep.Sniff(goal = RuleStep.Sniff.GOAL_VIDEO, timeoutMillis = 45_000)),
                    ),
                ),
                // AniBaka 的 replace 默认按字面匹配
                RuleStep.Replace(pattern = "\\/", replacement = "/", regex = false),
                RuleStep.VideoUrl,
                RuleStep.MediaHeaders(headers = mapOf("Referer" to "https://www.example.com/")),
            ),
            config.play,
        )
        // 分支里的嗅探时长也算, 代替全局设置
        assertEquals(45_000L, videoSniffTimeoutMillis(config.play))
    }

    @Test
    fun `sniff options are recorded`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "示例站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "sniff", "goal": "html", "url": "/s?wd={keyword}",
                             "readyContains": ["video-img-box"], "rejectContains": ["安全验证"], "timeoutMs": 30000, "settleMs": 2000},
                            {"op": "searchList", "selectors": [".card"]}],
                 "play": [{"op": "sniff", "goal": "video", "resolveMediaRedirects": true, "timeoutMs": 60000, "settleMs": 1000}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        val config = converted.arguments.rule
        assertEquals(
            RuleStep.Sniff(
                goal = RuleStep.Sniff.GOAL_HTML,
                url = "/s?wd={keyword}",
                timeoutMillis = 30_000,
            ),
            config.search[0],
        )
        assertEquals(
            RuleStep.Sniff(goal = RuleStep.Sniff.GOAL_VIDEO, timeoutMillis = 60_000),
            config.play.single(),
        )
        // 只有播放段嗅探视频的时长算数; 没写时长照全局设置
        assertEquals(60_000L, videoSniffTimeoutMillis(config.play))
        assertNull(videoSniffTimeoutMillis(config.search))
        assertNull(videoSniffTimeoutMillis(listOf(RuleStep.Sniff())))
    }

    @Test
    fun `rules with unsupported steps are skipped with reasons`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "专用步骤站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "fetch", "url": "/s?wd={keyword}"}, {"op": "searchList", "selectors": [".item"]}],
                 "detail": [{"op": "follow"}, {"op": "episodes", "roadsXPath": "//div/following-sibling::ul", "itemsXPath": ".//a"}],
                 "play": [{"op": "follow"}, {"op": "ecPlayer"}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Unsupported>(converted)
        assertEquals("专用步骤站", converted.name)
        assertTrue(converted.reasons.any { "ecPlayer" in it }, converted.reasons.toString())
        assertTrue(converted.reasons.any { "roadsXPath" in it }, converted.reasons.toString())
    }

    @Test
    fun `pipeline wrapper is unwrapped`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "包装站", "baseUrl": "https://www.example.com",
                 "pipeline": {"headers": {"Referer": "https://www.example.com/"},
                   "search": [{"op": "fetch", "url": "/s?wd={keyword:raw}"}, {"op": "searchList", "selectors": [".item"]}],
                   "detail": [{"op": "follow"}, {"op": "episodes", "listSelectors": [".eps"]}],
                   "play": [{"op": "sniff", "goal": "video"}]}}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        assertEquals(RuleStep.Fetch(url = "/s?wd={keyword:raw}"), converted.arguments.rule.search[0])
        assertEquals(mapOf("Referer" to "https://www.example.com/"), converted.arguments.rule.headers)
    }

    @Test
    fun `item templates keep field names`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "接口站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "fetch", "url": "/api/search?q={keyword}"},
                            {"op": "jsonSeries", "listPath": "data.list", "idKey": "vid", "nameKey": "title", "detailUrlTemplate": "/anime/{id}"}],
                 "detail": [{"op": "fetch", "url": "/api/episodes/{seriesId:raw}"},
                            {"op": "jsonEpisodes", "episodesPath": "data.episodes", "episodeNameKey": "title",
                             "episodeIdTemplate": "https://api.example.com/episode/{episodeId}/play", "sourceName": "主线"}],
                 "play": [{"op": "follow"}, {"op": "json", "path": "data.playUrl"}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        val rule = converted.arguments.rule
        assertEquals(
            RuleStep.JsonSubjects(listPath = "data.list", idPath = "vid", namePath = "title", urlTemplate = "/anime/{id:url}"),
            rule.search[1],
        )
        assertEquals(RuleStep.Fetch(url = "/api/episodes/{subjectUrl}"), rule.detail[0])
        // 剧集项的模板里 {episodeId} 是该项的字段, 不能换成 episodeUrl
        assertEquals(
            RuleStep.JsonEpisodes(
                listPath = "data.episodes",
                namePath = "title",
                urlTemplate = "https://api.example.com/episode/{episodeId:url}/play",
                channelName = "主线",
            ),
            rule.detail[1],
        )
    }

    @Test
    fun `hub is imported through its url`() = runTest {
        val hub = """
            {"format": "anx-rulehub/2", "entries": [
              {"key": "example", "title": "示例站", "ref": "example.json"},
              {"key": "broken", "title": "坏的", "ref": "broken.json"}
            ]}
        """.trimIndent()
        val files = mapOf(
            "https://rules.example.com/repo/index.json" to hub,
            "https://rules.example.com/repo/example.json" to sample,
        )
        val result = AniBakaRuleImporter.import("https://rules.example.com/repo/index.json") { url ->
            files[url] ?: error("404 $url")
        }
        checkNotNull(result)
        assertEquals(listOf("示例站"), result.sources.map { it.name })
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().startsWith("坏的"), result.failures.toString())
    }

    @Test
    fun `hub labels set the tier`() = runTest {
        val hub = """
            {"format": "anx-rulehub/2", "entries": [
              {"key": "a", "title": "无", "ref": "a.json", "labels": ["anime", "无广告", "高清"]},
              {"key": "b", "title": "少", "ref": "b.json", "labels": ["anime", "少广告"]},
              {"key": "c", "title": "有", "ref": "c.json", "labels": ["有广告", "超清"]},
              {"key": "d", "title": "没标", "ref": "d.json", "labels": ["anime"]}
            ]}
        """.trimIndent()
        val result = AniBakaRuleImporter.import("https://rules.example.com/repo/index.json") { url ->
            if (url.endsWith("index.json")) hub else sample.replace("\"示例站\"", "\"" + url.substringAfterLast('/') + "\"")
        }
        checkNotNull(result)
        assertEquals(listOf(0u, 1u, 3u, MediaSourceTier.Fallback.value), result.sources.map { it.tier.value })
    }

    @Test
    fun `object body becomes a form and plain variables are url encoded`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "表单站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "fetch", "url": "/s?wd={keyword}"}, {"op": "searchList", "selectors": [".item"]}],
                 "detail": [{"op": "follow"}, {"op": "episodes", "listSelectors": [".eps"]}],
                 "play": [{"op": "follow"},
                          {"op": "fetch", "url": "https://p.example.com/?url={token}&raw={token:raw}", "method": "POST",
                           "body": {"url": "{token:raw}", "t": "a b"}, "contentType": "form"}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        assertEquals(
            RuleStep.Fetch(
                url = "https://p.example.com/?url={token:url}&raw={token}",
                method = "POST",
                body = "url={token:url}&t=a%20b",
                contentType = "form",
            ),
            converted.arguments.rule.play[1],
        )
    }

    @Test
    fun `keywordMatch keeps only subjects named with the keyword`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "示例站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "first", "branches": [
                   [{"op": "fetch", "url": "/api?q={keyword}"},
                    {"op": "jsonSeries", "listPath": "data", "nameKey": "title", "detailUrlTemplate": "/anime/{id}", "keywordMatch": true}],
                   [{"op": "fetch", "url": "/s?wd={keyword}"}, {"op": "searchList", "selectors": [".item"], "keywordMatch": true}]
                 ]}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        val branches = assertIs<RuleStep.First>(converted.arguments.rule.search.single()).branches
        assertTrue(assertIs<RuleStep.JsonSubjects>(branches[0][1]).filterByKeyword)
        assertTrue(assertIs<RuleStep.Subjects>(branches[1][1]).filterByKeyword)
    }

    /** 仿 AniBaka anime1 规则结构的虚构站点: 全部番剧的 JSON 表 + 分页的分类页 + 用文章里的 token 换视频地址. */
    private val anime1Like = """
        {"format": "anx-rule/2", "name": "示例 anime1", "baseUrl": "https://anime.example.com",
         "headers": {"User-Agent": "Mozilla/5.0", "Referer": "https://anime.example.com/"},
         "search": [{"op": "anime1Search", "url": "https://anime.example.com/animelist.json", "idIndex": 0, "nameIndex": 1, "caseSensitive": true}],
         "detail": [{"op": "anime1Detail", "url": "https://anime.example.com/?cat={seriesId:raw}", "itemSelector": "video[data-apireq]",
                     "tokenAttribute": "data-apireq", "nextSelector": ".nav-previous a", "maxPages": 32, "reverse": true,
                     "sourceName": "示例", "episodeNameTemplate": "第{index}集"}],
         "play": [{"op": "anime1Play", "url": "https://v.anime.example.com/api", "method": "POST", "contentType": "form", "tokenField": "d",
                   "sourcePath": "s[0].src", "mediaBaseUrl": "https://v.anime.example.com/", "cookieNames": ["e", "p", "h"],
                   "cookiePrefixes": ["_ga"], "headers": {"Origin": "https://anime.example.com"},
                   "mediaHeaders": {"User-Agent": "Mozilla/5.0", "Referer": "https://anime.example.com/"}, "validateWithCookies": true}]}
    """.trimIndent()

    @Test
    fun `anime1 steps are translated into generic steps`() = runTest {
        val converted = AniBakaRuleImporter.convert(rule(anime1Like))
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        val config = converted.arguments.rule

        fun video(token: String) = """<video data-apireq="$token"></video>"""
        fun article(id: Int, title: String, token: String) =
            """<article><h2 class="entry-title"><a href="https://anime.example.com/$id" rel="bookmark">$title</a></h2>${video(token)}</article>"""

        val pages = mapOf(
            // 分类 id 为 0 的是外链, 不算
            "https://anime.example.com/animelist.json" to
                    """[[0,"<a href=\"https://other.example.com/?cat=1\">葬送的芙莉蓮外傳</a>","1-2","2026","春",""],""" +
                    """[1833,"葬送的芙莉蓮 第二季","29-30","2026","冬",""],[1307,"葬送的芙莉蓮","1-28","2023","秋",""],[5,"其他","1","2020","春",""]]""",
            "https://anime.example.com/?cat=1833" to
                    """<main>${article(30, "葬送的芙莉蓮 第二季 [30]", "%7B%22e%22%3A%2230%22%7D")}</main>""" +
                    """<div class="nav-previous"><a href="https://anime.example.com/cat/1833/page/2">上一頁</a></div>""",
            "https://anime.example.com/cat/1833/page/2" to
                    """<main>${article(29, "葬送的芙莉蓮 第二季 [29]", "%7B%22e%22%3A%2229%22%7D")}</main>""",
            "https://anime.example.com/29" to "<main>${article(29, "葬送的芙莉蓮 第二季 [29]", "%7B%22e%22%3A%2229%22%7D")}</main>",
            "https://v.anime.example.com/api" to """{"s":[{"src":"//cdn.anime.example.com/1833/29.mp4","type":"video/mp4"}]}""",
        )
        val requests = mutableListOf<RuleHttpRequest>()
        val http = RuleHttp { request ->
            requests += request
            val page = pages[request.url] ?: return@RuleHttp RuleHttpResponse(request.url, 404, "")
            val cookies = if (request.url == "https://v.anime.example.com/api") listOf("e=1", "p=2", "h=3") else emptyList()
            RuleHttpResponse(request.url, 200, page, cookies)
        }
        val engine = RuleEngine(config, http)

        val subjects = engine.search("葬送的芙莉蓮")
        assertEquals(listOf("葬送的芙莉蓮 第二季", "葬送的芙莉蓮"), subjects.map { it.name })
        assertEquals("https://anime.example.com/?cat=1833", subjects.first().url)

        // 翻页合并, 从旧到新
        val episodes = engine.detail(subjects.first().url).single().episodes
        assertEquals(listOf("葬送的芙莉蓮 第二季 [29]", "葬送的芙莉蓮 第二季 [30]"), episodes.map { it.name })
        assertEquals("""\[(?<ep>[^\[\]]+)\]\s*$""", config.matchEpisodeSortFromName)

        val play = engine.play(episodes.first().url)
        assertIs<RulePlayResult.Direct>(play)
        assertEquals("https://cdn.anime.example.com/1833/29.mp4", play.url)
        assertEquals("e=1; p=2; h=3", play.headers["Cookie"])
        assertEquals("https://anime.example.com/", play.headers["Referer"])
        // token 原样提交
        assertEquals("d=%7B%22e%22%3A%2229%22%7D", requests.last { it.method == "POST" }.body)
    }

    @Test
    fun `other json is not taken`() = runTest {
        assertNull(AniBakaRuleImporter.import("""{"factoryId": "web-selector", "arguments": {}}""") { error("no download") })
        assertNull(AniBakaRuleImporter.import("not json") { error("no download") })
    }
    /** 苹果 CMS 视频接口的站点 (站点是虚构的): 搜索结果只给 id, 详情段自己拼接口地址, 剧集地址就是 m3u8. */
    private val macCmsApiRule = """
        {
          "format": "anx-rule/2", "name": "接口站", "baseUrl": "https://dm.example.com",
          "headers": {"Referer": "https://www.example.com/"},
          "search": [
            {"op": "first", "branches": [
              [{"op": "fetch", "url": "/api.php/provide/vod/?ac=list&wd={keyword}"},
               {"op": "jsonSeries", "listPath": "list", "idKey": "vod_id", "nameKey": "vod_name", "imageKey": "vod_pic"}],
              [{"op": "sniff", "goal": "html", "url": "/api.php/provide/vod/?ac=list&wd={keyword}"},
               {"op": "select", "css": "body", "attr": "text"},
               {"op": "jsonSeries", "listPath": "list", "idKey": "vod_id", "nameKey": "vod_name"}]
            ]}
          ],
          "detail": [
            {"op": "fetch", "url": "/api.php/provide/vod/?ac=detail&ids={seriesId:raw}"},
            {"op": "maccmsApiEpisodes", "preferHls": true, "directOnly": true}
          ],
          "play": [{"op": "template", "value": "{episodeId:raw}", "filterHlsAds": true}]
        }
    """.trimIndent()

    @Test
    fun `json series with only ids takes the address of the detail request`() = runTest {
        val converted = AniBakaRuleImporter.convert(rule(macCmsApiRule))
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        val config = converted.arguments.rule
        // 详情段的请求改为直接打开条目地址
        assertEquals(RuleStep.Fetch(), config.detail.first())
        assertEquals(RuleStep.MacCmsEpisodes(listPath = "list", directOnly = true, preferHls = true), config.detail[1])

        val pages = mapOf(
            "https://dm.example.com/api.php/provide/vod/?ac=list&wd=%E7%A4%BA%E4%BE%8B" to
                """{"list":[{"vod_id":12,"vod_name":"示例动画","vod_pic":""}]}""",
            "https://dm.example.com/api.php/provide/vod/?ac=detail&ids=12" to
                """{"list":[{"vod_id":12,"vod_name":"示例动画","vod_play_from":"ffm3u8","vod_play_url":"第01集${'$'}https://cdn.example.com/12/1.m3u8"}]}""",
        )
        val engine = RuleEngine(config, RuleHttp { request -> RuleHttpResponse(request.url, if (request.url in pages) 200 else 404, pages[request.url].orEmpty()) })
        val subject = engine.search("示例").single()
        assertEquals("https://dm.example.com/api.php/provide/vod/?ac=detail&ids=12", subject.url)
        val episode = engine.detail(subject.url).single().episodes.single()
        assertEquals("第01集", episode.name)
        val play = engine.play(episode.url)
        assertIs<RulePlayResult.Direct>(play)
        assertEquals("https://cdn.example.com/12/1.m3u8", play.url)
    }

    @Test
    fun `crypto and xpath steps are converted`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "解密站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "fetch", "url": "/s?wd={keyword}"},
                            {"op": "searchList", "listXPath": "//a[contains(@class,\"topic-list-item\")]", "nameXPath": ".//div[contains(@class,\"h3\")]", "linkXPath": ""}],
                 "detail": [{"op": "follow"},
                            {"op": "episodes", "roadsXPath": "//section[contains(@class, 'anthology')]", "itemsXPath": ".//a[contains(@class, 'circuit_switch1')]"}],
                 "play": [{"op": "follow"},
                          {"op": "regex", "pattern": "^(.{9})", "group": 1},
                          {"op": "crypto", "algo": "md5"},
                          {"op": "setVar", "name": "key", "value": "{url:raw}"},
                          {"op": "crypto", "algo": "aes-cbc", "mode": "decrypt", "input": "{token:raw}", "key": "{key:raw}", "iv": "5re6a)@&wfDCMS^A"}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        val config = converted.arguments.rule
        assertEquals(
            RuleStep.Subjects(list = listOf("a[class*=\"topic-list-item\"]"), name = "div[class*=\"h3\"]"),
            config.search[1],
        )
        val episodes = config.detail[1]
        assertIs<RuleStep.Episodes>(episodes)
        assertEquals(listOf("section[class*=\"anthology\"]"), episodes.lists)
        assertEquals("a[class*=\"circuit_switch1\"]", episodes.items)
        assertEquals(RuleStep.Crypto(RuleStep.Crypto.MD5, input = "{value}"), config.play[2])
        assertEquals(RuleStep.Crypto(RuleStep.Crypto.AES_CBC, input = "{token}", key = "{key}", iv = "5re6a)@&wfDCMS^A"), config.play[4])
    }

    @Test
    fun `encryption and unconvertible xpath are reported`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "加密站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "fetch", "url": "/s?wd={keyword}"}, {"op": "searchList", "listXPath": "//a/@href"}],
                 "detail": [{"op": "follow"}, {"op": "episodes", "listSelectors": [".eps"]}],
                 "play": [{"op": "crypto", "algo": "aes-cbc", "mode": "encrypt", "key": "k", "iv": "v"}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Unsupported>(converted)
        assertTrue(converted.reasons.any { "crypto 的 mode encrypt" in it }, converted.reasons.toString())
        assertTrue(converted.reasons.any { "listXPath" in it }, converted.reasons.toString())
    }
    @Test
    fun `object bodies of json requests become json`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "接口站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "fetch", "url": "https://api.example.com/rpc/search", "method": "POST", "contentType": "application/json",
                             "body": {"search_term": "{keyword:raw}", "limit": 10}},
                            {"op": "jsonSeries", "idKey": "id", "nameKey": "title", "detailUrlTemplate": "/anime/{id}"}],
                 "detail": [{"op": "follow"}, {"op": "episodes", "listSelectors": [".eps"]}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Ok>(converted)
        val fetch = converted.arguments.rule.search.first()
        assertIs<RuleStep.Fetch>(fetch)
        assertEquals("""{"search_term":"{keyword:json}","limit":10}""", fetch.body)
        assertEquals(
            """{"search_term":"示\"例","limit":10}""",
            RuleText.substitute(fetch.body, mapOf("keyword" to "示\"例")),
        )
    }
}
