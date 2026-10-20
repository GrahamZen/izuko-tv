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
import android.widget.TextView
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
 * 选源面板的按键导航与排版: 方向键与确定键全由面板自己判 (见 [TvSourcePanelView]), 这里直接挂视图 (不经 Compose), 按键走 Instrumentation 注入.
 *
 * 胶囊行: 「组 a」(三个源, 有源那一栏)、「组 b」(只有一个源, 条目直接在胶囊下面)、BT; 表头是重新搜索.
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

    /** 打开面板, 落在列表 [rowId] 那一行 (上次停在那一行关的). */
    private fun showAt(content: TvSourcePanelContent, rowId: String) {
        host.onMain { view.resumeRow = TvSourceFocusPosition(content.railKey!!, content.sourceKey, rowId) }
        show(content)
        waitFocus(rowId)
    }

    /** 换一个新的面板视图 (关掉后再打开), 回到 [point] 再打开 [content]. */
    private fun reopenInNewView(point: TvSourceResumePoint?, content: TvSourcePanelContent) {
        host.onMain {
            host.root.removeView(view)
            view = TvSourcePanelView(host.activity, testPanelStyle(), host.sketch)
            view.listener = listener
            host.root.addView(view, FrameLayout.LayoutParams(1920, 1080))
            view.resumeAt(point)
        }
        show(content)
    }

    /** 打开面板, 落在胶囊行 [content] 选中的那颗上 (上次停在胶囊上关的). */
    private fun showOnRail(content: TvSourcePanelContent) {
        host.onMain { view.resumeRow = TvSourceFocusPosition(content.railKey!!, null, null) }
        show(content)
        waitFocus(content.railKey!!)
    }

    /** 打开面板, 落在源那一栏选中的源上 (上次停在这一栏关的). */
    private fun showOnColumn(content: TvSourcePanelContent) {
        host.onMain { view.resumeRow = TvSourceFocusPosition(content.railKey!!, content.sourceKey, null) }
        show(content)
        waitFocus(content.sourceKey!!)
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

    /** 胶囊 [id] 正上方最近的表头按钮 (「正在播放」与命令按钮里, 横向中线离得最近的). 在主线程上调. */
    private fun nearestHeaderAbove(id: String): String {
        fun centerX(v: View) = IntArray(2).also { v.getLocationOnScreen(it) }[0] + v.width / 2
        val x = centerX(rowView(id)!!)
        val rail = rail()
        fun inRail(v: View): Boolean {
            var p = v.parent
            while (p != null) {
                if (p === rail) return true
                p = p.parent
            }
            return false
        }
        val items = mutableListOf<TvSourceRowView>()
        fun collect(v: View) {
            if (v is TvSourceRowView && (v.row?.style == TvSourceRowStyle.Rail) && !inRail(v) && v.isShown) {
                val id = v.row!!.id
                if (id == TvSourceRailKeys.NOW_PLAYING || isCommandKey(id)) items += v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(view)
        return items.minBy { abs(centerX(it) - x) }.row!!.id
    }

    /** 表头里 [id] 那一颗正下方最近的胶囊 (横向中线离得最近的). 在主线程上调. */
    private fun nearestPillBelow(id: String): String {
        fun centerX(v: View) = IntArray(2).also { v.getLocationOnScreen(it) }[0] + v.width / 2
        val x = centerX(rowView(id)!!)
        val pills = (0 until rail().childCount).mapNotNull { rail().getChildAt(it) as? TvSourceRowView }
        return pills.minBy { abs(centerX(it) - x) }.row!!.id
    }

    /** 那一行的顶边 / 左缘 (面板坐标, 不算聚焦放大). 在主线程上调. */
    private fun rowTop(id: String): Int? = rowView(id)?.let { (it.parent as View).top + it.top }
    private fun rowBottom(id: String): Int? = rowView(id)?.let { (it.parent as View).top + it.bottom }
    private fun rowLeft(id: String): Int? = rowView(id)?.let { (it.parent as View).left + it.left }

    /** 那一行 / 那一颗在屏幕上的左缘. 在主线程上调. */
    private fun screenX(id: String): Int? = rowView(id)?.let { v -> IntArray(2).also { v.getLocationOnScreen(it) }[0] }

    // ---- 打开 ----

    @Test
    fun `opening lands on the now playing pill and down goes to the pill right below it`() {
        fun listAlpha() = (rowView("line:0")?.parent as? View)?.alpha
        fun columnAlpha() = (rowView("web:a1")?.parent as? View)?.alpha
        show(playingContent())
        waitFocus(TvSourceRailKeys.NOW_PLAYING)
        // 焦点在表头上: 胶囊行下面都收着
        host.waitUntil("源那一栏与条目都收着 (透明度 ${columnAlpha()} / ${listAlpha()})") { listAlpha() == 0f && columnAlpha() == 0f }
        // 往下就近找, 不跳到远处在播的那一组
        val below = host.onMain { nearestPillBelow(TvSourceRailKeys.NOW_PLAYING) }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(below)
        // 在分类胶囊上: 出源那一栏, 条目还收着
        host.waitUntil("胶囊上: 源那一栏出来, 条目收着 (透明度 ${columnAlpha()} / ${listAlpha()})") { columnAlpha() == 1f && listAlpha() == 0f }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.NOW_PLAYING)
    }

    @Test
    fun `down from a header button lands on the pill right below it`() {
        show(playingContent())
        waitFocus(TvSourceRailKeys.NOW_PLAYING)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(TvSourceRailKeys.ACTION_REFRESH)
        // 最右边的按钮下面最近的是最右边的那颗
        val below = host.onMain { nearestPillBelow(TvSourceRailKeys.ACTION_REFRESH) }
        assertEquals(TvSourceRailKeys.BT, below)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(below)
    }

    @Test
    fun `up from a pill lands on the header button right above it unless it just came down`() {
        show(playingContent())
        waitFocus(TvSourceRailKeys.NOW_PLAYING)
        // 刚从「正在播放」下来: 按上回到它
        val below = host.onMain { nearestPillBelow(TvSourceRailKeys.NOW_PLAYING) }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(below)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.NOW_PLAYING)
        // 在胶囊行上走到最右边那颗再按上: 落正上方最近的 (最右边的按钮)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(below)
        while (focusedId() != TvSourceRailKeys.BT) host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(host.onMain { nearestHeaderAbove(TvSourceRailKeys.BT) })
    }

    @Test
    fun `a list without a source column shows on its pill and hides in the header`() {
        fun listAlpha() = (rowView("line:0")?.parent as? View)?.alpha
        show(flatContent())
        waitFocus(GROUP_B)
        host.waitUntil("胶囊上: 列表出来, 焦点还在胶囊上 (透明度 ${listAlpha()})") { listAlpha() == 1f }
        assertEquals(GROUP_B, focusedId())
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("line:1")
        // 返回到胶囊: 不收起 (再按返回就关面板)
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(GROUP_B)
        assertEquals(1f, host.onMain { listAlpha() })
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.ACTION_REFRESH)
        host.waitUntil("表头上: 收着 (透明度 ${listAlpha()})") { listAlpha() == 0f }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(GROUP_B)
        host.waitUntil("回到胶囊: 又出来 (透明度 ${listAlpha()})") { listAlpha() == 1f }
    }

    @Test
    fun `opening when the playing entry failed lands on the group pill`() {
        show(playingContent().let { it.copy(nowPlaying = it.nowPlaying!!.copy(failed = true)) })
        waitFocus(GROUP_A)
    }

    @Test
    fun `the empty sources row toggles without switching the source`() {
        val toggle = TvSourceRow(
            id = TvSourceRailKeys.EMPTY_SOURCES,
            style = TvSourceRowStyle.Rail,
            title = "没有结果的 2 个",
            dimmed = true,
            action = TvSourceAction.ToggleEmptySources,
        )
        showOnColumn(groupContent(sources = listOf(source("web:a1"), source("web:a2"), toggle), enterable = setOf("web:a1", "web:a2")))
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a2")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(TvSourceRailKeys.EMPTY_SOURCES)
        // 那一行不是源: 右边照旧是 web:a2 的
        assertEquals(listOf(GROUP_A to "web:a2"), listener.sourceFocused)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.ToggleEmptySources), listener.actions)
    }

    @Test
    fun `opening without anything playing lands on the selected group pill`() {
        show(flatContent())
        waitFocus(GROUP_B)
    }

    @Test
    fun `confirm on the now playing pill jumps to the playing row`() {
        show(playingContent())
        waitFocus(TvSourceRailKeys.NOW_PLAYING)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("换到在播的 web:a2") { listener.sourceFocused.lastOrNull() == GROUP_A to "web:a2" }
        show(playingContent(sourceKey = "web:a2"), entry = false)
        waitFocus("line:1")
    }

    @Test
    fun `the now playing pill leads the header and icon only commands go last`() {
        val commands = listOf(
            rail(TvSourceRailKeys.FILTER, TvSourceAction.OpenFilter).copy(icon = TvSourceRowIcon.Filter),
            rail(TvSourceRailKeys.ACTION_REFRESH, TvSourceAction.Refresh).copy(icon = TvSourceRowIcon.Refresh),
            rail(TvSourceRailKeys.ACTION_FULL_SEARCH, TvSourceAction.SetFullSearch(true)).copy(trailing = "关"),
            rail(TvSourceRailKeys.ACTION_KEYWORDS, TvSourceAction.EditKeywords).copy(icon = TvSourceRowIcon.Edit),
        )
        show(playingContent().let { it.copy(rail = it.rail.filterNot { r -> isCommandKey(r.id) } + commands) })
        waitFocus(TvSourceRailKeys.NOW_PLAYING)
        for (id in listOf(TvSourceRailKeys.FILTER, TvSourceRailKeys.ACTION_FULL_SEARCH, TvSourceRailKeys.ACTION_REFRESH, TvSourceRailKeys.ACTION_KEYWORDS)) {
            host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFocus(id)
        }
        // 只画图标的: 左右内边距 + 图标
        host.waitUntil("刷新只有图标宽 (现在 ${rowView(TvSourceRailKeys.ACTION_REFRESH)?.width})") {
            rowView(TvSourceRailKeys.ACTION_REFRESH)?.width == 28 * 2 + 40
        }
        assertTrue(host.onMain { rowView(TvSourceRailKeys.ACTION_FULL_SEARCH)!!.width > 28 * 2 + 40 })
    }

    @Test
    fun `back on the now playing pill closes and reopens on it`() {
        show(playingContent())
        waitFocus(TvSourceRailKeys.NOW_PLAYING)
        assertFalse(host.onMain { view.handleBack() })
        assertEquals(TvSourceResumePoint(GROUP_A, TvSourceRailKeys.NOW_PLAYING, null), host.onMain { view.resumePoint() })
    }

    @Test
    fun `back from the entries goes to the group pill and closes from there`() {
        fun columnAlpha() = (rowView("web:a1")?.parent as? View)?.alpha
        fun listAlpha() = (rowView("line:0")?.parent as? View)?.alpha
        showAt(playingContent(), "line:1")
        // 条目 → 分类胶囊 (源那一栏还在, 条目收起) → 交给宿主关面板 (有「正在播放」也不再绕过去)
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(GROUP_A)
        host.waitUntil("胶囊上: 源那一栏在, 条目收着 (透明度 ${columnAlpha()} / ${listAlpha()})") { columnAlpha() == 1f && listAlpha() == 0f }
        assertFalse(host.onMain { view.handleBack() })
        // 再打开回到这颗胶囊, 内容里停的那一行记着
        assertEquals(
            TvSourceResumePoint(GROUP_A, null, TvSourceFocusPosition(GROUP_A, "web:a1", "line:1")),
            host.onMain { view.resumePoint() },
        )
    }

    @Test
    fun `opening with a resume row lands on that row`() {
        host.onMain { view.resumeRow = TvSourceFocusPosition(GROUP_A, "web:a1", "line:0") }
        show(groupContent())
        waitFocus("line:0")
    }

    @Test
    fun `opening with a resume source lands on that source row`() {
        showOnColumn(groupContent(sourceKey = "web:a2"))
    }

    @Test
    fun `opening with a resume pill lands on that pill`() {
        showOnRail(flatContent())
    }

    @Test
    fun `opening into the list lines the rail up with the selected group`() {
        // 胶囊行够长、选中的那颗在后面: 落进列表时胶囊行要已经对到它 (选中位置就是它)
        val longRail = (0 until 30).map { rail("group:$it") }
        val content = TvSourcePanelContent(
            status = "已查询 30 个数据源",
            rail = longRail,
            railKey = "group:25",
            right = TvSourceRight(key = "group:25", rows = listOf(line("r:0", selected = false))),
        )
        showAt(content, "r:0")
        host.waitUntil("胶囊行对到 group:25") { rail().selectedPosition == 25 }
    }

    // ---- 排版 ----

    @Test
    fun `commands go to the header and groups to the rail`() {
        showAt(filterScreen(), "bt:0")
        host.waitUntil("胶囊行只有各组") {
            (rail().adapter as TvSourceRowAdapter).currentList.map { it.id } == listOf(TvSourceRailKeys.BT, GROUP_A)
        }
    }

    @Test
    fun `rail pills are as wide as their names`() {
        val rows = listOf(rail(GROUP_A), rail("group:long").copy(title = "一个名字很长很长的数据源类型"))
        show(TvSourcePanelContent(status = "已查询 2 个数据源", rail = rows, railKey = GROUP_A, right = TvSourceRight(key = GROUP_A, rows = listOf(loadingRow()))))
        waitFocus(GROUP_A)
        host.waitUntil("胶囊宽度按名字") {
            val short = rowView(GROUP_A)?.width ?: 0
            val long = rowView("group:long")?.width ?: 0
            short in 1 until long && long < 1000
        }
    }

    @Test
    fun `the source column hugs the left margin and the entries sit to its right`() {
        showAt(groupContent(), "line:1")
        host.waitUntil("源那一栏贴左边距, 条目在它右边 (源 ${rowLeft("web:a1")}, 条目 ${rowLeft("line:0")})") {
            rowLeft("web:a1") == 96 && rowLeft("line:0") == 96 + COLUMN_WIDTH + COLUMN_GAP &&
                rowView("web:a1")?.width == COLUMN_WIDTH
        }
    }

    @Test
    fun `only the shown source is outlined and the playing one is not`() {
        val playing = source("web:a1").copy(icon = TvSourceRowIcon.Playing, selected = true, playing = true)
        showAt(groupContent(sources = listOf(playing, source("web:a2"), source("web:a3")), sourceKey = "web:a2"), "line:1")
        host.waitUntil("显示的 web:a2 与组 a 描边, 正在播的 web:a1 不描") {
            rowView("web:a2")?.outlined == true && rowView(GROUP_A)?.outlined == true && rowView("web:a1")?.outlined == false
        }
    }

    @Test
    fun `entries line up with the selected source row`() {
        showAt(groupContent(sourceKey = "web:a3"), "line:1")
        host.waitUntil("条目顶边对着 web:a3 (源 ${rowTop("web:a3")}, 条目 ${rowTop("line:0")})") {
            val source = rowTop("web:a3")
            source != null && rowTop("line:0") == source && source > CONTENT_TOP
        }
    }

    @Test
    fun `entries that do not fit below their source move up`() {
        val sources = (1..6).map { source("web:s$it") }
        val rows = (0 until 3).map { line("x:$it", selected = false) }
        showAt(groupContent(sources = sources, sourceKey = "web:s6", rightRows = rows), "x:0")
        host.waitUntil("往上让, 底边不出面板 (源 ${rowTop("web:s6")}, 条目 ${rowTop("x:0")}..${rowBottom("x:2")})") {
            val source = rowTop("web:s6") ?: return@waitUntil false
            val top = rowTop("x:0") ?: return@waitUntil false
            val bottom = rowBottom("x:2") ?: return@waitUntil false
            top < source && top > CONTENT_TOP && abs(bottom - PANEL_BOTTOM) <= 1
        }
    }

    @Test
    fun `entries taller than the column start at its top`() {
        val rows = (0 until 8).map { line("x:$it", selected = false) }
        showAt(groupContent(sourceKey = "web:a3", rightRows = rows), "x:0")
        host.waitUntil("从这一栏的顶上开始 (条目 ${rowTop("x:0")})") { rowTop("x:0") == CONTENT_TOP }
    }

    @Test
    fun `the settled position of a source row is known before the column stops`() {
        val sources = (0 until 30).map { source("web:s$it") }
        showOnColumn(groupContent(sources = sources, sourceKey = "web:s0", rightRows = listOf(loadingRow())))
        // 开头、中间、靠后 (还没排出来) 与最后一个 (下面贴边)
        for (key in listOf("web:s12", "web:s27", "web:s0", "web:s29")) {
            val predicted = host.onMain { view.settledSourceTop(key) }!!
            host.onMain { view.focusRow(null, key, inRail = true) }
            waitFocus(key)
            host.waitUntil("$key 停稳后的顶边同算的 $predicted (现在 ${rowTop(key)})") {
                (rowView(key)?.parent as? RecyclerView)?.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    rowTop(key)?.let { abs(it - predicted) <= 1 } == true
            }
        }
    }

    @Test
    fun `a flat list sits at the left margin and a wide one spans the width`() {
        showAt(flatContent(), "line:1")
        host.waitUntil("没有源那一栏的列表贴左边距 (行 ${rowLeft("line:0")})") {
            rowLeft("line:0") == 96 && (rowView("line:0")?.width ?: 0) < 1200
        }
        show(btContent(focusId = "bt:0").let { it.copy(right = it.right.copy(wide = true)) }, entry = false)
        host.waitUntil("BT 占满宽度") { (rowView("bt:0")?.width ?: 0) == 1920 - 96 - 96 }
    }

    @Test
    fun `wide entries beside the column take the rest of the width`() {
        showAt(groupContent().let { it.copy(right = it.right.copy(wide = true)) }, "line:1")
        host.waitUntil("占满源那一栏右边 (现在 ${rowView("line:0")?.width})") {
            rowView("line:0")?.width == 1920 - 96 - 96 - COLUMN_WIDTH - COLUMN_GAP
        }
    }

    @Test
    fun `a short list without icons is only as wide as its rows`() {
        showAt(flatContent(rightRows = listOf(line("x:0", selected = false), line("x:1", selected = false))), "x:0")
        host.waitUntil("宽度按内容 (现在 ${rowView("x:0")?.width})") {
            val width = rowView("x:0")?.width ?: 0
            // 不窄于下限 240; 没有带图标的行, 不留图标位
            width in 240 until 400
        }
    }

    @Test
    fun `a short list widens for more rows and narrows back when they go away`() {
        val short = listOf(line("x:0", selected = false), line("x:1", selected = false))
        showAt(flatContent(rightRows = short), "x:0")
        host.waitUntil("先按两行量") { (rowView("x:0")?.width ?: 0) > 0 }
        val narrow = host.onMain { rowView("x:0")!!.width }
        // 展开「其余」: 多了一行长的, 变宽
        val long = line("x:other", selected = false).copy(title = "一个名字很长很长很长的其余结果")
        show(flatContent(rightRows = short + long), entry = false)
        host.waitUntil("展开后变宽") { (rowView("x:0")?.width ?: 0) > narrow }
        // 收起: 回到原来的宽度
        show(flatContent(rightRows = short), entry = false)
        host.waitUntil("收起后变回去 (现在 ${rowView("x:0")?.width}, 原来 $narrow)") { rowView("x:0")?.width == narrow }
    }

    @Test
    fun `a very long row widens the list up to the full width`() {
        val long = line("x:long", selected = false).copy(title = "名字特别特别长".repeat(12))
        showAt(flatContent(rightRows = listOf(line("x:0", selected = false), long)), "x:0")
        host.waitUntil("最宽同 BT 占满宽度 (现在 ${rowView("x:0")?.width})") { rowView("x:0")?.width == 1920 - 96 - 96 }
    }

    @Test
    fun `the selected pill stays at the left edge until the last ones`() {
        val longRail = (0 until 30).map { rail("group:$it") }
        fun content(key: String) = TvSourcePanelContent(
            status = "已查询 30 个数据源",
            rail = longRail,
            railKey = key,
            right = TvSourceRight(key = key, rows = listOf(loadingRow())),
        )
        // 中间的一颗: 左缘对齐内容的左边距 (聚焦放大 1.03 倍, 屏幕上的左缘往外扩几个像素)
        showOnRail(content("group:10"))
        host.waitUntil("group:10 靠左 (现在 ${screenX("group:10")})") { screenX("group:10")?.let { abs(it - 96) <= 4 } == true }
        // 最后一颗: 胶囊行到头不再往左滚, 它停在右边
        showOnRail(content("group:29"))
        host.waitUntil("group:29 停在右边 (现在 ${screenX("group:29")})") { (screenX("group:29") ?: 0) > 960 }
    }

    // ---- 胶囊行 ----

    @Test
    fun `down from a group pill goes into the source column on the selected source`() {
        showOnRail(groupContent(sourceKey = "web:a2"))
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a2")
        assertTrue(listener.sourceFocused.isEmpty(), "落在选中的源上, 不用换")
    }

    @Test
    fun `down from a pill without a source column goes into the list`() {
        showOnRail(flatContent())
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("line:1")
    }

    @Test
    fun `left and right on the rail switch the group`() {
        showOnRail(groupContent())
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(GROUP_B)
        assertEquals(listOf(GROUP_B), listener.railFocused)
        // 组 b 的内容还没到就按回来: 照样要请换回组 a (此刻的内容虽然还是组 a, 宿主那边已经在换组 b 了)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus(GROUP_A)
        assertEquals(listOf(GROUP_B, GROUP_A), listener.railFocused)
    }

    @Test
    fun `holding right on the rail switches only on release`() {
        showOnRail(groupContent())
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1)
        waitFocus(TvSourceRailKeys.BT)
        assertTrue(listener.railFocused.isEmpty(), "按着时不换")
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(TvSourceRailKeys.BT), listener.railFocused)
    }

    @Test
    fun `entering waits for the content of the newly selected group`() {
        showOnRail(flatContent())
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus(GROUP_A)
        // 下面还是组 b 的内容: 下键先记着
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(GROUP_A, focusedId())
        show(groupContent(sourceKey = "web:a2"), entry = false)
        waitFocus("web:a2")
    }

    @Test
    fun `up from the rail goes to the header and stays there`() {
        showAt(flatContent(), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(GROUP_B)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.ACTION_REFRESH)
        // 表头再按上: 不收面板 (关面板只有返回键), 焦点不动
        assertTrue(host.onMain { dispatch(KeyEvent.KEYCODE_DPAD_UP) })
        assertEquals(TvSourceRailKeys.ACTION_REFRESH, focusedId())
        assertEquals(0, listener.closeRequests)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(GROUP_B)
    }

    @Test
    fun `holding up from the rail does not close the panel`() {
        showOnRail(groupContent())
        host.keyDown(KeyEvent.KEYCODE_DPAD_UP)
        host.keyDown(KeyEvent.KEYCODE_DPAD_UP, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(TvSourceRailKeys.ACTION_REFRESH, focusedId())
        assertEquals(0, listener.closeRequests)
    }

    @Test
    fun `header commands run on confirm`() {
        showOnRail(groupContent())
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

    // ---- 源那一栏 ----

    @Test
    fun `moving in the source column switches the entries`() {
        showOnColumn(groupContent())
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a2")
        assertEquals(listOf(GROUP_A to "web:a2"), listener.sourceFocused)
        // 最下面再按下: 不动
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a3")
        assertTrue(host.onMain { dispatch(KeyEvent.KEYCODE_DPAD_DOWN) })
        assertEquals("web:a3", focusedId())
    }

    @Test
    fun `holding down in the source column switches once on release`() {
        showOnColumn(groupContent())
        host.keyDown(KeyEvent.KEYCODE_DPAD_DOWN)
        host.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, repeatCount = 1)
        waitFocus("web:a3")
        assertTrue(listener.sourceFocused.isEmpty(), "按着时不换")
        host.keyUp(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf(GROUP_A to "web:a3"), listener.sourceFocused)
    }

    @Test
    fun `right from a source enters its entries and left comes back to the same source`() {
        showOnColumn(groupContent())
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("line:1")
        assertEquals(true, listener.inRight)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("web:a1")
        assertTrue(listener.railFocused.isEmpty())
        assertEquals(false, listener.inRight)
    }

    @Test
    fun `right in the entries goes to the right pill and left comes back to the same row`() {
        showAt(groupContent(), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(GROUP_B)
        assertEquals(listOf(GROUP_B), listener.railFocused)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("请回组 a 的 web:a1") { listener.sourceFocused.lastOrNull() == GROUP_A to "web:a1" }
        // 组 a 的内容到了: 落回原来那一行 (不是第一行)
        show(groupContent(rightRows = listOf(line("line:0", selected = true), line("line:1", selected = false))), entry = false)
        waitFocus("line:1")
    }

    @Test
    fun `left from the source column goes to the left pill and right comes back to that source`() {
        val rail = listOf(rail(TvSourceRailKeys.BT), rail(GROUP_A), rail(GROUP_B))
        showOnColumn(groupContent(rail = rail, sourceKey = "web:a2"))
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus(TvSourceRailKeys.BT)
        assertEquals(listOf(TvSourceRailKeys.BT), listener.railFocused)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("请回组 a 的 web:a2") { listener.sourceFocused.lastOrNull() == GROUP_A to "web:a2" }
        show(groupContent(rail = rail, sourceKey = "web:a2"), entry = false)
        waitFocus("web:a2")
    }

    @Test
    fun `right from a source without entries goes to the right pill and left comes back`() {
        val content = groupContent(sourceKey = "web:a2", rightRows = listOf(loadingRow()), enterable = setOf("web:a1"))
        showOnColumn(content)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(GROUP_B)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        show(content, entry = false)
        waitFocus("web:a2")
    }

    @Test
    fun `down past the last entry crosses to the next source with entries and up comes back`() {
        showAt(groupContent(enterable = setOf("web:a1", "web:a3")), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        // 跳过没有条目的 web:a2, 焦点留在条目里
        assertEquals(listOf(GROUP_A to "web:a3"), listener.sourceFocused)
        show(groupContent(sourceKey = "web:a3", rightRows = listOf(line("x:0", selected = false), line("x:1", selected = true))), entry = false)
        waitFocus("x:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(GROUP_A to "web:a1", listener.sourceFocused.last())
        // 回来时那一行没了: 落第一行
        show(groupContent(rightRows = listOf(line("line:0", selected = false), line("line:2", selected = true))), entry = false)
        waitFocus("line:0")
    }

    @Test
    fun `up past the first entry crosses to the last entry of the previous source`() {
        showAt(groupContent(sourceKey = "web:a3", rightRows = listOf(line("x:0", selected = false), line("x:1", selected = false))), "x:0")
        // 先在这一屏里走一下: 不是刚跨过来的, 按上照常跨
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("x:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("x:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf(GROUP_A to "web:a2"), listener.sourceFocused)
        show(groupContent(sourceKey = "web:a2", rightRows = listOf(line("y:0", selected = true), line("y:1", selected = false))), entry = false)
        waitFocus("y:1")
    }

    @Test
    fun `small pills at the edge move to the neighbouring pill and come back`() {
        showAt(btContent(focusId = "bt:0"), "bt:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("pill:episode")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus(GROUP_B)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(GROUP_B, TvSourceRailKeys.BT), listener.railFocused)
        show(btContent(focusId = "bt:0"), entry = false)
        waitFocus("pill:episode")
    }

    @Test
    fun `right from a source waits for its entries`() {
        showOnColumn(groupContent())
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a2")
        // 右边还是 web:a1 的条目: 右键先记着
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("web:a2", focusedId())
        show(groupContent(sourceKey = "web:a2", rightRows = listOf(line("a2-line", selected = false))), entry = false)
        waitFocus("a2-line")
    }

    @Test
    fun `up from the top source and from the first entry goes to the pill`() {
        showOnColumn(groupContent())
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(GROUP_A)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a1")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(GROUP_A)
    }

    @Test
    fun `confirm on a source enters its entries or runs its action`() {
        val login = source("web:a2").copy(action = TvSourceAction.LoginDrive("drive"))
        showOnColumn(groupContent(sources = listOf(source("web:a1"), login)))
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("web:a1")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a2")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.LoginDrive("drive")), listener.actions)
    }

    @Test
    fun `holding confirm on a source opens its details`() {
        val withDetails = source("web:a1").copy(details = TvSourceDetails(title = "a1"))
        showOnColumn(groupContent(sources = listOf(withDetails, source("web:a2"))))
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(LONG_PRESS_WAIT_MILLIS)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.ShowDetails("web:a1", inRail = true, railKey = null)), listener.actions)
    }

    // ---- 列表 ----

    @Test
    fun `left and right in a list move to the neighbouring group and come back to where they were`() {
        showAt(flatContent(), "line:1")
        // BT 还没进去过: 停在它的胶囊上
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(TvSourceRailKeys.BT)
        assertEquals(listOf(TvSourceRailKeys.BT), listener.railFocused)
        show(btContent(focusId = "bt:2"), entry = false)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("bt:2")
        // 组 b 进去过: 回到那时停的那一行
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(TvSourceRailKeys.BT, GROUP_B), listener.railFocused)
        show(flatContent(), entry = false)
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        // BT 也进去过了: 回到 bt:2
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        show(btContent(focusId = "bt:0"), entry = false)
        waitFocus("bt:2")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        show(flatContent(), entry = false)
        waitFocus("line:0")
    }

    @Test
    fun `holding right in a list moves only the pill and lands on release`() {
        showAt(flatContent(), "line:1")
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        // 单按的那一下不先描边
        assertFalse(host.onMain { rowView(TvSourceRailKeys.BT)!!.outlined })
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1)
        host.waitUntil("连发起胶囊行挪到 BT, 描着边") { rail().selectedPosition == 2 && rowView(TvSourceRailKeys.BT)?.outlined == true }
        assertEquals("line:1", focusedId(), "按着时焦点留在原处")
        assertTrue(listener.railFocused.isEmpty(), "按着时不换")
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(TvSourceRailKeys.BT)
        assertEquals(listOf(TvSourceRailKeys.BT), listener.railFocused)
    }

    @Test
    fun `holding across groups survives the rail getting shorter`() {
        val rail = listOf(rail(TvSourceRailKeys.BT), rail(GROUP_A), rail(GROUP_B))
        showAt(btContent(focusId = "bt:1").copy(rail = rail), "bt:1")
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1)
        host.waitUntil("胶囊挪到最后一颗") { rail().selectedPosition == 2 }
        // 按着期间最后一组没了 (搜索途中那一组唯一的源失败, 并进了查询失败)
        host.onMain { view.submit(btContent(focusId = "bt:1").copy(rail = rail.dropLast(1))) }
        host.waitUntil("胶囊行变短") { rail().adapter?.itemCount == 2 }
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 2)
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(GROUP_A)
    }

    @Test
    fun `a single press across groups does not outline the target before focusing it`() {
        val rail = listOf(rail(TvSourceRailKeys.BT), rail(GROUP_A), rail(GROUP_B))
        showAt(btContent(focusId = "bt:1").copy(rail = rail), "bt:1")
        host.onMain { dispatchDown(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertFalse(host.onMain { rowView(GROUP_A)!!.outlined }, "按下时还没描边")
        host.onMain { dispatchUp(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFocus(GROUP_A)
        // 在胶囊行上往右: 刚离开的那颗不带着边
        host.onMain { dispatchDown(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFocus(GROUP_B)
        assertFalse(host.onMain { rowView(GROUP_A)!!.outlined }, "刚离开的那颗没描边")
        host.onMain { dispatchUp(KeyEvent.KEYCODE_DPAD_RIGHT) }
    }

    @Test
    fun `holding across groups lands only on the last one`() {
        val rail = listOf(rail(TvSourceRailKeys.BT), rail(GROUP_A), rail(GROUP_B))
        showAt(btContent(focusId = "bt:1").copy(rail = rail), "bt:1")
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1)
        host.waitUntil("胶囊行挪到组 b") { rail().selectedPosition == 2 }
        assertEquals("bt:1", focusedId())
        assertTrue(listener.railFocused.isEmpty() && listener.sourceFocused.isEmpty(), "途经的组 a 不换不进")
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(GROUP_B)
        assertEquals(listOf(GROUP_B), listener.railFocused)
    }

    @Test
    fun `a group is entered again where it was left`() {
        val rail = listOf(rail(TvSourceRailKeys.BT), rail(GROUP_A), rail(GROUP_B))
        val bt = btContent(focusId = "bt:1").copy(rail = rail)
        showAt(bt, "bt:1")
        // 组 a 没进去过: 停在胶囊上, 按下进源那一栏
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(GROUP_A)
        show(groupContent(rail = rail), entry = false)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a1")
        // 从源那一栏往左: BT 离开时停在条目 bt:1, 回那里
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        show(bt, entry = false)
        waitFocus("bt:1")
        // 再往右: 组 a 离开时停在源那一栏, 回源那一栏 (不是进条目)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        show(groupContent(rail = rail), entry = false)
        waitFocus("web:a1")
        // 进条目再退回源那一栏后离开: 回来还是源那一栏
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("web:a1")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        show(bt, entry = false)
        waitFocus("bt:1")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        show(groupContent(rail = rail), entry = false)
        waitFocus("web:a1")
    }

    @Test
    fun `a group left from its pill is entered again on its pill`() {
        val rail = listOf(rail(TvSourceRailKeys.BT), rail(GROUP_A), rail(GROUP_B))
        val bt = btContent(focusId = "bt:1").copy(rail = rail)
        // 先进过组 a 的条目, 再从它的胶囊上往左走
        showAt(groupContent(rail = rail), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(GROUP_A)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus(TvSourceRailKeys.BT)
        show(bt, entry = false)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("bt:1")
        // 往右回组 a: 上次是从胶囊上离开的, 停在胶囊上
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(GROUP_A)
        // 胶囊上按下: 回到内容里上次停的那一行
        show(groupContent(rail = rail), entry = false)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("line:0")
    }

    @Test
    fun `a group pill shows its source column but not the entries`() {
        fun listAlpha() = (rowView("line:0")?.parent as? View)?.alpha
        fun columnAlpha() = (rowView("web:a1")?.parent as? View)?.alpha
        showOnRail(groupContent())
        host.waitUntil("胶囊上: 源那一栏出来, 条目收着, 源那一栏不标哪个源 (透明度 ${columnAlpha()} / ${listAlpha()})") {
            listAlpha() == 0f && columnAlpha() == 1f && rowView("web:a1")?.outlined == false
        }
        assertEquals(GROUP_A, focusedId())
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("web:a1")
        host.waitUntil("进了源那一栏: 都展开 (透明度 ${columnAlpha()} / ${listAlpha()})") { columnAlpha() == 1f && listAlpha() == 1f }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("line:1")
        host.waitUntil("web:a1 描着显示的是它的边") { rowView("web:a1")?.outlined == true }
    }

    @Test
    fun `down from a pill returns to where the group was left`() {
        showAt(groupContent(), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(GROUP_A)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("line:0")
    }

    @Test
    fun `edge keys are consumed and keep the focus`() {
        // BT 是最后一项 (重新搜索在表头)
        showAt(btContent(focusId = "bt:2"), "bt:2")
        for (code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            assertTrue(host.onMain { dispatch(code) }, "到头的方向键被面板吃掉")
            assertEquals("bt:2", focusedId())
        }
        assertTrue(listener.railFocused.isEmpty())
    }

    @Test
    fun `left and right inside a drill do not switch the group`() {
        showAt(drillContent(), "opt:720P")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("opt:720P", focusedId())
        assertTrue(listener.railFocused.isEmpty())
    }

    @Test
    fun `up goes from the rows to the pills and the rail and down comes back`() {
        showAt(btContent(focusId = "bt:0"), "bt:0")
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
    fun `back goes up one level and only the pill leaves it to the host to hide the panel`() {
        showAt(groupContent(sourceKey = "web:a2"), "line:1")
        assertEquals(TvSourceFocusPosition(GROUP_A, "web:a2", "line:1"), host.onMain { view.focusPosition() })
        // 条目 → 胶囊 (不经过源那一栏, 那是左键的事) → 交给宿主关面板
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(GROUP_A)
        assertFalse(host.onMain { view.handleBack() })
        assertEquals(TvSourceFocusPosition(GROUP_A, null, null), host.onMain { view.focusPosition() })
        // 源那一栏上按返回也回胶囊
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("line:1")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("web:a2")
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(GROUP_A)
        // 表头上也是关; 记下表头那一颗与下面那一组内容里最后停的地方
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(TvSourceRailKeys.ACTION_REFRESH)
        assertFalse(host.onMain { view.handleBack() })
        assertEquals(
            TvSourceResumePoint(GROUP_A, TvSourceRailKeys.ACTION_REFRESH, TvSourceFocusPosition(GROUP_A, "web:a2", null)),
            host.onMain { view.resumePoint() },
        )
    }

    @Test
    fun `closing from the entries reopens on the pill and down goes back to the entry`() {
        fun listAlpha() = (rowView("line:0")?.parent as? View)?.alpha
        fun columnAlpha() = (rowView("web:a1")?.parent as? View)?.alpha
        showAt(groupContent(), "line:1")
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(GROUP_A)
        assertFalse(host.onMain { view.handleBack() })
        val point = host.onMain { view.resumePoint() }
        assertEquals(TvSourceResumePoint(GROUP_A, null, TvSourceFocusPosition(GROUP_A, "web:a1", "line:1")), point)
        reopenInNewView(point, groupContent())
        waitFocus(GROUP_A)
        host.waitUntil("再打开: 胶囊上, 源那一栏出来, 条目收着 (透明度 ${columnAlpha()} / ${listAlpha()})") { columnAlpha() == 1f && listAlpha() == 0f }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("line:1")
        host.waitUntil("进去了: 展开") { columnAlpha() == 1f && listAlpha() == 1f }
    }

    @Test
    fun `closing from a header button reopens folded on that button`() {
        fun listAlpha() = (rowView("line:0")?.parent as? View)?.alpha
        showAt(groupContent(), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(GROUP_A)
        val command = host.onMain { nearestHeaderAbove(GROUP_A) }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(command)
        assertFalse(host.onMain { view.handleBack() })
        val point = host.onMain { view.resumePoint() }
        assertEquals(TvSourceResumePoint(GROUP_A, command, TvSourceFocusPosition(GROUP_A, "web:a1", "line:0")), point)
        reopenInNewView(point, groupContent())
        waitFocus(command)
        host.waitUntil("再打开: 下面收着 (透明度 ${listAlpha()})") { listAlpha() == 0f }
    }

    @Test
    fun `closing from the pill remembers the last place in its content`() {
        showAt(groupContent(), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(GROUP_A)
        assertFalse(host.onMain { view.handleBack() })
        assertEquals(TvSourceResumePoint(GROUP_A, null, TvSourceFocusPosition(GROUP_A, "web:a1", "line:0")), host.onMain { view.resumePoint() })
        // 没进过内容的那一项: 只记胶囊
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(GROUP_B)
        assertEquals(TvSourceResumePoint(GROUP_B, null, null), host.onMain { view.resumePoint() })
    }

    @Test
    fun `reopening the same panel while it fades out lands focus again`() {
        val controller = TvSourcePanelController()
        host.onMain { controller.view = view }
        showAt(groupContent(), "line:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("line:0")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(GROUP_A)
        val command = host.onMain { nearestHeaderAbove(GROUP_A) }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(command)
        // 表头上按返回关: 焦点回播放器根部, 面板淡出 (下面都收着)
        assertFalse(host.onMain { view.handleBack() })
        val resume = host.onMain { view.resumePoint() }
        val outside = host.onMain {
            View(host.activity).apply { isFocusable = true }.also { host.root.addView(it, FrameLayout.LayoutParams(10, 10)) }
        }
        host.onMain {
            view.leaving = true
            outside.requestFocus()
        }
        host.waitUntil("焦点离开面板") { !view.hasPanelFocus() }
        // 淡出途中又打开: 还是这个视图, 照新打开那样回到关时那一颗, 下面收着
        host.onMain {
            view.leaving = false
            controller.reopen(resume)
        }
        waitFocus(command)
        host.waitUntil("下面收着") { (rowView("line:0")?.parent as? View)?.alpha == 0f }
        // 回胶囊再进去落回内容里停的那一行
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(GROUP_A)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("line:0")
    }

    @Test
    fun `a resource row draws its badges right before the info`() {
        val content = btContent(focusId = "bt:0").let { c ->
            c.copy(right = c.right.copy(rows = c.right.rows.mapIndexed { i, row -> if (i == 1) row.copy(badges = listOf(TvSourceBadge.DolbyVision, TvSourceBadge.DolbyAtmos)) else row }))
        }
        showAt(content, "bt:0")
        fun badgesOf(id: String): TvSourceBadgeView? = (rowView(id) as ViewGroup?)?.let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TvSourceBadgeView>().firstOrNull()
        }
        host.waitUntil("bt:1 画出两个标签") { (badgesOf("bt:1")?.width ?: 0) > 0 }
        host.onMain {
            val badges = badgesOf("bt:1")!!
            val row = rowView("bt:1") as ViewGroup
            // 信息是最右边那个字 (没有右端字): 标签紧挨在它左边
            val meta = (0 until row.childCount).map { row.getChildAt(it) }
                .filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.maxBy { it.right }
            assertEquals(meta.left - 16, badges.right, "标签在信息左边, 隔 metaGapPx")
            assertEquals(View.GONE, badgesOf("bt:0")!!.visibility, "没有特性的行不画标签")
        }
    }

    @Test
    fun `reopening at a remembered row lands on it`() {
        host.onMain { view.resumeRow = TvSourceFocusPosition(GROUP_A, "web:a2", "line:0") }
        show(groupContent(sourceKey = "web:a2"))
        waitFocus("line:0")
    }

    @Test
    fun `back from a list without a source column and from its small pills goes to the pill`() {
        showAt(btContent(focusId = "bt:1"), "bt:1")
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(TvSourceRailKeys.BT)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("bt:1")
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus("pill:episode")
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(TvSourceRailKeys.BT)
    }

    @Test
    fun `confirm runs the row action and holding it runs the long action`() {
        showAt(btContent(focusId = "bt:0"), "bt:0")
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
    fun `holding confirm on an entry opens its details under its source and focusRow lands on the row last viewed`() {
        val rows = listOf(line("line:0", selected = false), line("line:1", selected = false)).map {
            it.copy(details = TvSourceDetails(title = it.title))
        }
        showAt(groupContent(rightRows = rows), "line:0")

        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(LONG_PRESS_WAIT_MILLIS)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf<TvSourceAction>(TvSourceAction.ShowDetails("line:0", inRail = false, railKey = "web:a1")), listener.actions)

        host.onMain { view.focusRow("web:a1", "line:1", inRail = false) }
        waitFocus("line:1")
    }

    @Test
    fun `focusRow under another source of the group switches the source and lands on the row`() {
        showAt(groupContent(), "line:1")
        host.onMain { view.focusRow("web:a2", "line:0", inRail = false) }
        host.waitUntil("源那一栏换到 web:a2") { listener.sourceFocused.lastOrNull() == GROUP_A to "web:a2" }
        // 状态按新的源拼好的内容到了: 落到那一行 (不是这一屏的默认落点 line:1)
        show(groupContent(sourceKey = "web:a2"), entry = false)
        waitFocus("line:0")
    }

    @Test
    fun `focusRow under a source of another group switches the rail too`() {
        showAt(groupContent(), "line:1")
        host.onMain { view.focusRow("web:b", "line:0", inRail = false) }
        host.waitUntil("换到组 b 的 web:b") { listener.sourceFocused.lastOrNull() == GROUP_B to "web:b" }
        host.waitUntil("胶囊行对到组 b") { rail().selectedPosition == 1 }
        show(flatContent(), entry = false)
        waitFocus("line:0")
    }

    @Test
    fun `a confirm release without its press is ignored`() {
        showAt(btContent(focusId = "bt:0"), "bt:0")
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(listener.actions.isEmpty())
    }

    @Test
    fun `a new screen while focused lands on its target and the old screen is remembered`() {
        showAt(btContent(focusId = "bt:0"), "bt:0")
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
            railKey = TvSourceRailKeys.BT,
            right = TvSourceRight(key = TvSourceRailKeys.BT, pills = listOf(pill), rows = rows),
        )
        showOnRail(manual(listOf(TvSourceRow(id = "status:loading", style = TvSourceRowStyle.Status, title = "正在加载剧集…"))))
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("mpill:back")
        show(manual(listOf(line("ep:0", selected = false), line("ep:1", selected = false))), entry = false)
        waitFocus("ep:0")
    }

    /** 表头有「筛选」与重新搜索, 胶囊行是 BT 与组 a. */
    private fun filterScreen() = TvSourcePanelContent(
        status = "已查询 1 个数据源",
        rail = listOf(
            rail(TvSourceRailKeys.FILTER, TvSourceAction.OpenFilter),
            rail(TvSourceRailKeys.BT),
            rail(GROUP_A),
            rail(TvSourceRailKeys.ACTION_REFRESH, TvSourceAction.Refresh),
        ),
        railKey = TvSourceRailKeys.BT,
        right = TvSourceRight(
            key = TvSourceRailKeys.BT,
            pills = listOf(TvSourceRow(id = "pill:episode", style = TvSourceRowStyle.Pill, title = "仅本集", action = TvSourceAction.ToggleEpisodeFilter)),
            rows = listOf(line("bt:0", selected = false)),
        ),
    )

    private fun dispatchDown(code: Int) {
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
    }

    private fun dispatchUp(code: Int) {
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun dispatch(code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return down
    }

    private class RecordingPanelListener : TvSourcePanelListener {
        val railFocused = mutableListOf<String>()
        val sourceFocused = mutableListOf<Pair<String, String>>()
        val actions = mutableListOf<TvSourceAction>()
        var inRight: Boolean? = null

        override fun onRailFocused(key: String) {
            railFocused += key
        }

        override fun onSourceFocused(groupKey: String, sourceKey: String) {
            sourceFocused += groupKey to sourceKey
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

        const val GROUP_A = "group:a"
        const val GROUP_B = "group:b"

        const val COLUMN_WIDTH = 368
        const val COLUMN_GAP = 24

        /** 胶囊行下面内容的顶边: 上边距 56 + 表头 68 (按钮高) + 24 + 胶囊行 80 + 28. */
        const val CONTENT_TOP = 56 + 68 + 24 + 80 + 28

        /** 面板下缘: 屏高 1080 减下边距 48. */
        const val PANEL_BOTTOM = 1080 - 48

        fun rail(id: String, action: TvSourceAction? = null) =
            TvSourceRow(id = id, style = TvSourceRowStyle.Rail, title = id, action = action)

        fun source(id: String) = TvSourceRow(id = id, style = TvSourceRowStyle.Rail, title = id, trailing = "2")

        fun line(id: String, selected: Boolean) =
            TvSourceRow(id = id, style = TvSourceRowStyle.Line, title = id, meta = "1080P", selected = selected, action = TvSourceAction.Refresh)

        fun loadingRow() = TvSourceRow(id = "status:loading", style = TvSourceRowStyle.Status, title = "正在查询…")

        val railRows = listOf(
            rail(GROUP_A),
            rail(GROUP_B),
            rail(TvSourceRailKeys.BT),
            rail(TvSourceRailKeys.ACTION_REFRESH, TvSourceAction.Refresh),
        )

        val defaultRows = listOf(line("line:0", selected = false), line("line:1", selected = true))

        /** 组 a: 源那一栏是 [sources] (其中 [enterable] 有条目), 右边是 [sourceKey] 的条目. */
        fun groupContent(
            sources: List<TvSourceRow> = listOf(source("web:a1"), source("web:a2"), source("web:a3")),
            sourceKey: String = sources.first().id,
            rightRows: List<TvSourceRow> = defaultRows,
            rail: List<TvSourceRow> = railRows,
            enterable: Set<String> = sources.mapTo(HashSet()) { it.id },
        ) = TvSourcePanelContent(
            status = "已查询 3 个数据源",
            rail = rail,
            railKey = GROUP_A,
            sources = sources,
            sourceKey = sourceKey,
            enterableSources = enterable,
            sourceGroups = sources.associate { it.id to GROUP_A } + ("web:b" to GROUP_B),
            right = TvSourceRight(key = sourceKey, rows = rightRows, focusId = rightRows.firstOrNull { it.selected }?.id),
        )

        /** 组 a, 正在播的是 web:a2 的 line:1 (表头上有「正在播放」). */
        fun playingContent(sourceKey: String = "web:a1") = groupContent(sourceKey = sourceKey).copy(
            nowPlaying = TvSourceNowPlaying(
                row = TvSourceRow(
                    id = TvSourceRailKeys.NOW_PLAYING,
                    style = TvSourceRowStyle.Rail,
                    title = "web:a2 · line:1",
                    icon = TvSourceRowIcon.Playing,
                ),
                target = TvSourceFocusPosition(GROUP_A, "web:a2", "line:1"),
            ),
        )

        /** 组 b: 只有 web:b 一个源, 条目直接在胶囊下面. */
        fun flatContent(rightRows: List<TvSourceRow> = defaultRows) = TvSourcePanelContent(
            status = "已查询 3 个数据源",
            rail = railRows,
            railKey = GROUP_B,
            sourceKey = "web:b",
            sourceGroups = mapOf("web:a1" to GROUP_A, "web:a2" to GROUP_A, "web:a3" to GROUP_A, "web:b" to GROUP_B),
            right = TvSourceRight(key = "web:b", rows = rightRows, focusId = rightRows.firstOrNull { it.selected }?.id),
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
                paddingBottomPx = 48,
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
                columnWidthPx = COLUMN_WIDTH,
                columnRowHeightPx = 76,
                columnGapPx = COLUMN_GAP,
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
                badge = TvSourceBadgeStyle(
                    heightPx = 32,
                    paddingHPx = 10,
                    gapPx = 8,
                    metaGapPx = 16,
                    cornerPx = 6f,
                    strokePx = 2f,
                    textSizePx = 20f,
                    letterSpacingEm = 0f,
                    pictureTop = Color.YELLOW,
                    pictureBottom = Color.YELLOW,
                    pictureText = Color.BLACK,
                    labels = TvSourceBadge.entries.associateWith { it.name },
                ),
            )
        }
    }
}
