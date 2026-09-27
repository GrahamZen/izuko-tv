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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 主屏「接下来播放」的对账 (detectPlayNextChanges): 写这一行的用户自己来对账时认出移除与手动添加;
 * 换了用户 (这一行是别人写的) 什么都不认; 两张以上一下子全没了当成外部重置.
 */
class TvPlayNextRecordTest {
    private fun row(id: Long, subjectId: Int, manuallyAdded: Boolean = false) = TvPlayNextRow(id, manuallyAdded, subjectId)

    @Test
    fun `the writer sees removed rows and manually added cards`() {
        val record = TvPlayNextRecord(writerProfileId = 2, rows = mapOf(10L to 100, 11L to 101, 12L to 102))

        val detection = detectPlayNextChanges(
            record,
            currentProfileId = 2,
            existing = listOf(row(10, 100), row(12, 102), row(20, 200, manuallyAdded = true)),
        )

        assertFalse(detection.changes.foreign)
        assertEquals(setOf(101), detection.changes.removed)
        assertEquals(mapOf(20L to 200), detection.changes.added)
        assertEquals(mapOf(10L to 100, 12L to 102), detection.remainingRows)
    }

    @Test
    fun `another users row is not applied to the current user`() {
        val record = TvPlayNextRecord(writerProfileId = 1, rows = mapOf(10L to 100, 11L to 101))

        val detection = detectPlayNextChanges(
            record,
            currentProfileId = 2,
            existing = listOf(row(10, 100), row(20, 200, manuallyAdded = true)),
        )

        assertTrue(detection.changes.foreign)
        assertTrue(detection.changes.isEmpty)
        assertNull(detection.remainingRows)
    }

    @Test
    fun `a record without a writer counts as the primary user`() {
        val record = TvPlayNextRecord(writerProfileId = null, rows = mapOf(10L to 100, 11L to 101))

        val primary = detectPlayNextChanges(record, currentProfileId = 1, existing = listOf(row(10, 100)))
        assertEquals(setOf(101), primary.changes.removed)

        val other = detectPlayNextChanges(record, currentProfileId = 2, existing = listOf(row(10, 100)))
        assertTrue(other.changes.foreign)
    }

    @Test
    fun `all recorded rows gone at once is an external reset`() {
        val record = TvPlayNextRecord(writerProfileId = 1, rows = mapOf(10L to 100, 11L to 101))

        val detection = detectPlayNextChanges(record, currentProfileId = 1, existing = emptyList())

        assertTrue(detection.externalReset)
        assertTrue(detection.changes.removed.isEmpty())
        assertEquals(emptyMap(), detection.remainingRows)
    }

    @Test
    fun `cards already handled are not added again`() {
        val record = TvPlayNextRecord(writerProfileId = 1, rows = emptyMap())

        val detection = detectPlayNextChanges(
            record,
            currentProfileId = 1,
            existing = listOf(row(20, 200, manuallyAdded = true), row(21, 201, manuallyAdded = true)),
        ) { it == 20L }

        assertEquals(mapOf(21L to 201), detection.changes.added)
    }
}
