/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.graphics.Rect
import android.os.SystemClock
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
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.NoticeEntry
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeButtonView
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGlassTabView
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGlassTabBar
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_section_earlier
import org.jetbrains.compose.resources.StringResource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 通知弹窗的原生视图 ([TvNoticeNativeBody] / [TvNoticeView]; docs/contributing/code/notices.md 5.2、5.4): 签能聚焦 (玻璃标签栏), 列表第一行按上键到签,
 * 签上左右键当场换签、最右的签再往右到刷新, 按下键落在第一行; 列表里右键、确定键都是进右栏 (一个操作也画按钮, 在按钮上才做);
 * 按钮排一行, 上下键翻正文, 放不下时那一行滚到持焦的那颗; 返回键一次退一层 (右栏回到那一行, 列表里交给弹窗); 右栏说的那一行没了焦点落到原来位置上的那一行;
 * 打开弹窗的那次确定键的抬起不算. (Compose 版 —— iPad —— 的规则见 TvNoticeTabsNavigationTest.)
 */
class TvNoticeViewTest {
    private val host = TvNativeTestHost()
    private lateinit var compose: ComposeView
    private val done = mutableListOf<String>()
    private val shown = mutableListOf<String>()
    private val read = mutableListOf<String>()
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
                CompositionLocalProvider(LocalTvTouchInputEnabled provides touch, LocalThemeSettings provides ThemeSettings.Default, LocalSketch provides host.sketch) {
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
                    onRead = { read += it },
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
    fun `up from the first row goes to the selected tab and down returns to that row`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_UP, expect = FIRST_NOTICE)
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 0)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(0, host.onMain { view().focusedTabIndex }, "最左的签按左、签上按上不动")
        assertEquals(TvNoticeTab.NOTICES, host.onMain { view().shownTabId }, "不换签")
        assertEquals(FIRST_NOTICE, host.onMain { view().shownKey }, "右栏还说刚才那一条")
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = FIRST_NOTICE)
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `left and right on the tabs switch tabs at once and down after a switch lands on the first row`() {
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 0)
        pressToTab(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        host.waitUntil("聚焦即选中: 两栏当场换成第二个签的") { view().shownTabId == TAB && dialogState.tab == TAB }
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = REFRESH)
        pressToTab(KeyEvent.KEYCODE_DPAD_LEFT, 1)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = OPEN)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(OPEN, host.onMain { focusedText() }, "列表里左键不动, 不换签")
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 1)
        pressToTab(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        host.waitUntil("换回第一个签") { view().shownTabId == TvNoticeTab.NOTICES && dialogState.tab == TvNoticeTab.NOTICES }
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = FIRST_NOTICE)
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `back steps out one layer from the pane to its row and leaves the list to the dialog`() {
        host.onMain { dialogState = TvNoticeDialogState(fresh = emptySet(), focusKey = CLOSED, focusIndex = 0, tab = TAB) }
        host.waitUntil("焦点在第二张单上 (现在在 ${focusedText()})") { focusedText() == CLOSED }
        // 右栏的按钮上按返回: 回到那一行, 不到签上
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = ON_PHONE)
        press(KeyEvent.KEYCODE_BACK, expect = CLOSED)
        // 列表里按返回: 视图不拦, 交给弹窗关掉 (直接发给视图, 不让测试的页面退出)
        val consumed = host.onMain {
            view().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)) ||
                view().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        assertFalse(consumed, "列表里的返回键交给弹窗")
        assertEquals(CLOSED, host.onMain { focusedText() })
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `a single action is drawn as a button and ok on the row only enters the pane`() {
        host.onMain { notices = listOf(notice(FIRST_NOTICE, atMillis = 3, action = OPEN_ACTION), notice(SECOND_NOTICE, atMillis = 2)) }
        host.waitUntil("一个操作也画成按钮") { view().shownKey == FIRST_NOTICE && view().actionCount == 1 }
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = OPEN_ACTION)
        assertEquals(emptyList(), host.onMain { done.toList() }, "右键只是落到按钮上")
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = FIRST_NOTICE)
        // 列表里按确定同右键: 进右栏落到按钮上, 不做; 在按钮上按确定才做
        press(KeyEvent.KEYCODE_DPAD_CENTER, expect = OPEN_ACTION)
        assertEquals(emptyList(), host.onMain { done.toList() }, "列表里按确定不做")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按钮上按确定做") { done == listOf(OPEN_ACTION) }
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = FIRST_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(SECOND_NOTICE, host.onMain { focusedText() }, "没有操作、正文放得下: 右键不动")
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
    fun `ok on refresh refreshes`() {
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 0)
        pressToTab(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = REFRESH)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定键刷新") { refreshes == 1 }
        assertEquals(REFRESH, host.onMain { focusedText() })
    }

    @Test
    fun `on an empty tab focus waits on refresh and left goes to the tabs`() {
        host.onMain { notices = emptyList() }
        host.waitUntil("「通知」签空了, 焦点在刷新上 (现在在 ${focusedText()})") { focusedText() == REFRESH }
        pressToTab(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(0, host.onMain { view().focusedTabIndex }, "这个签一行都没有, 按下不动")
        pressToTab(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = OPEN)
    }

    @Test
    fun `switching on the tabs to a tab still loading keeps focus on the tab until its rows arrive`() {
        host.onMain { ticketsLoaded = false }
        host.waitUntil("视图知道这个签的行还在取") { !view().rowsLoaded(TAB) }
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 0)
        pressToTab(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(1, host.onMain { view().focusedTabIndex }, "行还没取到, 按下不动")
        host.onMain { ticketsLoaded = true }
        host.waitUntil("行到了两栏换成新签的") { view().shownTabId == TAB }
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = OPEN)
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
        host.waitUntil("没有签时不画签") { view().childrenOf<TvNativeGlassTabBar>().none { it.visibility == View.VISIBLE } }
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
        pressToTab(KeyEvent.KEYCODE_DPAD_UP, 1)
        pressToTab(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        pressToTab(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        host.waitUntil("换回来再告诉一次") { tabShown[TAB] == 2 }
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = WRITE)
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
        // 正文里按返回回到那一行
        press(KeyEvent.KEYCODE_BACK, expect = LONG_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = FIRST_NOTICE)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(FIRST_NOTICE, host.onMain { focusedText() }, "正文放得下的不进")
        assertEquals(emptyList(), host.onMain { done.toList() })
    }

    @Test
    fun `actions that do not fit in one row scroll to the focused one`() {
        val labels = listOf(FOUR_1, FOUR_2, FOUR_3, FOUR_4)
        host.onMain {
            val actions = labels.map { TvNoticeAction(it, TvNoticeDestination.Run {}) }
            notices = listOf(
                TvShownNotice(
                    NoticeEntry(Notice(kind = TestKind.id, key = FOUR, atMillis = 4), pending = false, seen = true),
                    FOUR,
                    TestKind,
                    detail = TvNoticeDetail(listOf(TvNoticeParagraph("Why it happened")), actions = actions),
                    actions = actions,
                ),
            ) + notices
        }
        host.waitUntil("新的那一条列出来了") { view().childrenOf<TvNoticeRowView>().any { it.entry?.id == FOUR } }
        press(KeyEvent.KEYCODE_DPAD_UP, expect = FOUR)
        press(KeyEvent.KEYCODE_DPAD_CENTER, expect = FOUR_1)
        assertEquals(4, host.onMain { view().actionCount })
        assertFalse(host.onMain { actionFullyShown(FOUR_4) }, "一行放不下: 最后一颗起初没露全")
        for (label in labels.drop(1)) {
            press(KeyEvent.KEYCODE_DPAD_RIGHT, expect = label)
            host.waitUntil("滚到 $label 露全") { actionFullyShown(label) }
        }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(FOUR_4, host.onMain { focusedText() }, "最右一颗按右不动")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(FOUR_4, host.onMain { focusedText() }, "上下键翻正文, 焦点不动")
        for (label in labels.dropLast(1).reversed()) press(KeyEvent.KEYCODE_DPAD_LEFT, expect = label)
        host.waitUntil("滚回第一颗露全") { actionFullyShown(FOUR_1) }
        press(KeyEvent.KEYCODE_DPAD_LEFT, expect = FOUR)
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
    fun `the pane reports a row with a dot read after showing it for a while`() {
        // 三条都是打开时没读过的 (先后不变、不分段), 第三条随后在别处读过了 (没点)
        host.onMain {
            notices = notices.map { if (it.key == THIRD_NOTICE) markedRead(it) else it }
            dialogState = TvNoticeDialogState(fresh = setOf(FIRST_NOTICE, SECOND_NOTICE, THIRD_NOTICE), focusKey = null, focusIndex = 0)
        }
        val opened = SystemClock.uptimeMillis()
        host.waitUntil("右栏说第一条说满一会儿报读过 (现在 $read)") { read == listOf(FIRST_NOTICE) }
        assertTrue(SystemClock.uptimeMillis() - opened >= TV_NOTICE_READ_AFTER.inWholeMilliseconds - 100, "不是一落上就报")
        // 标成读过 (同 TvNotices): 点灭掉, 焦点不挪
        host.onMain { notices = notices.map { if (it.key == FIRST_NOTICE) markedRead(it) else it } }
        host.waitUntil("读过的那一行不再带点") { view().childrenOf<TvNoticeRowView>().first { it.entry?.id == FIRST_NOTICE }.entry?.row?.item?.fresh == false }
        assertEquals(FIRST_NOTICE, host.onMain { focusedText() })
        // 一下划过第二条、停在没点的第三条上: 都不报
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = SECOND_NOTICE)
        press(KeyEvent.KEYCODE_DPAD_DOWN, expect = THIRD_NOTICE)
        SystemClock.sleep(TV_NOTICE_READ_AFTER.inWholeMilliseconds * 3 / 2)
        assertEquals(listOf(FIRST_NOTICE), host.onMain { read.toList() })
        // 回到带点的第二条停住: 报一次
        press(KeyEvent.KEYCODE_DPAD_UP, expect = SECOND_NOTICE)
        host.waitUntil("停在第二条上报读过 (现在 $read)") { read == listOf(FIRST_NOTICE, SECOND_NOTICE) }
    }

    private fun markedRead(notice: TvShownNotice) = TvShownNotice(notice.entry.copy(read = true), notice.title, notice.kind, actions = notice.actions)

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
        host.onMain { view().childrenOf<TvNativeGlassTabView>().first { it.tab?.id == TAB }.performClick() }
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

    /** 按 [keyCode] 后焦点到第 [index] 个签上 */
    private fun pressToTab(keyCode: Int, index: Int) {
        host.press(keyCode)
        host.waitUntil("按键后焦点在第 $index 个签上 (现在在 ${focusedText()})") { view().focusedTabIndex == index }
    }

    /** 写着 [label] 的那颗操作按钮整颗露在外面 (没被那一行的可见范围截掉); 在主线程上调 */
    private fun actionFullyShown(label: String): Boolean {
        val button = view().childrenOf<TvNativeButtonView>().firstOrNull { it.visibility == View.VISIBLE && it.text == label } ?: return false
        val visible = Rect()
        return button.getGlobalVisibleRect(visible) && visible.width() == button.width
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
        const val FOUR = "Notice with four actions"
        const val FOUR_1 = "Choose a release for episode 12"
        const val FOUR_2 = "Change download preferences"
        const val FOUR_3 = "Search again"
        const val FOUR_4 = "Do not download these 3 episodes"
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
