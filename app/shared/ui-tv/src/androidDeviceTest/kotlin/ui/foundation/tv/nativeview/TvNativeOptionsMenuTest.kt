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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.widgets.LocalTvNativeMenuPresenter
import me.him188.ani.app.ui.foundation.widgets.TvMenuOption
import me.him188.ani.app.ui.tv.TvAniUiBehavior
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 播放器的选项菜单 (字幕 / 音轨 / 画面比例) 在 TV 上走原生菜单: 真的 [PlayerControllerDefaults.OptionsSwitcher] + 根部的按键截流 + 那一层,
 * 按键走系统派发. 选项里有 null (字幕的「关闭」).
 */
class TvNativeOptionsMenuTest {
    private val host = TvNativeTestHost()
    private val button = FocusRequester()
    private var menus: TvNativeMenuHost? = null
    private var value by mutableStateOf<String?>("中文")
    private var buttonFocused = false

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(LocalAniUiBehavior provides TvAniUiBehavior) {
                    val nativeMenus = rememberTvNativeMenuHost()
                    SideEffect { menus = nativeMenus }
                    CompositionLocalProvider(LocalTvNativeMenuPresenter provides nativeMenus) {
                        Box(Modifier.fillMaxSize().tvNativeMenuKeys(nativeMenus, null), contentAlignment = Alignment.BottomEnd) {
                            PlayerControllerDefaults.OptionsSwitcher(
                                value = value,
                                onValueChange = { value = it },
                                optionsProvider = { listOf("中文", "日文", null) },
                                renderValue = { Text(it ?: "关闭") },
                                modifier = Modifier.focusRequester(button).onFocusChanged { buttonFocused = it.hasFocus },
                                optionLabel = { TvMenuOption(it ?: "关闭") },
                            )
                        }
                    }
                    TvNativeMenuLayer(nativeMenus)
                }
            }
        }
        host.waitUntil("菜单宿主就位") { menus != null }
        host.onMain { button.requestFocus() }
        host.waitUntil("按钮拿到焦点") { buttonFocused }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun open(): TvNativeMenuHost {
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        val menus = menus!!
        host.waitUntil("菜单打开") { menus.isOpen }
        return menus
    }

    @Test
    fun `choosing the null option applies it and the menu opens again`() {
        val menus = open()
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("选中后收起") { !menus.isOpen }
        assertEquals(null, host.onMain { value })
        // 展开状态要跟着置回, 不然再按确定打不开
        open()
    }

    @Test
    fun `back closes only the menu and the button keeps the focus`() {
        val menus = open()
        host.press(KeyEvent.KEYCODE_BACK)
        host.waitUntil("返回收起") { !menus.isOpen }
        assertTrue(host.onMain { buttonFocused }, "焦点还在按钮上")
        assertEquals("中文", host.onMain { value })
        open()
    }
}
