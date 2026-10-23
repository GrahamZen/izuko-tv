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
 * 评论列表按需挪 ([tvRevealScrollAmount]): 可见区 0~1000, 上下各留 40.
 */
class TvRevealScrollAmountTest {
    private fun amount(top: Int, bottom: Int) = tvRevealScrollAmount(top, bottom, 0, 1000, 40)

    @Test
    fun `an item already inside with the margin stays`() {
        assertEquals(0, amount(40, 960))
        assertEquals(0, amount(300, 500))
    }

    @Test
    fun `an item below scrolls just enough to show it whole`() {
        // 露出一半
        assertEquals(240, amount(900, 1200))
        // 整条在下面很远: 也只挪到刚好露出来, 不按露出来的那一块算
        assertEquals(1440, amount(2200, 2400))
    }

    @Test
    fun `an item above scrolls back just enough`() {
        assertEquals(-140, amount(-100, 200))
        assertEquals(-2040, amount(-2000, -1800))
    }

    @Test
    fun `an item taller than the viewport aligns its top`() {
        assertEquals(460, amount(500, 2000))
        assertEquals(-140, amount(-100, 1500))
    }
}
