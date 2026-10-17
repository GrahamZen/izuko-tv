/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbLocalizedTextTest {
    @Test
    fun `placeholder episode names are dropped`() {
        for (name in listOf("Episode 3", "episode 12", "Ep. 4", "Épisode 2", "Folge 7", "Episodio 1", "第 3 話", "第5话", "第 11 集", "3화")) {
            assertNull(tmdbLocalizedEpisodeName(name), name)
        }
    }

    @Test
    fun `real episode names are kept and trimmed`() {
        assertEquals("The Beginning of the End", tmdbLocalizedEpisodeName("  The Beginning of the End "))
        assertEquals("Episode of the Dead", tmdbLocalizedEpisodeName("Episode of the Dead"))
        assertEquals("The 2nd Episode Begins", tmdbLocalizedEpisodeName("The 2nd Episode Begins"))
    }

    @Test
    fun `blank episode names are dropped`() {
        assertNull(tmdbLocalizedEpisodeName(null))
        assertNull(tmdbLocalizedEpisodeName("   "))
    }

    @Test
    fun `own season overview comes first`() {
        assertEquals("season", tmdbSubjectOverview("season", "show", ownSeason = 3))
        assertEquals("season", tmdbSubjectOverview("season", "show", ownSeason = 1))
    }

    @Test
    fun `show overview only describes the first season`() {
        assertEquals("show", tmdbSubjectOverview(null, "show", ownSeason = 1))
        assertNull(tmdbSubjectOverview(null, "show", ownSeason = 2))
        assertNull(tmdbSubjectOverview(null, "show", ownSeason = null))
        assertNull(tmdbSubjectOverview(null, "show", ownSeason = 0))
    }

    @Test
    fun `first season from the start uses the show name`() {
        assertEquals("Bleach", tmdbSubjectTitle("Bleach", 1 to 1, "Season 1"))
    }

    @Test
    fun `numbered sequel seasons append the season name with a space`() {
        assertEquals("SPY x FAMILY Season 3", tmdbSubjectTitle("SPY x FAMILY", 3 to 1, "Season 3"))
    }

    @Test
    fun `named sequel seasons append the season name with a colon`() {
        assertEquals("Bleach: Thousand-Year Blood War", tmdbSubjectTitle("Bleach", 2 to 1, "Thousand-Year Blood War"))
    }

    @Test
    fun `season names that already contain the show name are used alone`() {
        assertEquals(
            "Mobile Suit Gundam SEED Destiny",
            tmdbSubjectTitle("Mobile Suit Gundam SEED", 2 to 1, "Mobile Suit Gundam SEED Destiny"),
        )
    }

    @Test
    fun `parts starting mid season and specials have no title`() {
        assertNull(tmdbSubjectTitle("Bleach", 2 to 14, "Thousand-Year Blood War"))
        assertNull(tmdbSubjectTitle("Bleach", 0 to 1, "Specials"))
        assertNull(tmdbSubjectTitle("Bleach", null, null))
    }

    @Test
    fun `untranslated names fall back`() {
        assertNull(tmdbSubjectTitle("生徒会にも穴はある!", 1 to 1, "Season 1"))
        assertNull(tmdbSubjectTitle("Bleach", 2 to 1, "千年血戦篇"))
        assertNull(tmdbLocalizedName("葬送のフリーレン"))
        assertNull(tmdbLocalizedName("나 혼자만 레벨업"))
        assertEquals("SPY×FAMILY", tmdbLocalizedName(" SPY×FAMILY "))
    }

    @Test
    fun `season start comes from the episode airing on the subject air date`() {
        val seasons = mapOf(
            1 to listOf(1 to "2004-10-05", 2 to "2004-10-12"),
            2 to listOf(1 to "2022-10-11", 13 to "2022-12-27", 14 to "2023-07-08", 15 to "2023-07-15"),
        )
        assertEquals(2 to 14, tmdbSeasonStartByEpisodeAirDate(seasons, "2023-07-08"))
        assertEquals(2 to 14, tmdbSeasonStartByEpisodeAirDate(seasons, "2023-07-07"))
        assertEquals(2 to 1, tmdbSeasonStartByEpisodeAirDate(seasons, "2022-10-11"))
        assertNull(tmdbSeasonStartByEpisodeAirDate(seasons, "2023-07-01"))
        assertNull(tmdbSeasonStartByEpisodeAirDate(seasons, null))
    }

    @Test
    fun `double premieres take the smaller episode number`() {
        val seasons = mapOf(3 to listOf(1 to "2024-04-05", 2 to "2024-04-05", 3 to "2024-04-12"))
        assertEquals(3 to 1, tmdbSeasonStartByEpisodeAirDate(seasons, "2024-04-05"))
    }

    @Test
    fun `dates hitting several seasons or only specials are not claimed`() {
        val seasons = mapOf(
            0 to listOf(1 to "2023-07-08"),
            1 to listOf(5 to "2023-07-08"),
            2 to listOf(1 to "2023-07-09"),
        )
        assertNull(tmdbSeasonStartByEpisodeAirDate(seasons, "2023-07-08"))
        assertNull(tmdbSeasonStartByEpisodeAirDate(mapOf(0 to listOf(1 to "2023-07-08")), "2023-07-08"))
    }
}
