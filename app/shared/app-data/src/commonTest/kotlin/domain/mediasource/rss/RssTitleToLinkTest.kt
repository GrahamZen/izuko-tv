/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rss

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.rss.RssEnclosure
import me.him188.ani.app.domain.rss.RssItem
import me.him188.ani.app.domain.rss.guessResourceLocation
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 标题改写成直链: 用 ANi 的种子标题与文件站的实际规律 (见 [RssTitleToLink] 的例子) 验证.
 */
class RssTitleToLinkTest {
    private val ani = RssTitleToLink(
        pattern = """^\[ANi\]\s+(?:.+?\s[/-]\s\s)?(?<file>(?<series>.+?) - [^\[]+\[.+\])\s*\[MP4\]$""",
        url = "https://resources.ani.rip/{seasonYear}-{seasonMonth}/%5BANi%5D%20{file}?d=mp4",
        title = "[ANi] {file}",
        groupBy = "series",
        seasonRoundUpDays = 14,
        maxAgeDays = 400,
    )
    private val now = Instant.parse("2026-10-15T00:00:00Z")

    private fun item(title: String, date: String, guid: String = title) = RssItem(
        title = title,
        pubDate = LocalDateTime.parse(date),
        link = "https://share.dmhy.org/topics/view/$guid",
        guid = guid,
        enclosure = RssEnclosure("magnet:?xt=urn:btih:abc", length = 1, type = "application/x-bittorrent"),
    )

    @Test
    fun `title becomes a direct link in the season folder`() {
        val items = listOf(
            item("[ANi] Sousou no Frieren S02 /  葬送的芙莉蓮 第二季 - 29 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-01-16T23:00:00"),
            item("[ANi] Sousou no Frieren S02 /  葬送的芙莉蓮 第二季 - 38 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-03-27T23:00:00"),
        )
        val rewritten = ani.rewrite(items, now = now)

        assertEquals(2, rewritten.size)
        val last = rewritten[1]
        assertEquals("[ANi] 葬送的芙莉蓮 第二季 - 38 [1080P][Baha][WEB-DL][AAC AVC][CHT]", last.title)
        val location = last.guessResourceLocation()
        assertIs<ResourceLocation.HttpStreamingFile>(location)
        assertEquals(
            "https://resources.ani.rip/2026-1/%5BANi%5D%20" +
                "%E8%91%AC%E9%80%81%E7%9A%84%E8%8A%99%E8%8E%89%E8%93%AE%20%E7%AC%AC%E4%BA%8C%E5%AD%A3%20-%2038%20" +
                "%5B1080P%5D%5BBaha%5D%5BWEB-DL%5D%5BAAC%20AVC%5D%5BCHT%5D?d=mp4",
            location.uri,
        )
        // 种子页留作来源页; 在线源按改写后的标题认条目名
        assertEquals("https://share.dmhy.org/topics/view/${items[1].guid}", last.link)
        assertEquals("葬送的芙莉蓮 第二季", guessSubjectNameFromTitle(last.title))
    }

    @Test
    fun `episodes airing in a later quarter stay in the folder of the first episode`() {
        val items = listOf(
            item("[ANi] Scum of the Brave /  勇者之渣 - 02 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-01-18T22:00:00"),
            item("[ANi] Scum of the Brave /  勇者之渣 - 15 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-04-25T22:00:00"),
        )
        val links = ani.rewrite(items, now = now).map { it.enclosure!!.url }
        assertTrue(links.all { it.startsWith("https://resources.ani.rip/2026-1/") }, links.toString())
    }

    @Test
    fun `each series gets its own season and old ones are dropped`() {
        val items = listOf(
            // 第一季 2023-09-29 提前首播: 算 10 月那季, 但已经超过 maxAgeDays
            item("[ANi] Sōsō no Frieren -  葬送的芙莉蓮 - 01 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2023-09-29T23:00:00"),
            item("[ANi] Sousou no Frieren S02 /  葬送的芙莉蓮 第二季 - 29 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-01-16T23:00:00"),
        )
        val rewritten = ani.rewrite(items, now = now)
        assertEquals(listOf("[ANi] 葬送的芙莉蓮 第二季 - 29 [1080P][Baha][WEB-DL][AAC AVC][CHT]"), rewritten.map { it.title })
    }

    @Test
    fun `titles without the romaji part and other groups`() {
        val items = listOf(
            item("[ANi] 暗部共生少女 - 01 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-10-03T22:00:00"),
            item("[LoliHouse] Ankoku Kyousei - 01 [WebRip 1080p HEVC-10bit AAC]", "2026-10-03T22:00:00"),
        )
        val rewritten = ani.rewrite(items, now = now)
        assertEquals(1, rewritten.size)
        assertTrue(rewritten[0].enclosure!!.url.startsWith("https://resources.ani.rip/2026-10/%5BANi%5D%20"))
        assertEquals("[ANi] 暗部共生少女 - 01 [1080P][Baha][WEB-DL][AAC AVC][CHT]", rewritten[0].title)
    }

    @Test
    fun `a double space without the romaji part is not part of the file name`() {
        val items = listOf(item("[ANi]  黑色五葉草 Season 2 - 171 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-10-03T22:00:00"))
        val rewritten = ani.rewrite(items, subjectAirDate = LocalDate(2025, 10, 7), now = now).single()
        assertEquals("[ANi] 黑色五葉草 Season 2 - 171 [1080P][Baha][WEB-DL][AAC AVC][CHT]", rewritten.title)
        assertTrue(rewritten.enclosure!!.url.startsWith("https://resources.ani.rip/2025-10/%5BANi%5D%20%E9%BB%91"), rewritten.enclosure!!.url)
    }

    @Test
    fun `the subject air date decides the season when the search results only have recent episodes`() {
        // 4 月开播的两季番, 种子站只搜出 9 月的几集
        val items = listOf(
            item("[ANi] Mairimashita Irumakun S04 /  入間同學入魔了！第四季 - 21 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-09-05T22:00:00"),
            item("[ANi] Mairimashita Irumakun S04 /  入間同學入魔了！第四季 - 22 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-09-12T22:00:00"),
        )
        val links = ani.rewrite(items, subjectAirDate = LocalDate(2026, 4, 4), now = now).map { it.enclosure!!.url }
        assertEquals(2, links.size)
        assertTrue(links.all { it.startsWith("https://resources.ani.rip/2026-4/") }, links.toString())
        // 不知道开播日期时只能按最早一条
        assertTrue(ani.rewrite(items, now = now).all { it.enclosure!!.url.startsWith("https://resources.ani.rip/2026-7/") })
    }

    @Test
    fun `earlier seasons found by the search are dropped when the subject air date is known`() {
        val items = listOf(
            item("[ANi]  入間同學入魔了！第三季 - 01 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2022-10-08T22:00:00"),
            item("[ANi] Mairimashita Irumakun S04 /  入間同學入魔了！第四季 - 24 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-09-26T22:00:00"),
        )
        val rewritten = ani.copy(maxAgeDays = 0).rewrite(items, subjectAirDate = LocalDate(2026, 4, 4), now = now)
        assertEquals(listOf("[ANi] 入間同學入魔了！第四季 - 24 [1080P][Baha][WEB-DL][AAC AVC][CHT]"), rewritten.map { it.title })
    }

    @Test
    fun `a sequel with the same title as the first season gets its own folder`() {
        // 「XX 第2部分」在种子站上与第一季同名, 按名字搜两季都出来
        val items = listOf(
            item("[ANi] Magical Sisters LuluttoLilly /  魔法姊妹露露特莉莉 - 01 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-04-05T22:00:00"),
            item("[ANi] Magical Sisters LuluttoLilly /  魔法姊妹露露特莉莉 - 13 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-10-04T22:00:00"),
        )
        val rewritten = ani.rewrite(items, subjectAirDate = LocalDate(2026, 10, 4), now = now)
        assertEquals(1, rewritten.size)
        assertTrue(rewritten[0].enclosure!!.url.startsWith("https://resources.ani.rip/2026-10/"), rewritten[0].enclosure!!.url)
        assertTrue(rewritten[0].title.contains(" - 13 "))
    }

    @Test
    fun `first air date is the earliest dated normal episode`() {
        val request = MediaFetchRequest(
            subjectId = "1",
            episodeId = "2",
            subjectNames = listOf("x"),
            episodeSort = EpisodeSort(1),
            episodeName = "",
            episodes = listOf(
                MediaFetchRequest.Episode("0", EpisodeSort("SP1"), airDate = PackedDate(2025, 12, 20)),
                MediaFetchRequest.Episode("1", EpisodeSort(1), airDate = PackedDate(2026, 1, 9)),
                MediaFetchRequest.Episode("2", EpisodeSort(2), airDate = PackedDate(2026, 1, 16)),
                MediaFetchRequest.Episode("3", EpisodeSort(3)),
            ),
        )
        assertEquals(LocalDate(2026, 1, 9), request.firstAirDate())
        assertEquals(null, request.copy(episodes = emptyList()).firstAirDate())
    }

    @Test
    fun `items without a date are dropped when the link needs the season`() {
        val noDate = item("[ANi] 暗部共生少女 - 01 [1080P][Baha][WEB-DL][AAC AVC][CHT][MP4]", "2026-10-03T22:00:00").copy(pubDate = null)
        assertEquals(emptyList(), ani.copy(maxAgeDays = 0).rewrite(listOf(noDate), now = now))
        // 地址用不到季度时照常改写
        val plain = RssTitleToLink(pattern = """^\[ANi\] (?<file>.+)$""", url = "https://example.com/{file}")
        assertEquals("https://example.com/%E6%9A%97", plain.rewrite(listOf(noDate.copy(title = "[ANi] 暗")), now = now).single().enclosure!!.url)
    }

    @Test
    fun `invalid pattern keeps nothing`() {
        val items = listOf(item("[ANi] 暗部共生少女 - 01 [1080P][MP4]", "2026-10-03T22:00:00"))
        assertEquals(emptyList(), RssTitleToLink(pattern = "(", url = "https://example.com/").rewrite(items, now = now))
    }

    @Test
    fun `raw variables are not encoded`() {
        val config = RssTitleToLink(pattern = """^(?<path>.+)$""", url = "https://example.com/{path:raw}?q={path}")
        val items = listOf(item("a b/c", "2026-10-03T22:00:00"))
        assertEquals("https://example.com/a b/c?q=a%20b%2Fc", config.rewrite(items, now = now).single().enclosure!!.url)
    }

    @Test
    fun `season boundaries`() {
        assertEquals(AnimeSeason(2026, 1), seasonOf(LocalDate(2026, 3, 17), roundUpDays = 14))
        assertEquals(AnimeSeason(2026, 4), seasonOf(LocalDate(2026, 3, 18), roundUpDays = 14))
        assertEquals(AnimeSeason(2026, 10), seasonOf(LocalDate(2026, 12, 31), roundUpDays = 0))
        assertEquals(AnimeSeason(2027, 1), seasonOf(LocalDate(2026, 12, 31), roundUpDays = 1))
        assertEquals(AnimeSeason(2026, 7), seasonOf(LocalDate(2026, 7, 1), roundUpDays = 14))
    }

    @Test
    fun `config decodes from the source json`() {
        val json = """
            {
              "searchUrl": "https://share.dmhy.org/topics/rss/rss.xml?keyword=ANi+{keyword}",
              "searchFallbackKeywords": true,
              "titleToLink": {
                "pattern": "^\\[ANi\\]\\s+(?:.+?\\s[/-]\\s\\s)?(?<file>(?<series>.+?) - [^\\[]+\\[.+\\])\\s*\\[MP4\\]$",
                "url": "https://resources.ani.rip/{seasonYear}-{seasonMonth}/%5BANi%5D%20{file}?d=mp4",
                "title": "[ANi] {file}",
                "groupBy": "series",
                "seasonRoundUpDays": 14,
                "maxAgeDays": 400
              }
            }
        """.trimIndent()
        val config = Json.decodeFromString(RssSearchConfig.serializer(), json)
        assertEquals(ani, config.titleToLink)
        assertTrue(config.searchFallbackKeywords)
        assertTrue(config.filterByEpisodeSort)
    }
}
