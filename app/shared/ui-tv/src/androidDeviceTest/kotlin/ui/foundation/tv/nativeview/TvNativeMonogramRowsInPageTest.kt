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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 同一页里上下两条圆头像行 (详情页人物页的角色 / 制作人员): 页面按 Compose 的 moveFocus 在两行之间走 (同 PageSection). 焦点从一条原生行
 * 挪到另一条时, Compose 的互操作层先把焦点交给宿主视图再进下一条, 这一页的焦点组会短暂失焦再进来 (所以详情页的返回层级不挂在页上,
 * 只由各行上报). 各行上报的「谁持焦」最后要落在焦点真正所在的那一行, 焦点也不能漏到两行以外.
 */
class TvNativeMonogramRowsInPageTest {
    private val host = TvNativeTestHost()
    private val requester = FocusRequester()
    private val style = testMonogramStyle()

    /** 最后一次持焦的是哪一行 (各行的焦点组上报, 同详情页的 backLevelOrdinal). */
    private var lastRow = ""

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(
                    LocalSketch provides host.sketch,
                    LocalThemeSettings provides ThemeSettings.Default,
                ) {
                    val focusManager = LocalFocusManager.current
                    Column(
                        Modifier
                            .onKeyEvent { event ->
                                val direction = when (event.key) {
                                    Key.DirectionDown -> FocusDirection.Down
                                    Key.DirectionUp -> FocusDirection.Up
                                    else -> return@onKeyEvent false
                                }
                                if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                                true
                            }
                            .focusGroup(),
                    ) {
                        Row("A", Modifier.focusRequester(requester))
                        Spacer(Modifier.height(16.dp))
                        Row("B")
                    }
                }
            }
        }
        host.waitUntil("两行都排出来") { rows().size == 2 && rows().all { it.childCount > 0 } }
        host.onMain { requester.requestFocus() }
        host.waitUntil("焦点进了第一行") { rows()[0].hasFocus() }
        host.instrumentation.waitForIdleSync()
    }

    @Composable
    private fun Row(name: String, modifier: Modifier = Modifier) {
        TvNativeMonogramStrip(
            items = testPeople(10),
            style = style,
            onClick = {},
            onLongPress = {},
            repeatMillis = 40,
            modifier = modifier
                .onFocusChanged { if (it.hasFocus) lastRow = name }
                .focusGroup(),
            startPadding = 40.dp,
            endPadding = 40.dp,
        )
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun rows(): List<TvNativeMonogramRowView> = findRows(host.root)

    @Test
    fun `the rows report which one holds focus when moving between them`() {
        assertEquals("A", host.onMain { lastRow })
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("焦点到了第二行") { rows()[1].hasFocus() }
        host.instrumentation.waitForIdleSync()
        assertEquals("B", host.onMain { lastRow }, "记下的应是第二行")
        assertFalse(host.onMain { rows()[0].hasFocus() })

        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("焦点回到第一行") { rows()[0].hasFocus() }
        host.instrumentation.waitForIdleSync()
        assertEquals("A", host.onMain { lastRow })
        assertFalse(host.onMain { rows()[1].hasFocus() })
    }
}

private fun findRows(v: View): List<TvNativeMonogramRowView> = when (v) {
    is TvNativeMonogramRowView -> listOf(v)
    is ViewGroup -> (0 until v.childCount).flatMap { findRows(v.getChildAt(it)) }
    else -> emptyList()
}
