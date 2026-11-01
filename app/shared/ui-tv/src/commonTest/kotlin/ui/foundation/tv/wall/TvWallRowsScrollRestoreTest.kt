/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 触屏上返回本页时各行原样恢复 ([TvWallRowsSurface.rowStateFor] / [TvWallRowsSurface.currentRowScroll]): 行停在手指放开处 (可以在两张卡之间),
 * 页面存状态时还没排出来的行 (在屏幕外) 也照存着的位置交回去.
 */
class TvWallRowsScrollRestoreTest {
    private fun surface(restored: Map<String, Pair<Int, Int>>, touch: Boolean = true) = TvWallRowsSurface(
        TvWallHeroTimeline(initial = true), CoroutineScope(Dispatchers.Unconfined), FocusRequester(),
        restoredScrollPx = 0, restoredRowLeft = mapOf("a" to 2, "b" to 1), restoredRowFocused = emptyMap(),
        restoredRowKey = null, restoredIndex = 0, restoredRowScroll = restored,
    ).apply { touchInput = touch }

    @Test
    fun `touch rows come back exactly where the finger left them`() {
        val state = surface(mapOf("a" to (3 to 57))).rowStateFor("a")
        assertEquals(3, state.firstVisibleItemIndex)
        assertEquals(57, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun `rows that have not been laid out yet keep their saved place`() {
        val restored = HashMap(mapOf("a" to (3 to 57), "b" to (5 to 12)))
        val surface = surface(restored)
        surface.rowStateFor("a")
        // 页面存状态时先清空再按此刻的值重填 (TvExplorationNativeState.capture): 还没排出来的 b 不能丢
        restored.clear()
        restored.putAll(surface.currentRowScroll)
        assertEquals(mapOf("a" to (3 to 57), "b" to (5 to 12)), restored)
        assertEquals(5 to 12, surface(restored).rowStateFor("b").let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset })
    }

    @Test
    fun `without touch rows start from their row start`() {
        val state = surface(mapOf("a" to (3 to 57)), touch = false).rowStateFor("a")
        assertEquals(2, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }
}
