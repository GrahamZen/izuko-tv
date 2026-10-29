/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 网格页 (追番 / 搜索 / 新番时间表) 在触屏设备上照触屏的规矩走 ([TvNativeGridPageView.touchInput], 同 iPad 的 Compose 海报墙, 规则见
 * tvWallTouchTap): 按下不动焦点, 卡片墙上点一下聚焦并进 hero 态, 没有 hero 态的页面点一下就进, 长按弹菜单, hero 态里点 hero 区进聚焦那张;
 * 卡片墙上不画聚焦效果; 网格由手指自由滚 (不就近停), 手指一拖焦点就停放在网格页自己身上 (hero 态里原地退回卡片墙), 之后按方向键落到屏上
 * 中间那一行. 触摸经 Instrumentation.sendPointerSync 走窗口的输入派发.
 */
class TvNativeGridPageTouchRulesTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private lateinit var page: TvNativeGridPageView
    private val clicks = mutableListOf<Int>()
    private val longPresses = mutableListOf<Int>()
    private val heroChanges = mutableListOf<Boolean>()
    private val wallOpened = mutableListOf<Int>()

    private inner class PageListener : TvNativeGridPageListener {
        override fun onFocused(index: Int) = Unit
        override fun onClick(index: Int) {
            clicks += index
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            longPresses += index
        }

        override fun onTopRowUp(): Boolean = true
        override fun onHeroActiveChanged(active: Boolean) {
            heroChanges += active
        }

        override fun onWallOpened(index: Int) {
            wallOpened += index
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
            page.touchInput = true
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

    /** 第 [index] 张卡的视图. 在主线程上调. */
    private fun cardAt(index: Int): TvNativeCardView =
        page.grid!!.findViewHolderForAdapterPosition(index)!!.itemView as TvNativeCardView

    private fun focusedIndex(): Int = host.onMain { page.grid!!.let { g -> g.focusedChild?.let { g.getChildAdapterPosition(it) } ?: -1 } }

    private fun send(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        host.instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun tapAt(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, x, y)
        send(MotionEvent.ACTION_UP, down, x, y)
        host.instrumentation.waitForIdleSync()
    }

    /** 第 [index] 张封面中心的屏幕坐标. */
    private fun coverCenter(index: Int): Pair<Float, Float> = host.onMain {
        val card = cardAt(index)
        val rect = card.coverRectInWindow()
        val window = IntArray(2)
        card.rootView.getLocationOnScreen(window)
        window[0] + rect.exactCenterX() to window[1] + rect.exactCenterY()
    }

    private fun tapCard(index: Int) {
        val (x, y) = coverCenter(index)
        tapAt(x, y)
    }

    /** 一笔竖着的拖动, 分 [steps] 步, 停在终点不动 [hold] 步再松手 (不甩). */
    private fun drag(x: Float, fromY: Float, toY: Float, steps: Int = 12, stepMillis: Long = 40, hold: Int = 4) {
        val total = steps + hold + 1
        val start = SystemClock.uptimeMillis() - total * stepMillis
        for (i in 0..total) {
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                total -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val y = fromY + (toY - fromY) * i.coerceAtMost(steps) / steps
            val event = MotionEvent.obtain(start, start + i * stepMillis, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            host.instrumentation.sendPointerSync(event)
            event.recycle()
        }
        host.instrumentation.waitForIdleSync()
        host.waitUntil("网格停下") { page.grid!!.scrollState == RecyclerView.SCROLL_STATE_IDLE }
    }

    /** 网格中间一竖条的屏幕横坐标与下、上两处纵坐标. */
    private fun gridColumn(): Triple<Float, Float, Float> = host.onMain {
        val location = IntArray(2)
        page.getLocationOnScreen(location)
        Triple(location[0] + 1000f, location[1] + 950f, location[1] + 550f)
    }

    /** 已排出来的卡里下标最小那张的下标与它此刻的屏幕纵坐标 (网格滚了多少按同一张比). */
    private fun anchorCard(): Pair<Int, Int> = host.onMain {
        val grid = page.grid!!
        val child = (0 until grid.childCount).map { grid.getChildAt(it) }.filter { grid.getChildAdapterPosition(it) >= 0 }
            .minBy { grid.getChildAdapterPosition(it) }
        val location = IntArray(2)
        child.getLocationOnScreen(location)
        grid.getChildAdapterPosition(child) to location[1]
    }

    private fun topOf(index: Int): Int? = host.onMain {
        page.grid!!.findViewHolderForAdapterPosition(index)?.itemView?.let { v ->
            val location = IntArray(2)
            v.getLocationOnScreen(location)
            location[1]
        }
    }

    /** 先按电视的规矩把焦点送到第 [index] 张, 再切回手指的规矩 (见 lastInputTouch). */
    private fun focusWithKeysThenTouch(index: Int) {
        host.onMain {
            page.lastInputTouch = false
            page.grid!!.focusItem(index)
        }
        host.waitUntil("第 $index 张拿到焦点") { cardAt(index).isFocused }
        host.onMain { page.lastInputTouch = true }
    }

    @Test
    fun `a tap on a wall card focuses it and enters hero at once`() {
        tapCard(2)
        assertEquals(2, focusedIndex(), "点的那张拿到焦点")
        assertEquals(listOf(true), heroChanges, "同一下就进了 hero 态")
        assertEquals(emptyList(), clicks, "没进详情页")
    }

    @Test
    fun `in hero a tap on another card only moves focus and a second tap opens it`() {
        tapCard(2)
        tapCard(3)
        assertEquals(3, focusedIndex())
        assertEquals(emptyList(), clicks)
        tapCard(3)
        assertEquals(listOf(3), clicks)
    }

    @Test
    fun `a page without hero opens the tapped card at once and moves focus to it`() {
        host.onMain {
            page.heroEnabled = false
            page.confirmFocusesWall = false
        }
        tapCard(2)
        assertEquals(listOf(2), clicks, "第一下就进")
        assertEquals(2, focusedIndex(), "焦点跟到点的那张, 回来时落在它上面")
    }

    @Test
    fun `on a page whose backdrop follows the focus a tap opens at once without focusing the backdrop`() {
        host.onMain {
            page.heroEnabled = false
            page.enableWallBackdrop()
        }
        tapCard(3)
        assertEquals(listOf(3), clicks, "第一下就进")
        assertEquals(emptyList(), wallOpened, "不算点开: 平板的详情页没有清晰剧照, 回到本页不倒放")
        assertEquals(3, focusedIndex(), "焦点跟到点的那张, 背景随后换成它的")
    }

    @Test
    fun `a long press on a card opens its menu without opening it`() {
        val (x, y) = coverCenter(4)
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, x, y)
        host.waitUntil("长按触发") { longPresses.isNotEmpty() }
        send(MotionEvent.ACTION_UP, down, x, y)
        host.instrumentation.waitForIdleSync()
        assertEquals(listOf(4), longPresses)
        assertEquals(emptyList(), clicks)
        assertEquals(emptyList(), heroChanges, "长按不算点")
    }

    @Test
    fun `a press does not move focus until the finger lifts`() {
        val (x, y) = coverCenter(5)
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, x, y)
        host.instrumentation.waitForIdleSync()
        assertFalse(host.onMain { cardAt(5).isFocused }, "按下时焦点不动")
        send(MotionEvent.ACTION_UP, down, x, y)
        host.instrumentation.waitForIdleSync()
        assertEquals(5, focusedIndex())
    }

    @Test
    fun `in hero a tap on the hero area opens the focused card`() {
        tapCard(2)
        assertEquals(listOf(true), heroChanges)
        val (x, y) = host.onMain {
            val location = IntArray(2)
            page.getLocationOnScreen(location)
            location[0] + 1500f to location[1] + 250f
        }
        tapAt(x, y)
        assertEquals(listOf(2), clicks)
    }

    @Test
    fun `in hero a tap below a single row exits hero`() {
        // 只有一排卡 (同用户报的那种分类): 卡片下面没有别的行, 外包框只有这一排那么高
        host.onMain { page.setCards(0, testCards(6)) }
        host.waitUntil("一排卡排出来") { (page.grid?.childCount ?: 0) in 1..6 }
        tapCard(2)
        assertEquals(listOf(true), heroChanges)
        val (x, y) = host.onMain {
            val card = cardAt(2)
            val rect = card.coverRectInWindow()
            val window = IntArray(2)
            card.rootView.getLocationOnScreen(window)
            window[0] + rect.exactCenterX() to window[1] + rect.bottom + 200f
        }
        tapAt(x, y)
        assertEquals(listOf(true, false), heroChanges, "点那一排下面的空白处退回卡片墙")
        assertEquals(emptyList(), clicks, "没打开卡片")
    }

    @Test
    fun `the wall draws no focus look in touch mode until hero`() {
        host.onMain { page.grid!!.focusItem(0) }
        host.waitUntil("第 0 张拿到焦点") { cardAt(0).isFocused }
        assertTrue(host.onMain { cardAt(0).touchLookHidden }, "卡片墙上不画聚焦")
        tapCard(0)
        assertEquals(listOf(true), heroChanges)
        assertFalse(host.onMain { cardAt(0).touchLookHidden }, "hero 态里聚焦那张照画")
    }

    @Test
    fun `a drag parks the focus and the grid stays where the finger left it`() {
        focusWithKeysThenTouch(1)
        val (anchor, before) = anchorCard()
        val (x, bottom, top) = gridColumn()
        drag(x, bottom, top)
        assertTrue(host.onMain { page.isFocused }, "焦点停在网格页自己身上")
        val after = topOf(anchor) ?: error("第 $anchor 张滚没了")
        val moved = before - after
        val distance = (bottom - top).toInt()
        assertTrue(abs(moved - distance) < 60, "网格跟着手指走了 $moved px (拖了 $distance px), 不就近停到哪一行")
    }

    @Test
    fun `a drag in hero exits hero and keeps the focused row where it was`() {
        tapCard(8)
        assertEquals(listOf(true), heroChanges)
        val before = topOf(8) ?: error("第 8 张没排出来")
        val (x, bottom, _) = gridColumn()
        drag(x, bottom, bottom - 200f)
        assertEquals(listOf(true, false), heroChanges, "一拖就退回卡片墙")
        assertTrue(host.onMain { page.isFocused }, "焦点停放")
        val after = topOf(8) ?: error("第 8 张没排出来")
        assertTrue(abs(before - 200 - after) < 60, "聚焦行跟着手指上移了 ${before - after} px, 没滚回卡片墙的停位")
    }

    @Test
    fun `a direction key after parking lands on the row nearest the middle`() {
        focusWithKeysThenTouch(1)
        val (x, bottom, top) = gridColumn()
        drag(x, bottom, top)
        val expected = host.onMain { page.grid!!.indexNearestViewportCenter(1)!! }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("落到一张卡上") { page.grid!!.hasFocus() }
        assertEquals(expected, focusedIndex(), "落在屏上中间那一行、原来那一列")
    }

    @Test
    fun `without touch input the wall keeps the focus look`() {
        host.onMain { page.touchInput = false }
        host.onMain { page.grid!!.focusItem(0) }
        host.waitUntil("第 0 张拿到焦点") { cardAt(0).isFocused }
        assertFalse(host.onMain { cardAt(0).touchLookHidden })
    }
}
