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

class TvNativeCardsTailTest {
    private fun cards(vararg ids: Int): List<TvNativeCard?> = ids.map { TvNativeCard(imageUrl = null, title = "", subjectId = it) }

    @Test
    fun `new cards replace the trailing placeholders`() {
        val old = cards(1, 2, 3) + listOf(null, null)
        val new = cards(1, 2, 3, 4, 5, 6) + listOf(null, null)
        assertEquals(listOf(3..4), tvNativeCardsChangedRanges(old, new))
    }

    @Test
    fun `placeholders removed when the group is exhausted`() {
        assertEquals(emptyList(), tvNativeCardsChangedRanges(cards(1, 2, 3) + listOf(null, null), cards(1, 2, 3)))
    }

    @Test
    fun `sequel swaps change single positions`() {
        assertEquals(listOf(1..1, 3..4), tvNativeCardsChangedRanges(cards(1, 2, 3, 4, 5, 6), cards(1, 9, 3, 8, 7, 6)))
    }

    @Test
    fun `nothing changed`() {
        assertEquals(emptyList(), tvNativeCardsChangedRanges(cards(1, 2, 3), cards(1, 2, 3)))
    }

    @Test
    fun `everything changed`() {
        assertEquals(listOf(0..2), tvNativeCardsChangedRanges(cards(1, 2, 3), cards(4, 5, 6, 7)))
    }
}
