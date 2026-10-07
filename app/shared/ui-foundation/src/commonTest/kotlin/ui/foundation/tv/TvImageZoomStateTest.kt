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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 长按放大看图 ([TvImageZoomState]) 的一组图: 打开、换图、关闭. */
class TvImageZoomStateTest {
    @Test
    fun `single url opens and closes`() {
        val state = TvImageZoomState()
        state.open("a")
        assertTrue(state.zooming)
        assertEquals("a", state.url)
        state.close()
        assertFalse(state.zooming)
        assertNull(state.url)
    }

    @Test
    fun `blank urls do not open`() {
        val state = TvImageZoomState()
        state.open("")
        state.open(listOf(" ", ""))
        assertFalse(state.zooming)
    }

    @Test
    fun `start index counts only non blank urls before it`() {
        val state = TvImageZoomState()
        state.open(listOf("a", "", "b", "c"), index = 2)
        assertEquals(listOf("a", "b", "c"), state.urls)
        assertEquals("b", state.url)
        // 越界的起始位置收到最后一张
        state.open(listOf("a", "b"), index = 5)
        assertEquals("b", state.url)
    }

    @Test
    fun `step stays within the group`() {
        val state = TvImageZoomState()
        state.open(listOf("a", "b", "c"))
        assertFalse(state.step(-1))
        assertEquals("a", state.url)
        assertTrue(state.step(1))
        assertTrue(state.step(1))
        assertEquals("c", state.url)
        assertFalse(state.step(1))
        assertEquals("c", state.url)
    }
}
