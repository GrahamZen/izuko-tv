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
    private fun copy(fid: String, size: Long, updatedAt: Long, name: String = "$fid.mkv") =
        DriveFile(fid = fid, fileName = name, size = size, updatedAt = updatedAt)

    private val saved = listOf(copy("new", 2_000, updatedAt = 30), copy("old", 1_500, updatedAt = 10), copy("mid", 1_000, updatedAt = 20))

    private fun pick(folder: List<DriveFile>, needed: Long, free: Long, keep: Set<String> = emptySet()) =
        pickSavedCopiesToFree(folder, keep, needed, free).map { it.fid }

    @Test
    fun `nothing is removed when clearing every copy still would not fit`() {
        assertEquals(emptyList(), pick(saved, needed = 23_000, free = 500))
    }

    @Test
    fun `only enough copies are removed and the oldest go first`() {
        assertEquals(listOf("old"), pick(saved, needed = 1_800, free = 500))
        assertEquals(listOf("old", "mid"), pick(saved, needed = 2_500, free = 500))
    }

    @Test
    fun `an over quota drive removes enough copies to cover the overage too`() {
        // 超额 1 000: 放下 1 200 要腾出 2 200 (只删 old 不够)
        assertEquals(listOf("old", "mid"), pick(saved, needed = 1_200, free = -1_000))
        assertEquals(emptyList(), pick(saved, needed = 4_000, free = -1_000))
    }

    @Test
    fun `nothing is removed when the free space already fits`() {
        assertEquals(emptyList(), pick(saved, needed = 1_000, free = 5_000))
        assertEquals(emptyList(), pick(emptyList(), needed = 1_000, free = 1_000))
    }

    @Test
    fun `a video goes together with its subtitles and the files in use stay`() {
        val folder = listOf(
            copy("old", 1_000, updatedAt = 10, name = "old.mkv"),
            copy("old-sc", 10, updatedAt = 10, name = "old.sc.ass"),
            copy("old-tc", 10, updatedAt = 10, name = "old.tc.ass"),
            copy("used", 1_000, updatedAt = 5, name = "used.mkv"),
            copy("used-sc", 10, updatedAt = 5, name = "used.sc.ass"),
            copy("mid", 1_000, updatedAt = 20, name = "mid.mkv"),
        )
        // 这次要用 used: 它更早, 但它与它的字幕都不删; old 连同两条字幕算一份
        assertEquals(listOf("old", "old-sc", "old-tc"), pick(folder, needed = 900, free = 0, keep = setOf("used")))
        // 要用的不算进能腾出的空间: old 与 mid 两份一共 2 020, 不够 2 100
        assertEquals(emptyList(), pick(folder, needed = 2_100, free = 0, keep = setOf("used")))
    }

    @Test
    fun `a subtitle without its video is a copy of its own`() {
        val folder = listOf(
            copy("lone", 300, updatedAt = 1, name = "gone.sc.ass"),
            copy("old", 1_000, updatedAt = 10, name = "old.mkv"),
        )
        assertEquals(listOf("lone", "old"), pick(folder, needed = 1_200, free = 0))
    }
}
