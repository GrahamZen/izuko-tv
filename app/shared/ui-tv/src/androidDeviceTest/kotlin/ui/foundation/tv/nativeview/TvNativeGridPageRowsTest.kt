/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import androidx.compose.ui.geometry.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 网格页 ([TvNativeGridPageView]) 里与网格换着显示的一组一行的内容 ([TvNativeGridPageView.rows], 追番页「追更」): 卡片墙上确定进的是本页的
 * hero 态、hero 态里确定交给它自己的监听器; 与网格互换时 hero 态留着; 本页 hero 态里首行整片推到 hero 线; 换走时焦点停放在本页上,
 * 网格的卡拿到再落地; 它拿着焦点时不算网格的焦点.
 */
class TvNativeGridPageRowsTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val pageListener = PageListener()
    private val rowsListener = RowsListener()
    private lateinit var page: TvNativeGridPageView
    private lateinit var rows: TvNativeRowWallView
    private lateinit var rowsMetrics: TvNativeExploreMetrics
    private lateinit var outside: View

    private inner class PageListener : TvNativeGridPageListener {
        val heroActive = mutableListOf<Boolean>()
        val gridFocus = mutableListOf<Boolean>()
        val clicked = mutableListOf<Int>()

        override fun onFocused(index: Int) = Unit
        override fun onClick(index: Int) {
            clicked += index
        }

        override fun onTopRowUp(): Boolean = false
        override fun onHeroActiveChanged(active: Boolean) {
            heroActive += active
        }

        override fun onToneChanged(tone: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onGridFocusChanged(hasFocus: Boolean) {
            gridFocus += hasFocus
        }

        override fun onFocusParkedChanged(parked: Boolean) = Unit
    }

    private inner class RowsListener : TvNativeRowWallListener {
        val cardClicks = mutableListOf<Pair<String, Int>>()

        override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit
        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onExitLeft() = Unit
        override fun onHeroActiveChanged(active: Boolean) = Unit
        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onToneChanged(tone: Float, splitY: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val style = testWallStyle()
            val grid = TvNativeGridMetrics(
                columns = 6,
                startPx = 128,
                endPx = 48,
                topBleedPx = 0,
                bottomBleedPx = 0,
                endMarginPx = 60,
                heroLinePx = 400,
                fadeDistancePx = 128f,
            )
            val metrics = TvNativeGridPageMetrics(
                pageWidthPx = 1920,
                pageHeightPx = 1080,
                gridTopPx = 120,
                grid = grid,
                backdropWidthPx = 1344,
                backdropHeightPx = 756,
                heroLeftPx = 128,
                heroTopPx = 120,
                heroWidthPx = 900,
                heroHeightPx = 300,
                titleWidthPx = 900,
                summaryWidthPx = 800,
            )
            page = TvNativeGridPageView(host.activity, host.sketch, scope, style, metrics, testHeroTextStyle())
            page.transitions = false
            page.animatedScroll = false
            page.listener = pageListener
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, testCards(12))
            // 「追更」: 没有顶部区域, 首组标题在列表顶
            rowsMetrics = testExploreMetrics(style).copy(spacerPx = 0)
            rows = page.ensureRows(style, rowsMetrics, testTextStyle(32f, 44))
            rows.rowListener = rowsListener
            rows.hideHeadersInHero = true
            rows.setItems(
                listOf(
                    TvNativeExploreItem.Header("h-new", "有新集"),
                    TvNativeExploreItem.Row(NEW, testCards(10, "新")),
                    TvNativeExploreItem.Header("h-week", "本周更新"),
                    TvNativeExploreItem.Row(WEEK, testCards(12, "本周")),
                ),
            )
        }
        host.waitUntil("网格排出来") { (page.grid?.childCount ?: 0) > 0 }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun showRows() {
        host.onMain { page.showRows(ROWS_KEY, direction = -1, animated = false) }
        host.waitUntil("一组一行的内容排出来") { rows.isShown && rows.rowCenterYInWindow(NEW) != null }
    }

    private fun waitRowsCard(rowKey: String, index: Int) =
        host.waitUntil("焦点到 $rowKey 第 $index 张") { rows.cardAreaHasFocus && rows.focusedRowKey == rowKey && rows.focusedCardIndex == index }

    @Test
    fun `confirm on a rows card enters the page hero state and confirm in it goes to the rows listener`() {
        showRows()
        host.onMain { rows.requestFocus() }
        waitRowsCard(NEW, 0)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("本页进了 hero 态") { page.heroActive }
        assertTrue(host.onMain { rows.heroActive })
        assertEquals(listOf(true), pageListener.heroActive)
        assertEquals(emptyList(), rowsListener.cardClicks)
        // hero 态里按确定: 交给它自己的监听器 (进详情页), 本页的网格点击不报
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("交给一组一行的监听器") { rowsListener.cardClicks == listOf(NEW to 0) }
        assertEquals(emptyList(), pageListener.clicked)
    }

    @Test
    fun `switching between a grid and the rows keeps the page hero state`() {
        host.onMain { page.focusItem(0) }
        host.waitUntil("焦点到网格第一张") { page.grid?.focusedChild != null }
        host.onMain { page.setHeroActive(true, animated = false) }
        showRows()
        assertTrue(host.onMain { page.heroActive && rows.heroActive })
        assertFalse(host.onMain { page.grid?.isShown == true })
        host.onMain {
            page.showGrid(0, direction = 1, animated = false)
            page.setCards(0, testCards(12))
        }
        host.waitUntil("网格换回来") { page.grid?.isShown == true && !rows.isShown }
        assertTrue(host.onMain { page.heroActive })
        assertEquals(listOf(true), pageListener.heroActive)
    }

    @Test
    fun `the first row in the page hero state is pushed down onto the hero line and comes back after`() {
        showRows()
        host.onMain { rows.requestFocus() }
        waitRowsCard(NEW, 0)
        val wallY = host.onMain { rows.rowCenterYInWindow(NEW)!! }
        host.onMain { page.setHeroActive(true, animated = false) }
        host.waitUntil("首行推到 hero 线") { rows.rowCenterYInWindow(NEW)!! - wallY == rowsMetrics.heroHeaderTopPx }
        host.onMain { page.setHeroActive(false, animated = false) }
        host.waitUntil("首行回原处") { rows.rowCenterYInWindow(NEW) == wallY }
    }

    @Test
    fun `leaving the rows parks the focus on the page until a grid card takes it`() {
        showRows()
        host.onMain { rows.requestFocus() }
        waitRowsCard(NEW, 0)
        // 换回网格: 页面接着给这一份的数据 (同宿主)
        host.onMain {
            page.showGrid(0, direction = 1, animated = false)
            page.setCards(0, testCards(12))
        }
        host.waitUntil("焦点停放在本页上") { page.isFocused }
        assertFalse(host.onMain { rows.cardAreaHasFocus })
        host.onMain { page.focusItem(0) }
        host.waitUntil("网格的卡拿到焦点") { page.grid?.focusedChild != null && !page.isFocused }
    }

    @Test
    fun `focus in the rows is not reported as grid focus`() {
        showRows()
        host.onMain { rows.requestFocus() }
        waitRowsCard(NEW, 0)
        assertFalse(pageListener.gridFocus.lastOrNull() == true)
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused }
        host.onMain {
            page.showGrid(0, direction = 1, animated = false)
            page.setCards(0, testCards(12))
            page.focusItem(0)
        }
        host.waitUntil("网格报了焦点") { pageListener.gridFocus.lastOrNull() == true }
    }

    private companion object {
        const val ROWS_KEY = -1
        const val NEW = "row-new"
        const val WEEK = "row-week"
    }
}
