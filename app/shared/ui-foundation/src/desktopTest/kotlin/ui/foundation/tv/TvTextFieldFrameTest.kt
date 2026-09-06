/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.navigation.LocalOnBackPressedDispatcherOwner
import me.him188.ani.app.ui.foundation.navigation.OnBackPressedDispatcher
import me.him188.ani.app.ui.foundation.navigation.OnBackPressedDispatcherOwner
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TvTextFieldFrame] 的两态: 焦点先落在框上 (输入框不持焦, 不开输入会话), 按确认才把焦点交给输入框;
 * 焦点被带走或按返回即退出编辑, 回来时又落在框上. 非遥控器形态原样, 焦点直接给输入框.
 */
@OptIn(ExperimentalTestApi::class)
class TvTextFieldFrameTest {
    private var text by mutableStateOf("")
    private var enterKeyUps = 0
    private val fieldFocus = FocusRequester()
    private val otherFocus = FocusRequester()
    private val callerInteraction = MutableInteractionSource()
    private var callerSeesFocus = false
    private var backFallbacks = 0
    private val backDispatcher = OnBackPressedDispatcher(fallback = { backFallbacks++ })

    @Test
    fun `initial focus lands on frame and editor stays unfocused`() = runAniComposeUiTest {
        setFieldContent(focusDriven = true)
        onNodeWithTag(FIELD).assertIsFocused()
        editor().assertIsNotFocused()
        // 框持焦时输入框的聚焦样式照样亮
        assertTrue(callerSeesFocus)
    }

    @Test
    fun `confirm on frame hands focus to editor`() = runAniComposeUiTest {
        setFieldContent(focusDriven = true)
        onNodeWithTag(FIELD).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        editor().assertIsFocused()
        editor().performTextInput("abc")
        waitForIdle()
        assertEquals("abc", text)
        assertTrue(callerSeesFocus)
    }

    @Test
    fun `enter on frame starts editing instead of caller handler`() = runAniComposeUiTest {
        setFieldContent(focusDriven = true)
        onNodeWithTag(FIELD).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        editor().assertIsFocused()
        assertEquals(0, enterKeyUps)
        // 编辑态里回车照样交给调用方 (硬件键盘回车提交)
        editor().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, enterKeyUps)
    }

    @Test
    fun `focus leaving exits editing and coming back lands on frame`() = runAniComposeUiTest {
        setFieldContent(focusDriven = true)
        onNodeWithTag(FIELD).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        editor().assertIsFocused()

        runOnIdle { otherFocus.requestFocus() }
        waitForIdle()
        onNodeWithTag(OTHER).assertIsFocused()
        editor().assertIsNotFocused()

        runOnIdle { fieldFocus.requestFocus() }
        waitForIdle()
        onNodeWithTag(FIELD).assertIsFocused()
        editor().assertIsNotFocused()
    }

    @Test
    fun `back while editing returns to frame without closing`() = runAniComposeUiTest {
        setFieldContent(focusDriven = true)
        onNodeWithTag(FIELD).performKeyInput { pressKey(Key.DirectionCenter) }
        waitForIdle()
        editor().assertIsFocused()

        runOnIdle { backDispatcher.onBackPressed() }
        waitForIdle()
        onNodeWithTag(FIELD).assertIsFocused()
        editor().assertIsNotFocused()
        assertEquals(0, backFallbacks)

        // 回到框上之后的返回不再归输入框
        runOnIdle { backDispatcher.onBackPressed() }
        assertEquals(1, backFallbacks)
    }

    @Test
    fun `without focus driven navigation editor takes focus directly`() = runAniComposeUiTest {
        setFieldContent(focusDriven = false)
        editor().assertIsFocused()
        editor().performTextInput("abc")
        waitForIdle()
        assertEquals("abc", text)
    }

    private fun ComposeUiTest.setFieldContent(focusDriven: Boolean) {
        setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            val backOwner = remember {
                object : OnBackPressedDispatcherOwner, LifecycleOwner by lifecycleOwner {
                    override val onBackPressedDispatcher = backDispatcher
                }
            }
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAniUiBehavior provides AniUiBehavior(focusDrivenNavigation = focusDriven),
                    LocalOnBackPressedDispatcherOwner provides backOwner,
                ) {
                    Column {
                        Field()
                        Box(Modifier.size(40.dp).testTag(OTHER).focusRequester(otherFocus).focusable())
                    }
                }
            }
        }
        waitForIdle()
    }

    @Composable
    private fun Field() {
        val focused by callerInteraction.collectIsFocusedAsState()
        callerSeesFocus = focused
        AniOutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .testTag(FIELD)
                .focusRequester(fieldFocus)
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyUp) {
                        enterKeyUps++
                        true
                    } else false
                },
            singleLine = true,
            interactionSource = callerInteraction,
        )
        LaunchedEffect(Unit) { fieldFocus.requestFocus() }
    }

    private fun ComposeUiTest.editor() =
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)

    private companion object {
        const val FIELD = "field"
        const val OTHER = "other"
    }
}
