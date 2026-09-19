/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.rss

import me.him188.ani.app.domain.mediasource.rss.guessSubjectNameFromTitle
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * RSS 源既可能提供种子, 也可能提供视频直链, 这里覆盖两类以及"链接上看不出类型"的情况.
 */
class RssItemResourceLocationTest {
    @Test
    fun `torrent link`() {
        assertIs<ResourceLocation.HttpTorrentFile>(
            item(title = "[Group] Title - 01.torrent", link = "https://example.com/a.torrent")
                .guessResourceLocation(),
        )
    }

    @Test
    fun `magnet enclosure`() {
        assertIs<ResourceLocation.MagnetLink>(
            item(
                title = "[Group] Title - 01",
                link = "https://example.com/detail/1",
                enclosure = RssEnclosure("magnet:?xt=urn:btih:abc", type = "application/x-bittorrent"),
            ).guessResourceLocation(),
        )
    }

    @Test
    fun `direct video link`() {
        val location = item(title = "[Group] Title - 01.mp4", link = "https://example.com/a.mp4")
            .guessResourceLocation()
        assertIs<ResourceLocation.HttpStreamingFile>(location)
        assertEquals("https://example.com/a.mp4", location.uri)
    }

    @Test
    fun `direct video link without extension falls back to the title`() {
        // 有的源的直链把扩展名放在 query 里, 例如 ANi 的 ".../%5BANi%5D%20Title%20-%2001?d=mp4"
        val url = "https://example.com/files/Title%20-%2001?d=mp4"
        val location = item(title = "[Group] Title - 01 [1080P][WEB-DL][CHT].mp4", link = url)
            .guessResourceLocation()
        assertIs<ResourceLocation.HttpStreamingFile>(location)
        assertEquals(url, location.uri)
    }

    @Test
    fun `direct video link without extension falls back to the enclosure mime`() {
        val location = item(
            title = "Title - 01",
            link = "https://example.com/files/1",
            enclosure = RssEnclosure("https://example.com/files/1", type = "video/mp4"),
        ).guessResourceLocation()
        assertIs<ResourceLocation.HttpStreamingFile>(location)
    }

    @Test
    fun `web page is not a resource`() {
        assertNull(
            item(title = "Title - 01", link = "https://example.com/watch/1").guessResourceLocation(),
        )
    }

    @Test
    fun `guesses the anime name out of a release style title`() {
        // 在线源要用番名(而不是整条标题)去匹配 bangumi 条目, 而标题解析器对这种格式取不出中文名
        assertEquals(
            "無職轉生～到了異世界就拿出真本事～第三季",
            guessSubjectNameFromTitle(
                "[ANi] 無職轉生～到了異世界就拿出真本事～第三季 - 12 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4",
            ),
        )
        assertEquals(
            "葬送的芙莉莲 第二季 Sousou no Frieren S2",
            guessSubjectNameFromTitle("[云光字幕组]葬送的芙莉莲 第二季 Sousou no Frieren S2 [合集][简体双语][1080p]招募翻译"),
        )
        assertEquals("某番 第二季", guessSubjectNameFromTitle("【某字幕组】某番 第二季【01】【1080P】"))
        assertEquals("某番", guessSubjectNameFromTitle("某番"))
        assertNull(guessSubjectNameFromTitle("[ANi]"))
    }

    private fun item(
        title: String,
        link: String,
        enclosure: RssEnclosure? = null,
    ) = RssItem(
        title = title,
        description = "",
        pubDate = null,
        link = link,
        guid = link,
        enclosure = enclosure,
    )
}
