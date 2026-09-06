/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 网格海报墙的停位 ([TvNativeGridStops]): 卡片墙上聚焦行停在视口正中, 开头几行不滚, 末尾停在末行底边离视口底留一截处;
 * hero 态行顶对准 hero 线, 滚不到的那截由整片平移补.
 *
 * 数字取 dp 量级 (当像素用): 行高 215 (海报 173 + 番名 42), 行距 20 → 行间隔 235, 视口 450, 末尾留 40, hero 线 250.
 */
class TvNativeGridStopsTest {
    private fun stops(rowCount: Int = 10, viewportPx: Int = 450) = TvNativeGridStops(
        rowCount = rowCount,
        pitchPx = 235,
        rowHeightPx = 215,
        viewportPx = viewportPx,
        endMarginPx = 40,
        heroLinePx = 250,
    )

    @Test
    fun `first row does not scroll`() {
        assertEquals(0, stops().scrollFor(0, hero = false))
    }

    @Test
    fun `rows past the middle are centered`() {
        assertEquals(235 + 107 - 225, stops().scrollFor(1, hero = false))
        assertEquals(4 * 235 + 107 - 225, stops().scrollFor(4, hero = false))
    }

    @Test
    fun `last rows stop at the end margin`() {
        // 上限 = 9 × 235 + 215 + 40 − 450 = 1920 (末行居中要 1997); 倒数第二行居中 1762 还在上限内
        assertEquals(1920, stops().scrollFor(9, hero = false))
        assertEquals(8 * 235 + 107 - 225, stops().scrollFor(8, hero = false))
    }

    @Test
    fun `content shorter than the viewport never scrolls`() {
        assertEquals(0, stops(rowCount = 1).scrollFor(0, hero = false))
        // 两行: 上限 235 + 215 + 40 − 450 = 40
        assertEquals(40, stops(rowCount = 2).scrollFor(1, hero = false))
    }

    @Test
    fun `hero stop puts the focused row at the hero line`() {
        // 第 4 行 (顶 940) 滚到 690, 不用平移
        assertEquals(4 * 235 - 250, stops().scrollFor(4, hero = true))
        assertEquals(0, stops().heroShiftFor(4))
    }

    @Test
    fun `hero stop shifts the grid where scrolling cannot reach`() {
        // 首行滚不到负数: 整片往下推 250
        assertEquals(0, stops().scrollFor(0, hero = true))
        assertEquals(250, stops().heroShiftFor(0))
        // 只有三行、视口 800: 滚不动 (上限 470 + 215 + 40 − 800 < 0), 末行要 220 全靠往上平移
        val short = stops(rowCount = 3, viewportPx = 800)
        assertEquals(0, short.scrollFor(2, hero = true))
        assertEquals(-220, short.heroShiftFor(2))
    }
}
