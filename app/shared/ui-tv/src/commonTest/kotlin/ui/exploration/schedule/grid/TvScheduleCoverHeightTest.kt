/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.schedule.grid

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 时间表卡格的封面高 ([tvScheduleCoverHeightPx]): 海报墙大小 100% 时首屏两行; 调小后挑封面形状最接近 100% 的行数; 宽高比夹在上下限之间.
 * 数值是 960px 高的网格区, 100% 时封面 260 宽、番名块 80、行距 40、聚焦框空隙两份共 12.
 */
class TvScheduleCoverHeightTest {
    private val gridHeightPx = 960

    private fun coverHeight(scale: Float, referenceRatio: Float?) = tvScheduleCoverHeightPx(
        gridHeightPx,
        coverWidthPx = 260f * scale,
        labelHeightPx = (80 * scale).toInt(),
        rowSpacingPx = (40 * scale).toInt(),
        gapPx = (12 * scale).toInt(),
        referenceRatio = referenceRatio,
    )

    @Test
    fun `full size fills the grid with two rows`() {
        // (960 - 40) / 2 - 80 - 12
        assertEquals(368, coverHeight(1f, referenceRatio = null))
    }

    @Test
    fun `half size keeps the cover shape with four rows`() {
        val reference = 260f / coverHeight(1f, referenceRatio = null)
        val cover = coverHeight(0.5f, reference)
        // (960 - 3 × 20) / 4 - 40 - 6: 四行卡块加三个行距正好铺满
        assertEquals(179, cover)
        assertEquals(gridHeightPx, 4 * (cover + 6 + 40) + 3 * 20)
    }

    @Test
    fun `cards too large for two rows are clamped to the widest cover`() {
        val reference = 260f / coverHeight(1f, referenceRatio = null)
        // 两行放不下: 封面按最宽 3:4 定高 (338 / 0.75), 末行露不全
        assertEquals(451, coverHeight(1.3f, reference))
    }
}
