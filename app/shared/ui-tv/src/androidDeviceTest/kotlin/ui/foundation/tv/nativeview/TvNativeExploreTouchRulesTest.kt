/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Rect
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 探索页在触屏设备上照触屏的规矩走 ([TvNativeRowWallView.touchInput], 同 iPad 的 Compose 海报墙, 规则见 tvWallTouchTap): 按下不动焦点,
 * 卡片墙上点一下聚焦并进 hero 态, hero 态里点另一张只换焦点、点聚焦那张才进, 「更多」卡一下就进, 长按弹菜单, 点 hero 区进聚焦那张;
 * 卡片墙上不画聚焦效果; 列表由手指自由滚 (不就近停, 甩不进末尾的空白), 手指一拖焦点就停放在海报墙自己身上 (hero 态里原地退回卡片墙),
 * 程序送来的焦点也停放、列表不动, 之后按方向键落到屏上中间那一行. 触摸经 Instrumentation.sendPointerSync 走窗口的输入派发 (同真触摸).
 */
class TvNativeExploreTouchRulesTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = RulesListener()
    private lateinit var view: TvNativeExploreView
    private lateinit var items: List<TvNativeExploreItem>

    private class RulesListener : TvNativeExploreListener {
        val cardClicks = mutableListOf<Pair<String, Int>>()
        val moreClicks = mutableListOf<String>()
        val longPresses = mutableListOf<Pair<String, Int>>()
        val heroActive = mutableListOf<Boolean>()

        override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit
        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onMoreClick(rowKey: String) {
            moreClicks += rowKey
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) {
            longPresses += rowKey to index
        }

        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) = Unit
        override fun onHeroButtonClick(button: Int) = Unit
        override fun onSwitchCarousel(delta: Int): Boolean = true
        override fun onExitLeft() = Unit
        override fun onHeroActiveChanged(active: Boolean) {
            heroActive += active
        }

        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onToneChanged(tone: Float, splitY: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val style = testWallStyle()
            view = TvNativeExploreView(
                host.activity, host.sketch, scope, style, testExploreMetrics(style),
                testHeroTextStyle(), testHeroButtonStyle(), testTextStyle(32f, 44),
            )
            view.transitions = false
            view.animatedScroll = false
            view.listener = listener
            view.setButtons("立即观看", null, "新番时间表", null)
            view.setCarousel(3, 0, Color.WHITE)
            val empty = TvNativeHeroSource(backdrop = null, dimming = false, rawSubjectId = null, text = null)
            view.setSources(empty, empty)
            val more = TvNativeCard(imageUrl = null, title = "更多", more = TvNativeMore.Idle)
            items = listOf(
                TvNativeExploreItem.Spacer("spacer"),
                TvNativeExploreItem.Header("rec-header", "推荐"),
                TvNativeExploreItem.Row(REC, testCards(4, "推荐") + more),
                TvNativeExploreItem.Row(HOT, testCards(8, "热门")),
            ) + (0 until 8).map { TvNativeExploreItem.Row(longRow(it), testCards(8, "更多$it")) }
            view.setItems(items)
            view.touchInput = true
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        // 进页落点按电视的规矩送到 hero 按钮上, 之后按手指的规矩走 (见 lastInputTouch)
        host.onMain {
            view.lastInputTouch = false
            view.focusHeroButton(0)
        }
        host.instrumentation.waitForIdleSync()
        host.onMain { view.lastInputTouch = true }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun rowView(rowKey: String): TvNativeRowView? {
        fun find(v: View): TvNativeRowView? = when {
            v is TvNativeRowView && v.tag == rowKey -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(view)
    }

    /** [rowKey] 行第 [index] 张卡的视图. 在主线程上调. */
    private fun cardAt(rowKey: String, index: Int): TvNativeCardView =
        rowView(rowKey)?.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeCardView
            ?: error("$rowKey 第 $index 张没排出来")

    private fun focusedCard(): Pair<String?, Int> =
        host.onMain { (if (view.cardAreaHasFocus) view.focusedRowKey else null) to view.focusedCardIndex }

    /** 封面中心的屏幕坐标. */
    private fun coverCenter(rowKey: String, index: Int): Pair<Float, Float> = host.onMain {
        val card = cardAt(rowKey, index)
        val rect = card.coverRectInWindow()
        val window = IntArray(2)
        card.rootView.getLocationOnScreen(window)
        window[0] + rect.exactCenterX() to window[1] + rect.exactCenterY()
    }

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

    private fun tapCard(rowKey: String, index: Int) {
        val (x, y) = coverCenter(rowKey, index)
        tapAt(x, y)
    }

    private fun scrolled(): Int = host.onMain { view.scrolledPx }

    /**
     * 一笔竖着的拖动: 从屏幕坐标 ([x], [fromY]) 拖到 [toY], 分 [steps] 步, 相邻两步隔 [stepMillis] (隔得短就是甩出去); [hold] 步停在终点不动
     * 再松手 (松手速度为 0, 不甩).
     */
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
        host.waitUntil("列表停下") { exploreList(view)?.scrollState == RecyclerView.SCROLL_STATE_IDLE }
    }

    private fun exploreList(v: View): TvNativeExploreList? = when (v) {
        is TvNativeExploreList -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { exploreList(v.getChildAt(it)) }
        else -> null
    }

    /** 列表区中间一竖条的屏幕横坐标与下、上两处纵坐标 (避开 hero 按钮). */
    private fun listColumn(): Triple<Float, Float, Float> = host.onMain {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        Triple(location[0] + view.width * 0.6f, location[1] + view.height * 0.85f, location[1] + view.height * 0.45f)
    }

    /** 先按电视的规矩把焦点送到 [rowKey] 行第 [index] 张, 再切回手指的规矩 (见 lastInputTouch). */
    private fun focusWithKeysThenTouch(rowKey: String, index: Int) {
        host.onMain {
            view.lastInputTouch = false
            view.focusCard(rowKey, index)
        }
        host.waitUntil("$rowKey 第 $index 张拿到焦点") { cardAt(rowKey, index).isFocused }
        host.onMain { view.lastInputTouch = true }
    }

    @Test
    fun `a tap on a wall card focuses it and enters hero at once`() {
        tapCard(REC, 1)
        assertEquals(REC to 1, focusedCard(), "点的那张拿到焦点")
        assertEquals(listOf(true), listener.heroActive, "同一下就进了 hero 态")
        assertEquals(emptyList(), listener.cardClicks, "没进详情页")
    }

    @Test
    fun `in hero a tap on another card only moves focus and a second tap opens it`() {
        tapCard(REC, 1)
        tapCard(REC, 2)
        assertEquals(REC to 2, focusedCard(), "焦点换到点的那张")
        assertEquals(emptyList(), listener.cardClicks, "hero 态里点另一张不进详情页")
        tapCard(REC, 2)
        assertEquals(listOf(REC to 2), listener.cardClicks, "再点聚焦的那张才进")
    }

    @Test
    fun `the more card opens on the first tap`() {
        tapCard(REC, 4)
        assertEquals(listOf(REC), listener.moreClicks)
        assertEquals(emptyList(), listener.heroActive, "不进 hero 态")
    }

    @Test
    fun `a long press on a card opens its menu without opening the card`() {
        val (x, y) = coverCenter(REC, 2)
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, x, y)
        host.waitUntil("长按触发") { listener.longPresses.isNotEmpty() }
        send(MotionEvent.ACTION_UP, down, x, y)
        host.instrumentation.waitForIdleSync()
        assertEquals(listOf(REC to 2), listener.longPresses)
        assertEquals(emptyList(), listener.cardClicks)
        assertEquals(emptyList(), listener.heroActive, "长按不算点")
    }

    @Test
    fun `a press does not move focus until the finger lifts`() {
        val (x, y) = coverCenter(REC, 3)
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, x, y)
        host.instrumentation.waitForIdleSync()
        assertFalse(host.onMain { cardAt(REC, 3).isFocused }, "按下时焦点不动")
        send(MotionEvent.ACTION_UP, down, x, y)
        host.instrumentation.waitForIdleSync()
        assertEquals(REC to 3, focusedCard())
    }

    @Test
    fun `in hero a tap on the hero area opens the focused card`() {
        tapCard(REC, 1)
        assertEquals(listOf(true), listener.heroActive)
        val (x, y) = host.onMain {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            location[0] + view.width * 0.8f to location[1] + 200f
        }
        tapAt(x, y)
        assertEquals(listOf(REC to 1), listener.cardClicks)
    }

    @Test
    fun `the wall draws no focus look in touch mode until hero`() {
        focusWithKeysThenTouch(REC, 0)
        assertTrue(host.onMain { cardAt(REC, 0).touchLookHidden }, "卡片墙上不画聚焦")
        tapCard(REC, 0)
        assertEquals(listOf(true), listener.heroActive)
        assertFalse(host.onMain { cardAt(REC, 0).touchLookHidden }, "hero 态里聚焦那张照画")
    }

    @Test
    fun `a direction key brings the focus look back on the wall`() {
        focusWithKeysThenTouch(REC, 0)
        assertTrue(host.onMain { cardAt(REC, 0).touchLookHidden })
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(REC to 1, focusedCard())
        assertFalse(host.onMain { cardAt(REC, 1).touchLookHidden }, "按方向键就回到电视的规矩")
    }

    @Test
    fun `a focus request that drops the window touch mode keeps the touch rules`() {
        // 页面里的焦点请求 (弹窗关掉后交还焦点等) 会让窗口退出触摸模式: 手指的规矩看本页收到的输入, 不跟着变
        host.instrumentation.setInTouchMode(false)
        host.waitUntil("窗口离开触摸模式") { !view.isInTouchMode }
        host.onMain { view.focusCard(HOT, 3) }
        host.instrumentation.waitForIdleSync()
        assertTrue(host.onMain { view.isFocused }, "仍按手指的规矩停放")
    }

    @Test
    fun `a programmatic focus on the wall parks without moving the list`() {
        val before = scrolled()
        host.onMain { view.focusCard(HOT, 3) }
        host.instrumentation.waitForIdleSync()
        assertTrue(host.onMain { view.isFocused }, "焦点停在海报墙自己身上")
        assertEquals(null, focusedCard().first, "不在卡上")
        assertEquals(before, scrolled(), "列表不动")
    }

    @Test
    fun `a drag parks the focus and the list stays where the finger left it`() {
        focusWithKeysThenTouch(REC, 1)
        val before = scrolled()
        val (x, bottom, top) = listColumn()
        drag(x, bottom, top)
        assertTrue(host.onMain { view.isFocused }, "焦点停在海报墙自己身上")
        val moved = scrolled() - before
        val distance = (bottom - top).toInt()
        assertTrue(abs(moved - distance) < 60, "列表跟着手指走了 $moved px (拖了 $distance px), 不就近停到哪一行")
    }

    @Test
    fun `a drag in hero exits hero and leaves the list in place`() {
        tapCard(REC, 1)
        assertEquals(listOf(true), listener.heroActive)
        val before = scrolled()
        val (x, bottom, top) = listColumn()
        drag(x, bottom, bottom - (bottom - top) / 2)
        assertEquals(listOf(true, false), listener.heroActive, "一拖就退回卡片墙")
        assertTrue(host.onMain { view.isFocused }, "焦点停放")
        assertTrue(scrolled() - before > 0, "列表跟着手指往后滚, 没滚回卡片墙的停位")
    }

    @Test
    fun `back in first row hero returns to the carousel at the top`() {
        tapCard(REC, 1)
        assertEquals(listOf(true), listener.heroActive)
        // 页面按返回退出 hero 态
        host.onMain { view.setHeroActive(false) }
        host.waitUntil("列表滚回顶") { view.scrolledPx == 0 }
        assertEquals(listOf(true, false), listener.heroActive, "退出 hero 态")
        assertNull(host.onMain { view.focusedRowKey }, "第一排当场让出, 换回轮播 (同电视在首行按上)")
        assertTrue(host.onMain { view.isFocused }, "焦点停放")
    }

    @Test
    fun `a fling stops at the end of the wall`() {
        val end = host.onMain { view.metrics.wallEndScroll(items) }
        val (x, bottom, top) = listColumn()
        repeat(4) { drag(x, bottom, top, steps = 6, stepMillis = 8, hold = 0) }
        val scrolledNow = scrolled()
        assertTrue(scrolledNow <= end, "甩不进末尾的空白 ($scrolledNow > $end)")
        assertTrue(scrolledNow >= end - 4, "甩到了末端 ($scrolledNow < $end)")
    }

    @Test
    fun `a direction key after parking lands on the row nearest the middle`() {
        focusWithKeysThenTouch(REC, 1)
        val (x, bottom, top) = listColumn()
        drag(x, bottom, top)
        drag(x, bottom, top)
        val expected = host.onMain {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val middle = location[1] + view.metrics.listTopPx + view.metrics.viewportPx / 2
            items.filterIsInstance<TvNativeExploreItem.Row>().mapNotNull { row ->
                rowView(row.key)?.let { v ->
                    val at = IntArray(2)
                    v.getLocationInWindow(at)
                    row.key to abs(at[1] + (v.height - view.metrics.rowGapPx) / 2 - middle)
                }
            }.minBy { it.second }.first
        }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("落到一张卡上") { view.cardAreaHasFocus }
        assertEquals(expected, focusedCard().first, "落在屏上中间那一行")
    }

    private companion object {
        const val REC = "rec-row"
        const val HOT = "hot-row"

        fun longRow(index: Int) = "long-row-$index"
    }
}
