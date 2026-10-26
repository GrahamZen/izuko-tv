/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.location

import kotlinx.io.files.Path
import me.him188.ani.utils.io.inSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaCacheLocationChoicesTest {
    private val internal = MediaCacheLocation(id = null, label = "", root = Path("internal").inSystem, freeBytes = 1)
    private val usb = MediaCacheLocation(id = "usb", label = "USB", root = Path("usb").inSystem, freeBytes = 2)
    private val fat32 = MediaCacheLocation(
        id = "fat", label = "FAT", root = Path("fat").inSystem, unusable = MediaCacheLocationProblem.FAT32,
    )

    @Test
    fun `only usable locations are offered`() {
        val choices = mediaCacheLocationChoices(listOf(internal, usb, fat32), selected = null)
        assertEquals(listOf(null, "usb"), choices.map { it.id })
    }

    @Test
    fun `a selected drive that is not connected stays in the list without a location`() {
        val choices = mediaCacheLocationChoices(listOf(internal), selected = "usb")
        assertEquals(listOf(null, "usb"), choices.map { it.id })
        assertNull(choices.last().location)
    }

    @Test
    fun `a selected drive that became unusable stays in the list with its problem`() {
        val choices = mediaCacheLocationChoices(listOf(internal, fat32), selected = "fat")
        assertEquals(listOf(null, "fat"), choices.map { it.id })
        assertEquals(MediaCacheLocationProblem.FAT32, choices.last().location?.unusable)
    }

    @Test
    fun `choices are equal by id so the selection survives a rescan`() {
        val before = mediaCacheLocationChoices(listOf(internal, usb), selected = "usb")
        val after = mediaCacheLocationChoices(listOf(internal, usb.copy(freeBytes = 1)), selected = "usb")
        assertEquals(1, after.indexOf(before[1]))
    }
}
