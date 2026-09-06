/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvDetailsTouchSnapTest {
    private val threshold = 1000f

    // hero 0 / 选集 1000 / 人物 2000 / 评价 3000; 末页比一屏高 400
    private val pages = listOf(
        TvDetailsSnapPage(0, 0f, 0f),
        TvDetailsSnapPage(1, 1000f, 1000f),
        TvDetailsSnapPage(2, 2000f, 2000f),
        TvDetailsSnapPage(3, 3000f, 3400f),
    )

    private fun snap(scroll: Float, velocity: Float = 0f) = tvDetailsTouchSnapTarget(pages, scroll, velocity, threshold)

    @Test
    fun `no pages keeps default fling`() {
        assertNull(tvDetailsTouchSnapTarget(emptyList(), 500f, 0f, threshold))
    }

    @Test
    fun `slow release between pages goes to the nearer page`() {
        assertEquals(TvDetailsSnapTarget(0, 0f), snap(400f))
        assertEquals(TvDetailsSnapTarget(1, 1000f), snap(600f))
        assertEquals(TvDetailsSnapTarget(2, 2000f), snap(1501f, velocity = 200f))
    }

    @Test
    fun `fast swipe goes one page in the swipe direction`() {
        assertEquals(TvDetailsSnapTarget(2, 2000f), snap(1100f, velocity = 3000f))
        assertEquals(TvDetailsSnapTarget(1, 1000f), snap(1900f, velocity = -3000f))
    }

    @Test
    fun `release exactly on a page start stays`() {
        assertEquals(TvDetailsSnapTarget(1, 1000f), snap(1000f))
    }

    @Test
    fun `fling down from a page start goes to the next page`() {
        assertEquals(TvDetailsSnapTarget(2, 2000f), snap(1000f, velocity = 3000f))
    }

    @Test
    fun `inside a tall page a slow release stays where it is`() {
        assertEquals(TvDetailsSnapTarget(3, 3200f), snap(3200f))
    }

    @Test
    fun `inside a tall page a fling down reaches the page bottom first`() {
        assertEquals(TvDetailsSnapTarget(3, 3400f), snap(3200f, velocity = 3000f))
        assertEquals(TvDetailsSnapTarget(3, 3400f), snap(3400f, velocity = 3000f))
    }

    @Test
    fun `inside a tall page a fling up goes back to the page start`() {
        assertEquals(TvDetailsSnapTarget(3, 3000f), snap(3200f, velocity = -3000f))
    }

    @Test
    fun `past the last page settles at its bottom`() {
        assertEquals(TvDetailsSnapTarget(3, 3400f), snap(3600f))
    }

    @Test
    fun `before the first page start goes to the first page`() {
        val later = listOf(TvDetailsSnapPage(1, 1000f, 1000f), TvDetailsSnapPage(2, 2000f, 2000f))
        assertEquals(TvDetailsSnapTarget(1, 1000f), tvDetailsTouchSnapTarget(later, 300f, 0f, threshold))
    }
}
