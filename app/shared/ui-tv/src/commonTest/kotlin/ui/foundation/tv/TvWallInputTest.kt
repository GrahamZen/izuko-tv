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
    // 3 列 8 张: 0 1 2 / 3 4 5 / 6 7
    private fun key(direction: TvWallDirection, index: Int) = tvWallGridKey(direction, index, count = 8, columns = 3)

    @Test
    fun `up moves one row up and the top row goes to the page`() {
        assertEquals(TvWallGridKey.MoveTo(1), key(TvWallDirection.Up, 4))
        assertEquals(TvWallGridKey.TopRowUp, key(TvWallDirection.Up, 2))
    }

    @Test
    fun `down keeps the column or lands on the last card and the last row swallows`() {
        assertEquals(TvWallGridKey.MoveTo(4), key(TvWallDirection.Down, 1))
        assertEquals(TvWallGridKey.MoveTo(7), key(TvWallDirection.Down, 5))
        assertEquals(TvWallGridKey.Swallow, key(TvWallDirection.Down, 6))
        assertEquals(TvWallGridKey.Swallow, key(TvWallDirection.Down, 7))
    }

    @Test
    fun `left and right move within the row and hand row edges to the page`() {
        assertEquals(TvWallGridKey.MoveTo(3), key(TvWallDirection.Left, 4))
        assertEquals(TvWallGridKey.MoveTo(5), key(TvWallDirection.Right, 4))
        assertEquals(TvWallGridKey.RowEdge(-1, 1), key(TvWallDirection.Left, 3))
        assertEquals(TvWallGridKey.RowEdge(1, 1), key(TvWallDirection.Right, 5))
        // 末行没排满: 最后一张按右也是行尾
        assertEquals(TvWallGridKey.RowEdge(1, 2), key(TvWallDirection.Right, 7))
    }

    @Test
    fun `unhandled row edges swallow right and release left`() {
        assertTrue(TvWallGridKey.RowEdge(1, 0).swallowWhenUnhandled)
        assertFalse(TvWallGridKey.RowEdge(-1, 0).swallowWhenUnhandled)
    }

    @Test
    fun `zero columns count as one`() {
        assertEquals(TvWallGridKey.MoveTo(2), tvWallGridKey(TvWallDirection.Down, 1, count = 3, columns = 0))
    }

    @Test
    fun `confirm enters hero first then opens`() {
        assertEquals(TvWallConfirm.EnterHero, tvWallConfirm(isMore = false, heroEnabled = true, heroActive = false))
        assertEquals(TvWallConfirm.Open, tvWallConfirm(isMore = false, heroEnabled = true, heroActive = true))
        assertEquals(TvWallConfirm.Open, tvWallConfirm(isMore = false, heroEnabled = false, heroActive = false))
        assertEquals(TvWallConfirm.More, tvWallConfirm(isMore = true, heroEnabled = true, heroActive = false))
    }

    @Test
    fun `a tap also confirms only when it enters hero`() {
        assertTrue(tvWallTapConfirmsWhileFocusing(isMore = false, heroEnabled = true, heroActive = false))
        assertFalse(tvWallTapConfirmsWhileFocusing(isMore = false, heroEnabled = true, heroActive = true))
        assertFalse(tvWallTapConfirmsWhileFocusing(isMore = false, heroEnabled = false, heroActive = false))
        assertFalse(tvWallTapConfirmsWhileFocusing(isMore = true, heroEnabled = true, heroActive = false))
    }

    @Test
    fun `a touch tap on the wall enters hero and the more card or a page without hero confirms at once`() {
        assertEquals(TvWallTouchTap.FocusAndConfirm, tap(isFocused = false))
        assertEquals(TvWallTouchTap.FocusAndConfirm, tap(isFocused = true))
        assertEquals(TvWallTouchTap.Confirm, tap(isMore = true))
        assertEquals(TvWallTouchTap.Confirm, tap(heroEnabled = false))
        // 卡片墙上不看行: 没有聚焦行
        assertEquals(TvWallTouchTap.FocusAndConfirm, tap(aboveFocusedRow = true))
    }

    @Test
    fun `a touch tap in hero switches focus first and rows above the focused one open it`() {
        assertEquals(TvWallTouchTap.FocusOnly, tap(heroActive = true, isFocused = false))
        assertEquals(TvWallTouchTap.FocusOnly, tap(heroActive = true, isMore = true))
        assertEquals(TvWallTouchTap.Confirm, tap(heroActive = true, isFocused = true))
        assertEquals(TvWallTouchTap.OpenFocused, tap(heroActive = true, aboveFocusedRow = true))
    }

    private fun tap(
        isMore: Boolean = false,
        heroEnabled: Boolean = true,
        heroActive: Boolean = false,
        isFocused: Boolean = false,
        aboveFocusedRow: Boolean = false,
    ) = tvWallTouchTap(isMore, heroEnabled, heroActive, isFocused, aboveFocusedRow)

    @Test
    fun `swipe is claimed only when horizontal movement passes the slop first`() {
        assertEquals(TvWallSwipeClaim.Undecided, tvWallSwipeClaim(dx = 5f, dy = 2f, slop = 8f))
        assertEquals(TvWallSwipeClaim.Claim, tvWallSwipeClaim(dx = -12f, dy = 4f, slop = 8f))
        assertEquals(TvWallSwipeClaim.Release, tvWallSwipeClaim(dx = 4f, dy = -12f, slop = 8f))
        // 横竖一样多: 横向不占优, 竖向过了 slop 就放手
        assertEquals(TvWallSwipeClaim.Release, tvWallSwipeClaim(dx = 12f, dy = 12f, slop = 8f))
    }

    @Test
    fun `swipe steps by distance or by velocity in the same direction`() {
        val density = 2f
        // 64dp = 128px
        assertEquals(1, tvWallSwipeStep(dx = -130f, velocityX = 0f, density = density))
        assertEquals(-1, tvWallSwipeStep(dx = 130f, velocityX = 0f, density = density))
        assertEquals(0, tvWallSwipeStep(dx = -100f, velocityX = 0f, density = density))
        // 400dp/s = 800px/s
        assertEquals(1, tvWallSwipeStep(dx = -20f, velocityX = -900f, density = density))
        // 速度与位移反向不算
        assertEquals(0, tvWallSwipeStep(dx = -20f, velocityX = 900f, density = density))
    }

    @Test
    fun `repeat gate limits held keys but never the first press`() {
        val gate = TvKeyRepeatGate(intervalMillis = 40)
        assertTrue(gate.pass(repeatCount = 0, nowMillis = 1000))
        assertTrue(gate.pass(repeatCount = 1, nowMillis = 1000))
        assertFalse(gate.pass(repeatCount = 2, nowMillis = 1030))
        assertTrue(gate.pass(repeatCount = 3, nowMillis = 1040))
        // 新按下不受限, 也不改记下的时刻
        assertTrue(gate.pass(repeatCount = 0, nowMillis = 1045))
        assertFalse(gate.pass(repeatCount = 1, nowMillis = 1060))
        assertTrue(gate.pass(repeatCount = 1, nowMillis = 1080))
    }

    @Test
    fun `repeat gate follows a changed interval`() {
        val gate = TvKeyRepeatGate(intervalMillis = 40)
        assertTrue(gate.pass(repeatCount = 1, nowMillis = 1000))
        gate.intervalMillis = 100
        assertFalse(gate.pass(repeatCount = 1, nowMillis = 1050))
        assertTrue(gate.pass(repeatCount = 1, nowMillis = 1100))
    }
}
