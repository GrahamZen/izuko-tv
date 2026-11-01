/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.widgets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * @see ActionToastState
 * @see ActionToastHost
 */
class ActionToastTest {
    private class RecordingToaster : Toaster {
        val texts = mutableListOf<String>()
        override fun toast(text: String) {
            texts += text
        }
    }

    @Test
    fun `tapping a toast with an action runs it and hides the toast`() = runAniComposeUiTest {
        var clicks = 0
        lateinit var state: ActionToastState
        setContent {
            state = rememberActionToastState(plain = RecordingToaster())
            ActionToastHost(state)
        }
        runOnIdle { state.toastWithAction("ready") { clicks++ } }
        onNodeWithText("ready").assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(1, clicks)
            assertFalse(state.showing)
        }
    }

    @Test
    fun `plain toasts go to the plain toaster and are not drawn`() = runAniComposeUiTest {
        val plain = RecordingToaster()
        lateinit var state: ActionToastState
        setContent {
            state = rememberActionToastState(plain = plain)
            ActionToastHost(state)
        }
        runOnIdle { state.toast("copied") }
        runOnIdle {
            assertEquals(listOf("copied"), plain.texts)
            assertFalse(state.showing)
        }
        onNodeWithText("copied").assertDoesNotExist()
    }

    @Test
    fun `without a plain toaster plain toasts are drawn and not clickable`() = runAniComposeUiTest {
        lateinit var state: ActionToastState
        setContent {
            state = rememberActionToastState(plain = null)
            ActionToastHost(state)
        }
        runOnIdle { state.toast("saved") }
        onNodeWithText("saved").assertIsDisplayed().performClick()
        runOnIdle { assertTrue(state.showing) }
    }

    @Test
    fun `a system toaster ignores the action and shows the text`() {
        val plain = RecordingToaster()
        var clicks = 0
        plain.toastWithAction("ready") { clicks++ }
        assertEquals(listOf("ready"), plain.texts)
        assertEquals(0, clicks)
    }
}
