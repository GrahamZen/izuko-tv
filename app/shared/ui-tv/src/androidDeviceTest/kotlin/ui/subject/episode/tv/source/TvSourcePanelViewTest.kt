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
import androidx.leanback.widget.VerticalGridView
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.foundation.tv.nativeview.testTextStyle
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

    private fun focusedId(): String? = host.onMain { (view.findFocus() as? TvSourceRowView)?.row?.id }

    private fun show(content: TvSourcePanelContent, entry: Boolean = true) {
        host.onMain {
            view.submit(content)
            if (entry) view.requestEntryFocus()
        }
    }

    private fun waitFocus(id: String) = host.waitUntil("焦点到 $id (现在 ${focusedIdUnsafe()})") { focusedIdUnsafe() == id }

    private fun focusedIdUnsafe(): String? = (view.findFocus() as? TvSourceRowView)?.row?.id

    /** 打开面板 (落在左栏那一项上), 再按右键进分支落到 [landOn]. */
    private fun showInRight(content: TvSourcePanelContent, landOn: String) {
        show(content)
        waitFocus(content.railKey!!)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(landOn)
    }

    private fun rowView(id: String): TvSourceRowView? {
        fun find(v: View): TvSourceRowView? {
            if (v is TvSourceRowView && v.row?.id == id) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(view)
    }

    private fun screenTop(id: String): Int? = rowView(id)?.let { v -> IntArray(2).also { v.getLocationOnScreen(it) }[1] }

    @Test
    fun `lands on the source of the playing row and right enters at that row`() {
        showInRight(webContent(railKey = "web:a"), "line:1")
    }

    @Test
    fun `the branch grows out of its rail row`() {
        show(webContent(railKey = "web:b"))
        waitFocus("web:b")
        host.waitUntil("分支第一行与左栏 web:b 顶边对齐 (左栏 ${screenTop("web:b")}, 分支 ${screenTop("line:0")})") {
            val rail = screenTop("web:b")
            val branch = screenTop("line:0")
            rail != null && branch != null && kotlin.math.abs(rail - branch) <= 1
        }
    }

    @Test
    fun `lands on the rail item without a playing row`() {
        show(webContent(railKey = "web:a"))
        waitFocus("web:a")
    }

    @Test
    fun `up and down move along the rail and report the focused item`() {
        show(webContent(railKey = "web:a"))
        waitFocus("web:a")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:b")
        assertEquals("web:b", listener.railFocused.last())
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("web:a")
    }

    @Test
    fun `right enters the list and left returns to the active rail item`() {
        show(webContent(railKey = "web:a"))
        waitFocus("web:a")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("line:1")
        assertEquals(true, listener.inRight)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("web:a")
        assertEquals(false, listener.inRight)
    }

    @Test
    fun `up from the first row goes to the pills and down comes back`() {
        showInRight(btContent(focusId = "bt:0"), "bt:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("pill:episode")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("pill:Resolution")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("bt:0")
    }

    @Test
    fun `left from the first pill returns to the rail`() {
        showInRight(btContent(focusId = "bt:0"), "bt:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("pill:episode")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus(TvSourceRailKeys.BT)
    }

    @Test
    fun `edge keys are consumed and keep the focus`() {
        show(webContent(railKey = "web:a"))
        waitFocus("web:a")
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)) {
            assertTrue(host.onMain { dispatch(code) }, "到头的方向键被面板吃掉")
            assertEquals("web:a", focusedId())
        }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("line:1")
        assertTrue(host.onMain { dispatch(KeyEvent.KEYCODE_DPAD_DOWN) })
        assertTrue(host.onMain { dispatch(KeyEvent.KEYCODE_DPAD_RIGHT) })
        assertEquals("line:1", focusedId())
    }

    @Test
    fun `back in the right column is left to the host to hide the panel and reports where it was`() {
        showInRight(webContent(railKey = "web:a"), "line:1")
        assertFalse(host.onMain { view.handleBack() })
        assertEquals("web:a" to "line:1", host.onMain { view.rightRowPosition() })
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("web:a")
        assertFalse(host.onMain { view.handleBack() })
        assertEquals(null, host.onMain { view.rightRowPosition() })
        // 左栏上关的也记: 左栏那一项
        assertEquals("web:a" to null, host.onMain { view.focusPosition() })
    }

    @Test
    fun `opening with a resume row lands on that row`() {
        host.onMain { view.resumeRow = "web:a" to "line:0" }
        show(webContent(railKey = "web:a"))
        waitFocus("line:0")
    }

    @Test
    fun `opening into the right column lines the rail up with its item`() {
        // 左栏够长、右栏所属的那一项在下面: 落进右栏时左栏要已经对到它 (选中位置就是它)
        val longRail = (0 until 30).map { rail("web:$it") }
        val content = TvSourcePanelContent(
            status = "已查询 30 个数据源",
            rail = longRail,
            railKey = "web:25",
            right = TvSourceRight(key = "web:25", rows = listOf(line("r:0", selected = false))),
        )
        host.onMain { view.resumeRow = "web:25" to "r:0" }
        show(content)
        waitFocus("r:0")
        host.waitUntil("左栏对到 web:25") { railSelectedPosition() == 25 }
    }

    /** 左栏 (行里有 web:25 的那一栏) 此刻选中的位置. */
    private fun railSelectedPosition(): Int = host.onMain {
        fun find(v: View): VerticalGridView? {
            if (v is VerticalGridView && (v.adapter as? TvSourceRowAdapter)?.currentList?.any { it.id == "web:25" } == true) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(view)?.selectedPosition ?: -1
    }

    @Test
    fun `confirm runs the row action and holding it runs the long action`() {
        showInRight(btContent(focusId = "bt:0"), "bt:0")
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
        waitFocus("web:a")
        host.onMain { view.focusRow("web:a", "line:0", inRail = false) }
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
    fun `focusRow under another rail item switches the rail and lands on the row once its screen arrives`() {
        showInRight(webContent(railKey = "web:a"), "line:1")
        host.onMain { view.focusRow("web:b", "line:0", inRail = false) }
        host.waitUntil("左栏换到 web:b") { listener.railFocused.lastOrNull() == "web:b" }
        // 状态按新的左栏项拼好的内容到了: 落到那一行 (不是这一屏的默认落点 line:1)
        show(webContent(railKey = "web:b"), entry = false)
        waitFocus("line:0")
    }

    @Test
    fun `left in the rail asks to close but left from the list back to the rail does not`() {
        show(webContent(railKey = "web:a"))
        waitFocus("web:a")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("web:a")
        assertEquals(0, listener.closeRequests)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, listener.closeRequests)
    }

    @Test
    fun `up and down past the ends of a source cross to the neighbouring source`() {
        showInRight(webContent(railKey = "web:a"), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("左栏跟到 web:b") { listener.railFocused.lastOrNull() == "web:b" }
        show(webContent(railKey = "web:b", rightRows = listOf(line("b:0", selected = false), line("b:1", selected = false))), entry = false)
        waitFocus("b:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("左栏跟回 web:a") { listener.railFocused.lastOrNull() == "web:a" }
        show(webContent(railKey = "web:a"), entry = false)
        waitFocus("line:1")
    }

    @Test
    fun `crossing skips a source whose rows have not arrived yet`() {
        showInRight(webContent(railKey = "web:a"), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("左栏跟到 web:b") { listener.railFocused.lastOrNull() == "web:b" }
        // web:b 还在查: 右栏只有一行说明 —— 接着跨到 BT
        show(webContent(railKey = "web:b", rightRows = listOf(TvSourceRow(id = "status:loading", style = TvSourceRowStyle.Status, title = "正在查询…"))), entry = false)
        host.waitUntil("左栏跟到 BT") { listener.railFocused.lastOrNull() == TvSourceRailKeys.BT }
        show(btContent(focusId = "bt:1"), entry = false)
        waitFocus("bt:0")
    }

    @Test
    fun `crossing skips rail items without a right column and reaches the next source`() {
        // web:a 下面先是没有右栏的「手动查找」, 再往下才是 web:b
        val rail = listOf(rail("web:a"), rail(TvSourceRailKeys.MANUAL), rail("web:b"), rail(TvSourceRailKeys.ACTION_REFRESH, TvSourceAction.Refresh))
        fun screen(railKey: String, rows: List<TvSourceRow>) = TvSourcePanelContent(
            status = "已查询 3 个数据源",
            rail = rail,
            railKey = railKey,
            right = TvSourceRight(key = railKey, rows = rows),
        )
        showInRight(screen("web:a", listOf(line("a:0", selected = false))), "a:0")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("左栏跟到 web:b") { listener.railFocused.lastOrNull() == "web:b" }
        show(screen("web:b", listOf(line("b:0", selected = false))), entry = false)
        waitFocus("b:0")
        // web:b 再往下只剩操作: 回左栏落在它上面
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(TvSourceRailKeys.ACTION_REFRESH)
    }

    @Test
    fun `down past the last row onto an item without rows goes back to the rail`() {
        showInRight(btContent(focusId = "bt:2"), "bt:2")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(TvSourceRailKeys.ACTION_REFRESH)
    }

    @Test
    fun `crossing up into a long source lands on its last row even when it is off screen`() {
        showInRight(webContent(railKey = "web:b", rightRows = listOf(line("b:0", selected = false), line("b:1", selected = false))), "b:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("左栏跟到 web:a") { listener.railFocused.lastOrNull() == "web:a" }
        show(webContent(railKey = "web:a", rightRows = (0 until 20).map { line("a:$it", selected = false) }), entry = false)
        waitFocus("a:19")
    }

    @Test
    fun `a confirm release without its press is ignored`() {
        showInRight(btContent(focusId = "bt:0"), "bt:0")
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(listener.actions.isEmpty())
    }

    @Test
    fun `a new right screen while focused lands on its target and the old screen is remembered`() {
        showInRight(btContent(focusId = "bt:0"), "bt:0")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("bt:1")
        show(drillContent(), entry = false)
        waitFocus("opt:720P")
        show(btContent(focusId = "bt:0"), entry = false)
        waitFocus("bt:1")
    }

    @Test
    fun `entering waits for the right screen of the newly focused rail item`() {
        show(webContent(railKey = "web:a"))
        waitFocus("web:a")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:b")
        // 右栏还是 web:a 的内容: 右键先记着
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("web:b", focusedId())
        show(webContent(railKey = "web:b", rightRows = listOf(line("b-line", selected = false))), entry = false)
        waitFocus("b-line")
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
        showInRight(manual(listOf(TvSourceRow(id = "status:loading", style = TvSourceRowStyle.Status, title = "正在加载剧集…"))), "mpill:back")
        show(manual(listOf(line("ep:0", selected = false), line("ep:1", selected = false))), entry = false)
        waitFocus("ep:0")
    }

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
                rightSampleTitle = "完成验证",
                rightSampleMeta = "在网页里完成验证后重新查询",
                rightMinWidthPx = 400,
                rightSlackPx = 24,
                maxRightWidthPx = 1040,
                paddingStartPx = 80,
                paddingEndPx = 96,
                paddingTopPx = 72,
                paddingBottomPx = 48,
                headerGapPx = 32,
                railWidthPx = 368,
                columnGapPx = 32,
                rowGapPx = 12,
                dividerGapPx = 28,
                dividerColor = 0x1FFFFFFF,
                cornerPx = 20f,
                rowPaddingHPx = 28,
                iconSizePx = 40,
                iconGapPx = 24,
                trailingGapPx = 20,
                spinnerSizePx = 32,
                railRowHeightPx = 88,
                lineRowHeightPx = 116,
                resourceRowHeightPx = 164,
                optionRowHeightPx = 92,
                cellHeightPx = 92,
                statusPaddingVPx = 20,
                textGapPx = 4,
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
                idleBg = 0x14FFFFFF,
                activeBg = 0x33FFFFFF,
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
                branchShiftPx = 32,
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
