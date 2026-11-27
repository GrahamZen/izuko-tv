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
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 通知弹窗的列表与右栏 ([TvNoticeContent]): 操作都画成按钮 (只有一个也画), 按确定键进右栏第一颗、在按钮上才做
 * (没有别的签时按右键也进), 操作排一行 (左右键在几颗之间走, 放不下时那一行滚到持焦的那颗), 在最左一颗按左键或在右栏按返回键回到那一条 (返回键不关弹窗).
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
            item(FOUR, atMillis = -1, FOUR_1, FOUR_2, FOUR_3, FOUR_4, detail = true),
        )
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(LocalTvTouchInputEnabled provides touch) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(880.dp)) {
                            // 同真机: 换了要落的那一条时通知弹窗是重新打开的 (如从去处页面回来), 初始焦点照新的落
                            key(dialogState) {
                                TvNoticeContent(
                                    items,
                                    dialogState,
                                    onAction = { _, _, action -> done += action.label },
                                    onRead = {},
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
    fun `ok on a notice with one action enters its button instead of doing it`() {
        host.waitUntil("只有一个操作也画按钮") { actionButtons() == 1 }
        press(KeyEvent.KEYCODE_DPAD_CENTER, expect = ONE_ACTION)
        assertEquals(emptyList(), host.onMain { done.toList() }, "列表里按确定只是进右栏")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按钮上按确定做") { done == listOf(ONE_ACTION) }
    }

    @Test
    fun `ok or right on a notice with several actions moves to the first action and left goes back`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = MANY)
        host.waitUntil("右栏画出两颗操作") { actionButtons() == 2 }
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = FIRST)
        assertEquals(emptyList(), host.onMain { done.toList() }, "没有别的签: 右键同确定键只是进右栏")
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = MANY)
        press(KeyEvent.KEYCODE_DPAD_CENTER, expect = FIRST)
        assertEquals(emptyList(), host.onMain { done.toList() }, "按确定键只是进右栏, 不做操作")
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = MANY)
        press(KeyEvent.KEYCODE_DPAD_CENTER, expect = FIRST)
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
        press(KeyEvent.KEYCODE_DPAD_CENTER, expect = FIRST)
        press(KeyEvent.KEYCODE_BACK, expect = MANY)
        assertFalse(host.onMain { dismissed }, "返回键回到列表, 不关弹窗")
    }

    @Test
    fun `right enters the button of a notice with one action and stays on a notice with none`() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = ONE_ACTION)
        assertEquals(emptyList(), host.onMain { done.toList() }, "右键只是落到按钮上")
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = ONE)
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
        press(KeyEvent.KEYCODE_BACK, expect = MANY)
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
    fun `actions that do not fit in one row scroll to the focused one`() {
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = FOUR, focusIndex = 4, focusAction = 0) }
        host.waitUntil("焦点在那一条的第一个操作上 (现在在 ${focusedText()})") { focusedText() == FOUR_1 }
        assertEquals(4, host.onMain { actionButtons() })
        assertFalse(host.onMain { actionFullyShown(FOUR_4) }, "一行放不下: 最后一颗起初没露全")
        for (label in listOf(FOUR_2, FOUR_3, FOUR_4)) {
            press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = label)
            host.waitUntil("滚到 $label 露全") { actionFullyShown(label) }
        }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(FOUR_4, host.onMain { focusedText() }, "最右一颗按右不动")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(FOUR_4, host.onMain { focusedText() }, "上下键翻正文, 焦点不动")
        for (label in listOf(FOUR_3, FOUR_2, FOUR_1)) press(KeyEvent.KEYCODE_DPAD_LEFT, expect = label)
        host.waitUntil("滚回第一颗露全") { actionFullyShown(FOUR_1) }
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = FOUR)
        assertEquals(emptyList(), host.onMain { done.toList() })
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

    /** 写着 [label] 的那颗操作按钮整颗露在外面 (没被那一行的可见范围截掉); 在主线程上调 */
    private fun actionFullyShown(label: String): Boolean {
        val root = (compose.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return false
        val button = root.descendants().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) == TV_NOTICE_ACTION_TAG &&
                node.descendants().any { d -> d.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }
        } ?: return false
        return abs(button.boundsInRoot.width - button.size.width) < 1f
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
        const val FOUR = "Notice with four actions"
        const val FOUR_1 = "Choose a release for episode 12"
        const val FOUR_2 = "Change download preferences"
        const val FOUR_3 = "Search again"
        const val FOUR_4 = "Do not download these 3 episodes"
    }
}
