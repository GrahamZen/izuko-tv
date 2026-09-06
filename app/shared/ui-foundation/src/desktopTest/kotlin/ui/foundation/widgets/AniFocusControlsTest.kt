/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 弹窗里自绘示焦的共享控件的外观: 聚焦实底 ([aniFocusContainerColor], 深色配色下是 inversePrimary)、胶囊选中
 * secondaryContainer、菜单当前项不铺底色; 对话框按钮替身在弹窗里画成动作按钮, 弹窗外是 M3 原样.
 *
 * 焦点在**首帧布局时**给出去, 与弹窗 / 菜单打开时窗口初始焦点的时机一致. 真机上那一刻焦点事件可能早于
 * 交互事件的收集 (控件因此按 onFocusChanged 记焦点, 见 FocusHighlight.kt 开头), 但这个时序取决于设备,
 * 这里复现不出来; 本测试保证的是这个时机下三态照样画对.
 *
 * 聚焦实底上还叠着 ripple 的焦点态层 (内容色 10%), 所以聚焦的颜色按 [FOCUS_TOLERANCE] 比:
 * 足够把聚焦实底与常态 / 选中那两档底色分开.
 */
@OptIn(ExperimentalTestApi::class)
class AniFocusControlsTest {
    private lateinit var colors: ColorScheme

    @Test
    fun `chip focused during first layout shows focus fill`() = runAniComposeUiTest {
        setTvContent {
            FocusOnFirstLayout { focus ->
                AniFocusChip(selected = false, onClick = {}, modifier = focus.testTag(TAG)) {
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
        assertCenterColor(TAG, colors.inversePrimary, FOCUS_TOLERANCE)
    }

    @Test
    fun `selected chip without focus shows selection container`() = runAniComposeUiTest {
        setTvContent {
            AniFocusChip(selected = true, onClick = {}, modifier = Modifier.testTag(TAG)) {
                Spacer(Modifier.width(48.dp))
            }
        }
        assertCenterColor(TAG, colors.secondaryContainer)
    }

    @Test
    fun `action button focused during first layout shows focus fill`() = runAniComposeUiTest {
        setTvContent {
            FocusOnFirstLayout { focus ->
                AniFocusActionButton(onClick = {}, modifier = focus.testTag(TAG)) {
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
        assertCenterColor(TAG, colors.inversePrimary, FOCUS_TOLERANCE)
    }

    @Test
    fun `menu item focused on open shows focus fill and current item is not filled`() = runAniComposeUiTest {
        setTvContent {
            AniDropdownMenu(expanded = true, onDismissRequest = {}) {
                FocusOnFirstLayout { focus ->
                    AniDropdownMenuItem(
                        text = { Spacer(Modifier.width(96.dp)) },
                        onClick = {},
                        modifier = focus.testTag(TAG),
                    )
                }
                AniDropdownMenuItem(
                    text = { Spacer(Modifier.width(96.dp)) },
                    onClick = {},
                    modifier = Modifier.testTag(TAG_SECOND),
                )
                AniDropdownMenuItem(
                    text = { Spacer(Modifier.width(96.dp)) },
                    onClick = {},
                    modifier = Modifier.testTag(TAG_THIRD),
                    selected = true,
                )
            }
        }
        // 菜单有展开动画 (缩放 + 淡入), 走完再取色
        mainClock.advanceTimeBy(MENU_ANIMATION_MILLIS)
        waitForIdle()
        assertCenterColor(TAG, colors.inversePrimary, FOCUS_TOLERANCE)
        // 当前项只在右端打勾, 中间与普通项同底
        assertCenterColor(TAG_THIRD, centerColor(TAG_SECOND))
    }

    @Test
    fun `dialog text button inside a popup renders as an action button`() = runAniComposeUiTest {
        setTvContent {
            ProvidePopupControlStyle {
                FocusOnFirstLayout { focus ->
                    AniTextButton(onClick = {}, modifier = focus.testTag(TAG)) {
                        Spacer(Modifier.width(48.dp))
                    }
                }
            }
        }
        assertCenterColor(TAG, colors.inversePrimary, FOCUS_TOLERANCE)
    }

    @Test
    fun `dialog text button outside a popup stays an M3 text button`() = runAniComposeUiTest {
        setTvContent {
            FocusOnFirstLayout { focus ->
                AniTextButton(onClick = {}, modifier = focus.testTag(TAG)) {
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
        // M3 文字按钮没有底色, 聚焦只有一层淡态层: 离动作按钮的实底差得远
        assertTrue(!centerColor(TAG).isCloseTo(colors.inversePrimary, FOCUS_TOLERANCE))
    }

    @Test
    fun `custom non-error button colors inside a popup do not become the content color`() = runAniComposeUiTest {
        setTvContent {
            ProvidePopupControlStyle {
                // 常态灰底 + 正文色字 (按焦点换色的按钮常这么配): 不能把灰底当成字色
                AniButton(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    ContentColorSwatch(Modifier.testTag(TAG))
                }
            }
        }
        assertCenterColor(TAG, colors.onSurface)
    }

    @Test
    fun `error button colors inside a popup keep the error content color`() = runAniComposeUiTest {
        setTvContent {
            ProvidePopupControlStyle {
                AniTextButton(
                    onClick = {},
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    ContentColorSwatch(Modifier.testTag(TAG))
                }
            }
        }
        assertCenterColor(TAG, colors.error)
    }

    private fun ComposeUiTest.setTvContent(content: @Composable () -> Unit) {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                colors = MaterialTheme.colorScheme
                CompositionLocalProvider(LocalAniUiBehavior provides AniUiBehavior(focusDrivenNavigation = true)) {
                    Column { content() }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.centerColor(tag: String): Color {
        val pixels = onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun ComposeUiTest.assertCenterColor(tag: String, expected: Color, tolerance: Float = COLOR_TOLERANCE) {
        val actual = centerColor(tag)
        assertTrue(actual.isCloseTo(expected, tolerance), "center of '$tag': expected $expected, got $actual")
    }

    private fun Color.isCloseTo(other: Color, tolerance: Float): Boolean =
        abs(red - other.red) < tolerance &&
            abs(green - other.green) < tolerance &&
            abs(blue - other.blue) < tolerance

    private companion object {
        const val TAG = "control"
        const val TAG_SECOND = "control2"
        const val TAG_THIRD = "control3"

        /** 截图是 8 位色, 比较留两三级的余量. */
        const val COLOR_TOLERANCE = 3f / 255f

        /** 聚焦色的余量: 容下焦点态层 (内容色 10%) 的偏移; 深色配色里聚焦实底与常态 / 选中底总有一个通道差 0.19 以上. */
        const val FOCUS_TOLERANCE = 0.12f

        const val MENU_ANIMATION_MILLIS = 500L
    }
}

/** 按当前内容色填满的方块: 代替按钮里的字, 取色看内容色. */
@Composable
private fun ContentColorSwatch(modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).background(LocalContentColor.current))
}

/** 首帧布局一完成就把焦点给 [content] 里挂了这个 Modifier 的控件 (只给一次). */
@Composable
private fun FocusOnFirstLayout(content: @Composable (Modifier) -> Unit) {
    val requester = remember { FocusRequester() }
    val requested = remember { BooleanArray(1) }
    content(
        Modifier
            .focusRequester(requester)
            .onGloballyPositioned {
                if (!requested[0]) {
                    requested[0] = true
                    requester.requestFocus()
                }
            },
    )
}
