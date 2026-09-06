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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 装在 Compose 里的圆头像行 ([TvNativeMonogramStrip], 详情页人物页): 数据在路上时焦点停在它的占位格上, 数据到了原地换 ——
 * 焦点还在这一行, 没有被系统改派到同一窗口里下方的原生行 (详情页末页的关联条目). 持焦的节点被拆掉时系统会按屏幕位置把焦点塞给
 * 窗口里第一个可聚焦的视图, 不经页面的补救 (模拟器实测落进关联条目).
 */
class TvNativeMonogramStripSwapTest {
    private val host = TvNativeTestHost()
    private val requester = FocusRequester()
    private var items by mutableStateOf<List<TvNativeMonogram?>?>(null)
    private var stripFocused by mutableStateOf(false)
    private var downSeen = 0
    private lateinit var below: TvNativeRowView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            below = TvNativeRowView(host.activity, testWallStyle(), host.sketch, pool = null, startPx = 128, endPx = 48, fadeDistancePx = 128f)
            below.animatedScroll = false
            below.bind(testCards(12), { it.toLong() }, leftIndex = 0, columns = 6)
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(
                    LocalSketch provides host.sketch,
                    LocalThemeSettings provides ThemeSettings.Default,
                ) {
                    // 同详情页的 PageSection: 行不接的上下键冒泡到这里, 由页面消费 (交给路由)
                    Column(
                        Modifier.onKeyEvent { event ->
                            if (event.key != Key.DirectionDown) return@onKeyEvent false
                            if (event.type == KeyEventType.KeyDown) downSeen++
                            true
                        },
                    ) {
                        TvNativeMonogramStrip(
                            items = items,
                            style = testMonogramStyle(),
                            onClick = {},
                            onLongPress = {},
                            repeatMillis = 40,
                            modifier = Modifier
                                .focusRequester(requester)
                                .onFocusChanged { stripFocused = it.hasFocus }
                                .focusGroup(),
                            startPadding = 40.dp,
                            endPadding = 40.dp,
                        )
                        AndroidView(factory = { below }, modifier = Modifier.fillMaxWidth().height(260.dp))
                    }
                }
            }
        }
        host.waitUntil("下方原生行排出来") { below.childCount > 0 }
        host.onMain { requester.requestFocus() }
        host.waitUntil("焦点进了占位的圆头像行") { stripFocused && focusedMonogramIndex() == 0 }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun strip(): TvNativeMonogramRowView? = findMonogramRow(host.root)

    private fun focusedMonogramIndex(): Int {
        val row = strip() ?: return -1
        return row.focusedChild?.let { row.getChildAdapterPosition(it) } ?: -1
    }

    @Test
    fun `data arriving under a focused placeholder keeps focus in the row`() {
        host.onMain { items = testPeople(10) }
        host.waitUntil("真数据排出来") { strip()?.cells?.loading == false }
        host.instrumentation.waitForIdleSync()
        assertEquals(0, host.onMain { focusedMonogramIndex() }, "焦点还在第 0 格")
        assertFalse(host.onMain { below.hasFocus() }, "焦点被改派进了下方的原生行")
        assertTrue(stripFocused)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("右键在行里走") { focusedMonogramIndex() == 1 }
    }

    @Test
    fun `down from the native row bubbles up to compose and does not leak into the native row below`() {
        host.onMain { items = testPeople(10) }
        host.waitUntil("真数据排出来") { strip()?.cells?.loading == false }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(1, downSeen, "行不接的下键交给了外层的 Compose")
        assertFalse(host.onMain { below.hasFocus() }, "下键漏给系统找焦点, 跳进了下方的原生行")
        assertEquals(0, host.onMain { focusedMonogramIndex() })
    }

    @Test
    fun `left and right on placeholders stay put`() {
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0, host.onMain { focusedMonogramIndex() })
        assertFalse(host.onMain { below.hasFocus() })
    }
}

private fun findMonogramRow(v: View): TvNativeMonogramRowView? = when (v) {
    is TvNativeMonogramRowView -> v
    is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findMonogramRow(v.getChildAt(it)) }
    else -> null
}
