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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.NoticeEntry
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeButtonView
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_section_earlier
import org.jetbrains.compose.resources.StringResource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 通知弹窗的原生视图 ([TvNoticeNativeBody] / [TvNoticeView]): 规则同 Compose 版 (见 TvNoticeTabsNavigationTest) —— 签不能聚焦,
 * 列表第一行按上键到刷新、刷新上按下键回到那一行; 列表里左右键换签、落在那个签上次看的那一行, 最右的签按右键进右栏; 横排按钮上上下键翻正文; 返回键从按钮回到那一行;
 * 右栏说的那一行没了焦点落到原来位置上的那一行; 打开弹窗的那次确定键的抬起不算.
 */
class TvNoticeViewTest {
    private val host = TvNativeTestHost()
    private lateinit var compose: ComposeView
    private val done = mutableListOf<String>()
    private val shown = mutableListOf<String>()
    private val seen = mutableListOf<String>()
    private val tabShown = HashMap<String, Int>()
    private var dialogState by mutableStateOf(TvNoticeDialogState(fresh = emptySet(), focusKey = null, focusIndex = 0))
    private var tabs by mutableStateOf<List<TvNoticeTab>>(emptyList())
    private var notices by mutableStateOf(listOf(notice(FIRST_NOTICE, atMillis = 3), notice(SECOND_NOTICE, atMillis = 2), notice(THIRD_NOTICE, atMillis = 1)))
    private var ticketsLoaded by mutableStateOf(true)
    private var withWriteRow by mutableStateOf(false)
    private var touch by mutableStateOf(false)
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

        override fun onTabShown() {
            tabShown[TAB] = (tabShown[TAB] ?: 0) + 1
        }
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        tabs = listOf(tab)
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(LocalTvTouchInputEnabled provides touch) {
                    TvNoticeTestBody()
                }
            }
        }
        host.waitUntil("一打开焦点在第一条通知 (现在在 ${focusedText()})") { focusedText() == FIRST_NOTICE }
    }

    @Composable
    private fun TvNoticeTestBody() {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.width(880.dp)) {
                TvNoticeNativeBody(
                    notices,
                    dialogState,
                    tabs = tabs,
                    onAction = { _, _, action -> done += action.label },
                    onSeen = { seen += it },
                    // 同 TvNotices.selectTab
                    onSelectTab = { id, intoList, focusKey ->
                        dialogState = TvNoticeDialogState(dialogState.fresh, focusKey = focusKey, focusIndex = 0, tab = id, focusOnTabs = !intoList)
                    },
                    refreshUi = { refreshUi },
                    onRefresh = { refreshes++ },
                )
            }
        }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `up from the first row goes to refresh and the tabs never take focus`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_UP, expect = FIRST_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_UP, expect = REFRESH)
        assertTrue(host.onMain { view().childrenOf<TvNoticeTabView>().none { it.isFocusable } }, "签不能聚焦")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(REFRESH, host.onMain { focusedText() }, "刷新上左右、上键不动")
        assertEquals(TvNoticeTab.NOTICES, host.onMain { view().shownTabId }, "不换签")
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = FIRST_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = OPEN)
        press(KeyEvent.KEYCODE_DPAD_UP, expect = REFRESH)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = OPEN)
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `a tab drawing one action as a button enters the pane and pages the body`() {
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = CLOSED, focusIndex = 0, tab = TAB) }
        host.waitUntil("一打开焦点在指定的那张单 (现在在 ${focusedText()})") { focusedText() == CLOSED }
        assertTrue(host.onMain { CLOSED in shown }, "右栏显示了它")
        press(KeyEvent.KEYCODE_DPAD_UP, expect = OPEN)
        host.waitUntil("右栏正文一打开停在末尾 (现在 ${bodyScroll()})") { bodyScroll().let { (value, max) -> max > 0 && value == max } }
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
    fun `left and right in the list switch tabs and land on the row last seen there`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = OPEN)
        assertEquals(TAB, host.onMain { dialogState.tab }, "换到第二个签")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = INSTALL)
        assertEquals(TAB, host.onMain { dialogState.tab }, "最右的签按右键不绕回, 进右栏 (同确定键)")
        assertEquals(emptyList(), host.onMain { done.toList() }, "只是进右栏, 不做操作")
        press(KeyEvent.KEYCODE_BACK, expect = OPEN)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = CLOSED)
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = SECOND_NOTICE)
        assertEquals(TvNoticeTab.NOTICES, host.onMain { dialogState.tab })
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(SECOND_NOTICE, host.onMain { focusedText() }, "最左的签再按左不动")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = CLOSED)
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `ok on refresh refreshes`() {
        press(KeyEvent.KEYCODE_DPAD_UP, expect = REFRESH)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定键刷新") { refreshes == 1 }
        assertEquals(REFRESH, host.onMain { focusedText() })
    }

    @Test
    fun `on an empty tab refresh switches tabs with left and right`() {
        host.onMain { notices = emptyList() }
        host.waitUntil("「通知」签空了, 焦点在刷新上 (现在在 ${focusedText()})") { focusedText() == REFRESH }
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = OPEN)
        assertEquals(TAB, host.onMain { view().shownTabId })
        press(KeyEvent.KEYCODE_DPAD_UP, expect = REFRESH)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(REFRESH, host.onMain { focusedText() }, "这个签有行时刷新上左键不动")
    }

    @Test
    fun `switching from the list to a tab still loading waits on the tab and then enters its list`() {
        host.onMain { ticketsLoaded = false }
        host.waitUntil("视图知道这个签的行还在取") { !view().rowsLoaded(TAB) }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("焦点先停住等新签的行") { focusedText() == null }
        host.onMain { ticketsLoaded = true }
        host.waitUntil("行到了焦点进新签的列表 (现在在 ${focusedText()})") { focusedText() == OPEN && view().shownTabId == TAB }
    }

    @Test
    fun `ok on a row with a row action does it at once without a button`() {
        host.onMain {
            withWriteRow = true
            dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = WRITE, focusIndex = 0, tab = TAB)
        }
        host.waitUntil("一打开焦点在「写」那一行 (现在在 ${focusedText()})") { focusedText() == WRITE }
        assertEquals(0, host.onMain { view().actionCount }, "右栏不画按钮")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(WRITE, host.onMain { focusedText() }, "右栏没有能聚焦的, 右键不动")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定键直接做") { done == listOf(WRITE_ACTION) }
        assertEquals(WRITE, host.onMain { focusedText() }, "焦点留在那一行")
    }

    @Test
    fun `without tabs up from the first row goes to refresh`() {
        host.onMain { tabs = emptyList() }
        host.waitUntil("没有签时不画签") { view().childrenOf<TvNoticeTabView>().none { it.visibility == View.VISIBLE } }
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = FIRST_NOTICE, focusIndex = 0) }
        host.waitUntil("焦点在第一条通知") { view().appliedOpening === dialogState && focusedText() == FIRST_NOTICE }
        press(KeyEvent.KEYCODE_DPAD_UP, expect = REFRESH)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(REFRESH, host.onMain { focusedText() }, "没有签时刷新上按左不动")
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = FIRST_NOTICE)
    }

    @Test
    fun `a focused row removed from the list hands focus to the row in its place`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        host.onMain { notices = notices.filter { it.key != SECOND_NOTICE } }
        host.waitUntil("焦点落到原来位置上的那一行 (现在在 ${focusedText()})") { focusedText() == THIRD_NOTICE && view().shownKey == THIRD_NOTICE }
        press(KeyEvent.KEYCODE_DPAD_UP, expect = FIRST_NOTICE)
    }

    @Test
    fun `a row starting its body at the top overrides the tab and the tab is told when selected`() {
        assertEquals(0, host.onMain { tabShown[TAB] ?: 0 }, "还没选中第二个签")
        host.onMain {
            withWriteRow = true
            dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = WRITE, focusIndex = 0, tab = TAB)
        }
        host.waitUntil("一打开焦点在「写」那一行") { focusedText() == WRITE }
        assertEquals(1, host.onMain { tabShown[TAB] }, "打开时就在这个签, 告诉它一次")
        assertEquals(0, host.onMain { bodyScroll().first }, "「写」那一行的正文从开头看, 不随签停在末尾")
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = FIRST_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = WRITE)
        assertEquals(2, host.onMain { tabShown[TAB] }, "换回来再告诉一次")
    }

    @Test
    fun `a row without actions whose body does not fit enters the body to scroll`() {
        host.onMain {
            notices = listOf(
                TvShownNotice(
                    NoticeEntry(Notice(kind = TestKind.id, key = LONG_NOTICE, atMillis = 4), pending = false, seen = true),
                    LONG_NOTICE,
                    TestKind,
                    detail = TvNoticeDetail(List(RECORD_LINES) { TvNoticeParagraph("Line $it") }, actions = emptyList()),
                ),
            ) + notices
        }
        host.waitUntil("新的那一条列出来了") { view().childrenOf<TvNoticeRowView>().any { it.entry?.id == LONG_NOTICE } }
        press(KeyEvent.KEYCODE_DPAD_UP, expect = LONG_NOTICE)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("没有操作、正文放不下: 按确定键进正文") { view().bodyFocused }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("下键往后翻 (现在 ${bodyScroll()})") { bodyScroll().first > 0 }
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = LONG_NOTICE)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("再进正文") { view().bodyFocused }
        press(KeyEvent.KEYCODE_BACK, expect = LONG_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = FIRST_NOTICE)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(FIRST_NOTICE, host.onMain { focusedText() }, "正文放得下的不进")
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `the release of the ok that opened the dialog does nothing`() {
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = CLOSED, focusIndex = 0, tab = TAB) }
        host.waitUntil("焦点在那张单上") { focusedText() == CLOSED }
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(CLOSED, host.onMain { focusedText() }, "只有抬起的确定键不进右栏")
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `rows shown on screen are reported seen once`() {
        host.waitUntil("露出来的几行报看过 (现在 $seen)") { seen.toSet() == setOf(FIRST_NOTICE, SECOND_NOTICE, THIRD_NOTICE) }
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        assertEquals(3, host.onMain { seen.size }, "每行只报一次")
    }

    @Test
    fun `on touch a tap shows the row and draws even a single action as a button`() {
        host.onMain {
            notices = listOf(notice(FIRST_NOTICE, atMillis = 3, action = OPEN_ACTION), notice(SECOND_NOTICE, atMillis = 2, action = OPEN_ACTION))
            touch = true
        }
        host.waitUntil("触屏上一个操作也画成按钮") { view().shownKey == FIRST_NOTICE && view().actionCount == 1 }
        host.onMain { view().childrenOf<TvNoticeRowView>().first { it.entry?.id == SECOND_NOTICE }.performClick() }
        host.waitUntil("点一行右栏换成它, 不做操作") { view().shownKey == SECOND_NOTICE }
        assertEquals(emptyList(), host.onMain { done.toList() })
        assertTrue(host.onMain { view().childrenOf<TvNoticeRowView>().first { it.entry?.id == SECOND_NOTICE }.selectedLook }, "右栏说的那一行画成选中")
        host.onMain { view().childrenOf<TvNativeButtonView>().first { it.visibility == View.VISIBLE && it.text == OPEN_ACTION }.performClick() }
        host.waitUntil("点按钮做这个操作") { done == listOf(OPEN_ACTION) }
        host.onMain { view().childrenOf<TvNoticeTabView>().first { it.tabId == TAB }.performClick() }
        host.waitUntil("点签换过去") { view().shownTabId == TAB && dialogState.tab == TAB }
    }

    private fun writeRow() = TvNoticeColumnItem(
        key = WRITE,
        icon = Icons.Outlined.Feedback,
        iconLabel = WRITE,
        title = WRITE,
        atMillis = null,
        rowAction = TvNoticeAction(WRITE_ACTION, TvNoticeDestination.Run {}),
        bodyStartsAtEnd = false,
        blocks = listOf(TvNoticeQr(null, "Open the console", title = "Scan with your phone", status = "Waiting", console = true)) +
            List(RECORD_LINES) { TvNoticeParagraph("How to write it $it") },
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
        blocks = listOf(TvNoticeCard(Icons.Outlined.Feedback, "Test build app-debug.apk", "Downloading", progress = 0.4f)) +
            List(RECORD_LINES) { TvNoticeMessage("Maintainer · 11-20 10:00", "$title record $it", mine = it % 2 == 0) },
    )

    private fun notice(text: String, atMillis: Long, action: String? = null) = TvShownNotice(
        NoticeEntry(Notice(kind = TestKind.id, key = text, atMillis = atMillis), pending = false, seen = true),
        text,
        TestKind,
        actions = listOfNotNull(action?.let { TvNoticeAction(it, TvNoticeDestination.Run {}) }),
    )

    private fun press(keyCode: Int, expect: String) {
        host.press(keyCode)
        host.waitUntil("按键后焦点在 $expect (现在在 ${focusedText()})") { focusedText() == expect }
    }

    private fun view(): TvNoticeView = assertNotNull(compose.findChild<TvNoticeView>(), "没有画出原生视图")

    /** 焦点所在那一项的字; 在主线程上调 */
    private fun focusedText(): String? = compose.findChild<TvNoticeView>()?.focusedText()

    private fun bodyScroll(): Pair<Int, Int> = view().bodyScroll()

    private inline fun <reified T : View> View.findChild(): T? = allViews(this).firstOrNull { it is T } as T?

    private inline fun <reified T : View> View.childrenOf(): List<T> = allViews(this).filterIsInstance<T>()

    private fun allViews(root: View): List<View> {
        val found = ArrayList<View>()
        fun walk(v: View) {
            found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }

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
        const val THIRD_NOTICE = "Third notice"
        const val LONG_NOTICE = "Long notice"
        const val OPEN = "Open ticket"
        const val CLOSED = "Closed ticket"
        const val INSTALL = "Install test build"
        const val ON_PHONE = "Add on phone"
        const val OPEN_ACTION = "View"
        const val RECORD_LINES = 30
        const val REFRESH = "Refresh"
        const val WRITE = "Write new feedback"
        const val WRITE_ACTION = "Write"
    }
}
