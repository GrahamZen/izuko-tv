/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TvFlowFocusState]: 几节折行排布的项放在一个定高的滚动框里, 上下键按坐标落到相邻一行, 按住时一路走到底不停, 列表跟着把焦点所在的行滚进视口.
 *
 * 每节 [PER_SECTION] 项, 宽度轮流 [WIDTHS], 框宽 [VIEWPORT_WIDTH] 放得下三四项一行; 共 [SECTIONS] 节, 远高于视口.
 */
class TvFlowFocusTest {
    private lateinit var state: TvFlowFocusState
    private lateinit var scroll: ScrollState
    private val first = FocusRequester()
    private val third = FocusRequester()

    private fun key(section: Int, index: Int) = "$section-$index"

    @OptIn(ExperimentalFoundationApi::class)
    private fun AniComposeUiTest.show() {
        setContent {
            state = rememberTvFlowFocusState()
            scroll = rememberScrollState()
            TvFlowFocusScrollEffect(state, scroll)
            CompositionLocalProvider(LocalBringIntoViewSpec provides TvNoBringIntoViewSpec) {
                Box(
                    Modifier.width(VIEWPORT_WIDTH).height(VIEWPORT_HEIGHT)
                        .tvFlowFocusViewport(state)
                        .verticalScroll(scroll)
                        .tvFlowFocusContent(state),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        repeat(SECTIONS) { section ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 分节标题 (不可聚焦)
                                Box(Modifier.size(120.dp, 20.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    repeat(PER_SECTION) { i ->
                                        Box(
                                            Modifier.size(WIDTHS[i % WIDTHS.size], 36.dp)
                                                .then(
                                                    when {
                                                        section == 0 && i == 0 -> Modifier.focusRequester(first)
                                                        section == 0 && i == 2 -> Modifier.focusRequester(third)
                                                        else -> Modifier
                                                    },
                                                )
                                                .tvFlowFocusItem(state, key(section, i))
                                                .focusable()
                                                .testTag(key(section, i)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        runOnIdle { first.requestFocus() }
        waitForIdle()
    }

    @Test
    fun `holding down walks every row to the last one`() = runAniComposeUiTest {
        show()
        runOnIdle { assertEquals(key(0, 0), state.focusedKey) }
        onRoot().performKeyInput {
            keyDown(Key.DirectionDown)
            advanceEventTime(4_000)
            keyUp(Key.DirectionDown)
        }
        waitForIdle()
        runOnIdle {
            val focused = state.focusedKey as String
            val lastSectionBounds = (0 until PER_SECTION).mapNotNull { state.boundsOf(key(SECTIONS - 1, it)) }
            val lastRowTop = lastSectionBounds.maxOf { it.top }
            val bounds = state.boundsOf(focused)!!
            assertEquals(lastRowTop, bounds.top, "按住下键应走到最后一行, 停在 $focused")
            // 列表跟着滚: 焦点那一项在视口里
            val top = bounds.top - scroll.value
            assertTrue(top >= 0f && bounds.bottom - scroll.value <= scroll.viewportSize, "焦点项不在视口里: top=$top scroll=${scroll.value}")
        }
    }

    @Test
    fun `vertical moves keep the column they started from`() = runAniComposeUiTest {
        show()
        // 从第一行的第三项起步, 一路按下: 每一行落在离它中线最近的那一项
        runOnIdle { third.requestFocus() }
        waitForIdle()
        val startX = runOnIdle { state.boundsOf(state.focusedKey!!)!!.center.x }
        repeat(6) {
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            runOnIdle {
                val focused = state.focusedKey!!
                val bounds = state.boundsOf(focused)!!
                val rowItems = (0 until SECTIONS).flatMap { s -> (0 until PER_SECTION).map { key(s, it) } }
                    .mapNotNull { k -> state.boundsOf(k)?.takeIf { it.top == bounds.top }?.let { k to it } }
                val nearest = rowItems.minBy { abs(it.second.center.x - startX) }.first
                assertEquals(nearest, focused, "第 ${it + 1} 次下键应落在离起步列最近的那一项")
            }
        }
    }

    private companion object {
        const val SECTIONS = 6
        const val PER_SECTION = 11
        val WIDTHS = listOf(80.dp, 120.dp, 64.dp, 140.dp, 96.dp)
        val VIEWPORT_WIDTH = 420.dp
        val VIEWPORT_HEIGHT = 300.dp
    }
}
