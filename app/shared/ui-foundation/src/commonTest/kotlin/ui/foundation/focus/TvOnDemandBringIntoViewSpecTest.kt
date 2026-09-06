/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 按详情页演职人员行的实际尺寸: 屏宽 960, 两端留白 40, 格宽 130, 步距 150 —— 静止时一屏正好 6 格
 * (第 k 格占 `[40 + 150k, 170 + 150k]`, 第 6 格右缘 920 正好贴着右留白).
 */
class TvOnDemandBringIntoViewSpecTest {
    private val spec = tvOnDemandBringIntoViewSpec(startPx = 40f, endPx = 40f)
    private fun distance(offset: Float, size: Float = 130f) = spec.calculateScrollDistance(offset, size, 960f)

    @Test
    fun `cells within the first screen do not scroll`() {
        assertEquals(0f, distance(40f))
        assertEquals(0f, distance(40f + 150f * 5)) // 第 6 格, 右缘 920
    }

    @Test
    fun `stepping past the right margin scrolls one pitch`() {
        // 第 7 格 [940, 1070]: 滚到右缘贴 920
        assertEquals(150f, distance(40f + 150f * 6))
    }

    @Test
    fun `stepping past the left margin aligns the cell to it`() {
        // 行已右滚, 左边那格只露出一半
        assertEquals(-65f, distance(-25f))
    }

    @Test
    fun `cell wider than the band aligns its start`() {
        assertEquals(60f, distance(100f, size = 900f))
    }
}
