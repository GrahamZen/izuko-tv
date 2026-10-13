/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * 在看条目的分集过了缓存期是否照用库里的 ([inProgressEpisodesUnchanged]): 收藏没变、看到第几集对得上、正片播完了才照用,
 * 其余一律重取.
 */
class InProgressEpisodesUnchangedTest {
    @Test
    fun `finished subject with the same collection keeps its episodes`() {
        assertTrue(unchanged())
    }

    @Test
    fun `collection updated elsewhere refetches`() {
        assertFalse(unchanged(current = subject(lastUpdated = UPDATED + 60_000)))
    }

    @Test
    fun `watched count that does not match refetches`() {
        assertFalse(unchanged(epStatus = 4))
        assertFalse(unchanged(epStatus = null))
    }

    @Test
    fun `subject that may still air refetches`() {
        val airing = episodes(lastAirDate = "2026-10-12")
        assertFalse(unchanged(cached = airing))
    }

    @Test
    fun `more episodes on the server than in the library refetches`() {
        assertFalse(unchanged(current = subject(totalEpisodes = 13)))
    }

    @Test
    fun `episodes fetched before login or too long ago refetch`() {
        assertFalse(unchanged(cached = episodes().map { it.copy(lastFetched = 0) }))
        assertFalse(unchanged(cached = episodes().map { it.copy(lastFetched = NOW - 8.days.inWholeMilliseconds) }))
    }

    @Test
    fun `no previous row or no episodes refetches`() {
        assertFalse(unchanged(previous = null))
        assertFalse(unchanged(cached = emptyList()))
    }

    private fun unchanged(
        cached: List<EpisodeCollectionEntity> = episodes(),
        previous: SubjectCollectionEntity? = subject(),
        current: SubjectCollectionEntity = subject(),
        epStatus: Int? = 3,
    ) = inProgressEpisodesUnchanged(cached, previous, current, epStatus, NOW, TODAY)

    /** 12 集正片, 前 3 集看过, 最后一集 [lastAirDate] 播出, 两小时前取的. */
    @Suppress("DEPRECATION")
    private fun episodes(lastAirDate: String = "2026-06-28") = (1..12).map { n ->
        EpisodeCollectionEntity(
            subjectId = SUBJECT, episodeId = n, episodeType = EpisodeType.MainStory, name = "", nameCn = "",
            airDate = PackedDate.parseFromDate(if (n == 12) lastAirDate else "2026-04-${n + 5}"), comment = 0, desc = "",
            sort = EpisodeSort(n), sortNumber = n.toFloat(),
            selfCollectionType = if (n <= 3) UnifiedCollectionType.DONE else UnifiedCollectionType.WISH,
            lastFetched = NOW - 2.hours.inWholeMilliseconds,
        )
    }

    private fun subject(lastUpdated: Long = UPDATED, totalEpisodes: Int = 12) = SubjectCollectionEntity(
        subjectId = SUBJECT,
        name = "",
        nameCn = "",
        summary = "",
        nsfw = false,
        imageLarge = "",
        totalEpisodes = totalEpisodes,
        airDate = PackedDate.parseFromDate("2026-04-06"),
        aliases = emptyList(),
        tags = emptyList(),
        collectionStats = SubjectCollectionStats.Zero,
        ratingInfo = RatingInfo.Empty,
        completeDate = PackedDate.Invalid,
        selfRatingInfo = SelfRatingInfo(0, null, emptyList(), false),
        collectionType = UnifiedCollectionType.DOING,
        recurrence = null,
        lastUpdated = lastUpdated,
        lastFetched = NOW,
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )

    private companion object {
        const val SUBJECT = 512190
        const val NOW = 1_791_900_000_000L
        const val UPDATED = 1_791_000_000_000L
        val TODAY = PackedDate.parseFromDate("2026-10-13")
    }
}
