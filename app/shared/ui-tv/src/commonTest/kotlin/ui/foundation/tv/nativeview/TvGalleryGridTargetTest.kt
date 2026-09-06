/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 画廊网格的方向键 ([tvGalleryGridTarget]): 一行 6 格、14 格 (末行 2 格). 焦点不出网格, 下一行没有同一列时落到最后一格.
 */
class TvGalleryGridTargetTest {
    private val columns = 6
    private val count = 14

    private fun target(index: Int, keyCode: Int) = tvGalleryGridTarget(index, count, columns, keyCode)

    @Test
    fun `left and right stay within the row`() {
        assertEquals(1, target(0, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(target(0, KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(target(5, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(6, target(7, KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(target(6, KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(target(13, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `up and down keep the column`() {
        assertEquals(8, target(2, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(2, target(8, KeyEvent.KEYCODE_DPAD_UP))
        assertNull(target(3, KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `down onto a shorter last row lands on the last cell`() {
        assertEquals(13, target(10, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(12, target(6, KeyEvent.KEYCODE_DPAD_DOWN))
        assertNull(target(12, KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `out of range index or empty grid stays`() {
        assertNull(tvGalleryGridTarget(0, 0, columns, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(tvGalleryGridTarget(20, count, columns, KeyEvent.KEYCODE_DPAD_UP))
    }
}
