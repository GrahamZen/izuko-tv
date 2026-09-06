/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 缩回整屏底色 ([TvHeroZoomHandoff.Shrink.scrimAlpha]): 列表页还在组合里时跟着运动在尾段化开 (下面的列表页接着画);
 * 列表页要在层下重建时 ([TvHeroZoomHandoff.Shrink.holdScrim]) 运动途中下面没有页面, 底色一直盖着, 落地后才化开 —— 否则露出来的是黑的.
 */
class TvHeroShrinkScrimTest {
    private fun shrink() = TvHeroZoomHandoff.Shrink(
        subjectId = 1, url = "u", startUrl = "u", bounds = Rect.Zero, dim = Color.Transparent,
        treatment = null, fromSession = null, entryKey = null, onPop = {},
    )

    @Test
    fun `scrim fades with the motion when the list page is still composed`() {
        val s = shrink()
        TvHeroZoomHandoff.armShrink(s)
        assertFalse(s.holdScrim)
        s.linear = 0f
        assertEquals(1f, s.scrimAlpha)
        s.linear = 1f
        assertEquals(0f, s.scrimAlpha)
    }

    @Test
    fun `scrim stays opaque through the motion when the list page is rebuilt under the layer`() {
        val s = shrink()
        TvHeroZoomHandoff.armShrink(s, rebuildList = true)
        assertTrue(s.holdScrim)
        s.linear = 0.7f
        assertEquals(1f, s.scrimAlpha)
        s.linear = 1f
        assertEquals(1f, s.scrimAlpha)
    }

    @Test
    fun `scrim clears after landing as the reveal progresses`() {
        val s = shrink()
        TvHeroZoomHandoff.armShrink(s, rebuildList = true)
        s.linear = 1f
        s.landedReveal = 0.5f
        assertEquals(0.5f, s.scrimAlpha)
        s.landedReveal = 1f
        assertEquals(0f, s.scrimAlpha)
    }
}
