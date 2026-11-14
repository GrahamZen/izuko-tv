/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenu
import me.him188.ani.app.ui.foundation.widgets.AniDropdownMenuItem
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.foundation.widgets.rememberTvBesideAnchorPositionProvider
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 弹窗 / 菜单开在播放页上时, 遥控器的播放/暂停键照样切换播放 (见 [tvOverlayWindowKeys]).
 *
 * 弹层不在播放页的组合里 (挂在应用根部的那种), 拿不到 [LocalTvPlayPauseHandler], 处理器从 [TvVisiblePlayerPlayPause] 取.
 */
@OptIn(ExperimentalTestApi::class)
class TvOverlayWindowKeysTest {
    private var toggles = 0
    private var unregister: (() -> Unit)? = null

    private fun showPlayer() {
        unregister = TvVisiblePlayerPlayPause.register { toggles++ }
    }

    @AfterTest
    fun hidePlayer() {
        unregister?.invoke()
    }

    @Test
    fun `the play key in an alert dialog toggles the player behind it`() = runAniComposeUiTest {
        showPlayer()
        setTvContent {
            AniAlertDialog(
                onDismissRequest = {},
                confirmButton = { FocusOnFirstLayout { AniTextButton({}, it.testTag(TAG)) { Text("OK") } } },
            )
        }
        onNodeWithTag(TAG).performKeyInput { pressKey(Key.MediaPlayPause) }
        assertEquals(1, toggles)
    }

    @Test
    fun `the play key in a menu toggles the player behind it`() = runAniComposeUiTest {
        showPlayer()
        setTvContent { Menu(positioned = false) }
        onNodeWithTag(TAG).performKeyInput { pressKey(Key.MediaPlayPause) }
        assertEquals(1, toggles)
    }

    @Test
    fun `the play key in a menu placed beside its anchor toggles the player behind it`() = runAniComposeUiTest {
        showPlayer()
        setTvContent { Menu(positioned = true) }
        onNodeWithTag(TAG).performKeyInput { pressKey(Key.MediaPlay) }
        assertEquals(1, toggles)
    }

    @Test
    fun `the play key reaches the player from a dialog at the very app root`() = runAniComposeUiTest {
        showPlayer()
        // 应用最根部: 没有长按宿主, 也不在播放页的组合里
        setTvContent(withLongPressHost = false) {
            AniAlertDialog(
                onDismissRequest = {},
                confirmButton = { FocusOnFirstLayout { AniTextButton({}, it.testTag(TAG)) { Text("OK") } } },
            )
        }
        onNodeWithTag(TAG).performKeyInput { pressKey(Key.MediaPause) }
        assertEquals(1, toggles)
    }

    @Test
    fun `without a player on screen the play key is left alone`() = runAniComposeUiTest {
        setTvContent {
            AniAlertDialog(
                onDismissRequest = {},
                confirmButton = { FocusOnFirstLayout { AniTextButton({}, it.testTag(TAG)) { Text("OK") } } },
            )
        }
        onNodeWithTag(TAG).performKeyInput { pressKey(Key.MediaPlayPause) }
        assertEquals(0, toggles)
    }

    @Composable
    private fun Menu(positioned: Boolean) {
        AniDropdownMenu(
            expanded = true,
            onDismissRequest = {},
            positionProvider = if (positioned) rememberTvBesideAnchorPositionProvider() else null,
        ) {
            FocusOnFirstLayout { focus ->
                AniDropdownMenuItem(text = { Spacer(Modifier.width(96.dp)) }, onClick = {}, modifier = focus.testTag(TAG))
            }
        }
    }

    /** 电视的样子: 焦点导航; [withLongPressHost] = 弹层挂在长按宿主之下 (应用最根部的弹层没有) */
    private fun ComposeUiTest.setTvContent(withLongPressHost: Boolean = true, content: @Composable () -> Unit) {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(
                    LocalAniUiBehavior provides AniUiBehavior(focusDrivenNavigation = true),
                    LocalTvBackLongPressHost provides if (withLongPressHost) remember { TvBackLongPressHost() } else null,
                ) {
                    Column { content() }
                }
            }
        }
        // 菜单有展开动画, 走完再按键
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }

    private companion object {
        const val TAG = "focused"
    }
}

/** 首帧布局时把焦点给出去, 同弹窗 / 菜单打开时窗口的初始焦点 */
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
