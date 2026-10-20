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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 探索页在触屏设备上照触屏的规矩走 ([TvNativeRowWallView.touchInput], 同 iPad 的 Compose 海报墙, 规则见 tvWallTouchTap): 按下不动焦点,
 * 卡片墙上点一下聚焦并进 hero 态, hero 态里点另一张只换焦点、点聚焦那张才进, 「更多」卡一下就进, 长按弹菜单, 点 hero 区进聚焦那张;
 * 卡片墙上不画聚焦效果. 触摸经 Instrumentation.sendPointerSync 走窗口的输入派发 (同真触摸).
 */
class TvNativeExploreTouchRulesTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = RulesListener()
    private lateinit var view: TvNativeExploreView

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
            view.setItems(
                listOf(
                    TvNativeExploreItem.Spacer("spacer"),
                    TvNativeExploreItem.Header("rec-header", "推荐"),
                    TvNativeExploreItem.Row(REC, testCards(4, "推荐") + more),
                    TvNativeExploreItem.Row(HOT, testCards(8, "热门")),
                ),
            )
            view.touchInput = true
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusHeroButton(0) }
        host.instrumentation.waitForIdleSync()
        host.instrumentation.setInTouchMode(true)
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
        host.onMain { view.focusCard(REC, 0) }
        host.waitUntil("第 0 张拿到焦点") { cardAt(REC, 0).isFocused }
        assertTrue(host.onMain { cardAt(REC, 0).touchLookHidden }, "卡片墙上不画聚焦")
        tapCard(REC, 0)
        assertEquals(listOf(true), listener.heroActive)
        assertFalse(host.onMain { cardAt(REC, 0).touchLookHidden }, "hero 态里聚焦那张照画")
    }

    @Test
    fun `leaving touch mode brings the focus look back on the wall`() {
        host.onMain { view.focusCard(REC, 0) }
        host.waitUntil("第 0 张拿到焦点") { cardAt(REC, 0).isFocused }
        assertTrue(host.onMain { cardAt(REC, 0).touchLookHidden })
        host.instrumentation.setInTouchMode(false)
        host.instrumentation.waitForIdleSync()
        assertFalse(host.onMain { cardAt(REC, 0).touchLookHidden }, "按方向键 (离开触摸模式) 就回到电视的规矩")
    }

    private companion object {
        const val REC = "rec-row"
        const val HOT = "hot-row"
    }
}
