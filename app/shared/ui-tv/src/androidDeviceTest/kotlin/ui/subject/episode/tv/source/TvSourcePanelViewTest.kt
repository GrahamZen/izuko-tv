/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.foundation.tv.nativeview.testTextStyle
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 选源面板的按键导航: 方向键与确定键全由面板自己判 (见 [TvSourcePanelView]), 这里直接挂视图 (不经 Compose), 按键走 Instrumentation 注入.
 */
class TvSourcePanelViewTest {
    private val host = TvNativeTestHost()
    private val listener = RecordingPanelListener()
    private lateinit var view: TvSourcePanelView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            view = TvSourcePanelView(host.activity, testPanelStyle(), host.sketch)
            view.listener = listener
            host.root.addView(view, FrameLayout.LayoutParams(1920, 1080))
        }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun focusedId(): String? = host.onMain { focusedIdUnsafe() }

    private fun show(content: TvSourcePanelContent, entry: Boolean = true) {
        host.onMain {
            view.submit(content)
            if (entry) view.requestEntryFocus()
        }
    }

    private fun waitFocus(id: String) = host.waitUntil("焦点到 $id (现在 ${focusedIdUnsafe()})") { focusedIdUnsafe() == id }

    private fun focusedIdUnsafe(): String? = (view.findFocus() as? TvSourceRowView)?.row?.id

    /** 打开面板, 落在胶囊行 [content] 选中的那颗上 (上次停在胶囊上关的). */
    private fun showOnRail(content: TvSourcePanelContent) {
        host.onMain { view.resumeRow = content.railKey!! to null }
        show(content)
        waitFocus(content.railKey!!)
    }

    private fun rowView(id: String): TvSourceRowView? {
        fun find(v: View): TvSourceRowView? {
            if (v is TvSourceRowView && v.row?.id == id) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(view)
    }

    /** 胶囊行. 在主线程上调 (waitUntil 的条件就在主线程上跑). */
    private fun rail(): HorizontalGridView {
        fun find(v: View): HorizontalGridView? {
            if (v is HorizontalGridView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(view)!!
    }

    @Test
    fun `opening lands on the playing row of the selected source`() {
        show(webContent(railKey = "web:a"))
        waitFocus("line:1")
        assertEquals(true, listener.inRight)
    }

    @Test
    fun `opening lands on the rail pill when the source has nothing to pick`() {
        show(webContent(railKey = "web:a", rightRows = listOf(loadingRow())))
        waitFocus("web:a")
    }

    @Test
    fun `opening with a resume row lands on that row`() {
        host.onMain { view.resumeRow = "web:a" to "line:0" }
        show(webContent(railKey = "web:a"))
        waitFocus("line:0")
    }

    @Test
    fun `opening with a resume pill lands on that pill`() {
        showOnRail(webContent(railKey = "web:b"))
    }

    @Test
    fun `opening into the list lines the rail up with the selected source`() {
        // 胶囊行够长、选中的那颗在后面: 落进列表时胶囊行要已经对到它 (选中位置就是它)
        val longRail = (0 until 30).map { rail("web:$it") }
        val content = TvSourcePanelContent(
            status = "已查询 30 个数据源",
            rail = longRail,
            railKey = "web:25",
            right = TvSourceRight(key = "web:25", rows = listOf(line("r:0", selected = false))),
        )
        show(content)
        waitFocus("r:0")
        host.waitUntil("胶囊行对到 web:25") { rail().selectedPosition == 25 }
    }

    @Test
    fun `commands go to the header and sources to the rail`() {
        show(filterScreen())
        waitFocus("bt:0")
        host.waitUntil("胶囊行只有数据源") {
            (rail().adapter as TvSourceRowAdapter).currentList.map { it.id } == listOf(TvSourceRailKeys.BT, "web:a")
        }
    }

    @Test
    fun `rail pills are as wide as their names`() {
        val rows = listOf(rail("web:a"), rail("web:long").copy(title = "一个名字很长很长的在线数据源"))
        show(TvSourcePanelContent(status = "已查询 2 个数据源", rail = rows, railKey = "web:a", right = TvSourceRight(key = "web:a", rows = listOf(loadingRow()))))
        waitFocus("web:a")
        host.waitUntil("胶囊宽度按名字") {
            val short = rowView("web:a")?.width ?: 0
            val long = rowView("web:long")?.width ?: 0
            short in 1 until long && long < 1000
        }
    }

    @Test
    fun `left and right in the list switch to the neighbouring source`() {
        show(webContent(railKey = "web:a"))
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("web:b"), listener.railFocused)
        show(webContent(railKey = "web:b", rightRows = listOf(line("b:0", selected = false), line("b:1", selected = false))), entry = false)
        waitFocus("b:0")
        host.waitUntil("胶囊行对到 web:b") { rail().selectedPosition == 1 }
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf("web:b", "web:a"), listener.railFocused)
        // 回到 web:a: 也落第一行 (不落回上次停的、正在播的 line:1)
        show(webContent(railKey = "web:a"), entry = false)
        waitFocus("line:0")
    }

    @Test
    fun `a short source sits under its pill and a wide one spans the width`() {
        show(webContent(railKey = "web:b"))
        waitFocus("line:1")
        host.waitUntil("web:b 的列表左缘对齐它的胶囊 (胶囊 ${screenX("web:b")}, 行 ${screenX("line:0")})") {
            val pill = screenX("web:b")
            val row = screenX("line:0")
            pill != null && row != null && abs(pill - row) <= 1 && (rowView("line:0")?.width ?: 0) < 1200
        }
        show(btContent(focusId = "bt:0").let { it.copy(right = it.right.copy(wide = true)) }, entry = false)
        host.waitUntil("BT 占满宽度") { (rowView("bt:0")?.width ?: 0) == 1920 - 96 - 96 }
    }

    @Test
    fun `a short list without icons is only as wide as its rows`() {
        show(webContent(railKey = "web:b", rightRows = listOf(line("x:0", selected = false), line("x:1", selected = false))))
        waitFocus("x:0")
        host.waitUntil("宽度按内容 (现在 ${rowView("x:0")?.width})") {
            val width = rowView("x:0")?.width ?: 0
            // 下限 240 与胶囊宽之间; 没有带图标的行, 不留图标位
            width in 240 until 400
        }
    }

    @Test
    fun `a short list widens for more rows and narrows back when they go away`() {
        val short = listOf(line("x:0", selected = false), line("x:1", selected = false))
        show(webContent(railKey = "web:b", rightRows = short))
        waitFocus("x:0")
        host.waitUntil("先按两行量") { (rowView("x:0")?.width ?: 0) > 0 }
        val narrow = host.onMain { rowView("x:0")!!.width }
        // 展开「其余」: 多了一行长的, 变宽
        val long = line("x:other", selected = false).copy(title = "一个名字很长很长很长的其余结果")
        show(webContent(railKey = "web:b", rightRows = short + long), entry = false)
        host.waitUntil("展开后变宽") { (rowView("x:0")?.width ?: 0) > narrow }
        // 收起: 回到原来的宽度
        show(webContent(railKey = "web:b", rightRows = short), entry = false)
        host.waitUntil("收起后变回去 (现在 ${rowView("x:0")?.width}, 原来 $narrow)") { rowView("x:0")?.width == narrow }
    }

    @Test
    fun `a very long row widens the list up to the full width`() {
        val long = line("x:long", selected = false).copy(title = "名字特别特别长".repeat(12))
        show(webContent(railKey = "web:b", rightRows = listOf(line("x:0", selected = false), long)))
        waitFocus("x:0")
        host.waitUntil("最宽同 BT 占满宽度 (现在 ${rowView("x:0")?.width})") { rowView("x:0")?.width == 1920 - 96 - 96 }
    }

    @Test
    fun `the settled position of a pill is known before the rail stops`() {
        // 宽窄不一、有的带分隔线 / 数 / 转圈: 估的宽度与排出来的要一致
        val longRail = (0 until 30).map {
            rail("web:$it").copy(
                title = "数据源$it".repeat(1 + it % 3),
                trailing = if (it % 4 == 0) "12" else "",
                loading = it % 7 == 3,
                dividerAbove = it % 10 == 5,
            )
        }
        val content = TvSourcePanelContent(
            status = "已查询 30 个数据源",
            rail = longRail,
            railKey = "web:0",
            right = TvSourceRight(key = "web:0", rows = listOf(loadingRow())),
        )
        showOnRail(content)
        // 开头、中间、靠后 (没排出来、按字量) 与最后一颗 (右边贴边)
        for (key in listOf("web:12", "web:27", "web:0", "web:29")) {
            val predicted = host.onMain { view.settledPillLeft(key) }!!
            host.onMain { view.focusRow(null, key, inRail = true) }
            waitFocus(key)
            host.waitUntil("$key 停稳后的左缘同估的 $predicted (现在 ${pillLeft(key)})") {
                rail().scrollState == RecyclerView.SCROLL_STATE_IDLE && pillLeft(key)?.let { abs(it - predicted) <= 1f } == true
            }
        }
    }

    /** 那一颗胶囊在面板里的左缘 (不算聚焦放大). 在主线程上调. */
    private fun pillLeft(id: String): Float? = rowView(id)?.let { (it.parent as View).left + it.left + it.translationX }

    /** 那一行 / 那一颗在屏幕上的左缘. 在主线程上调. */
    private fun screenX(id: String): Int? = rowView(id)?.let { v -> IntArray(2).also { v.getLocationOnScreen(it) }[0] }

    @Test
    fun `holding right in the list moves only the rail and switches once on release`() {
        show(webContent(railKey = "web:a"))
        waitFocus("line:1")
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1)
        assertTrue(listener.railFocused.isEmpty(), "按着时不换")
        assertEquals("line:1", focusedId())
        host.waitUntil("胶囊行挪到 BT") { rail().selectedPosition == 2 }
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(TvSourceRailKeys.BT), listener.railFocused)
    }

    @Test
    fun `switching from the list to a source with nothing to pick lands on its pill`() {
        show(webContent(railKey = "web:a"))
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        show(webContent(railKey = "web:b", rightRows = listOf(loadingRow())), entry = false)
        waitFocus("web:b")
    }

    @Test
    fun `edge keys are consumed and keep the focus`() {
        // BT 是最后一个源 (重新搜索在表头)
        show(btContent(focusId = "bt:2"))
        waitFocus("bt:2")
        for (code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            assertTrue(host.onMain { dispatch(code) }, "到头的方向键被面板吃掉")
            assertEquals("bt:2", focusedId())
        }
        assertTrue(listener.railFocused.isEmpty())
    }

    @Test
    fun `left and right inside a drill do not switch the source`() {
        show(drillContent())
        waitFocus("opt:720P")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("opt:720P", focusedId())
        assertTrue(listener.railFocused.isEmpty())
    }

    @Test
    fun `up goes from the rows to the pills and the rail and down comes back`() {
        show(btContent(focusId = "bt:0"))
        waitFocus("bt:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("pill:episode")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("pill:Resolution")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.BT)
        assertEquals(false, listener.inRight)
        // 下键落回这一屏上次停的 (小胶囊), 再下进列表落回上次那一行
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("pill:Resolution")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("bt:0")
    }

    @Test
    fun `left and right on the rail switch the source`() {
        showOnRail(webContent(railKey = "web:a"))
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("web:b")
        assertEquals(listOf("web:b"), listener.railFocused)
        // web:b 的内容还没到就按回来: 照样要请换回 web:a (此刻的内容虽然还是 web:a, 宿主那边已经在换 web:b 了)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("web:a")
        assertEquals(listOf("web:b", "web:a"), listener.railFocused)
    }

    @Test
    fun `holding right on the rail switches only on release`() {
        showOnRail(webContent(railKey = "web:a"))
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1)
        waitFocus(TvSourceRailKeys.BT)
        assertTrue(listener.railFocused.isEmpty(), "按着时不换")
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(TvSourceRailKeys.BT), listener.railFocused)
    }

    @Test
    fun `entering waits for the screen of the newly selected source`() {
        showOnRail(webContent(railKey = "web:a"))
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("web:b")
        // 下面还是 web:a 的内容: 下键先记着
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("web:b", focusedId())
        show(webContent(railKey = "web:b", rightRows = listOf(line("b-line", selected = false))), entry = false)
        waitFocus("b-line")
    }

    @Test
    fun `up from the rail goes to the header and stays there`() {
        show(webContent(railKey = "web:a"))
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("web:a")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.ACTION_REFRESH)
        // 表头再按上: 不收面板 (关面板只有返回键), 焦点不动
        assertTrue(host.onMain { dispatch(KeyEvent.KEYCODE_DPAD_UP) })
        assertEquals(TvSourceRailKeys.ACTION_REFRESH, focusedId())
        assertEquals(0, listener.closeRequests)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a")
    }

    @Test
    fun `holding up from the rail does not close the panel`() {
        showOnRail(webContent(railKey = "web:a"))
        host.keyDown(KeyEvent.KEYCODE_DPAD_UP)
        host.keyDown(KeyEvent.KEYCODE_DPAD_UP, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(TvSourceRailKeys.ACTION_REFRESH, focusedId())
        assertEquals(0, listener.closeRequests)
    }

    @Test
    fun `header commands run on confirm`() {
        showOnRail(webContent(railKey = "web:a"))
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.ACTION_REFRESH)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.Refresh), listener.actions)
    }

    @Test
    fun `the filter in the header asks to open the filter dialog`() {
        showOnRail(filterScreen())
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.FILTER)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.OpenFilter), listener.actions)
        // 不换下面的内容, 焦点留在「筛选」上 (弹窗关掉后回到这里)
        assertTrue(listener.railFocused.isEmpty())
        assertEquals(TvSourceRailKeys.FILTER, focusedId())
    }

    @Test
    fun `the selected pill stays at the left edge until the last ones`() {
        val longRail = (0 until 30).map { rail("web:$it") }
        fun content(key: String) = TvSourcePanelContent(
            status = "已查询 30 个数据源",
            rail = longRail,
            railKey = key,
            right = TvSourceRight(key = key, rows = listOf(loadingRow())),
        )
        // 中间的一颗: 左缘对齐内容的左边距 (聚焦放大 1.03 倍, 屏幕上的左缘往外扩几个像素)
        showOnRail(content("web:10"))
        host.waitUntil("web:10 靠左 (现在 ${screenX("web:10")})") { screenX("web:10")?.let { abs(it - 96) <= 4 } == true }
        // 最后一颗: 胶囊行到头不再往左滚, 它停在右边
        host.onMain { view.focusRow(null, "web:29", inRail = true) }
        waitFocus("web:29")
        host.waitUntil("web:29 停在右边 (现在 ${screenX("web:29")})") { (screenX("web:29") ?: 0) > 960 }
    }

    @Test
    fun `back in the list is left to the host to hide the panel and reports where it was`() {
        show(webContent(railKey = "web:a"))
        waitFocus("line:1")
        assertFalse(host.onMain { view.handleBack() })
        assertEquals("web:a" to "line:1", host.onMain { view.rightRowPosition() })
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("web:a")
        assertFalse(host.onMain { view.handleBack() })
        assertEquals(null, host.onMain { view.rightRowPosition() })
        // 胶囊上关的也记: 那个源
        assertEquals("web:a" to null, host.onMain { view.focusPosition() })
    }

    @Test
    fun `confirm runs the row action and holding it runs the long action`() {
        show(btContent(focusId = "bt:0"))
        waitFocus("bt:0")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.Refresh), listener.actions)
        listener.actions.clear()

        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(LONG_PRESS_WAIT_MILLIS)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.ToggleExcluded), listener.actions, "长按只跑长按动作, 抬起不再跑短按")
    }

    @Test
    fun `holding confirm on a row with details opens them and focusRow lands on the row last viewed`() {
        val rows = listOf(line("line:0", selected = false), line("line:1", selected = false)).map {
            it.copy(details = TvSourceDetails(title = it.title))
        }
        show(webContent(railKey = "web:a", rightRows = rows))
        waitFocus("line:0")

        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(LONG_PRESS_WAIT_MILLIS)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.ShowDetails("line:0", inRail = false, railKey = "web:a")), listener.actions)

        host.onMain { view.focusRow("web:a", "line:1", inRail = false) }
        waitFocus("line:1")
    }

    @Test
    fun `focusRow under another source switches the rail and lands on the row once its screen arrives`() {
        show(webContent(railKey = "web:a"))
        waitFocus("line:1")
        host.onMain { view.focusRow("web:b", "line:0", inRail = false) }
        host.waitUntil("胶囊行换到 web:b") { listener.railFocused.lastOrNull() == "web:b" }
        // 状态按新的源拼好的内容到了: 落到那一行 (不是这一屏的默认落点 line:1)
        show(webContent(railKey = "web:b"), entry = false)
        waitFocus("line:0")
    }

    @Test
    fun `a confirm release without its press is ignored`() {
        show(btContent(focusId = "bt:0"))
        waitFocus("bt:0")
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(listener.actions.isEmpty())
    }

    @Test
    fun `a new screen while focused lands on its target and the old screen is remembered`() {
        show(btContent(focusId = "bt:0"))
        waitFocus("bt:0")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("bt:1")
        show(drillContent(), entry = false)
        waitFocus("opt:720P")
        show(btContent(focusId = "bt:0"), entry = false)
        waitFocus("bt:1")
    }

    @Test
    fun `focus moves from a fallback pill to the rows once they arrive`() {
        val pill = TvSourceRow(id = "mpill:back", style = TvSourceRowStyle.Pill, title = "返回结果")
        fun manual(rows: List<TvSourceRow>) = TvSourcePanelContent(
            status = "已查询 3 个数据源",
            rail = railRows,
            railKey = "web:a",
            right = TvSourceRight(key = "web:a", pills = listOf(pill), rows = rows),
        )
        show(manual(listOf(TvSourceRow(id = "status:loading", style = TvSourceRowStyle.Status, title = "正在加载剧集…"))))
        waitFocus("mpill:back")
        show(manual(listOf(line("ep:0", selected = false), line("ep:1", selected = false))), entry = false)
        waitFocus("ep:0")
    }

    /** 表头有「筛选」与重新搜索, 胶囊行是 BT 与一个在线源. */
    private fun filterScreen() = TvSourcePanelContent(
        status = "已查询 1 个数据源",
        rail = listOf(
            rail(TvSourceRailKeys.FILTER, TvSourceAction.OpenFilter),
            rail(TvSourceRailKeys.BT),
            rail("web:a"),
            rail(TvSourceRailKeys.ACTION_REFRESH, TvSourceAction.Refresh),
        ),
        railKey = TvSourceRailKeys.BT,
        right = TvSourceRight(
            key = TvSourceRailKeys.BT,
            pills = listOf(TvSourceRow(id = "pill:episode", style = TvSourceRowStyle.Pill, title = "仅本集", action = TvSourceAction.ToggleEpisodeFilter)),
            rows = listOf(line("bt:0", selected = false)),
        ),
    )

    private fun dispatch(code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return down
    }

    private class RecordingPanelListener : TvSourcePanelListener {
        val railFocused = mutableListOf<String>()
        val actions = mutableListOf<TvSourceAction>()
        var inRight: Boolean? = null

        override fun onRailFocused(key: String) {
            railFocused += key
        }

        override fun onRightFocusChanged(inRight: Boolean) {
            this.inRight = inRight
        }

        override fun onAction(action: TvSourceAction) {
            actions += action
        }

        override fun onBackInRight(): Boolean = false
        var closeRequests = 0

        override fun onCloseRequested() {
            closeRequests++
        }
    }

    private companion object {
        /** 长按阈值 (LONG_PRESS_MIN_HOLD 350ms) 之后. */
        const val LONG_PRESS_WAIT_MILLIS = 420L

        fun rail(id: String, action: TvSourceAction? = null) =
            TvSourceRow(id = id, style = TvSourceRowStyle.Rail, title = id, action = action)

        fun line(id: String, selected: Boolean) =
            TvSourceRow(id = id, style = TvSourceRowStyle.Line, title = id, meta = "1080P", selected = selected, action = TvSourceAction.Refresh)

        fun loadingRow() = TvSourceRow(id = "status:loading", style = TvSourceRowStyle.Status, title = "正在查询…")

        val railRows = listOf(
            rail("web:a"),
            rail("web:b"),
            rail(TvSourceRailKeys.BT),
            rail(TvSourceRailKeys.ACTION_REFRESH, TvSourceAction.Refresh),
        )

        fun webContent(
            railKey: String,
            rightRows: List<TvSourceRow> = listOf(line("line:0", selected = false), line("line:1", selected = true)),
        ) = TvSourcePanelContent(
            status = "已查询 3 个数据源",
            rail = railRows,
            railKey = railKey,
            right = TvSourceRight(key = railKey, rows = rightRows, focusId = rightRows.firstOrNull { it.selected }?.id),
        )

        fun btContent(focusId: String) = TvSourcePanelContent(
            status = "已查询 3 个数据源",
            rail = railRows,
            railKey = TvSourceRailKeys.BT,
            right = TvSourceRight(
                key = TvSourceRailKeys.BT,
                pills = listOf(
                    TvSourceRow(id = "pill:episode", style = TvSourceRowStyle.Pill, title = "仅本集", action = TvSourceAction.ToggleEpisodeFilter),
                    TvSourceRow(id = "pill:Resolution", style = TvSourceRowStyle.Pill, title = "分辨率"),
                ),
                rows = (0 until 3).map {
                    TvSourceRow(
                        id = "bt:$it",
                        style = TvSourceRowStyle.Resource,
                        title = "[喵萌奶茶屋] 孤独摇滚 0$it [1080P]",
                        meta = "1080P · 简日",
                        action = TvSourceAction.Refresh,
                        longAction = TvSourceAction.ToggleExcluded,
                    )
                },
                focusId = focusId,
            ),
        )

        fun drillContent() = TvSourcePanelContent(
            status = "已查询 3 个数据源",
            rail = railRows,
            railKey = TvSourceRailKeys.BT,
            right = TvSourceRight(
                key = TvSourceRailKeys.BT + "/filter/Resolution",
                title = "分辨率",
                rows = listOf("all", "1080P", "720P").map { TvSourceRow(id = "opt:$it", style = TvSourceRowStyle.Option, title = it) },
                focusId = "opt:720P",
            ),
        )

        fun testPanelStyle(): TvSourcePanelStyle {
            val icon = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            return TvSourcePanelStyle(
                paddingStartPx = 96,
                paddingEndPx = 96,
                paddingTopPx = 56,
                maxBottomFraction = 0.78f,
                headerGapPx = 24,
                railGapPx = 28,
                railPillHeightPx = 80,
                railPillGapPx = 20,
                railPillMaxWidthPx = 520,
                fadeLengthPx = 64,
                rowGapPx = 12,
                dividerGapPx = 28,
                dividerColor = 0x1FFFFFFF,
                cornerPx = 20f,
                rowPaddingHPx = 28,
                iconSizePx = 40,
                iconGapPx = 24,
                trailingGapPx = 20,
                spinnerSizePx = 32,
                lineRowHeightPx = 116,
                fileRowPaddingVPx = 18,
                metaGapPx = 32,
                metaSplitWidthPx = 300,
                branchMinWidthPx = 240,
                optionRowHeightPx = 92,
                cellHeightPx = 92,
                statusPaddingVPx = 20,
                pillHeightPx = 68,
                pillPaddingHPx = 28,
                pillGapPx = 16,
                pillsBottomGapPx = 24,
                drillTitleBottomGapPx = 20,
                focusBleedPx = 16,
                status = testTextStyle(24f, 32),
                drillTitle = testTextStyle(28f, 40),
                railTitle = testTextStyle(28f, 40),
                rowTitle = testTextStyle(28f, 40),
                rowMeta = testTextStyle(24f, 32),
                trailing = testTextStyle(24f, 32),
                pill = testTextStyle(28f, 40),
                cell = testTextStyle(28f, 40),
                idleBg = 0x59000000,
                activeBg = 0x99000000.toInt(),
                activeBorderPx = 3f,
                activeBorderColor = Color.WHITE,
                focusedBg = Color.WHITE,
                text = Color.WHITE,
                textSecondary = 0x9EFFFFFF.toInt(),
                focusedText = Color.BLACK,
                focusedTextSecondary = 0xA8000000.toInt(),
                error = Color.RED,
                focusedError = Color.RED,
                dimmedAlpha = 0.5f,
                chipPaddingHPx = 24,
                chipPaddingVPx = 12,
                branchFadeMillis = 0,
                focusScale = 1.03f,
                marqueeRepeat = 0,
                focusInMillis = 0,
                focusOutMillis = 0,
                animated = false,
                icons = TvSourceRowIcon.entries.associateWith { icon },
            )
        }
    }
}
