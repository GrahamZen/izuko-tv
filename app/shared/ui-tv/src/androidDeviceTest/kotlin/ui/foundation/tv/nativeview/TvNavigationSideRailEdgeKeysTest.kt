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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.him188.ani.app.ui.foundation.session.TvNavRailItem
import me.him188.ani.app.ui.foundation.session.TvNavigationSideRail
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 侧边栏竖向到头 ([TvNavigationSideRail]): 最下面的条目按下停在栏里. 原生页的视图从屏幕左缘铺起、就在栏底下 (探索 / 追番 / 搜索页),
 * 按几何它就在最下面那个条目的正下方 —— 放给焦点搜索 (Compose 的, 或 Compose 没接住后 Android 的) 就会跳进卡片区.
 */
class TvNavigationSideRailEdgeKeysTest {
    private val host = TvNativeTestHost()
    private val requesters = List(3) { FocusRequester() }
    private var focused by mutableIntStateOf(-1)
    private lateinit var native: TvNativeRowView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            native = TvNativeRowView(host.activity, testWallStyle(), host.sketch, pool = null, startPx = 128, endPx = 48, fadeDistancePx = 128f)
            native.animatedScroll = false
            native.bind(testCards(12), { it.toLong() }, leftIndex = 0, columns = 6)
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                Box(Modifier.fillMaxSize()) {
                    // 原生页的一行卡: 从屏幕左缘 (栏底下) 铺满整宽, 在栏的条目下方
                    AndroidView(
                        factory = { native },
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(260.dp),
                    )
                    TvNavigationSideRail(
                        selfInfo = null,
                        onAvatarClick = {},
                        items = List(3) { i ->
                            TvNavRailItem(
                                icon = Icons.Rounded.Star,
                                label = "条目 $i",
                                iconContent = { f ->
                                    SideEffect { if (f) focused = i }
                                    Box(Modifier.size(24.dp))
                                },
                                focusRequester = requesters[i],
                                onClick = {},
                            )
                        },
                        modifier = Modifier.fillMaxHeight(0.6f),
                    )
                }
            }
        }
        host.waitUntil("原生行排出来") { native.childCount > 0 }
        host.onMain { requesters[2].requestFocus() }
        host.waitUntil("焦点在最下面的条目") { focused == 2 }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `down at the last item stays in the rail`() {
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFalse(host.onMain { native.hasFocus() }, "焦点跳进了栏底下的原生视图")
        assertEquals(2, focused)
    }

    @Test
    fun `up and down inside the rail still move`() {
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("往上走到第 1 个") { focused == 1 }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("往下回到第 2 个") { focused == 2 }
    }
}
