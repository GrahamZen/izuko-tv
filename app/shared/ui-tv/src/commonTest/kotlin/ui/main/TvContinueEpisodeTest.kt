/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType

class TvContinueEpisodeTest {
    private val duration = 24 * 60_000L

    /** 第 [count] 集为止的正片, 分集 id 为 100 + 集号; 前 [watchedThrough] 集标了看过. */
    private fun episodes(count: Int, watchedThrough: Int = 0) = (1..count).map { sort ->
        EpisodeCollectionInfo(
            EpisodeInfo(episodeId = 100 + sort, type = EpisodeType.MainStory, sort = EpisodeSort(sort), ep = EpisodeSort(sort)),
            if (sort <= watchedThrough) UnifiedCollectionType.DONE else UnifiedCollectionType.NOT_COLLECTED,
        )
    }

    private fun record(sort: Int, positionMillis: Long, version: Long, deleted: Boolean = false) = EpisodeHistory(
        episodeId = 100 + sort,
        positionMillis = positionMillis,
        durationMillis = duration,
        updatedAtMillis = version,
        deletedAtMillis = if (deleted) version else null,
    )

    /** 结果摘要: 哪一种 + 第几集 + 从哪条记录续播, 不比剧集的收藏状态. */
    private fun continueAt(
        episodes: List<EpisodeCollectionInfo>,
        vararg records: EpisodeHistory,
        notYetAired: Set<Int> = emptySet(),
    ): String = when (val result = tvContinueEpisode(episodes, records.associateBy { it.episodeId }) { it.episodeId in notYetAired }) {
        TvContinueEpisode.NotStarted -> "not started"
        is TvContinueEpisode.Play -> "play ${result.episode.episodeId - 100} resume ${result.resume?.positionMillis}"
        is TvContinueEpisode.Upcoming -> "upcoming ${result.episode.episodeId - 100}"
    }

    private fun play(sort: Int, resume: EpisodeHistory? = null) = "play $sort resume ${resume?.positionMillis}"

    @Test
    fun `nothing marked and no history is not started`() {
        assertEquals("not started", continueAt(episodes(12)))
    }

    @Test
    fun `half watched first episode resumes it`() {
        val half = record(1, positionMillis = 600_000, version = 10)
        assertEquals(play(1, half), continueAt(episodes(12), half))
    }

    @Test
    fun `marked episodes go to the next one`() {
        assertEquals(play(4), continueAt(episodes(12, watchedThrough = 3)))
    }

    @Test
    fun `finished episode without a mark goes to the next one`() {
        // autoMarkDone 关掉时看完一集只留下墓碑
        assertEquals(play(4), continueAt(episodes(12), record(3, positionMillis = 0, version = 10, deleted = true)))
    }

    @Test
    fun `record stopped within the last seconds counts as finished`() {
        assertEquals(play(4), continueAt(episodes(12), record(3, positionMillis = duration - 2_000, version = 10)))
    }

    @Test
    fun `episode marked at ninety percent goes to the next one instead of resuming`() {
        val ninetyTwo = record(3, positionMillis = duration * 92 / 100, version = 10)
        assertEquals(play(4), continueAt(episodes(12, watchedThrough = 3), ninetyTwo))
    }

    @Test
    fun `half watched episode after the marked ones resumes it`() {
        val half = record(4, positionMillis = 300_000, version = 10)
        assertEquals(play(4, half), continueAt(episodes(12, watchedThrough = 3), half))
    }

    @Test
    fun `half watched episode before the marked ones is stale`() {
        val stale = record(2, positionMillis = 300_000, version = 10)
        assertEquals(play(6), continueAt(episodes(12, watchedThrough = 5), stale))
    }

    @Test
    fun `most recently touched record decides`() {
        val oldHalf = record(5, positionMillis = 300_000, version = 10)
        val newFinished = record(2, positionMillis = 0, version = 20, deleted = true)
        assertEquals(play(3), continueAt(episodes(12), oldHalf, newFinished))
    }

    @Test
    fun `next episode known not yet aired is upcoming`() {
        assertEquals("upcoming 4", continueAt(episodes(12, watchedThrough = 3), notYetAired = setOf(104)))
    }

    @Test
    fun `everything watched starts over from the first episode`() {
        assertEquals(play(1), continueAt(episodes(12, watchedThrough = 12)))
    }

    @Test
    fun `special episodes are ignored`() {
        val special = EpisodeCollectionInfo(
            EpisodeInfo(episodeId = 900, type = EpisodeType.SP, sort = EpisodeSort(1, EpisodeType.SP)),
            UnifiedCollectionType.NOT_COLLECTED,
        )
        val specialHalf = EpisodeHistory(episodeId = 900, positionMillis = 300_000, durationMillis = duration, updatedAtMillis = 10)
        assertEquals(play(2), continueAt(episodes(12, watchedThrough = 1) + special, specialHalf))
    }
}
