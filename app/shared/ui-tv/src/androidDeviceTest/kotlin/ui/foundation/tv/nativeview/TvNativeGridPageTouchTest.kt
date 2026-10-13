/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 网格页 (追番 / 新番时间表 / 搜索) 在触屏设备上的横向手势: 多列网格不随手指横向滑走 (原来会整块滑出画面变空白);
 * 页面要了左右滑 ([TvNativeGridPageView.onTouchSwipe]) 时一下翻一项. 触摸经 Instrumentation.sendPointerSync 走窗口的输入派发.
 */
class TvNativeGridPageTouchTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private lateinit var page: TvNativeGridPageView
    private val swipes = mutableListOf<Int>()
    private val clicks = mutableListOf<Int>()
    private val heroChanges = mutableListOf<Boolean>()

    private inner class PageListener : TvNativeGridPageListener {
        override fun onFocused(index: Int) = Unit
        override fun onClick(index: Int) {
            clicks += index
        }
        override fun onTopRowUp(): Boolean = true
        override fun onHeroActiveChanged(active: Boolean) {
            heroChanges += active
        }
        override fun onToneChanged(tone: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onGridFocusChanged(hasFocus: Boolean) = Unit
        override fun onFocusParkedChanged(parked: Boolean) = Unit
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
            page.listener = PageListener()
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, testCards(30))
        }
        host.waitUntil("卡片排出来") { (page.grid?.childCount ?: 0) > 0 }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { scope.cancel() }
        host.close()
    }

    /** 第一张卡在屏幕上的位置. */
    private fun firstCardOnScreen(): Pair<Int, Int> = host.onMain {
        val card: View = page.grid!!.findViewHolderForAdapterPosition(0)!!.itemView
        val location = IntArray(2)
        card.getLocationOnScreen(location)
        location[0] to location[1]
    }

    private fun cardCenterY(): Float = host.onMain {
        val card: View = page.grid!!.findViewHolderForAdapterPosition(0)!!.itemView
        val location = IntArray(2)
        card.getLocationOnScreen(location)
        location[1] + card.height / 2f
    }

    /** 一笔触摸, 依次经过 [points], 相邻两点隔 16ms. */
    private fun stroke(points: List<Pair<Float, Float>>) {
        val start = SystemClock.uptimeMillis() - points.size * 16L
        points.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(start, start + i * 16L, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            host.instrumentation.sendPointerSync(event)
            event.recycle()
        }
        host.instrumentation.waitForIdleSync()
    }

    private fun line(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int = 10) =
        (0..steps).map { fromX + (toX - fromX) * it / steps to fromY + (toY - fromY) * it / steps }

    private fun settle() = host.waitUntil("网格停下") { page.grid!!.scrollState == 0 }

    @Test
    fun `a horizontal swipe on the grid switches once and the cards stay in place`() {
        host.onMain { page.onTouchSwipe = { swipes += it } }
        val before = firstCardOnScreen()
        val y = cardCenterY()
        stroke(line(1500f, y, 400f, y))
        settle()
        assertEquals(listOf(1), swipes, "左滑翻到下一项")
        assertEquals(before, firstCardOnScreen(), "网格没有横着滑走")
        stroke(line(400f, y, 1500f, y))
        assertEquals(listOf(1, -1), swipes, "右滑翻回上一项")
    }

    @Test
    fun `a drag that starts vertical never scrolls the grid sideways`() {
        host.onMain { page.onTouchSwipe = { swipes += it } }
        val columnLeft = firstCardOnScreen().first
        val y = cardCenterY()
        // 先竖着拖 (列表接管), 再一路横着拖
        stroke(line(1000f, y + 300f, 1000f, y, steps = 5) + line(1000f, y, 200f, y, steps = 8).drop(1))
        settle()
        assertEquals(emptyList(), swipes, "竖着起手的手势不算左右滑")
        val leftAfter = host.onMain {
            val card = page.grid!!.getChildAt(0)
            val location = IntArray(2)
            card.getLocationOnScreen(location)
            location[0]
        }
        assertEquals(columnLeft, leftAfter, "首列仍在原来的横向位置上")
    }

    @Test
    fun `scrolling down by finger keeps going instead of springing back to the focused row`() {
        host.onMain { page.grid!!.focusItem(0) }
        host.waitUntil("第 0 张拿到焦点") { page.grid!!.focusedChild?.let { page.grid!!.getChildAdapterPosition(it) } == 0 }
        val y = cardCenterY()
        stroke(line(1000f, y + 500f, 1000f, y - 300f, steps = 12))
        settle()
        host.instrumentation.waitForIdleSync()
        val grid = page.grid!!
        val focused = host.onMain { grid.focusedChild?.let { grid.getChildAdapterPosition(it) } ?: -1 }
        assertTrue(focused >= 6, "焦点跟着翻到下面的行, 不是原来第一行 (焦点 $focused)")
        assertEquals(0, focused % 6, "还在原来那一列")
        val focusedOnScreen = host.onMain { grid.findViewHolderForAdapterPosition(focused)!!.itemView.let { it.top >= 0 && it.bottom <= grid.height } }
        assertTrue(focusedOnScreen, "持焦那张在屏上")
    }

    private fun focusedIndex(): Int = host.onMain { page.grid!!.let { g -> g.focusedChild?.let { g.getChildAdapterPosition(it) } ?: -1 } }

    private fun tapCard(index: Int) {
        val (x, y) = host.onMain {
            val card = page.grid!!.findViewHolderForAdapterPosition(index)!!.itemView
            val location = IntArray(2)
            card.getLocationOnScreen(location)
            location[0] + card.width / 2f to location[1] + card.height / 2f
        }
        stroke(listOf(x to y, x to y))
    }

    @Test
    fun `on the card wall one tap focuses a card and enters the hero state`() {
        tapCard(2)
        assertEquals(2, focusedIndex(), "点的那张拿到焦点")
        assertEquals(listOf(true), heroChanges, "同一下就进了 hero 态")
        assertEquals(emptyList(), clicks, "没进详情页")
    }

    @Test
    fun `in the hero state a tap on another card only moves the focus`() {
        tapCard(2)
        assertEquals(listOf(true), heroChanges)
        host.instrumentation.waitForIdleSync()
        tapCard(3)
        assertEquals(3, focusedIndex(), "焦点换到点的那张")
        assertEquals(emptyList(), clicks, "hero 态里点另一张不进详情页")
        tapCard(3)
        assertEquals(listOf(3), clicks, "再点聚焦的那张才进")
    }

    @Test
    fun `a horizontal swipe also switches in the hero state`() {
        host.onMain { page.onTouchSwipe = { swipes += it } }
        tapCard(2)
        assertEquals(listOf(true), heroChanges)
        host.instrumentation.waitForIdleSync()
        val y = cardCenterY()
        stroke(line(1500f, y, 400f, y))
        assertEquals(listOf(1), swipes, "hero 态里左滑同样翻到下一项")
        assertEquals(emptyList(), clicks, "滑动不算点卡")
    }

    @Test
    fun `without a hero state a tap on an unfocused card only focuses it`() {
        // 「海报上按确定」选直接播放: 确认就离开页面, 点没聚焦的卡只聚焦
        host.onMain {
            page.heroEnabled = false
            page.confirmFocusesWall = false
        }
        tapCard(2)
        assertEquals(2, focusedIndex())
        assertEquals(emptyList(), clicks, "第一下只聚焦")
        tapCard(2)
        assertEquals(listOf(2), clicks, "点聚焦的那张才确认")
    }

    @Test
    fun `without a swipe handler a horizontal drag still leaves the cards in place`() {
        val before = firstCardOnScreen()
        val y = cardCenterY()
        stroke(line(1500f, y, 400f, y))
        settle()
        assertEquals(before, firstCardOnScreen(), "网格没有横着滑走 (原来整块滑出画面变空白)")
    }
}
