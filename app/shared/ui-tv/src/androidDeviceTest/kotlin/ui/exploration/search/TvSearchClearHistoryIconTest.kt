/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.exploration.search

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.paging.PagingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LONG_PRESS_MIN_HOLD
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.search_tv_clear_history
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 搜索框里的「清除历史」图标 ([TvSearchInputPane]): 图标嵌在搜索框里面, 而搜索框自己认确认键 (短按进编辑态、长按把焦点送到图标上).
 * 长按搜索框之后那一次按住剩下的连发与抬起不算按了清除; 再按一下确认键清空历史, 不被外层的搜索框当成自己的短按. 搜索框自己的短按照常进编辑态.
 */
class TvSearchClearHistoryIconTest {
    private val host = TvNativeTestHost()
    private val field = FocusRequester()
    private lateinit var compose: ComposeView
    private var paneFocused = false
    private var clears = 0
    private val history = MutableStateFlow(PagingData.from(listOf(HISTORY_FIRST, "葬送的芙莉莲")))
    private val clearLabel = runBlocking { getString(Lang.search_tv_clear_history) }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(LocalThemeSettings provides ThemeSettings.Default) {
                    var query by remember { mutableStateOf(TextFieldValue()) }
                    TvSearchInputPane(
                        query = query,
                        onQueryChange = { query = it },
                        historyPager = history,
                        suggestionsPager = { flowOf(PagingData.empty()) },
                        onSubmit = {},
                        fieldFocusRequester = field,
                        onOpenFilter = {},
                        hasFilters = false,
                        remoteInputUrl = null,
                        remotePhoneConnected = false,
                        remoteKnownHost = null,
                        remoteHostChanged = false,
                        onResetRemoteAddress = {},
                        onRemoveHistory = {},
                        onClearHistory = {
                            clears++
                            history.value = PagingData.empty()
                        },
                        modifier = Modifier.onFocusChanged { paneFocused = it.hasFocus },
                    )
                }
            }
        }
        host.waitUntil("历史排出来") { nodes().any { HISTORY_FIRST in it.texts() } }
        host.waitUntil("焦点落到搜索框上") { runCatching { field.requestFocus() }.isSuccess && paneFocused }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `holding confirm on the field moves focus to the clear icon and the next press clears history`() {
        longPressConfirm()
        host.waitUntil("长按把焦点送到清除图标上") { clearIconFocused() }
        assertEquals(0, host.onMain { clears }, "长按那一次按住剩下的连发与抬起不算按了清除")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("再按一下确认键清空历史") { clears == 1 }
    }

    @Test
    fun `a short press on the field still starts editing`() {
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("短按进编辑态, 焦点进了输入框") { editorFocused() }
        assertEquals(0, host.onMain { clears })
    }

    /** 按住确认键过长按阈值 (第二发且距按下 [LONG_PRESS_MIN_HOLD] 以上), 再连发两下, 抬起. */
    private fun longPressConfirm() {
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(LONG_PRESS_MIN_HOLD.inWholeMilliseconds + 100)
        for (repeat in 1..3) {
            host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = repeat)
            SystemClock.sleep(50)
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** 整棵语义树 (未合并), 在主线程上调. */
    private fun nodes(): List<SemanticsNode> {
        val root = (compose.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        return listOf(root) + root.descendants()
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.selfAndDescendants(): List<SemanticsNode> = listOf(this) + descendants()

    private fun focusedNode(): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }

    /** 持焦的是清除图标那一项: 子树里有它的图标描述, 而没有输入框 (搜索框的子树里两样都有). */
    private fun clearIconFocused(): Boolean {
        val subtree = focusedNode()?.selfAndDescendants() ?: return false
        return subtree.any { clearLabel in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } &&
            subtree.none { it.config.getOrNull(SemanticsProperties.EditableText) != null }
    }

    private fun editorFocused(): Boolean = focusedNode()?.config?.getOrNull(SemanticsProperties.EditableText) != null

    private companion object {
        const val HISTORY_FIRST = "孤独摇滚"
    }
}
