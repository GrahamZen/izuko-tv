/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `resolveVideo` 只能通过**导入 JSON** 配置 (编辑页没有对应 UI), 所以这条路必须有测试守着.
 *
 * 见 memory `project-old-webview-source-fail`.
 */
class SelectorResolveVideoConfigCodecTest {
    private val json: Json = MediaSourceCodecManager.json

    /** 用户实际会粘的那一段 (稀饭动漫 Next) */
    private val argumentsWithResolveVideo = """
        {
          "name": "稀饭动漫 Next",
          "description": "",
          "iconUrl": "https://next.xifanacg.com/favicon.ico",
          "searchConfig": {
            "searchUrl": "https://next.xifanacg.com/search?q={keyword}",
            "resolveVideo": {
              "requestUrl": "https://xxx.supabase.co/functions/v1/issue-web-playback",
              "matchPageUrl": "/play/(?<episodeId>\\d+)",
              "method": "POST",
              "requestHeaders": "Content-Type: application/json",
              "requestBody": "{\"action\":\"fallback\",\"episode_id\":{episodeId}}",
              "selectUrlJsonPath": "$.url"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `导入带 resolveVideo 的配置`() {
        val args = json.decodeFromString(SelectorMediaSourceArguments.serializer(), argumentsWithResolveVideo)
        val resolve = args.searchConfig.resolveVideo

        assertTrue(resolve.enabled)
        assertEquals("https://xxx.supabase.co/functions/v1/issue-web-playback", resolve.requestUrl)
        assertEquals("POST", resolve.method)
        // JSON 里 \\d 解出来是 \d, 正则才能用
        assertEquals("""/play/(?<episodeId>\d+)""", resolve.matchPageUrl)
        assertEquals("""{"action":"fallback","episode_id":{episodeId}}""", resolve.requestBody)
        assertEquals("$.url", resolve.selectUrlJsonPath)
        // 配置里写的正则必须真能编译, 且抽得出变量名
        assertEquals(listOf("episodeId"), namedGroupNames(resolve.matchPageUrl))
    }

    @Test
    fun `老配置没有这个字段时默认关闭`() {
        val args = json.decodeFromString(
            SelectorMediaSourceArguments.serializer(),
            """{"name":"旧源","description":"","iconUrl":"","searchConfig":{"searchUrl":"https://a/s?wd={keyword}"}}""",
        )
        assertFalse(args.searchConfig.resolveVideo.enabled)
    }

    @Test
    fun `未知字段被忽略，新版导出的配置不会把旧版打挂`() {
        // 反过来的方向: 旧版 app 的 json 同样是 ignoreUnknownKeys, 这里验的是我们这边的宽容度
        val args = json.decodeFromString(
            SelectorMediaSourceArguments.serializer(),
            """{"name":"x","description":"","iconUrl":"","searchConfig":{"searchUrl":"u"},"futureField":123}""",
        )
        assertEquals("x", args.name)
    }

    @Test
    fun `编码再解码保持一致`() {
        val original = json.decodeFromString(SelectorMediaSourceArguments.serializer(), argumentsWithResolveVideo)
        val roundTripped = json.decodeFromString(
            SelectorMediaSourceArguments.serializer(),
            json.encodeToString(SelectorMediaSourceArguments.serializer(), original),
        )
        assertEquals(original.searchConfig.resolveVideo, roundTripped.searchConfig.resolveVideo)
    }
}
