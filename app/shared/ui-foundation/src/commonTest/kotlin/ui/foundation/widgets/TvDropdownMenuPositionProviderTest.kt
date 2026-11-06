/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/** 原生菜单没给定位器时的摆法, 同 M3 下拉菜单. */
class TvDropdownMenuPositionProviderTest {
    private val window = IntSize(1920, 1080)
    private val provider = TvDropdownMenuPositionProvider(offsetXPx = 0, offsetYPx = 0, verticalMarginPx = 96)

    private fun position(anchor: IntRect, menu: IntSize, direction: LayoutDirection = LayoutDirection.Ltr): IntOffset =
        provider.calculatePosition(anchor, window, direction, menu)

    @Test
    fun `below the anchor and aligned to its start when there is room`() {
        assertEquals(IntOffset(100, 150), position(IntRect(100, 100, 300, 150), IntSize(240, 300)))
    }

    @Test
    fun `above the anchor when the space below is not enough`() {
        // 播放器底栏的按钮: 下面放不下, 菜单底边贴着按钮顶边
        assertEquals(IntOffset(1100, 600), position(IntRect(1100, 900, 1200, 950), IntSize(240, 300)))
    }

    @Test
    fun `aligned to the anchor end near the right edge`() {
        assertEquals(IntOffset(1600, 150), position(IntRect(1800, 100, 1900, 150), IntSize(300, 300)))
    }

    @Test
    fun `a menu taller than both sides keeps the window margins`() {
        // 字幕几十条: 上下都放不下, 也跨不过锚点顶边居中 ⇒ 贴着离锚点近的那一边的窗口边 (留出边距)
        val y = position(IntRect(1100, 900, 1200, 950), IntSize(240, 888)).y
        assertEquals(1080 - 888 - 96, y)
    }

    @Test
    fun `right to left aligns the menu to the anchor right edge`() {
        assertEquals(IntOffset(60, 150), position(IntRect(100, 100, 300, 150), IntSize(240, 300), LayoutDirection.Rtl))
    }
}
