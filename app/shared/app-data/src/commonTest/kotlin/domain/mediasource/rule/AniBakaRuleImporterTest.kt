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
                        listOf(RuleStep.Sniff(goal = RuleStep.Sniff.GOAL_VIDEO)),
                    ),
                ),
                // AniBaka 的 replace 默认按字面匹配
                RuleStep.Replace(pattern = "\\/", replacement = "/", regex = false),
                RuleStep.VideoUrl,
                RuleStep.MediaHeaders(headers = mapOf("Referer" to "https://www.example.com/")),
            ),
            config.play,
        )
    }

    @Test
    fun `rules with unsupported steps are skipped with reasons`() {
        val converted = AniBakaRuleImporter.convert(
            rule(
                """
                {"format": "anx-rule/2", "name": "加密站", "baseUrl": "https://www.example.com",
                 "search": [{"op": "fetch", "url": "/s?wd={keyword}"}, {"op": "searchList", "selectors": [".item"]}],
                 "detail": [{"op": "follow"}, {"op": "episodes", "roadsXPath": "//div", "itemsXPath": ".//a"}],
                 "play": [{"op": "follow"}, {"op": "crypto", "algo": "aes-cbc", "key": "k", "iv": "v"}]}
                """.trimIndent(),
            ),
        )
        assertIs<AniBakaRuleImporter.Converted.Unsupported>(converted)
        assertEquals("加密站", converted.name)
        assertTrue(converted.reasons.any { "crypto" in it }, converted.reasons.toString())
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
    fun `other json is not taken`() = runTest {
        assertNull(AniBakaRuleImporter.import("""{"factoryId": "web-selector", "arguments": {}}""") { error("no download") })
        assertNull(AniBakaRuleImporter.import("not json") { error("no download") })
    }
}
