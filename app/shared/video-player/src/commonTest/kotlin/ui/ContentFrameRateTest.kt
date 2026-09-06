/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentFrameRateTest {
    private fun mode(id: Int, hz: Float, width: Int = 3840, height: Int = 2160) = DisplayModeOption(id, width, height, hz)

    // 4K 电视常见的一组模式, 刷新率照系统报的写 (23.976 报成 23.976025)
    private val hz23976 = mode(1, 23.976025f)
    private val hz24 = mode(2, 24f)
    private val hz25 = mode(3, 25f)
    private val hz50 = mode(4, 50f)
    private val hz5994 = mode(5, 59.94006f)
    private val hz60 = mode(6, 60f)
    private val tv = listOf(hz23976, hz24, hz25, hz50, hz5994, hz60, mode(7, 60f, 1920, 1080))

    @Test
    fun `film on a 60 Hz screen goes to the matching rate`() {
        assertEquals(hz23976.id, pickDisplayModeForFrameRate(tv, hz60, 23.976f))
        assertEquals(hz24.id, pickDisplayModeForFrameRate(tv, hz60, 24f))
        assertEquals(hz25.id, pickDisplayModeForFrameRate(tv, hz60, 25f))
        assertEquals(hz50.id, pickDisplayModeForFrameRate(listOf(hz50, hz60), hz60, 25f))
        assertEquals(hz5994.id, pickDisplayModeForFrameRate(tv, hz60, 29.97f))
    }

    @Test
    fun `a screen already at a whole multiple is left alone`() {
        assertNull(pickDisplayModeForFrameRate(tv, hz60, 30f))
        assertNull(pickDisplayModeForFrameRate(tv, hz60, 60f))
        assertNull(pickDisplayModeForFrameRate(tv, hz24, 24f))
        assertNull(pickDisplayModeForFrameRate(tv, hz50, 25f))
    }

    @Test
    fun `film plays at 24 Hz when the screen has no 23_976 Hz mode`() {
        assertEquals(hz24.id, pickDisplayModeForFrameRate(listOf(hz24, hz60), hz60, 23.976f))
    }

    @Test
    fun `nothing changes without a fitting mode of the same size or a frame rate`() {
        assertNull(pickDisplayModeForFrameRate(listOf(hz60, mode(8, 24f, 1920, 1080)), hz60, 24f))
        assertNull(pickDisplayModeForFrameRate(tv, hz60, 0f))
    }
}
