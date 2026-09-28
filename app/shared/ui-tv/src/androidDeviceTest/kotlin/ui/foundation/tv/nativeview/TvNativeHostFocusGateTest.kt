/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 原生页面外面那层焦点闸 ([TvNativeHost]): 本页不在前台 (被放大进来的详情页盖着) 时, 上层页面没消费的方向键交给系统找焦点
 * (ViewRootImpl → FocusFinder, 按屏幕位置在整个窗口里找), 也进不了下面这页的原生卡片 —— 否则列表页在看不见的地方换了聚焦卡,
 * 返回缩回时落到那张卡上. 回到前台当场放开; 已经持着焦点的原生视图不被赶走.
 */
class TvNativeHostFocusGateTest {
    private val host = TvNativeTestHost()
    private val foreground = mutableStateOf(false)
    private val overlay = FocusRequester()
    private var overlayFocused by mutableStateOf(false)
    private lateinit var card: View

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                Box(Modifier.fillMaxSize()) {
                    // 垫在下面的列表页: 原生视图铺满整页 (整页大的框不在任何方向上, Compose 自己按方向找不到它), 一张可聚焦的卡在左下
                    CompositionLocalProvider(LocalPageIsForeground provides foreground) {
                        TvNativeHost(
                            factory = { context ->
                                FrameLayout(context).also { page ->
                                    card = View(context).apply {
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                    }
                                    page.addView(card, FrameLayout.LayoutParams(200, 200, Gravity.BOTTOM or Gravity.START))
                                }
                            },
                            update = {},
                            bleedLeft = 0.dp,
                        )
                    }
                    // 盖在上面的详情页: 左上角一个可聚焦的块, Compose 里它下面什么也没有 —— 下键 Compose 不消费, 交给系统
                    Box(
                        Modifier.size(100.dp)
                            .focusRequester(overlay)
                            .onFocusChanged { overlayFocused = it.isFocused }
                            .focusable(),
                    )
                }
            }
        }
        host.waitUntil("原生卡片排出来") { card.isLaidOut }
        host.onMain { overlay.requestFocus() }
        host.waitUntil("焦点在上面那页") { overlayFocused }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `down on the page on top does not land in the covered native page`() {
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFalse(host.onMain { card.isFocused }, "下键漏进了被盖住的原生页")
        assertTrue(overlayFocused, "焦点应还在上面那页")
    }

    @Test
    fun `the covered page cannot pull focus to itself`() {
        assertFalse(host.onMain { card.requestFocus() }, "被盖住的原生页自己送焦不应成功")
        assertTrue(overlayFocused, "焦点应还在上面那页")
    }

    @Test
    fun `the gate opens as soon as the page is foreground again`() {
        host.onMain { foreground.value = true }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("回到前台后下键照常进原生卡片") { card.isFocused }
    }

    @Test
    fun `a native view already holding focus keeps it when its page leaves the foreground`() {
        host.onMain { foreground.value = true }
        host.onMain { card.requestFocus() }
        host.waitUntil("原生卡片拿到焦点") { card.isFocused }
        host.onMain { foreground.value = false }
        host.instrumentation.waitForIdleSync()
        assertTrue(host.onMain { card.isFocused }, "持焦的原生视图不该被当场赶走")
    }
}
