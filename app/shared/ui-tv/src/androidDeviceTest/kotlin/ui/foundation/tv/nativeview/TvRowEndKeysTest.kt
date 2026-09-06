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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.him188.ani.app.ui.foundation.focus.tvRowEndKeys
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Compose 横向一行 (详情页选集 / 角色行) 的末项按右 ([tvRowEndKeys]): 行内没有下一张时这一下被吞掉, 不交给 Android 的 FocusFinder ——
 * 它会按屏幕几何挑到同一窗口里下方原生行 ([TvNativeRowView], 详情页的关联条目) 的卡, 长按右键跑到头就跳进那一行.
 */
class TvRowEndKeysTest {
    private val host = TvNativeTestHost()
    private val requesters = List(3) { FocusRequester() }
    private var focused by mutableIntStateOf(-1)
    private lateinit var native: TvNativeRowView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val style = testWallStyle()
            native = TvNativeRowView(host.activity, style, host.sketch, pool = null, startPx = 128, endPx = 48, fadeDistancePx = 128f)
            native.animatedScroll = false
            native.bind(testCards(12), { it.toLong() }, leftIndex = 0, columns = 6)
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                Column {
                    Row(Modifier.tvRowEndKeys(itemCount = { 3 }, focusedIndex = { focused })) {
                        repeat(3) { i ->
                            Box(
                                Modifier.size(100.dp)
                                    .focusRequester(requesters[i])
                                    .onFocusChanged { if (it.isFocused) focused = i }
                                    .focusable(),
                            )
                        }
                    }
                    AndroidView(factory = { native }, modifier = Modifier.fillMaxWidth().height(260.dp))
                }
            }
        }
        host.waitUntil("原生行排出来") { native.childCount > 0 }
        host.onMain { requesters[2].requestFocus() }
        host.waitUntil("焦点在 Compose 行最后一张") { focused == 2 }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `right at the last item stays in the compose row`() {
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(host.onMain { native.hasFocus() }, "焦点跳进了下方的原生行")
        assertEquals(2, focused)
    }

    @Test
    fun `left and right inside the row still move`() {
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("往左走到第 1 张") { focused == 1 }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("往右回到第 2 张") { focused == 2 }
    }
}
