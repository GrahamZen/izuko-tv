/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku.server

import me.him188.ani.app.domain.danmaku.imported.normalizeVideoLink
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.mediasource.subscription.SubscriptionUpdateData
import me.him188.ani.app.domain.mediasource.subscription.SubscriptionUpdateDataReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DanmakuServersTest {
    private fun sub(id: String, vararg servers: DanmakuServerEntry, enabled: Boolean = true) =
        MediaSourceSubscription(id, "https://example.com/$id.json", enabled = enabled, danmakuServers = servers.toList())

    @Test
    fun `url is normalized`() {
        assertEquals("https://a.com/t", normalizeDanmakuServerUrl(" https://a.com/t/ "))
        assertEquals("https://a.com/t", normalizeDanmakuServerUrl("https://a.com/t/api/v2"))
        assertEquals("https://a.com/t", normalizeDanmakuServerUrl("https://a.com/t/api/v2/"))
        assertEquals("http://10.0.0.2:7768/api/v1/tok", normalizeDanmakuServerUrl("http://10.0.0.2:7768/api/v1/tok"))
    }

    @Test
    fun `bad urls and the official service are refused`() {
        assertNull(normalizeDanmakuServerUrl(""))
        assertNull(normalizeDanmakuServerUrl("a.com/t"))
        assertNull(normalizeDanmakuServerUrl("ftp://a.com"))
        assertNull(normalizeDanmakuServerUrl("https://api.dandanplay.net"))
        assertNull(normalizeDanmakuServerUrl("https://API.dandanplay.net/api/v2"))
    }

    @Test
    fun `manual servers come first and the same address is kept once`() {
        val merged = mergeDanmakuServers(
            SavedDanmakuServers(listOf(DanmakuServerEntry("手动", "https://a.com/t"))),
            listOf(
                sub("s1", DanmakuServerEntry("重复", "https://a.com/t/"), DanmakuServerEntry("订阅", "https://b.com")),
            ),
        )
        assertEquals(listOf("手动", "订阅"), merged.map { it.name })
        assertEquals(listOf(null, "s1"), merged.map { it.subscriptionId })
    }

    @Test
    fun `disabled subscription brings nothing`() {
        val merged = mergeDanmakuServers(
            SavedDanmakuServers(),
            listOf(sub("s1", DanmakuServerEntry("订阅", "https://b.com"), enabled = false)),
        )
        assertEquals(emptyList(), merged)
    }

    @Test
    fun `invalid address and unknown type are skipped`() {
        val merged = mergeDanmakuServers(
            SavedDanmakuServers(),
            listOf(
                sub(
                    "s1",
                    DanmakuServerEntry("坏地址", "not a url"),
                    DanmakuServerEntry("别的格式", "https://b.com", type = "other"),
                    DanmakuServerEntry("写明格式", "https://c.com", type = "DanDanPlay"),
                ),
            ),
        )
        assertEquals(listOf("写明格式"), merged.map { it.name })
    }

    @Test
    fun `names are unique and never take a built-in name`() {
        val merged = mergeDanmakuServers(
            SavedDanmakuServers(
                listOf(
                    DanmakuServerEntry("A", "https://a.com"),
                    DanmakuServerEntry("a", "https://b.com"),
                    DanmakuServerEntry("Dandanplay", "https://c.com"),
                    DanmakuServerEntry("", "https://d.com/token"),
                ),
            ),
            emptyList(),
        )
        assertEquals(listOf("A", "2 a", "2 Dandanplay", "d.com"), merged.map { it.name })
    }

    @Test
    fun `servers on one host without names stay apart at the start of the name`() {
        val merged = mergeDanmakuServers(
            SavedDanmakuServers(
                listOf(
                    DanmakuServerEntry("", "https://danmaku.example.com/token-a"),
                    DanmakuServerEntry("", "https://danmaku.example.com/token-b"),
                    DanmakuServerEntry("", "https://danmaku.example.com/token-c"),
                ),
            ),
            emptyList(),
        )
        assertEquals(
            listOf("danmaku.example.com", "2 danmaku.example.com", "3 danmaku.example.com"),
            merged.map { it.name },
        )
    }

    @Test
    fun `features decide what a server is used for`() {
        val merged = mergeDanmakuServers(
            SavedDanmakuServers(
                listOf(
                    DanmakuServerEntry("都有", "https://a.com"),
                    DanmakuServerEntry("只链接", "https://b.com", features = listOf("link")),
                    DanmakuServerEntry("只匹配", "https://c.com", features = listOf("match")),
                ),
            ),
            emptyList(),
        )
        assertEquals(listOf(true, false, true), merged.map { it.autoMatch })
        assertEquals(listOf(true, true, false), merged.map { it.linkImport })
    }

    @Test
    fun `stopped server is listed as disabled`() {
        val merged = mergeDanmakuServers(
            SavedDanmakuServers(
                listOf(DanmakuServerEntry("A", "https://a.com"), DanmakuServerEntry("B", "https://b.com")),
                disabledUrls = setOf("https://b.com"),
            ),
            emptyList(),
        )
        assertEquals(listOf(true, false), merged.map { it.enabled })
    }

    @Test
    fun `video link from id or shared text`() {
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", normalizeVideoLink(" BV1GJ411x7h7 "))
        assertEquals("https://www.bilibili.com/video/av170001", normalizeVideoLink("av170001"))
        assertEquals("https://v.qq.com/x/cover/a/b.html", normalizeVideoLink("https://v.qq.com/x/cover/a/b.html"))
        assertEquals("https://b23.tv/abc", normalizeVideoLink("【标题】 https://b23.tv/abc 分享给你"))
        assertEquals("https://www.bilibili.com/bangumi/play/ep1?season_id=2", normalizeVideoLink("https://www.bilibili.com/bangumi/play/ep1?season_id=2"))
    }

    @Test
    fun `text that is no link is refused`() {
        assertNull(normalizeVideoLink(""))
        assertNull(normalizeVideoLink("孤独摇滚"))
        assertNull(normalizeVideoLink("BV123"))
    }

    private fun decode(text: String) = MediaSourceCodecManager.json.decodeFromString(SubscriptionUpdateDataReader, text)

    @Test
    fun `subscription carries danmaku servers`() {
        val data = decode(
            """
            {"schemaVersion":2,"mediaSources":[],"danmakuServers":[
              {"name":"甲","url":"https://a.com/t","features":["link"]},
              {"url":"https://b.com","type":"dandanplay","extra":1}
            ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                DanmakuServerEntry("甲", "https://a.com/t", features = listOf("link")),
                DanmakuServerEntry("", "https://b.com", type = "dandanplay"),
            ),
            data.danmakuServers,
        )
    }

    @Test
    fun `subscription may have only danmaku servers`() {
        val data = decode("""{"danmakuServers":[{"url":"https://a.com"}]}""")
        assertEquals(emptyList(), data.mediaSources)
        assertEquals(1, data.danmakuServers.size)
    }

    @Test
    fun `subscription with neither is still an error`() {
        assertFailsWith<Exception> { decode("""{"name":"x"}""") }
        assertFailsWith<Exception> { decode("""{"danmakuServers":[]}""") }
    }

    @Test
    fun `exported sources do not write the field`() {
        val text = MediaSourceCodecManager.json.encodeToString(
            SubscriptionUpdateData.serializer(),
            SubscriptionUpdateData(mediaSources = emptyList()),
        )
        assertFalse(text.contains("danmakuServers"), text)
        assertTrue(text.contains("mediaSources"), text)
    }
}
