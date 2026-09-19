/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 两份 protobuf 测试数据是构造出来的, 形状照着这类接口常见的样子:
 * [LINES_RESPONSE] 是某一集的播放地址列表 (1=地址 4=集名 5=线路代号 6=条目名),
 * [EPISODES_RESPONSE] 是剧集列表 (2=集号 5=外部站点 id 列表).
 */
class DirectApiEngineTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `json path with filter`() {
        val root = JsonNode(
            json.parseToJsonElement(
                """{"id":123,"sites":[{"site":"tmdb","id":"tv_1"},{"site":"bangumi","id":"456"}]}""",
            ),
        )
        assertEquals("456", root.stringByPath("sites[site=bangumi].id"))
        assertEquals("tv_1", root.stringByPath("sites[site=tmdb].id"))
        assertEquals("123", root.stringByPath("id"))
        assertNull(root.stringByPath("sites[site=nope].id"))
    }

    @Test
    fun `json path with index`() {
        val root = JsonNode(json.parseToJsonElement("""{"items":[{"u":"a"},{"u":"b"}]}"""))
        assertEquals("a", root.stringByPath("items[0].u"))
        assertEquals("b", root.stringByPath("items[1].u"))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `protobuf path reads fields by number`() {
        val root = checkNotNull(
            parseResponse(Base64.Default.decode(LINES_RESPONSE), ResponseFormat.Protobuf, json),
        )
        val items = root.selectByPath("1")
        assertEquals(4, items.size)
        assertEquals("alpha-10", items.first().stringByPath("5"))
        assertEquals("示例动画", items.first().stringByPath("6"))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `protobuf path filters a nested message`() {
        // 剧集匹配靠的就是这种路径: 在外部 id 列表里挑出 bangumi 的那条
        val root = checkNotNull(
            parseResponse(Base64.Default.decode(EPISODES_RESPONSE), ResponseFormat.Protobuf, json),
        )
        val episodes = root.selectByPath("1")
        assertEquals(3, episodes.size)
        assertEquals(listOf("111", "222", "333"), episodes.map { it.stringByPath("5[1=bangumi].2") })

        val target = episodes.first { it.selectByPath("5[1=bangumi].2").any { node -> node.asStringOrNull() == "333" } }
        assertEquals("78", target.stringByPath("2"))
    }

    @Test
    fun `protobuf in json bytes`() {
        val raw = """[10,95,10,59,97,72,82,88,48,99,72,77,54,76,121,57,106,90,71,52,117,90,88,104,104,98,88,66,115,90,83,53,106,98,50,48,118,100,109,108,107,90,87,56,118,90,88,65,120,76,84,69,119,79,68,66,119,76,109,49,119,78,65,34,8,231,172,172,48,49,233,155,134,42,8,97,108,112,104,97,45,49,48,50,12,231,164,186,228,190,139,229,138,168,231,148,187,10,98,10,57,97,72,82,88,48,99,72,77,54,76,121,57,106,90,71,52,117,90,88,104,104,98,88,66,115,90,83,53,106,98,50,48,118,100,109,108,107,90,87,56,118,90,88,65,120,76,84,99,121,77,72,65,117,98,88,65,48,34,14,231,172,172,48,49,233,155,134,40,55,50,48,80,41,42,7,97,108,112,104,97,45,51,50,12,231,164,186,228,190,139,229,138,168,231,148,187,10,89,10,55,97,72,82,88,48,99,72,77,54,76,121,57,109,97,87,120,108,99,121,53,108,101,71,70,116,99,71,120,108,76,109,57,121,90,121,57,104,76,50,73,118,97,87,53,107,90,88,103,117,98,84,78,49,79,65,34,8,231,172,172,48,49,233,155,134,42,6,98,101,116,97,45,57,50,12,231,164,186,228,190,139,229,138,168,231,148,187,10,80,10,45,97,72,82,88,48,99,72,77,54,76,121,57,106,90,71,52,117,90,88,104,104,98,88,66,115,90,83,53,117,90,88,81,118,101,67,57,108,99,68,69,117,98,88,65,48,34,8,231,172,172,48,49,233,155,134,42,7,103,97,109,109,97,45,52,50,12,231,164,186,228,190,139,229,138,168,231,148,187]""".encodeToByteArray()
        val root = checkNotNull(parseResponse(raw, ResponseFormat.ProtobufInJsonBytes, json))
        assertEquals(4, root.selectByPath("1").size)
    }

    @Test
    fun `transforms restore an obfuscated url`() {
        assertEquals(
            "https://example.com/a.mp4",
            applyTransforms(
                "aHRX0cHM6Ly9leGFtcGxlLmNvbS9hLm1wNA==",
                listOf(Transform(TransformOp.RemoveCharAt, index = 3), Transform(TransformOp.Base64Decode)),
            ),
        )
    }

    @Test
    fun `transforms swap case before decoding`() {
        // 另一种常见混淆: 先大小写互换再 base64
        val swapped = buildString {
            for (c in "aHR0cHM6Ly9leGFtcGxlLmNvbQ==") {
                append(if (c.isUpperCase()) c.lowercaseChar() else if (c.isLowerCase()) c.uppercaseChar() else c)
            }
        }
        assertEquals(
            "https://example.com",
            applyTransforms(swapped, listOf(Transform(TransformOp.SwapCase), Transform(TransformOp.Base64Decode))),
        )
    }

    @Test
    fun `transforms fail loudly on invalid base64`() {
        assertNull(applyTransforms("ab", listOf(Transform(TransformOp.Base64Decode))))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `build links with the example config`() {
        val config = DirectApiMediaSourceArguments.Example.config
        val root = checkNotNull(
            parseResponse(Base64.Default.decode(LINES_RESPONSE), ResponseFormat.Protobuf, json),
        )

        val links = buildDirectLinks(root.selectByPath(config.lines.request.itemsPath), config.lines)

        assertEquals(4, links.size)
        // 线路名去掉了会变的优先级后缀, 配置里列出的代号翻成名字, 没列出的原样保留
        assertEquals(listOf("线路甲", "线路甲", "beta", "gamma"), links.map { it.channel })
        assertEquals(
            listOf(
                "https://cdn.example.com/video/ep1-1080p.mp4",
                "https://cdn.example.com/video/ep1-720p.mp4",
                "https://files.example.org/a/b/index.m3u8",
                "https://cdn.example.net/x/ep1.mp4",
            ),
            links.map { it.url },
        )
        assertEquals("示例动画 第01集", links.first().title)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `maxPerChannel keeps the first few of each channel`() {
        val config = DirectApiMediaSourceArguments.Example.config
        val root = checkNotNull(
            parseResponse(Base64.Default.decode(LINES_RESPONSE), ResponseFormat.Protobuf, json),
        )
        val items = root.selectByPath(config.lines.request.itemsPath)

        val limited = buildDirectLinks(items, config.lines.copy(maxPerChannel = 1))

        assertEquals(listOf("线路甲", "beta", "gamma"), limited.map { it.channel })
    }

    @Test
    fun `example config survives a json round trip`() {
        val text = json.encodeToString(
            DirectApiMediaSourceArguments.serializer(),
            DirectApiMediaSourceArguments.Example,
        )
        val decoded = json.decodeFromString(DirectApiMediaSourceArguments.serializer(), text)
        assertEquals(DirectApiMediaSourceArguments.Example, decoded)
        // 配置里的关键字段以人能读的名字出现在 JSON 里, 方便用户手改
        assertTrue(text.contains("protobufInJsonBytes"), text)
        assertTrue(text.contains("removeCharAt"), text)
    }
}

private val LINES_RESPONSE = """
        Cl8KO2FIUlgwY0hNNkx5OWpaRzR1WlhoaGJYQnNaUzVqYjIwdmRtbGtaVzh2WlhBeExURXdPREJ3TG0xd05BIgjnrKwwMembhioI
        YWxwaGEtMTAyDOekuuS+i+WKqOeUuwpiCjlhSFJYMGNITTZMeTlqWkc0dVpYaGhiWEJzWlM1amIyMHZkbWxrWlc4dlpYQXhMVGN5
        TUhBdWJYQTAiDuesrDAx6ZuGKDcyMFApKgdhbHBoYS0zMgznpLrkvovliqjnlLsKWQo3YUhSWDBjSE02THk5bWFXeGxjeTVsZUdG
        dGNHeGxMbTl5Wnk5aEwySXZhVzVrWlhndWJUTjFPQSII56ysMDHpm4YqBmJldGEtOTIM56S65L6L5Yqo55S7ClAKLWFIUlgwY0hN
        Nkx5OWpaRzR1WlhoaGJYQnNaUzV1WlhRdmVDOWxjREV1YlhBMCII56ysMDHpm4YqB2dhbW1hLTQyDOekuuS+i+WKqOeUuw==
""".filter { !it.isWhitespace() }

private val EPISODES_RESPONSE = """
        CikQASoMCgR0bWRiEgR0dl8xKg4KB2Jhbmd1bWkSAzExMUIH56ysMembhgopEAIqDAoEdG1kYhIEdHZfMSoOCgdiYW5ndW1pEgMy
        MjJCB+esrDLpm4YKKhBOKgwKBHRtZGISBHR2XzEqDgoHYmFuZ3VtaRIDMzMzQgjnrKw3OOmbhg==
""".filter { !it.isWhitespace() }
