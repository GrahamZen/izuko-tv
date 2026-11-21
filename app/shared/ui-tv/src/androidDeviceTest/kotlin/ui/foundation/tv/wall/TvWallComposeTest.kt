/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.exploration.TvCardFocusRequest
import me.him188.ani.app.ui.exploration.TvExplorationComposeWall
import me.him188.ani.app.ui.exploration.TvExplorationNativeState
import me.him188.ani.app.ui.exploration.TvHeroFocusButton
import me.him188.ani.app.ui.exploration.TvHeroFocusRequest
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.TvGridFocusState
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvGridFocus
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvPageBackdropGeometry
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreItem
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeExploreListener
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageCallbacks
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageRows
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeRowWallListener
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeGridPageState
import me.him188.ani.app.ui.foundation.tv.nativeview.testCards
import me.him188.ani.app.ui.foundation.tv.nativeview.testExploreMetrics
import me.him188.ani.app.ui.foundation.tv.nativeview.testWallStyle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compose 版海报墙 (iOS 上画的那一套, 安卓上由调试开关切换) 的遥控器导航: 关联作品横排、网格页的 hero 态、网格页里的一组一行 (追番页「追更」)、
 * 探索页的上下左右与 hero 按钮.
 * 规则同原生版 (见各原生视图的导航测试); 这里只看焦点落在哪、页面收到了哪些回调.
 */
class TvWallComposeTest {
    private val host = TvNativeTestHost()

    @AfterTest
    fun tearDown() = host.close()

    private fun show(content: @Composable () -> Unit) {
        host.launch()
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(
                    LocalSketch provides host.sketch,
                    LocalThemeSettings provides ThemeSettings.Default,
                    content = content,
                )
            }
        }
        host.instrumentation.waitForIdleSync()
    }

    // ---- 关联作品横排 ----

    @Test
    fun `poster strip moves within the row and swallows the left edge`() {
        val outside = FocusRequester()
        val rowAnchor = FocusRequester()
        val focused = mutableListOf<Int>()
        val clicked = mutableListOf<Int>()
        show {
            Column {
                Box(Modifier.size(48.dp).focusRequester(outside).focusable())
                Box(Modifier.focusRequester(rowAnchor).focusGroup()) {
                    TvWallPosterStrip(
                        testCards(10, "作品"),
                        onClick = { clicked += it },
                        modifier = Modifier,
                        startPadding = 48.dp,
                        endPadding = 48.dp,
                        onFocused = { focused += it },
                        cardWidth = null,
                        onBind = {},
                        holdFocusLookOnClick = true,
                    )
                }
            }
        }
        host.onMain { rowAnchor.requestFocus() }
        host.waitUntil("焦点进了行首那张") { focused.lastOrNull() == 0 }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("走到第 2 张") { focused.lastOrNull() == 2 }
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("回到行首") { focused.lastOrNull() == 0 }
        // 行首按左吞掉: 焦点不离开这一行
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0), clicked, "行首按左之后焦点还在第 0 张上")
    }

    // ---- 网格页 ----

    private class GridRecord {
        val focused = mutableListOf<Int>()
        val clicked = mutableListOf<Int>()
        var topRowUp = 0
    }

    private fun showGrid(
        record: GridRecord,
        heroEnabled: Boolean,
        rows: TvNativeGridPageRows? = null,
    ): Pair<() -> TvNativeGridPageState, () -> TvGridFocusState> {
        var pageState: TvNativeGridPageState? = null
        var gridFocus: TvGridFocusState? = null
        val metrics = TvNativeGridPageMetrics(
            pageWidthPx = 1824,
            pageHeightPx = 1080,
            gridTopPx = 120,
            grid = TvNativeGridMetrics(
                columns = 6,
                startPx = 32,
                endPx = 48,
                topBleedPx = 64,
                bottomBleedPx = 128,
                endMarginPx = 60,
                heroLinePx = 400,
                fadeDistancePx = 128f,
            ),
            backdropWidthPx = 1344,
            backdropHeightPx = 756,
            heroLeftPx = 32,
            heroTopPx = 120,
            heroWidthPx = 900,
            heroHeightPx = 300,
            titleWidthPx = 900,
            summaryWidthPx = 800,
            bleedLeftPx = 96,
        )
        val cards = testCards(18)
        show {
            val focusScope = rememberTvFocusScope()
            val focus = rememberTvGridFocus(focusScope)
            val state = rememberTvNativeGridPageState()
            pageState = state
            gridFocus = focus
            TvWallGridPage(
                state = state,
                metrics = metrics,
                cardWidth = with(LocalDensity.current) { 257.toDp() },
                gridKey = rows?.key ?: 0,
                slideDirection = { _, _ -> 0 },
                cards = cards,
                itemAt = { index -> cards.getOrNull(index) },
                itemCount = { cards.size },
                onBind = {},
                heroEnabled = heroEnabled,
                badge = null,
                source = null,
                fadeColor = Color.Black,
                treatment = TvBackdropTreatment(),
                gridFocus = focus,
                farJump = { false },
                onFarJumpConsumed = {},
                callbacks = TvNativeGridPageCallbacks(
                    onCardFocused = { index, _ -> record.focused += index },
                    onCardClick = { index, _ -> record.clicked += index },
                    onTopRowUp = {
                        record.topRowUp++
                        true
                    },
                    onGridFocusChanged = {},
                    onScrollingChanged = {},
                ),
                menuFor = { { _, _ -> } },
                wallBackdrop = null,
                modifier = Modifier,
                landingIndex = -1,
                topBarScrollAwayPx = 0,
                columnSpacing = TV_POSTER_WALL_COLUMN_SPACING,
                cardHeight = null,
                rows = rows,
                emptyContent = {},
            )
        }
        return { pageState!! } to { gridFocus!! }
    }

    @Test
    fun `grid page enters the hero state on the first confirm and opens the card on the second`() {
        val record = GridRecord()
        val (state, focus) = showGrid(record, heroEnabled = true)
        host.onMain { focus().focusItem(0) }
        host.waitUntil("页面送焦到第 0 张") { record.focused.lastOrNull() == 0 }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(host.onMain { state().heroActive }, "卡片墙上按确定先进 hero 态")
        assertEquals(emptyList(), record.clicked)
        // hero 态里换卡只换焦点
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("走到第 1 张") { record.focused.lastOrNull() == 1 }
        assertTrue(host.onMain { state().heroActive })
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(1), record.clicked, "hero 态里按确定进详情页")
        // 返回键 (页面调) 退回卡片墙
        host.onMain { state().exitHero() }
        assertFalse(host.onMain { state().heroActive })
    }

    @Test
    fun `grid page hands the top row up key to the page and moves down by a full row`() {
        val record = GridRecord()
        val (_, focus) = showGrid(record, heroEnabled = true)
        host.onMain { focus().focusItem(2) }
        host.waitUntil("页面送焦到第 2 张") { record.focused.lastOrNull() == 2 }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("下一行同一列") { record.focused.lastOrNull() == 8 }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("回到首行") { record.focused.lastOrNull() == 2 }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, record.topRowUp, "首行按上交给页面")
    }

    @Test
    fun `grid page without a hero state opens the card directly`() {
        val record = GridRecord()
        val (state, focus) = showGrid(record, heroEnabled = false)
        host.onMain { focus().focusItem(3) }
        host.waitUntil("页面送焦到第 3 张") { record.focused.lastOrNull() == 3 }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(3), record.clicked)
        assertFalse(host.onMain { state().heroActive })
    }

    // ---- 网格页里的一组一行 (追番页「追更」) ----

    private class RowsRecord : TvNativeRowWallListener {
        val focused = mutableListOf<Pair<String, Int>>()
        val clicked = mutableListOf<Pair<String, Int>>()
        var topRowUp = 0
        var exitLeft = 0
        var exitRight = 0

        override fun onCardFocused(rowKey: String, index: Int, column: Int) {
            focused += rowKey to index
        }

        override fun onCardClick(rowKey: String, index: Int) {
            clicked += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onExitLeft() {
            exitLeft++
        }

        override fun onExitRight() {
            exitRight++
        }

        override fun onTopRowUp(): Boolean {
            topRowUp++
            return true
        }

        override fun onHeroActiveChanged(active: Boolean) = Unit
        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onToneChanged(tone: Float, splitY: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
    }

    /** 一组一行的内容的几何: 同探索页的, 只是顶上没有轮播区 (前几行在 hero 态里要整片往下推). */
    private val rowsMetrics = testExploreMetrics(testWallStyle()).copy(spacerPx = 0)

    /** 网格页显示一组一行的内容 (占 key −1), 页面那一侧照「追更」接上数据与事件. */
    private fun showGridRows(record: RowsRecord): Pair<TvNativeGridPageState, TvWallRowsGuest> {
        val style = testWallStyle()
        val cardWidth = Density(host.instrumentation.targetContext).run { style.cardWidthPx.toDp() }
        val (state, _) = showGrid(GridRecord(), heroEnabled = true, rows = TvWallGridRowsSpec(ROWS_KEY, rowsMetrics, cardWidth))
        host.waitUntil("网格页建出一组一行的内容") { state().rows != null }
        val guest = host.onMain { state().rows as TvWallRowsGuest }
        host.onMain {
            guest.rowListener = record
            guest.heroEnabled = true
            guest.setItems(
                listOf(
                    TvNativeExploreItem.Header("h0", "有新集"),
                    TvNativeExploreItem.Row("r0", subjectCards(0, 3)),
                    TvNativeExploreItem.Header("h1", "本周更新"),
                    TvNativeExploreItem.Row("r1", subjectCards(100, 8)),
                ),
            )
        }
        host.instrumentation.waitForIdleSync()
        return host.onMain { state() } to guest
    }

    @Test
    fun `grid page rows content moves between rows and hands the edges to the page`() {
        val record = RowsRecord()
        val (_, guest) = showGridRows(record)
        host.onMain { guest.requestCardsFocus() }
        host.waitUntil("送焦到首行第一张") { record.focused.lastOrNull() == ("r0" to 0) }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("走到首行最后一张") { record.focused.lastOrNull() == ("r0" to 2) }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, record.exitRight, "行尾按右交给页面 (换到右边的标签)")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("下一行屏上同一列") { record.focused.lastOrNull() == ("r1" to 2) }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("回到首行") { record.focused.lastOrNull() == ("r0" to 2) }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, record.topRowUp, "首行按上交给页面 (回标签行)")
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("回到行首") { record.focused.lastOrNull() == ("r0" to 0) }
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, record.exitLeft, "行首按左交给页面")
    }

    @Test
    fun `grid page rows content enters the host hero state and pushes the first row down to the hero line`() {
        val record = RowsRecord()
        val (state, guest) = showGridRows(record)
        host.onMain { guest.requestCardsFocus() }
        host.waitUntil("送焦到首行第一张") { record.focused.lastOrNull() == ("r0" to 0) }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(host.onMain { state.heroActive }, "卡片墙上按确定进网格页的 hero 态")
        assertEquals(emptyList(), record.clicked)
        // 首行滚到顶也落不到 hero 线上: 整片往下推, 组标题落在 hero 线
        host.waitUntil("首行整片推到 hero 线") { guest.shift.value == rowsMetrics.heroHeaderTopPx.toFloat() }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("hero 态里换卡") { record.focused.lastOrNull() == ("r0" to 1) }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("r0" to 1), record.clicked, "hero 态里按确定进详情页")
        // 返回键 (页面调) 退回卡片墙, 撤掉整片下推
        host.onMain { state.exitHero() }
        assertFalse(host.onMain { state.heroActive })
        host.waitUntil("撤掉整片下推") { guest.shift.value == 0f }
    }

    // ---- 探索页 ----

    private class ExploreRecord : TvNativeExploreListener {
        val focused = mutableListOf<Pair<String, Int>>()
        val clicked = mutableListOf<Pair<String, Int>>()
        val heroButtons = mutableListOf<Int>()
        val heroActive = mutableListOf<Boolean>()
        var exitLeft = 0

        override fun onCardFocused(rowKey: String, index: Int, column: Int) {
            focused += rowKey to index
        }

        override fun onCardClick(rowKey: String, index: Int) {
            clicked += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) {
            heroButtons += button
        }

        override fun onHeroButtonClick(button: Int) = Unit
        override fun onSwitchCarousel(delta: Int): Boolean = false
        override fun onExitLeft() {
            exitLeft++
        }

        override fun onHeroActiveChanged(active: Boolean) {
            heroActive += active
        }

        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onToneChanged(tone: Float, splitY: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
    }

    private fun exploreItems(): List<TvNativeExploreItem> = listOf(
        TvNativeExploreItem.Spacer("spacer"),
        TvNativeExploreItem.Header("h0", "继续观看"),
        TvNativeExploreItem.Row("r0", subjectCards(0, 10)),
        TvNativeExploreItem.Header("h1", "推荐"),
        TvNativeExploreItem.Row("r1", subjectCards(100, 12)),
    )

    private fun subjectCards(base: Int, count: Int): List<TvNativeCard?> =
        List(count) { TvNativeCard(imageUrl = null, title = "卡 ${base + it}", subjectId = base + it) }

    /** 页面以外的落点 (代替侧边栏). */
    private val outside = FocusRequester()

    /**
     * [redirectEnter] = 外面包一层探索页根部那样的焦点组: 从页面外进来的焦点先改道到入口节点 (见 TvExplorationPage 的 onEnter),
     * 入口再转给上次那张卡. [returnTo] = 返回本页 (页面重建): 上次聚焦的行与那一行停的那张.
     */
    private fun showExplore(
        record: ExploreRecord,
        request: () -> TvCardFocusRequest?,
        heroRequest: () -> TvHeroFocusRequest?,
        redirectEnter: Boolean = false,
        returnTo: Pair<String, Int>? = null,
    ): TvExplorationNativeState {
        val savedRowFocused = HashMap<String, Int>()
        returnTo?.let { (row, index) -> savedRowFocused[row] = index }
        val state = TvExplorationNativeState(heroActive = false, savedScrollPx = 0, savedRowLeft = HashMap(), savedRowFocused = savedRowFocused)
        val style = testWallStyle()
        val items = exploreItems()
        val page = if (redirectEnter) {
            Modifier
                .focusProperties {
                    onEnter = { if (state.surface?.hasFocus() != true) state.focusRequester.requestFocus() }
                }
                .focusGroup()
        } else {
            Modifier
        }
        show {
            Box(Modifier.size(48.dp).focusRequester(outside).focusable())
            Box(page) {
                TvExplorationComposeWall(
                    state = state,
                    metrics = testExploreMetrics(style),
                    cardWidth = with(LocalDensity.current) { style.cardWidthPx.toDp() },
                    items = items,
                    carouselCount = 3,
                    carouselIndex = { 0 },
                    fadeColor = Color.Black,
                    geometry = TvPageBackdropGeometry(0f, 0f, 0.5f, 0.5f, 0.8f, 0.8f, 0f),
                    splitGate = { 1f },
                    listener = record,
                    cardFocusRequest = request,
                    heroFocusRequest = heroRequest,
                    farJumpRow = { null },
                    focusedRowKey = { returnTo?.first },
                    focusedCardIndex = { returnTo?.second ?: 0 },
                    menuFor = { { _, _ -> } },
                    modifier = Modifier,
                )
            }
        }
        return state
    }

    @Test
    fun `explore wall moves between rows by screen column and goes up to the schedule button`() {
        val record = ExploreRecord()
        var request by mutableStateOf<TvCardFocusRequest?>(null)
        showExplore(record, { request }, { null })
        host.onMain { request = TvCardFocusRequest("r0", 2) }
        host.waitUntil("页面送焦到首行第 2 张") { record.focused.lastOrNull() == ("r0" to 2) }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("下一行屏上同一列") { record.focused.lastOrNull() == ("r1" to 2) }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("回到首行") { record.focused.lastOrNull() == ("r0" to 2) }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("首行按上回「新番时间表」") { record.heroButtons.lastOrNull() == 1 }
        // 「新番时间表」按下: 进首行屏上第一列
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("进首行第一列") { record.focused.lastOrNull() == ("r0" to 0) }
        // 行首按左出页面
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, record.exitLeft)
    }

    @Test
    fun `explore wall enters the hero state on the first confirm and opens the card on the second`() {
        val record = ExploreRecord()
        var request by mutableStateOf<TvCardFocusRequest?>(null)
        showExplore(record, { request }, { null })
        host.onMain { request = TvCardFocusRequest("r1", 1) }
        host.waitUntil("页面送焦到第二行第 1 张") { record.focused.lastOrNull() == ("r1" to 1) }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(true), record.heroActive, "卡片墙上按确定先进 hero 态")
        assertEquals(emptyList(), record.clicked)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("hero 态里换卡") { record.focused.lastOrNull() == ("r1" to 2) }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("r1" to 2), record.clicked, "hero 态里按确定进详情页")
    }

    @Test
    fun `entering the explore wall from outside returns to the last card`() {
        val record = ExploreRecord()
        var request by mutableStateOf<TvCardFocusRequest?>(null)
        val state = showExplore(record, { request }, { null })
        host.onMain { request = TvCardFocusRequest("r1", 3) }
        host.waitUntil("页面送焦到第二行第 3 张") { record.focused.lastOrNull() == ("r1" to 3) }
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点离开海报墙") { state.surface?.hasFocus() == false }
        record.focused.clear()
        // 页面经入口送焦进来 (侧边栏按右 / 全局兜底)
        host.onMain { state.focusRequester.requestFocus() }
        host.waitUntil("回到上次那张卡") { record.focused.lastOrNull() == ("r1" to 3) }
        assertEquals(emptyList(), record.heroButtons)
    }

    @Test
    fun `a card request redirected to the entry on the way in still lands on the card`() {
        val record = ExploreRecord()
        var request by mutableStateOf<TvCardFocusRequest?>(null)
        showExplore(record, { request }, { null }, redirectEnter = true, returnTo = "r1" to 3)
        host.onMain { outside.requestFocus() }
        host.instrumentation.waitForIdleSync()
        // 返回本页时的恢复请求 (这一行上次那张): 卡请求焦点时页面的进组把焦点改道到入口, 入口再送同一张
        host.onMain { request = TvCardFocusRequest("r1", -1) }
        host.waitUntil("焦点落到第二行第 3 张") { record.focused.lastOrNull() == ("r1" to 3) }
        assertEquals(emptyList(), record.heroButtons)
    }

    @Test
    fun `explore wall lands on the requested hero button`() {
        val record = ExploreRecord()
        var heroRequest by mutableStateOf<TvHeroFocusRequest?>(null)
        showExplore(record, { null }, { heroRequest })
        host.onMain { heroRequest = TvHeroFocusRequest(TvHeroFocusButton.PRIMARY) }
        host.waitUntil("焦点到「立即观看」") { record.heroButtons.lastOrNull() == 0 }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("按下到「新番时间表」") { record.heroButtons.lastOrNull() == 1 }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("按上回「立即观看」") { record.heroButtons.lastOrNull() == 0 }
    }
}

/** 一组一行的内容在网格页里占的 key (同追番页「追更」). */
private const val ROWS_KEY = -1
