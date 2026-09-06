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
import android.widget.FrameLayout
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusRail
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusRailItem
import me.him188.ani.app.ui.foundation.focus.tvFocusRailKeys
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 横向索引栏 ([tvFocusRailKeys], 追番页的分类标签行) 首项按左: 有左出口时新按下与按住的连发一样走它 (追番页进侧边栏), 没有出口就不动
 * (时间表的日期行). 两种都不交给空间焦点搜索: 行左边摆一个可聚焦的方块代替侧边栏, 出口就是送焦给它; 没有出口时它不该被搜索捞到.
 */
class TvFocusRailLeftEdgeTest {
    private val host = TvNativeTestHost()
    private val outside = FocusRequester()
    private lateinit var focusScope: TvFocusScope
    private var focusedItem by mutableIntStateOf(-1)
    private var outsideFocused by mutableStateOf(false)
    private var exits = 0

    private data class ItemKey(val index: Int) : TvFocusKey

    @BeforeTest
    fun setUp() = host.launch()

    @AfterTest
    fun tearDown() = host.close()

    /** 摆出一行 [ITEMS] 项, [withExit] = 首项按左送焦给左边的方块; 焦点先落在第 [start] 项. */
    private fun show(withExit: Boolean, start: Int) {
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                val scope = rememberTvFocusScope()
                focusScope = scope
                val rail = rememberTvFocusRail(
                    scope = scope,
                    keyAt = { ItemKey(it) },
                    onMove = { runCatching { scope.requesterOf(ItemKey(it)).requestFocus() } },
                )
                Row {
                    Box(Modifier.size(80.dp).focusRequester(outside).onFocusChanged { outsideFocused = it.isFocused }.focusable())
                    Row(
                        Modifier.tvFocusRailKeys(
                            state = rail,
                            itemCount = { ITEMS },
                            onNavigateDown = { true },
                            onLeftEdge = if (withExit) {
                                {
                                    exits++
                                    outside.requestFocus()
                                }
                            } else {
                                null
                            },
                        ),
                    ) {
                        repeat(ITEMS) { i ->
                            Box(
                                Modifier.size(80.dp)
                                    .tvFocusRailItem(rail, i, onFocusChanged = { if (it) focusedItem = i }, onSelectByFocus = {})
                                    .focusable(),
                            )
                        }
                    }
                }
            }
        }
        host.waitUntil("行排出来") { this::focusScope.isInitialized }
        host.onMain { focusScope.requesterOf(ItemKey(start)).requestFocus() }
        host.waitUntil("焦点在第 $start 项") { focusedItem == start }
    }

    @Test
    fun `left at the first item takes the exit`() {
        show(withExit = true, start = 0)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, exits)
        host.waitUntil("焦点到左边的方块") { outsideFocused }
    }

    @Test
    fun `holding left walks to the first item and the next repeat takes the exit`() {
        show(withExit = true, start = 2)
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("到第 1 项") { focusedItem == 1 }
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 1)
        host.waitUntil("到第 0 项") { focusedItem == 0 }
        assertEquals(0, exits)
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 2)
        host.keyUp(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, exits)
        host.waitUntil("焦点到左边的方块") { outsideFocused }
    }

    @Test
    fun `without an exit left at the first item stays put`() {
        show(withExit = false, start = 0)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT)
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFalse(host.onMain { outsideFocused }, "焦点被空间搜索带出了行")
        assertEquals(0, host.onMain { focusedItem })
    }

    private companion object {
        const val ITEMS = 3
    }
}
