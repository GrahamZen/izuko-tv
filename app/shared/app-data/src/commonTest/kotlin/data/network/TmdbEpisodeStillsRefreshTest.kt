/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbEpisodeStillsRefreshTest {
    private val firstEpisode = LocalDate(2026, 10, 5)
    private val today = LocalDate(2026, 11, 24)

    private fun date(episode: Int) = firstEpisode.plus((episode - 1) * 7, DateTimeUnit.DAY).toString()

    /** 一季 12 集按周播, TMDB 一开始就列出全部播出日; 前 [withStills] 集有图. */
    private fun weeklySeason(withStills: Int, fetchedOn: String?) = TmdbEpisodeStills(
        byAirDate = (1..12).associate { episode ->
            date(episode) to listOf(TmdbEpisodeMedia(stillUrl = if (episode <= withStills) "/still$episode.jpg" else null))
        },
        airing = fetchedOn?.let { TmdbAiring(fetchedOn = it) },
    )

    @Test
    fun `future placeholder dates do not count as covered`() {
        // 第 4 集播完那周建的缓存, 第 8 集已播
        val stills = weeklySeason(withStills = 4, fetchedOn = date(4))
        assertTrue(stills.stillsNeedRefresh(date(8), today))
    }

    @Test
    fun `covered when newest still is within one day of newest aired`() {
        val stills = weeklySeason(withStills = 8, fetchedOn = date(8))
        assertFalse(stills.stillsNeedRefresh(date(8), today))
        // Bangumi 记的日期晚一天也算对上
        assertFalse(stills.stillsNeedRefresh(LocalDate.parse(date(8)).plus(1, DateTimeUnit.DAY).toString(), today))
    }

    @Test
    fun `at most one refetch per day`() {
        val stills = weeklySeason(withStills = 7, fetchedOn = today.toString())
        assertFalse(stills.stillsNeedRefresh(date(8), today))
    }

    @Test
    fun `stops waiting for a still a month after the episode aired`() {
        val aired = LocalDate(2024, 3, 1)
        val noStills = TmdbEpisodeStills(
            byAirDate = mapOf(aired.toString() to listOf(TmdbEpisodeMedia())),
            airing = TmdbAiring(fetchedOn = aired.plus(TmdbEpisodeStills.STILL_WAIT_DAYS, DateTimeUnit.DAY).toString()),
        )
        assertFalse(noStills.stillsNeedRefresh(aired.toString(), today))

        val fetchedSoonAfter = noStills.copy(airing = TmdbAiring(fetchedOn = aired.plus(3, DateTimeUnit.DAY).toString()))
        assertTrue(fetchedSoonAfter.stillsNeedRefresh(aired.toString(), today))
    }

    @Test
    fun `cache without fetch date refetches when not covered`() {
        assertTrue(weeklySeason(withStills = 4, fetchedOn = null).stillsNeedRefresh(date(8), today))
        assertFalse(weeklySeason(withStills = 8, fetchedOn = null).stillsNeedRefresh(date(8), today))
    }

    @Test
    fun `cache without dates follows fetch date only`() {
        val byNumber = TmdbEpisodeStills(byEpisodeNumber = mapOf(1 to TmdbEpisodeMedia(stillUrl = "/a.jpg")))
        assertTrue(byNumber.stillsNeedRefresh(date(8), today))
        assertFalse(byNumber.copy(airing = TmdbAiring(fetchedOn = today.toString())).stillsNeedRefresh(date(8), today))
    }

    @Test
    fun `unparseable date never triggers refetch`() {
        assertFalse(weeklySeason(withStills = 0, fetchedOn = null).stillsNeedRefresh("2026-11", today))
    }

    @Test
    fun `stills episodes only for a tv source with a readable episode map`() {
        fun entry(stills: String?, episodes: String?) = TmdbSubjectMapEntry(
            backdrop = TmdbSubjectMapRef("tv", 1),
            backdropPath = null,
            stills = listOfNotNull(stills?.let { TmdbSubjectMapRef.parse(it) }),
            manual = false,
            episodes = episodes,
        )
        assertEquals("1-8:S1E1", entry("tv/283938", "1-8:S1E1").stillsEpisodes)
        assertEquals("ED1:S1E25 25-32:S1E25", entry("tv/259140", "ED1:S1E25 25-32:S1E25").stillsEpisodes)
        assertNull(entry("movie/16516", "S1E1").stillsEpisodes)
        assertNull(entry(null, "1-8:S1E1").stillsEpisodes)
        assertNull(entry("tv/283938", null).stillsEpisodes)
    }
}
