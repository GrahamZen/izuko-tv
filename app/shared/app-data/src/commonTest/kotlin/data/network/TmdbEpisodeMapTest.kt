/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbEpisodeMapTest {
    /** 与 app 从数据库读出来的一致: 集号存的是 EpisodeSort.toString(), 读回来再解析 */
    private fun ep(id: Int, type: EpisodeType?, sort: String) = EpisodeCollectionInfo(
        EpisodeInfo(episodeId = id, type = type, sort = EpisodeSort(sort)),
        UnifiedCollectionType.NOT_COLLECTED,
    )

    private val main = (1..12).map { ep(it, EpisodeType.MainStory, it.toString().padStart(2, '0')) }

    @Test
    fun `接续记号 - 本篇按集号一集接一集`() {
        val map = TmdbEpisodeMap.parse("S3E13")!!
        val resolved = map.resolve(main.reversed())
        assertEquals(3 to 13, resolved[1])
        assertEquals(3 to 24, resolved[12])
        assertEquals(setOf(3), map.seasons)
    }

    @Test
    fun `区间与单集`() {
        val map = TmdbEpisodeMap.parse("1-5:S1E1 7:S1E9")!!
        val resolved = map.resolve(main)
        assertEquals(1 to 1, resolved[1])
        assertEquals(1 to 5, resolved[5])
        assertNull(resolved[6])
        assertEquals(1 to 9, resolved[7])
    }

    @Test
    fun `特别篇 - 前缀与夹在两集之间的集号`() {
        // Bangumi 用 12.1 这种集号把特别篇排在两集之间, EpisodeSort 认不出 (只认整数和 .5), 按存储原文对
        val sp = listOf(ep(101, EpisodeType.SP, "SP01"), ep(102, EpisodeType.SP, "12.1"), ep(103, EpisodeType.SP, "SP13.5"))
        val map = TmdbEpisodeMap.parse("S1E1 SP1:S0E1 SP12.1:S0E7 SP13.5:S0E9")!!
        val resolved = map.resolve(main + sp)
        assertEquals(1 to 1, resolved[1])
        assertEquals(0 to 1, resolved[101])
        assertEquals(0 to 7, resolved[102])
        assertEquals(0 to 9, resolved[103])
        assertEquals(setOf(0, 1), map.seasons)
    }

    @Test
    fun `认不出的编码整条不用`() {
        assertNull(TmdbEpisodeMap.parse(""))
        assertNull(TmdbEpisodeMap.parse("S1E1 S2E1"))
        assertNull(TmdbEpisodeMap.parse("XX1:S1E1"))
        assertNull(TmdbEpisodeMap.parse("5-3:S1E1"))
        assertNull(TmdbEpisodeMap.parse("1.5-3:S1E1"))
        assertNull(TmdbEpisodeMap.parse("tv/1"))
    }

    @Test
    fun `对应表第 6 列 - 认不出就当没有，不连累整行`() {
        val map = parseTmdbSubjectMap(
            listOf(
                "1\ttv/1\t/a.jpg\ttv/1\tauto\tS1E1",
                "2\ttv/2\t/b.jpg\ttv/2\tauto\t???",
                "3\ttv/3\t/c.jpg\ttv/3\tauto",
            ).joinToString("\n"),
        )
        assertEquals("S1E1", map.getValue(1).episodes)
        assertNull(map.getValue(2).episodes)
        assertEquals("/b.jpg", map.getValue(2).backdropPath)
        assertNull(map.getValue(3).episodes)
    }

    @Test
    fun `对集入口 - 有逐集对位时直接照它取`() {
        val media = (1..12).associate { "3:$it" to TmdbEpisodeMedia(stillUrl = "https://image.tmdb.org/t/p/original/$it.jpg") }
        val stills = TmdbEpisodeStills(episodeMap = "S3E1", bySeasonEpisode = media)
        val matched = stills.matchToEpisodes(main)
        assertEquals(12, matched.size)
        assertEquals("https://image.tmdb.org/t/p/original/7.jpg", matched[7]?.stillUrl)
    }
}
