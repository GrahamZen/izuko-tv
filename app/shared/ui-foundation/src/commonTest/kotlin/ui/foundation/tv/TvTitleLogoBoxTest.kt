/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 标题 logo 的大小规则 ([TvTitleLogoBox]): 按 Prime Video 实测的五部 (dp, 960×540 画面) 放大一倍回归. */
class TvTitleLogoBoxTest {
    /** 标题一行 37dp (同 Prime 的 logo 高度上限): 框是 Prime 的两倍, 高 74、宽 250. */
    private val box = tvTitleLogoBox(titleLineHeightPx = 37, titleWidthPx = 0)!!

    private fun assertNear(expected: Int, actual: Int, what: String) =
        assertTrue(abs(expected - actual) <= expected * 0.07 + 1, "$what: expected ≈$expected, got $actual")

    @Test
    fun `prime samples scaled up twice`() {
        // 宽高比 → Prime 上实测的宽 × 高
        val samples = listOf(1.16f to (43 to 37), 2.45f to (91 to 37), 3.06f to (95 to 31), 5.4f to (124 to 23), 7.9f to (125 to 16))
        for ((aspect, size) in samples) {
            val (w, h) = size
            val got = box.sizeOf(aspect)
            assertNear(w * 2, got.widthPx, "width of $aspect")
            assertNear(h * 2, got.heightPx, "height of $aspect")
        }
    }

    @Test
    fun `tall logos are capped by height and wide ones by width`() {
        assertEquals(74, box.sizeOf(0.4f).heightPx)
        assertEquals(box.maxWidthPx, box.sizeOf(12f).widthPx)
    }

    @Test
    fun `the width never exceeds the title width`() {
        val narrow = tvTitleLogoBox(titleLineHeightPx = 40, titleWidthPx = 200)!!
        assertEquals(80, narrow.maxHeightPx)
        assertEquals(200, narrow.maxWidthPx)
        assertTrue(narrow.sizeOf(9f).widthPx <= 200)
    }

    @Test
    fun `no line height has no box`() {
        assertNull(tvTitleLogoBox(titleLineHeightPx = 0, titleWidthPx = 300))
    }
}
