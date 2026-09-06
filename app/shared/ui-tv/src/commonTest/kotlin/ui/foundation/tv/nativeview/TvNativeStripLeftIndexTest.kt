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

/** 海报墙横滑行的按需挪 ([tvStripLeftIndex]): 探索页的行与详情页的关联条目共用. */
class TvNativeStripLeftIndexTest {
    @Test
    fun `strip scrolls only when the focus reaches a card that is not fully visible`() {
        // 一行 12 张、屏上完整放得下 6 张: 在屏上几张之间走不挪
        assertEquals(0, tvStripLeftIndex(current = 0, target = 5, count = 12, columns = 6))
        // 走到右边露一截的第 7 张: 整行挪一格, 它刚好完整露出 (贴右)
        assertEquals(1, tvStripLeftIndex(current = 0, target = 6, count = 12, columns = 6))
        assertEquals(6, tvStripLeftIndex(current = 5, target = 11, count = 12, columns = 6))
        // 往左在屏上几张之间走不挪, 走出左边才挪 (贴左)
        assertEquals(3, tvStripLeftIndex(current = 3, target = 4, count = 12, columns = 6))
        assertEquals(2, tvStripLeftIndex(current = 3, target = 2, count = 12, columns = 6))
    }

    @Test
    fun `strip left index is clamped to the row`() {
        // 一屏放得下整行: 永远不挪
        assertEquals(0, tvStripLeftIndex(current = 0, target = 3, count = 4, columns = 6))
        // 行首不越过"末张贴右"的位置
        assertEquals(6, tvStripLeftIndex(current = 10, target = 8, count = 12, columns = 6))
    }
}
