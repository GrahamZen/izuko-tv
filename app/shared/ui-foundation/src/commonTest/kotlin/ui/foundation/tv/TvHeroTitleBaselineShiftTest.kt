/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 放大 / 缩回时两份标题按首行基线对齐 ([TvHeroZoomHandoff.titleBaselineShift]): 列表页标题是原生 TextView, 详情页的是 Compose Text,
 * 字在框里的高度不同, 框顶对齐时两边的字差的就是这一截.
 */
class TvHeroTitleBaselineShiftTest {
    @Test
    fun `shift is the difference between the two baselines`() {
        assertEquals(6f, TvHeroZoomHandoff.titleBaselineShift(listBaseline = 46f, detailsBaseline = 40f))
    }

    @Test
    fun `unknown baseline falls back to box alignment`() {
        assertEquals(0f, TvHeroZoomHandoff.titleBaselineShift(Float.NaN, 40f))
        assertEquals(0f, TvHeroZoomHandoff.titleBaselineShift(30f, Float.NaN))
    }
}
