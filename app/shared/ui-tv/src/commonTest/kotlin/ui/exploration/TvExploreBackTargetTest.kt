/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 探索页卡片区的返回键分层 ([tvExploreBackTarget], 照 Prime Video): 本行首卡 → hero 主按钮. */
class TvExploreBackTargetTest {
    @Test
    fun `not at the row start goes back to the start of the same row`() {
        assertEquals("rec-3", tvExploreBackTarget("rec-3", cardIndex = 5))
        assertEquals("followed", tvExploreBackTarget("followed", cardIndex = 2))
    }

    @Test
    fun `the start of any row goes to the hero button`() {
        assertNull(tvExploreBackTarget("rec-3", cardIndex = 0))
        assertNull(tvExploreBackTarget("followed", cardIndex = 0))
    }

    @Test
    fun `focus outside the cards goes to the hero button`() {
        assertNull(tvExploreBackTarget(null, cardIndex = 0))
    }
}
