/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.NoticeEntry
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 通知弹窗的列表与右栏 ([TvNoticeContent]): 只有一个操作的在列表里按确定键直接做、右栏不画按钮; 几个操作的按确定键或右键进右栏第一颗,
 * 两颗横排 (左右键在两颗之间走), 在最左一颗按左键或在右栏按返回键回到那一条 (返回键不关弹窗); 一个操作或没有操作的按右键不动.
 * 资源名这类长串 ([TvNoticeParagraph.longName]) 触屏上截到三行, 电视上写全.
 */
class TvNoticeContentNavigationTest {
    private val host = TvNativeTestHost()
    private lateinit var compose: ComposeView
    private val done = mutableListOf<String>()
    private var dismissed = false
    private var dialogState by mutableStateOf(TvNoticeDialogState(fresh = emptySet(), focusKey = null, focusIndex = 0))
    private var touch by mutableStateOf(false)

    @BeforeTest
    fun setUp() {
        host.launch()
        val items = listOf(
            item(ONE, atMillis = 3, ONE_ACTION),
            item(MANY, atMillis = 2, FIRST, SECOND, detail = true),
            item(NONE, atMillis = 1),
            item(LONG, atMillis = 0, LONG_ACTION, detail = true, longName = LONG_NAME),
        )
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(LocalTvTouchInputEnabled provides touch) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(880.dp)) {
                            // 同真机: 换了要落的那一条时通知弹窗是重新打开的 (叠在上面的弹窗关掉、从别处处理一条), 初始焦点照新的落
                            key(dialogState) {
                                TvNoticeContent(
                                    items,
                                    dialogState,
                                    onAction = { _, _, action -> done += action.label },
                                    onSeen = {},
                                    onDismiss = { dismissed = true },
                                )
                            }
                        }
                    }
                }
            }
        }
        host.waitUntil("一打开焦点在第一条") { focusedText() == ONE }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `ok on a notice with one action does it at once and draws no button`() {
        assertEquals(0, host.onMain { actionButtons() }, "只有一个操作时右栏不画按钮")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(ONE_ACTION), host.onMain { done.toList() })
        assertEquals(ONE, host.onMain { focusedText() }, "焦点留在那一条")
    }

    @Test
    fun `ok or right on a notice with several actions moves to the first action`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = MANY)
        host.waitUntil("右栏画出两颗操作") { actionButtons() == 2 }
        press(KeyEvent.KEYCODE_DPAD_CENTER, expect = FIRST)
        assertEquals(emptyList(), host.onMain { done.toList() }, "按确定键只是进右栏, 不做操作")
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = MANY)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = FIRST)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = SECOND)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(SECOND, host.onMain { focusedText() }, "最右一颗按右不动")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(SECOND, host.onMain { focusedText() }, "横排的按下不动")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(SECOND), host.onMain { done.toList() })
    }

    @Test
    fun `back in the actions returns to the notice without closing`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = MANY)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = FIRST)
        press(KeyEvent.KEYCODE_BACK, expect = MANY)
        assertFalse(host.onMain { dismissed }, "返回键回到列表, 不关弹窗")
    }

    @Test
    fun `right on a notice with one action or none stays on the list`() {
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(ONE, host.onMain { focusedText() })
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = MANY)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = NONE)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(NONE, host.onMain { focusedText() })
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(emptyList(), host.onMain { done.toList() }, "只是告知的按确定键不动")
    }

    @Test
    fun `handling a notice with several actions from elsewhere opens on its first action once`() {
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = MANY, focusIndex = 1, focusAction = 0) }
        host.waitUntil("焦点在那一条的第一个操作上 (现在在 ${focusedText()})") { focusedText() == FIRST }
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = MANY)
        press(KeyEvent.KEYCODE_DPAD_UP, expect = ONE)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = MANY)
        assertEquals(MANY, host.onMain { focusedText() }, "回到这一条时照常落在行上, 不再跳进右栏")
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `closing a dialog opened by the second action lands back on that action`() {
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = MANY, focusIndex = 1, focusAction = 1) }
        host.waitUntil("焦点在开弹窗的那颗按钮上 (现在在 ${focusedText()})") { focusedText() == SECOND }
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = FIRST)
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = MANY)
    }

    @Test
    fun `long names are cut to three lines on touch and written in full with a remote`() {
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = LONG, focusIndex = 3) }
        host.waitUntil("一打开焦点在指定的那一条 (现在在 ${focusedText()})") { focusedText() == LONG }
        host.waitUntil("遥控器: 资源名写全 (现在 ${lineCountOf(LONG_NAME)} 行)") { (lineCountOf(LONG_NAME) ?: 0) > NAME_LINES }
        assertTrue(host.onMain { lineCountOf(DETAIL_BODY) } != null, "说明照常写")
        host.onMain { touch = true }
        host.waitUntil("触屏: 资源名截到三行 (现在 ${lineCountOf(LONG_NAME)} 行)") { lineCountOf(LONG_NAME) == NAME_LINES }
    }

    private fun item(text: String, atMillis: Long, vararg actions: String, detail: Boolean = false, longName: String? = null): TvShownNotice {
        val shown = actions.map { TvNoticeAction(it, TvNoticeDestination.Run {}) }
        val body = listOf(TvNoticeParagraph(DETAIL_BODY)) + listOfNotNull(longName?.let { TvNoticeParagraph(it, longName = true) })
        return TvShownNotice(
            NoticeEntry(Notice(kind = TestKind.id, key = text, atMillis = atMillis), pending = false, seen = true),
            text,
            TestKind,
            detail = if (detail) TvNoticeDetail(body = body, actions = shown) else null,
            actions = shown,
        )
    }

    /** 写着 [text] 的那段字排了几行; 没画出来为 null. 在主线程上调 */
    private fun lineCountOf(text: String): Int? {
        val root = (compose.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
        val node = (listOf(root) + root.descendants())
            .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } } ?: return null
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.firstOrNull()?.lineCount
    }

    private fun press(keyCode: Int, expect: String) {
        host.press(keyCode)
        host.waitUntil("按键后焦点在 $expect (现在在 ${focusedText()})") { focusedText() == expect }
    }

    /** 焦点所在那一项的字 (列表一行的标题, 或右栏按钮上的字); 在主线程上调 */
    private fun focusedText(): String? {
        val root = (compose.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
        val focused = (listOf(root) + root.descendants()).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true } ?: return null
        return (listOf(focused) + focused.descendants())
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .firstOrNull()
    }

    /** 右栏画了几颗操作按钮; 在主线程上调 */
    private fun actionButtons(): Int {
        val root = (compose.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return 0
        return root.descendants().count { it.config.getOrNull(SemanticsProperties.TestTag) == TV_NOTICE_ACTION_TAG }
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private object TestKind : TvNoticeKind {
        override val id: String = "test"
        override val category: TvNoticeCategory = TvNoticeCategory.Announcement
        override suspend fun title(notice: Notice): String = notice.key
        override fun destination(notice: Notice): TvNoticeDestination? = null
    }

    private companion object {
        const val ONE = "Notice with one action"
        const val ONE_ACTION = "Open it"
        const val MANY = "Notice with two actions"
        const val FIRST = "First action"
        const val SECOND = "Second action"
        const val NONE = "Notice without actions"
        const val DETAIL_BODY = "Why it happened and what can be done"
        const val LONG = "Notice with a long release name"
        const val LONG_ACTION = "Change release"
        const val LONG_NAME = "Original release: [Some Group & Another Group] A Very Long Show Title / Another Very Long Title Written Out In Full / The Third Alternative Title Of The Same Show - 01-13 [BDRip 1080p HEVC-10bit FLAC 5.1 + 2.0][Simplified and Traditional Chinese Subtitles][Batch Release With Specials]"
        const val NAME_LINES = 3
    }
}
