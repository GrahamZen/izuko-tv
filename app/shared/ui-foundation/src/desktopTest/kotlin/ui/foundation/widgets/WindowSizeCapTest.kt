/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * iOS 转屏那一下, 开着的弹窗、菜单会拿到不限宽或不限高的约束; 里面可以滚的东西收到无限大就抛异常. 这里拿「宽高都不限」的约束去量,
 * 有 [windowSizeCap] / [menuHeightCap] 时照常量出来、不超过窗口 (没有的话量的那一下就抛异常).
 */
class WindowSizeCapTest {
    /** 前提: 不加上限时同样的东西量的那一下就抛异常 (iOS 转屏闪退的样子). 这条不成立的话下面几条也说明不了什么. */
    @Test
    fun `without a cap a vertical scroll measured without limits throws`() {
        assertFails {
            runAniComposeUiTest {
                setContent {
                    Unbounded {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Box(Modifier.width(100.dp).height(100_000.dp))
                        }
                    }
                }
                waitForIdle()
            }
        }
    }

    @Test
    fun `window size cap bounds a vertical scroll measured without limits`() = runAniComposeUiTest {
        var window = 0 to 0
        setContent {
            window = LocalWindowInfo.current.containerSize.let { it.width to it.height }
            Unbounded {
                Column(Modifier.testTag(TAG).windowSizeCap().verticalScroll(rememberScrollState())) {
                    Box(Modifier.width(100_000.dp).height(100_000.dp))
                }
            }
        }
        assertWithinWindow(window)
    }

    @Test
    fun `window size cap bounds a grid measured without limits`() = runAniComposeUiTest {
        var window = 0 to 0
        setContent {
            window = LocalWindowInfo.current.containerSize.let { it.width to it.height }
            Unbounded {
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.testTag(TAG).windowSizeCap()) {
                    items(30) { Box(Modifier.height(200.dp)) }
                }
            }
        }
        assertWithinWindow(window)
    }

    @Test
    fun `menu height cap bounds a menu column measured without limits`() = runAniComposeUiTest {
        var window = 0 to 0
        setContent {
            window = LocalWindowInfo.current.containerSize.let { it.width to it.height }
            Unbounded {
                Column(Modifier.testTag(TAG).menuHeightCap().verticalScroll(rememberScrollState())) {
                    Box(Modifier.width(200.dp).height(100_000.dp))
                }
            }
        }
        assertWithinWindow(window)
    }

    /** 拿宽高都不限的约束量 [content] (同 iOS 转屏那一下弹窗拿到的). */
    @Composable
    private fun Unbounded(content: @Composable () -> Unit) {
        Layout(content) { measurables, _ ->
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(placeables.maxOf { it.width }, placeables.maxOf { it.height }) {
                placeables.forEach { it.place(0, 0) }
            }
        }
    }

    private fun AniComposeUiTest.assertWithinWindow(window: Pair<Int, Int>) {
        val bounds = onNodeWithTag(TAG).getBoundsInRoot()
        val (w, h) = with(density) { (bounds.right - bounds.left).toPx() to (bounds.bottom - bounds.top).toPx() }
        assertTrue(window.first > 0 && window.second > 0, "window size unknown: $window")
        assertTrue(w <= window.first + 1f, "width $w exceeds window ${window.first}")
        assertTrue(h <= window.second + 1f, "height $h exceeds window ${window.second}")
    }

    private companion object {
        const val TAG = "capped"
    }
}
