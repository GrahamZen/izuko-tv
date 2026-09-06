/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/** 长按卡片的菜单摆在封面旁边 ([TvBesideAnchorPositionProvider]): 1920 × 1080 窗口, 菜单 400 × 500, 空隙 40, 离窗口边至少 48. */
class TvBesideAnchorPositionProviderTest {
    private val provider = TvBesideAnchorPositionProvider(gapPx = 40, marginPx = 48)
    private val window = IntSize(1920, 1080)
    private val menu = IntSize(400, 500)

    private fun place(anchor: IntRect): IntOffset = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, menu)

    @Test
    fun `right of the cover when there is room`() {
        assertEquals(IntOffset(640, 200), place(IntRect(300, 200, 600, 650)))
    }

    @Test
    fun `left of the cover when the right side is too narrow`() {
        assertEquals(IntOffset(1000, 200), place(IntRect(1440, 200, 1740, 650)))
    }

    @Test
    fun `never overlaps the cover horizontally`() {
        for (left in 0..1620 step 60) {
            val anchor = IntRect(left, 200, left + 300, 650)
            val x = place(anchor).x
            val overlaps = x < anchor.right && x + menu.width > anchor.left
            if (x + menu.width <= window.width && x >= 0 && (anchor.left >= menu.width + 40 + 48 || anchor.right + 40 + menu.width + 48 <= window.width)) {
                assertEquals(false, overlaps, "封面在 $left 时菜单压住了封面")
            }
        }
    }

    @Test
    fun `the menu grows out of the side next to the cover`() {
        val cover = IntRect(300, 200, 600, 650)
        // 在封面右边: 从左缘长出来; 在左边: 从右缘. 竖向与封面重叠的一段取中点
        assertEquals(TransformOrigin(0f, 0.45f), tvMenuTransformOrigin(cover, IntRect(IntOffset(640, 200), menu)))
        assertEquals(TransformOrigin(1f, 0.45f), tvMenuTransformOrigin(cover, IntRect(IntOffset(-140, 200), menu)))
    }

    @Test
    fun `top aligned with the cover and moved up when it would leave the window`() {
        assertEquals(200, place(IntRect(300, 200, 600, 650)).y)
        // 封面靠下: 菜单底边贴到离窗口底 48 的线上
        assertEquals(1080 - 48 - 500, place(IntRect(300, 800, 600, 1250)).y)
    }
}
