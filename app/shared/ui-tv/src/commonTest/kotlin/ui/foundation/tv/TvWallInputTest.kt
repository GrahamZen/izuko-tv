/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvWallInputTest {
    @Test
    fun `confirm enters hero first then opens`() {
        assertEquals(TvWallConfirm.EnterHero, tvWallConfirm(isPlaceholder = false, heroEnabled = true, heroActive = false))
        assertEquals(TvWallConfirm.Open, tvWallConfirm(isPlaceholder = false, heroEnabled = true, heroActive = true))
        assertEquals(TvWallConfirm.Open, tvWallConfirm(isPlaceholder = false, heroEnabled = false, heroActive = false))
        assertEquals(TvWallConfirm.Ignore, tvWallConfirm(isPlaceholder = true, heroEnabled = true, heroActive = false))
    }

    @Test
    fun `a tap also confirms only when it enters hero`() {
        assertTrue(tvWallTapConfirmsWhileFocusing(isPlaceholder = false, heroEnabled = true, heroActive = false))
        assertFalse(tvWallTapConfirmsWhileFocusing(isPlaceholder = false, heroEnabled = true, heroActive = true))
        assertFalse(tvWallTapConfirmsWhileFocusing(isPlaceholder = false, heroEnabled = false, heroActive = false))
        assertFalse(tvWallTapConfirmsWhileFocusing(isPlaceholder = true, heroEnabled = true, heroActive = false))
    }

    @Test
    fun `a touch tap on the wall enters hero and a placeholder or a page without hero confirms at once`() {
        assertEquals(TvWallTouchTap.FocusAndConfirm, tap(isFocused = false))
        assertEquals(TvWallTouchTap.FocusAndConfirm, tap(isFocused = true))
        assertEquals(TvWallTouchTap.Confirm, tap(isPlaceholder = true))
        assertEquals(TvWallTouchTap.Confirm, tap(heroEnabled = false))
        // 卡片墙上不看行: 没有聚焦行
        assertEquals(TvWallTouchTap.FocusAndConfirm, tap(aboveFocusedRow = true))
    }

    @Test
    fun `a touch tap in hero switches focus first and rows above the focused one open it`() {
        assertEquals(TvWallTouchTap.FocusOnly, tap(heroActive = true, isFocused = false))
        assertEquals(TvWallTouchTap.FocusOnly, tap(heroActive = true, isPlaceholder = true))
        assertEquals(TvWallTouchTap.Confirm, tap(heroActive = true, isFocused = true))
        assertEquals(TvWallTouchTap.OpenFocused, tap(heroActive = true, aboveFocusedRow = true))
    }

    private fun tap(
        isPlaceholder: Boolean = false,
        heroEnabled: Boolean = true,
        heroActive: Boolean = false,
        isFocused: Boolean = false,
        aboveFocusedRow: Boolean = false,
    ) = tvWallTouchTap(isPlaceholder, heroEnabled, heroActive, isFocused, aboveFocusedRow)

    @Test
    fun `a tap goes to the card while scrolling slowly and only stops a fast scroll`() {
        assertTrue(tvWallTapPassesScroll(0f))
        assertTrue(tvWallTapPassesScroll(TV_WALL_TAP_THROUGH_MAX_SPEED_DP - 1f))
        assertTrue(tvWallTapPassesScroll(-(TV_WALL_TAP_THROUGH_MAX_SPEED_DP - 1f)))
        assertFalse(tvWallTapPassesScroll(TV_WALL_TAP_THROUGH_MAX_SPEED_DP))
        assertFalse(tvWallTapPassesScroll(-2000f))
    }
}
