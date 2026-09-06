/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.subject.EpisodesFetchState

class TvPlayAwaitEpisodesTest {
    private val hasEpisodes = MutableStateFlow(false)
    private val fetchState = MutableStateFlow<EpisodesFetchState>(EpisodesFetchState.Pending)
    private var refetches = 0

    @Test
    fun `episodes arriving while still fetching start playback`() = runTest {
        val outcome = async { awaitEpisodesForPlay(hasEpisodes, fetchState) { refetches++ } }
        runCurrent()
        assertFalse(outcome.isCompleted)

        hasEpisodes.value = true
        assertEquals(TvPlayEpisodesOutcome.READY, outcome.await())
        assertEquals(0, refetches)
    }

    @Test
    fun `fetched episodes not yet on screen keep waiting instead of showing the dialog`() = runTest {
        val outcome = async { awaitEpisodesForPlay(hasEpisodes, fetchState) { refetches++ } }
        runCurrent()
        // 仓库落库后先记了「取到 15 集」, 界面还没收到
        fetchState.value = EpisodesFetchState.Fetched(visibleCount = 15)
        runCurrent()
        assertFalse(outcome.isCompleted)

        hasEpisodes.value = true
        assertEquals(TvPlayEpisodesOutcome.READY, outcome.await())
    }

    @Test
    fun `fetched with nothing visible shows the dialog`() = runTest {
        val outcome = async { awaitEpisodesForPlay(hasEpisodes, fetchState) { refetches++ } }
        runCurrent()
        assertFalse(outcome.isCompleted)

        fetchState.value = EpisodesFetchState.Fetched(visibleCount = 0)
        assertEquals(TvPlayEpisodesOutcome.NO_EPISODES, outcome.await())
    }

    @Test
    fun `already fetched with nothing visible shows the dialog at once`() = runTest {
        fetchState.value = EpisodesFetchState.Fetched(visibleCount = 0)
        assertEquals(TvPlayEpisodesOutcome.NO_EPISODES, awaitEpisodesForPlay(hasEpisodes, fetchState) { refetches++ })
        assertEquals(0, refetches)
    }

    @Test
    fun `a fetch failing while waiting reports the failure without refetching`() = runTest {
        val outcome = async { awaitEpisodesForPlay(hasEpisodes, fetchState) { refetches++ } }
        runCurrent()

        fetchState.value = EpisodesFetchState.Failed
        assertEquals(TvPlayEpisodesOutcome.FETCH_FAILED, outcome.await())
        assertEquals(0, refetches)
    }

    @Test
    fun `pressing after a failed fetch refetches and plays what arrives`() = runTest {
        fetchState.value = EpisodesFetchState.Failed
        val outcome = awaitEpisodesForPlay(hasEpisodes, fetchState) {
            refetches++
            fetchState.value = EpisodesFetchState.Fetched(visibleCount = 13)
            hasEpisodes.value = true
        }
        assertEquals(TvPlayEpisodesOutcome.READY, outcome)
        assertEquals(1, refetches)
    }

    @Test
    fun `pressing after a failed fetch reports the failure when the refetch fails too`() = runTest {
        fetchState.value = EpisodesFetchState.Failed
        val outcome = awaitEpisodesForPlay(hasEpisodes, fetchState) {
            refetches++
            throw IllegalStateException("network")
        }
        assertEquals(TvPlayEpisodesOutcome.FETCH_FAILED, outcome)
        assertEquals(1, refetches)
    }

    @Test
    fun `refetch finding that Bangumi has no episodes shows the dialog`() = runTest {
        fetchState.value = EpisodesFetchState.Failed
        val outcome = awaitEpisodesForPlay(hasEpisodes, fetchState) {
            refetches++
            fetchState.value = EpisodesFetchState.Fetched(visibleCount = 0)
        }
        assertEquals(TvPlayEpisodesOutcome.NO_EPISODES, outcome)
    }
}
