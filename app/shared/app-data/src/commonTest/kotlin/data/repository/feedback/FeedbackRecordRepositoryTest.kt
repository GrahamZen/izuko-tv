/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.feedback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.feedback.FeedbackKind
import me.him188.ani.app.data.models.feedback.FeedbackRecord
import me.him188.ani.app.data.models.feedback.FeedbackRecords
import me.him188.ani.app.data.models.feedback.FeedbackState
import me.him188.ani.app.data.repository.user.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackRecordRepositoryTest {
    private class MemorySettings<T>(initial: T) : Settings<T> {
        val state = MutableStateFlow(initial)
        override val flow: Flow<T> get() = state
        override suspend fun set(value: T) {
            state.value = value
        }
    }

    private fun record(kind: FeedbackKind, issue: Int, at: Long = 0) = FeedbackRecord(kind, issue, submittedAt = at)

    @Test
    fun `新的在前，同一个 issue 只留一条`() = runTest {
        val repo = FeedbackRecordRepository(MemorySettings(FeedbackRecords.Empty))
        repo.add(record(FeedbackKind.LOGO, 1))
        repo.add(record(FeedbackKind.ENTRY, 1))
        repo.add(record(FeedbackKind.LOGO, 1, at = 5))
        assertEquals(listOf(record(FeedbackKind.LOGO, 1, at = 5), record(FeedbackKind.ENTRY, 1)), repo.records.first())
    }

    @Test
    fun `超过上限丢最旧的`() = runTest {
        val repo = FeedbackRecordRepository(MemorySettings(FeedbackRecords.Empty))
        repeat(FeedbackRecords.MAX_ITEMS + 3) { repo.add(record(FeedbackKind.LOGO, it + 1)) }
        val items = repo.records.first()
        assertEquals(FeedbackRecords.MAX_ITEMS, items.size)
        assertEquals(FeedbackRecords.MAX_ITEMS + 3, items.first().issue)
        assertEquals(4, items.last().issue)
    }

    @Test
    fun `改一条`() = runTest {
        val repo = FeedbackRecordRepository(MemorySettings(FeedbackRecords.Empty))
        repo.add(record(FeedbackKind.LOGO, 1))
        repo.update(record(FeedbackKind.LOGO, 1).copy(state = FeedbackState.ACCEPTED, checkedAt = 9))
        repo.update(record(FeedbackKind.ENTRY, 2).copy(state = FeedbackState.REJECTED))
        assertEquals(listOf(record(FeedbackKind.LOGO, 1).copy(state = FeedbackState.ACCEPTED, checkedAt = 9)), repo.records.first())
    }
}
