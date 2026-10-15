/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EpisodesFetchTrackerTest {
    private val tracker = EpisodesFetchTracker()
    private val visibleTypes = listOf(EpisodeType.MainStory, EpisodeType.SP)

    private suspend fun state(subjectId: Int = SUBJECT) = tracker.stateFlow(subjectId).first()

    @Test
    fun `nothing recorded is pending`() = runTest {
        assertEquals(EpisodesFetchState.Pending, state())
    }

    @Test
    fun `fetched counts only the visible episode types`() = runTest {
        tracker.recordFetched(SUBJECT, listOf(episode(1, EpisodeType.MainStory), episode(2, EpisodeType.PV)), visibleTypes)
        assertEquals(EpisodesFetchState.Fetched(1), state())
        assertEquals(EpisodesFetchState.Pending, state(SUBJECT + 1))
    }

    @Test
    fun `episodes that could not be fetched are failed`() = runTest {
        tracker.recordFetched(SUBJECT, null, visibleTypes)
        assertEquals(EpisodesFetchState.Failed, state())

        // 下一次取到了就盖掉
        tracker.recordFetched(SUBJECT, emptyList(), visibleTypes)
        assertEquals(EpisodesFetchState.Fetched(0), state())
    }

    @Test
    fun `settling from the library does not override a fetch result`() = runTest {
        tracker.settleIfPending(SUBJECT) { 0 }
        assertEquals(EpisodesFetchState.Fetched(0), state())

        tracker.recordFailed(SUBJECT + 1)
        var queried = false
        tracker.settleIfPending(SUBJECT + 1) { queried = true; 3 }
        assertEquals(EpisodesFetchState.Failed, state(SUBJECT + 1))
        assertEquals(false, queried)
    }

    @Test
    fun `errors are recorded as failed and rethrown - cancellation is not`() = runTest {
        assertFailsWith<IllegalStateException> { tracker.failedOnError(SUBJECT) { throw IllegalStateException() } }
        assertEquals(EpisodesFetchState.Failed, state())

        assertFailsWith<CancellationException> { tracker.failedOnError(SUBJECT + 1) { throw CancellationException() } }
        assertEquals(EpisodesFetchState.Pending, state(SUBJECT + 1))
    }

    private fun episode(id: Int, type: EpisodeType) = EpisodeCollectionEntity(
        subjectId = SUBJECT, episodeId = id, episodeType = type, name = "", nameCn = "",
        airDate = PackedDate.Invalid, comment = 0, desc = "",
        sort = EpisodeSort(id), sortNumber = id.toFloat(),
        selfCollectionType = UnifiedCollectionType.NOT_COLLECTED,
        lastFetched = 0,
    )

    private companion object {
        const val SUBJECT = 339326
    }
}
