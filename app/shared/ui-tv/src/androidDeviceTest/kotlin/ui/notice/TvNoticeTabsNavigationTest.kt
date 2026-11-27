/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.NoticeEntry
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_section_earlier
import org.jetbrains.compose.resources.StringResource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 通知弹窗顶上的签 ([TvNoticeDialogBody] / [TvNoticeTabRow]) 与别的签里的两栏 ([TvNoticeColumns]), 按键同电视上的原生版: 签能聚焦、聚焦即选中,
 * 签上左右键换签、最右的签再往右到刷新、刷新上按左回到选中的签, 签与刷新上按下键进这个签的第一行; 列表第一行按上键到选中的签, 列表里左键不动、不换签;
 * 按确定键进右栏, 正文一打开停在末尾, 横排按钮上按上下键翻正文、焦点不动, 按了当场做完的操作焦点留在按钮上.
 */
class TvNoticeTabsNavigationTest {
    private val host = TvNativeTestHost()
    private lateinit var compose: ComposeView
    private val done = mutableListOf<String>()
    private val shown = mutableListOf<String>()
    private var dialogState by mutableStateOf(TvNoticeDialogState(fresh = emptySet(), focusKey = null, focusIndex = 0))
    private var tabs by mutableStateOf<List<TvNoticeTab>>(emptyList())
    private var ticketsLoaded by mutableStateOf(true)
    private var withWriteRow by mutableStateOf(false)
    private var refreshes = 0
    private val refreshUi = TvNoticeRefreshUi(
        label = REFRESH,
        status = "Refreshed 12 minutes ago",
        statusHighlight = false,
        busy = false,
        cooling = false,
    )

    private val tab = object : TvNoticeTab {
        override val id: String = TAB
        override val label: StringResource = Lang.tv_notice_section_earlier
        override val hasFresh: Flow<Boolean> = flowOf(true)
        override val bodyStartsAtEnd: Boolean = true
        override val buttonsForSingleAction: Boolean = true

        @Composable
        override fun rememberItems(): List<TvNoticeColumnItem>? =
            if (!ticketsLoaded) null else listOfNotNull(writeRow().takeIf { withWriteRow }, ticket(OPEN, INSTALL), ticket(CLOSED, ON_PHONE))

        @Composable
        override fun emptyText(): String = "No tickets"

        override fun onShown(key: String) {
            shown += key
        }
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        val notices = listOf(notice(FIRST_NOTICE, atMillis = 2), notice(SECOND_NOTICE, atMillis = 1))
        tabs = listOf(tab)
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.width(880.dp)) {
                        TvNoticeDialogBody(
                            notices,
                            dialogState,
                            tabs = tabs,
                            onAction = { _, _, action -> done += action.label },
                            onRead = {},
                            // 同 TvNotices.selectTab
                            onSelectTab = { id, intoList, focusKey ->
                                dialogState = TvNoticeDialogState(dialogState.fresh, focusKey = focusKey, focusIndex = 0, tab = id, focusOnTabs = !intoList)
                            },
                            onDismiss = {},
                            refreshUi = { refreshUi },
                            onRefresh = { refreshes++ },
                        )
                    }
                }
            }
        }
        host.waitUntil("一打开焦点在第一条通知") { focusedText() == FIRST_NOTICE }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `up from the first row goes to the selected tab and the tabs switch as focus moves`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_UP, expect = FIRST_NOTICE)
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 0)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0, host.onMain { focusedTab() }, "签上按上、最左的签按左不动")
        // 聚焦即选中: 两栏换成第二个签的, 焦点留在签上
        pressToTab(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        host.waitUntil("换到第二个签") { dialogState.tab == TAB && shows(OPEN) }
        assertEquals(1, host.onMain { focusedTab() })
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = REFRESH)
        pressToTab(KeyEvent.KEYCODE_DPAD_LEFT, 1)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = OPEN)
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 1)
        pressToTab(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        host.waitUntil("换回第一个签") { dialogState.tab == TvNoticeTab.NOTICES && shows(FIRST_NOTICE) }
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = FIRST_NOTICE)
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `a tab drawing one action as a button enters the pane and pages the body`() {
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = CLOSED, focusIndex = 0, tab = TAB) }
        host.waitUntil("一打开焦点在指定的那张单 (现在在 ${focusedText()})") { focusedText() == CLOSED }
        assertTrue(host.onMain { CLOSED in shown }, "右栏显示了它")
        press(KeyEvent.KEYCODE_DPAD_UP, expect = OPEN)
        host.waitUntil("右栏正文一打开停在末尾 (现在 ${bodyScroll()})") { bodyScroll().let { (value, max) -> max > 0f && value == max } }
        // 上键换到这一行后右栏整个换成它的; 紧接着 (同一两帧里) 就按确定时, tv36 上整包跑偶尔焦点落空、落到第一个签上
        // (单跑这个类、按下与抬起分开发都不出现, 原因还没查清). 遥控器上按两下之间总有这点空, 这里先等一下再按
        SystemClock.sleep(SETTLE_MILLIS)
        press(KeyEvent.KEYCODE_DPAD_CENTER, expect = INSTALL)
        assertEquals(emptyList(), host.onMain { done.toList() }, "按确定键只是进右栏, 不做操作")
        val end = host.onMain { bodyScroll().first }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("上键往前翻 (现在 ${bodyScroll()})") { bodyScroll().first < end }
        assertEquals(INSTALL, host.onMain { focusedText() }, "翻页时焦点不动")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("下键往后翻回末尾 (现在 ${bodyScroll()})") { bodyScroll().first == end }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定键做这个操作") { done == listOf(INSTALL) }
        assertEquals(INSTALL, host.onMain { focusedText() }, "当场做完的操作, 焦点留在按钮上")
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = OPEN)
    }

    @Test
    fun `left and right in the list do not switch tabs and right enters the pane`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(SECOND_NOTICE, host.onMain { focusedText() }, "没有操作的一行: 左右键都不动")
        assertEquals(TvNoticeTab.NOTICES, host.onMain { dialogState.tab }, "列表里不换签")
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = OPEN, focusIndex = 0, tab = TAB) }
        host.waitUntil("焦点在第一张单 (现在在 ${focusedText()})") { focusedText() == OPEN }
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = INSTALL)
        assertEquals(emptyList(), host.onMain { done.toList() }, "右键只是进右栏, 不做操作")
        // 右栏按返回回到那一行
        press(KeyEvent.KEYCODE_BACK, expect = OPEN)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(OPEN, host.onMain { focusedText() }, "列表里左键不动")
        assertEquals(TAB, host.onMain { dialogState.tab })
    }

    @Test
    fun `ok on refresh refreshes`() {
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 0)
        pressToTab(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = REFRESH)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定键刷新") { refreshes == 1 }
        assertEquals(REFRESH, host.onMain { focusedText() })
    }

    @Test
    fun `switching to a tab still loading keeps the old columns until its rows arrive`() {
        host.onMain { ticketsLoaded = false }
        SystemClock.sleep(SETTLE_MILLIS)
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 0)
        pressToTab(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        host.waitUntil("换到第二个签") { dialogState.tab == TAB }
        SystemClock.sleep(SETTLE_MILLIS)
        assertTrue(host.onMain { shows(FIRST_NOTICE) && !shows(OPEN) }, "新签的行还没取到: 两栏留着旧签的, 不空着")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(1, host.onMain { focusedTab() }, "还没取到时按下键不进旧签的列表")
        host.onMain { ticketsLoaded = true }
        host.waitUntil("取到了换成新签的两栏, 焦点留在签上") { shows(OPEN) && focusedTab() == 1 }
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = OPEN)
    }

    @Test
    fun `ok on a row with a row action does it at once without a button`() {
        host.onMain {
            withWriteRow = true
            dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = WRITE, focusIndex = 0, tab = TAB)
        }
        host.waitUntil("一打开焦点在「写」那一行 (现在在 ${focusedText()})") { focusedText() == WRITE }
        assertEquals(0, host.onMain { nodes().count { it.config.getOrNull(SemanticsProperties.TestTag) == TV_NOTICE_ACTION_TAG } }, "右栏不画按钮")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(WRITE, host.onMain { focusedText() }, "右栏没有能聚焦的, 右键不动")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定键直接做") { done == listOf(WRITE_ACTION) }
        assertEquals(WRITE, host.onMain { focusedText() }, "焦点留在那一行")
    }

    @Test
    fun `without tabs up from the first row goes to refresh`() {
        host.onMain { tabs = emptyList() }
        host.waitUntil("没有签时不画签") { focusedTab() == -1 && nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == TV_NOTICE_TAB_TAG } }
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = FIRST_NOTICE, focusIndex = 0) }
        host.waitUntil("焦点在第一条通知") { focusedText() == FIRST_NOTICE }
        press(KeyEvent.KEYCODE_DPAD_UP, expect = REFRESH)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(REFRESH, host.onMain { focusedText() }, "没有签时刷新上按左不动")
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = FIRST_NOTICE)
    }

    private fun writeRow() = TvNoticeColumnItem(
        key = WRITE,
        icon = Icons.Outlined.Feedback,
        iconLabel = WRITE,
        title = WRITE,
        atMillis = null,
        rowAction = TvNoticeAction(WRITE_ACTION, TvNoticeDestination.Run {}),
        blocks = listOf(TvNoticeParagraph("Scan the code to write it on your phone")),
    )

    private fun ticket(title: String, action: String) = TvNoticeColumnItem(
        key = title,
        icon = Icons.Outlined.Feedback,
        iconLabel = "Ticket",
        title = title,
        atMillis = 0,
        section = title,
        chips = listOf(TvNoticeChip("Bug"), TvNoticeChip("Open", accent = true)),
        meta = "#61",
        actions = listOf(TvNoticeAction(action, TvNoticeDestination.Run {})),
        blocks = List(RECORD_LINES) { TvNoticeParagraph("$title record $it") },
    )

    private fun notice(text: String, atMillis: Long) = TvShownNotice(
        NoticeEntry(Notice(kind = TestKind.id, key = text, atMillis = atMillis), pending = false, seen = true),
        text,
        TestKind,
    )

    private fun press(keyCode: Int, expect: String) {
        host.press(keyCode)
        host.waitUntil("按键后焦点在 $expect (现在在 ${focusedText()})") { focusedText() == expect }
    }

    /** 按 [keyCode] 后焦点到第 [index] 个签上 */
    private fun pressToTab(keyCode: Int, index: Int) {
        host.press(keyCode)
        host.waitUntil("按键后焦点在第 $index 个签上 (现在 ${focusedTab()})") { focusedTab() == index }
    }

    /** 画着写 [text] 的字; 在主线程上调 */
    private fun shows(text: String): Boolean =
        nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }

    private fun nodes(): List<SemanticsNode> {
        val root = (compose.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return emptyList()
        return listOf(root) + root.descendants()
    }

    /** 焦点所在那一项的字 (列表一行的标题, 或右栏按钮上的字); 在主线程上调 */
    private fun focusedText(): String? {
        val focused = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true } ?: return null
        return (listOf(focused) + focused.descendants())
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .firstOrNull()
    }

    /** 焦点在第几个签上; 不在签上为 -1. 在主线程上调 */
    private fun focusedTab(): Int = nodes()
        .filter { it.config.getOrNull(SemanticsProperties.TestTag) == TV_NOTICE_TAB_TAG }
        .indexOfFirst { tabNode -> (listOf(tabNode) + tabNode.descendants()).any { it.config.getOrNull(SemanticsProperties.Focused) == true } }

    /** 右栏正文滚到哪、最多能滚到哪; 在主线程上调 */
    private fun bodyScroll(): Pair<Float, Float> {
        val range = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == TV_NOTICE_BODY_TAG }
            ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return 0f to 0f
        return range.value() to range.maxValue()
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private object TestKind : TvNoticeKind {
        override val id: String = "test"
        override val category: TvNoticeCategory = TvNoticeCategory.Announcement
        override suspend fun title(notice: Notice): String = notice.key
        override fun destination(notice: Notice): TvNoticeDestination? = null
    }

    private companion object {
        const val TAB = "tickets"
        const val FIRST_NOTICE = "First notice"
        const val SECOND_NOTICE = "Second notice"
        const val OPEN = "Open ticket"
        const val CLOSED = "Closed ticket"
        const val INSTALL = "Install test build"
        const val ON_PHONE = "Add on phone"
        const val RECORD_LINES = 40
        const val SETTLE_MILLIS = 300L
        const val REFRESH = "Refresh"
        const val WRITE = "Write new feedback"
        const val WRITE_ACTION = "Write"
    }
}
