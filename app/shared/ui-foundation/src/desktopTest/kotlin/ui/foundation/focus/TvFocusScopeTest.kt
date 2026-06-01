/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.him188.ani.app.ui.framework.runAniComposeUiTest

class TvFocusScopeTest {
    @Test
    fun `attached target rejected during transition retries after fallback focus settles`() = runAniComposeUiTest {
        lateinit var scope: TvFocusScope
        lateinit var targetEnabled: MutableState<Boolean>
        val outgoingFocus = FocusRequester()
        val fallbackFocus = FocusRequester()

        setContent {
            scope = rememberTvFocusScope()
            targetEnabled = remember { mutableStateOf(false) }

            Row {
                Row(
                    Modifier.focusRequester(outgoingFocus)
                        .focusable()
                        .testTag("outgoing"),
                ) {}
                Row(
                    Modifier.focusRequester(fallbackFocus)
                        .onFocusChanged {
                            if (it.isFocused) scope.notifyFocusFallbackSettled()
                        }
                        .focusable()
                        .testTag("fallback"),
                ) {}
                Row(
                    Modifier.tvFocusAnchor(scope, Target)
                        .focusProperties { canFocus = targetEnabled.value }
                        .focusable()
                        .testTag("target"),
                ) {}
            }
        }

        runOnIdle { outgoingFocus.requestFocus() }
        onNodeWithTag("outgoing").assertIsFocused()

        runOnIdle { scope.request(Target) }
        waitForIdle()
        onNodeWithTag("target").assertIsNotFocused()
        runOnIdle { assertNotNull(scope.pending) }

        runOnIdle { targetEnabled.value = true }
        waitForIdle()
        runOnIdle { fallbackFocus.requestFocus() }
        waitForIdle()

        onNodeWithTag("target").assertIsFocused()
        runOnIdle { assertNull(scope.pending) }
    }

    @Test
    fun `transit anchor cancellation restores focus to caller fallback`() = runAniComposeUiTest {
        lateinit var switching: MutableState<Boolean>
        val transitFocus = FocusRequester()
        val fallbackFocus = FocusRequester()

        setContent {
            switching = remember { mutableStateOf(true) }
            Row {
                TvFocusTransitAnchor(
                    requester = transitFocus,
                    switching = { switching.value },
                    onStranded = { fallbackFocus.requestFocus() },
                    modifier = Modifier.testTag("transit"),
                )
                Row(
                    Modifier.focusRequester(fallbackFocus)
                        .focusable()
                        .testTag("fallback"),
                ) {}
            }
        }

        runOnIdle { transitFocus.requestFocus() }
        onNodeWithTag("transit").assertIsFocused()

        runOnIdle { switching.value = false }
        waitForIdle()

        onNodeWithTag("fallback").assertIsFocused()
    }

    @Test
    fun `requesting an already focused anchor stays idle`() = runAniComposeUiTest {
        lateinit var scope: TvFocusScope
        val targetFocus = FocusRequester()

        setContent {
            scope = rememberTvFocusScope()
            Row(
                Modifier.focusRequester(targetFocus)
                    .tvFocusAnchor(scope, Target)
                    .focusable()
                    .testTag("target"),
            ) {}
        }

        runOnIdle { targetFocus.requestFocus() }
        onNodeWithTag("target").assertIsFocused()

        runOnIdle { scope.request(Target) }

        runOnIdle { assertNull(scope.pending) }
        onNodeWithTag("target").assertIsFocused()
    }

    @Test
    fun `exact anchor does not treat focused descendant as arrival`() = runAniComposeUiTest {
        lateinit var scope: TvFocusScope
        val childFocus = FocusRequester()

        setContent {
            scope = rememberTvFocusScope()
            Row(
                Modifier
                    .tvFocusAnchor(scope, ExactTarget, includeDescendants = false)
                    .focusable()
                    .testTag("exactTarget"),
            ) {
                Row(Modifier.focusRequester(childFocus).focusable().testTag("child")) {}
            }
        }

        runOnIdle { childFocus.requestFocus() }
        onNodeWithTag("child").assertIsFocused()
        runOnIdle { assertFalse(scope.isFocused(ExactTarget)) }

        runOnIdle { scope.request(ExactTarget) }
        onNodeWithTag("exactTarget").assertIsFocused()
    }

    /**
     * 锚点记账的不变量 (纯逻辑, 不经过 Compose): **一个 key 的持焦标记不能比它的节点活得更久**.
     *
     * 为什么必须在 [TvFocusScope] 这一层保证, 而不是靠 Compose 的焦点事件:
     * `Modifier.onFocusChanged` 在**节点脱离时是否补发 Inactive 因平台而异** —— 桌面会发,
     * Android TV 真机实测不发 (2026-08-22 app.log). 所以 [TvFocusScope] 不能依赖它, 必须在
     * 附着/脱离两侧自己把标记收回来. 用 Compose UI 测试守这条会在桌面上假绿.
     *
     * 漏了的后果: [TvFocusScope.request] 的"已持焦即空闲"短路永久生效. 真机症状 —— 追番页第一次
     * 下键能进网格、之后再也下不去; 换 tab 后内容变了焦点不动; 时间表行末右键到不了下一行;
     * 且 switching 闸门要卡到 4 秒超时才回落, 期间方向键与返回键都失灵.
     */
    @Test
    fun `focused flag never outlives the anchor node`() {
        val scope = TvFocusScope()

        // 节点挂上并真的拿到焦点
        scope.onAnchorAttached(Target)
        scope.onAnchorFocusChanged(Target, focused = true)
        assertTrue(scope.isFocused(Target))

        // 节点脱离 (真机上不会补发 onFocusChanged(false)): 没有节点就不可能持焦
        scope.onAnchorDetached(Target)
        assertFalse(scope.isFocused(Target))

        // 于是新一轮请求必须真的登记, 不能被当成"已经在那儿了"
        scope.onAnchorAttached(Target)
        scope.request(Target)
        assertNotNull(scope.pending)
    }

    /** 同一 key 在两个节点之间迁移 (新节点先 attach、旧节点后 detach) 时, 持焦标记同样要作废. */
    @Test
    fun `focused flag is dropped when the anchor migrates to another node`() {
        val scope = TvFocusScope()

        scope.onAnchorAttached(Target)              // 旧节点
        scope.onAnchorFocusChanged(Target, focused = true)
        assertTrue(scope.isFocused(Target))

        scope.onAnchorAttached(Target)              // 新节点先挂上 (引用数 1 -> 2)
        scope.onAnchorDetached(Target)              // 旧节点后脱离 (引用数 2 -> 1, 不会走清空分支)
        assertFalse(scope.isFocused(Target))        // 标记指的是旧节点, 必须已作废

        scope.request(Target)
        assertNotNull(scope.pending)
    }

    private data object Target : TvFocusKey
    private data object ExactTarget : TvFocusKey
}
