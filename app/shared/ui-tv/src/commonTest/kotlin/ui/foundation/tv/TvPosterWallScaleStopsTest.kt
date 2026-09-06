/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [tvPosterWallScaleStops] / [tvPosterWallScaleStep]: 「海报墙大小」滑块一档一种每排张数, 同一列数里取离 100% 最近的那一格;
 * 走一档必然换列数. 几何按 1080p 电视 (960dp 宽、density 2): 卡片区 = 960 − 侧边栏 48 − 左留白 16 − 右边距 48 = 848dp.
 */
class TvPosterWallScaleStopsTest {
    private val density = Density(2f)
    private val width = 848.dp

    private fun columnsAt(percent: Int) = with(density) { tvPosterWallGrid(width, percent / 100f).columns }

    private val stops = with(density) { tvPosterWallScaleStops(width, minPercent = 50, maxPercent = 150, stepPercent = 5) }

    @Test
    fun `one stop per column count - the one closest to 100 percent`() {
        // 12 / 11 / 10 / 9 / 8 / 7 / 6 / 5 / 4 张一排
        assertEquals(listOf(50, 55, 60, 65, 75, 85, 100, 105, 130), stops)
        assertEquals(listOf(12, 11, 10, 9, 8, 7, 6, 5, 4), stops.map(::columnsAt))
    }

    @Test
    fun `every step changes the column count`() {
        with(density) {
            assertEquals(105, tvPosterWallScaleStep(width, stops, currentPercent = 100, delta = 1))
            assertEquals(85, tvPosterWallScaleStep(width, stops, currentPercent = 100, delta = -1))
            // 以前按 5% 一格存下的值不在档位上: 按它此刻的列数 (80% = 7 张) 往两边走
            assertEquals(100, tvPosterWallScaleStep(width, stops, currentPercent = 80, delta = 1))
            assertEquals(75, tvPosterWallScaleStep(width, stops, currentPercent = 80, delta = -1))
        }
    }

    @Test
    fun `no step past either end`() {
        with(density) {
            assertNull(tvPosterWallScaleStep(width, stops, currentPercent = 130, delta = 1))
            assertNull(tvPosterWallScaleStep(width, stops, currentPercent = 150, delta = 1))
            assertNull(tvPosterWallScaleStep(width, stops, currentPercent = 50, delta = -1))
        }
    }
}
