/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.him188.ani.app.data.models.preference.TvCardFocusStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TvGridFocusSlot] 的状态规则. 走不带过渡的路径 (流畅档): Unconfined 调度下 snapTo 当场生效, 不需要帧时钟.
 * 按位置算权重那部分依赖网格实测布局, 只能真机看.
 */
class TvGridFocusSlotTest {
    private fun slot(style: TvCardFocusStyle = TvCardFocusStyle.ScaleAndRing) = TvGridFocusSlot().apply {
        scope = CoroutineScope(Dispatchers.Unconfined)
        animated = false
        this.style = style
    }

    @Test
    fun `shown while grid has focus`() {
        val slot = slot()
        slot.setGridFocused(true)
        assertEquals(1f, slot.presence.value)
        slot.setGridFocused(false)
        assertEquals(0f, slot.presence.value)
    }

    @Test
    fun `stays shown while long-press menu is open`() {
        val slot = slot()
        slot.setGridFocused(true)
        slot.setHeld(true)
        // 焦点进了菜单
        slot.setGridFocused(false)
        assertEquals(1f, slot.presence.value)
        // 菜单关了, 焦点没回网格
        slot.setHeld(false)
        assertEquals(0f, slot.presence.value)
    }

    @Test
    fun `moves to the focused cell`() {
        val slot = slot()
        slot.moveTo(column = 3, row = 1)
        assertEquals(listOf(Triple(3, 1, 1f)), slot.cells.map { Triple(it.column, it.row, it.fade.value) })
    }

    @Test
    fun `changing cell without transitions replaces it outright`() {
        val slot = slot()
        slot.setGridFocused(true)
        slot.moveTo(column = 0, row = 0)
        slot.moveTo(column = 1, row = 0)
        assertEquals(listOf(Triple(1, 0, 1f)), slot.cells.map { Triple(it.column, it.row, it.fade.value) })
    }

    @Test
    fun `outline-only style never scales`() {
        val slot = slot(TvCardFocusStyle.Ring)
        slot.setGridFocused(true)
        assertEquals(1f, slot.focusScale)
        assertEquals(1f, slot.scaleOf(LazyGridState(), 0, 0f))
    }

    @Test
    fun `nothing is scaled when the item is not laid out`() {
        val slot = slot()
        slot.setGridFocused(true)
        assertEquals(1f, slot.scaleOf(LazyGridState(), 0, 0f))
    }
}
