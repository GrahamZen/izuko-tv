/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlin.test.Test
import kotlin.test.assertEquals

class DriveSaveSpaceTest {
    private fun copy(fid: String, size: Long, updatedAt: Long) = DriveFile(fid = fid, fileName = "$fid.mkv", size = size, updatedAt = updatedAt)

    private val saved = listOf(copy("new", 2_000, updatedAt = 30), copy("old", 1_500, updatedAt = 10), copy("mid", 1_000, updatedAt = 20))

    @Test
    fun `nothing is removed when clearing every copy still would not fit`() {
        assertEquals(emptyList(), pickSavedCopiesToFree(saved, needed = 23_000, free = 500))
    }

    @Test
    fun `only enough copies are removed and the oldest go first`() {
        assertEquals(listOf("old"), pickSavedCopiesToFree(saved, needed = 1_800, free = 500).map { it.fid })
        assertEquals(listOf("old", "mid"), pickSavedCopiesToFree(saved, needed = 2_500, free = 500).map { it.fid })
    }

    @Test
    fun `an over quota drive removes enough copies to cover the overage too`() {
        // 超额 1 000: 放下 1 200 要腾出 2 200 (只删 old 不够)
        assertEquals(listOf("old", "mid"), pickSavedCopiesToFree(saved, needed = 1_200, free = -1_000).map { it.fid })
        assertEquals(emptyList(), pickSavedCopiesToFree(saved, needed = 4_000, free = -1_000))
    }

    @Test
    fun `every copy is removed when the free space is unknown or does not explain the failure`() {
        assertEquals(saved, pickSavedCopiesToFree(saved, needed = 23_000, free = null))
        assertEquals(saved, pickSavedCopiesToFree(saved, needed = 1_000, free = 5_000))
        assertEquals(emptyList(), pickSavedCopiesToFree(emptyList(), needed = 1_000, free = null))
    }
}
