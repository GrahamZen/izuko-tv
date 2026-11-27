/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.session

import kotlin.test.Test
import kotlin.test.assertEquals

class TvRailBadgeTextTest {
    @Test
    fun `a count within the limit is written as it is`() {
        assertEquals("1", tvRailBadgeText(1, TV_RAIL_BADGE_MAX))
        assertEquals("9", tvRailBadgeText(9, TV_RAIL_BADGE_MAX))
        assertEquals("23", tvRailBadgeText(23, 99))
        assertEquals("99", tvRailBadgeText(99, 99))
    }

    @Test
    fun `a count over the limit is written as the limit with a plus`() {
        // 默认至多一位数: 10 条以上写「9+」; 至多两位数时 100 以上写「99+」
        assertEquals("9+", tvRailBadgeText(10, TV_RAIL_BADGE_MAX))
        assertEquals("9+", tvRailBadgeText(120, TV_RAIL_BADGE_MAX))
        assertEquals("99+", tvRailBadgeText(100, 99))
    }
}
