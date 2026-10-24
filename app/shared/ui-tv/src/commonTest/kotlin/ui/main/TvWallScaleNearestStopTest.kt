/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals

class TvWallScaleNearestStopTest {
    private val stops = listOf(55, 70, 85, 100, 120, 150)

    @Test
    fun `picks the closest stop to the finger`() {
        assertEquals(100, tvWallScaleNearestStop(stops, 104f))
        assertEquals(120, tvWallScaleNearestStop(stops, 113f))
        assertEquals(55, tvWallScaleNearestStop(stops, 50f))
        assertEquals(150, tvWallScaleNearestStop(stops, 150f))
    }

    @Test
    fun `rounds when there are no stops`() {
        assertEquals(97, tvWallScaleNearestStop(emptyList(), 96.6f))
    }
}
