/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.him188.ani.app.data.models.schedule.AnimeSeason
import me.him188.ani.app.data.models.schedule.AnimeSeasonId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 季度列表按时间降序 (最新在前), 覆盖 1943 年至今的全部季度.
 */
class GetAnimeSeasonIdsFlowUseCaseTest {
    private fun useCaseAt(year: Int, month: Int) = GetAnimeSeasonIdsFlowUseCaseImpl(
        clock = object : Clock {
            override fun now(): Instant = LocalDateTime(year, month, 15, 0, 0).toInstant(TimeZone.UTC)
        },
        timeZone = TimeZone.UTC,
    )

    @Test
    fun `lists all seasons from 1943 down to the current one`() = runTest {
        val seasons = useCaseAt(2026, 9)().first()

        assertEquals(AnimeSeasonId(2026, AnimeSeason.AUTUMN), seasons.first())
        assertEquals(AnimeSeasonId(1943, AnimeSeason.WINTER), seasons.last())
        // 1943q1..2026q4 每年四季, 最后一年只到当前季度
        assertEquals((2026 - 1943) * 4 + 4, seasons.size)
        assertEquals(seasons.size, seasons.distinct().size)
    }

    @Test
    fun `does not list seasons after the current one`() = runTest {
        // 2 月属于当年冬季, 该年只应有这一个季度
        val seasons = useCaseAt(2026, 2)().first()

        assertEquals(AnimeSeasonId(2026, AnimeSeason.WINTER), seasons.first())
        assertTrue(seasons.none { it.year == 2026 && it.season != AnimeSeason.WINTER })
        assertEquals(AnimeSeasonId(2025, AnimeSeason.AUTUMN), seasons[1])
    }

    @Test
    fun `sorted puts latest season first`() {
        val seasons = listOf(
            AnimeSeasonId(2025, AnimeSeason.SPRING),
            AnimeSeasonId(2026, AnimeSeason.WINTER),
            AnimeSeasonId(2025, AnimeSeason.AUTUMN),
        )

        assertEquals(
            listOf(
                AnimeSeasonId(2026, AnimeSeason.WINTER),
                AnimeSeasonId(2025, AnimeSeason.AUTUMN),
                AnimeSeasonId(2025, AnimeSeason.SPRING),
            ),
            GetAnimeSeasonIdsFlowUseCase.sorted(seasons),
        )
    }

    @Test
    fun `sorted keeps empty list empty`() {
        assertEquals(emptyList(), GetAnimeSeasonIdsFlowUseCase.sorted(emptyList()))
    }
}
