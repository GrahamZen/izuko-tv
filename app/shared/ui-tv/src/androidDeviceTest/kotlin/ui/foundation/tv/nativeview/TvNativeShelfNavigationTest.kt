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
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 货架页 ([TvNativeShelfView], 追番页「追更」) 的导航: 上下落到屏上同一列 (行短了落最后一张)、首行按上交给页面、行首按左出页面、
 * 确定直接交给页面 (没有 hero 态)、远跳回首卡与途中的确认、聚焦那一行被数据拿掉时交给下一行、从页面外进来回到上次那张.
 *
 * 列表: [有新集] 10 张 [今天] 3 张 [本周] 12 张 [已追平] 12 张. 1080p, 一屏完整放得下 6 张.
 */
class TvNativeShelfNavigationTest {
    private val host = TvNativeTestHost()
    private val listener = ShelfListener()
    private lateinit var view: TvNativeShelfView
    private lateinit var outside: View

    private inner class ShelfListener : TvNativeShelfListener {
        val cardFocused = mutableListOf<Pair<String, Int>>()
        val cardClicks = mutableListOf<Pair<String, Int>>()
        var topRowUp = 0
        var exitLeft = 0

        override fun onCardFocused(rowKey: String, index: Int) {
            cardFocused += rowKey to index
        }

        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit

        // 页面把焦点送回标签行 (这里用页面外的那个落点代替)
        override fun onTopRowUp(): Boolean {
            topRowUp++
            outside.requestFocus()
            return true
        }

        override fun onExitLeft() {
            exitLeft++
        }

        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val style = testWallStyle()
            view = TvNativeShelfView(host.activity, host.sketch, style, testExploreMetrics(style).copy(spacerPx = 0), testTextStyle(32f, 44))
            view.transitions = false
            view.animatedScroll = false
            view.listener = listener
            view.setItems(defaultItems())
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.requestFocus() }
        waitCard(NEW, 0)
    }

    @AfterTest
    fun tearDown() {
        host.close()
    }

    private fun defaultItems() = listOf(
        TvNativeExploreItem.Header("h-new", "有新集"),
        TvNativeExploreItem.Row(NEW, testCards(10, "新")),
        TvNativeExploreItem.Header("h-today", "今天更新"),
        TvNativeExploreItem.Row(TODAY, testCards(3, "今天")),
        TvNativeExploreItem.Header("h-week", "本周更新"),
        TvNativeExploreItem.Row(WEEK, testCards(12, "本周")),
        TvNativeExploreItem.Header("h-caught", "已追平"),
        TvNativeExploreItem.Row(CAUGHT, testCards(12, "追平")),
    )

    private fun card(): Pair<String?, Int> = host.onMain { (if (view.cardAreaHasFocus) view.focusedRowKey else null) to view.focusedCardIndex }

    private fun waitCard(rowKey: String, index: Int) =
        host.waitUntil("焦点到 $rowKey 第 $index 张") { view.cardAreaHasFocus && view.focusedRowKey == rowKey && view.focusedCardIndex == index }

    private fun press(keyCode: Int, times: Int = 1) = repeat(times) { host.press(keyCode) }

    /** 在主线程上当场派发一次按下 + 抬起 (远跳途中焦点停放在本视图上, 按键先到这里). */
    private fun pressOnView(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    @Test
    fun `down and up keep the on screen column and a short row lands on its last card`() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 4)
        waitCard(NEW, 4)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(TODAY, 2)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        // 从「今天」的第 3 张 (屏上第 3 列) 下去
        waitCard(WEEK, 2)
        press(KeyEvent.KEYCODE_DPAD_UP, 2)
        waitCard(NEW, 2)
    }

    @Test
    fun `the first row going up is handed to the page`() {
        press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("交给页面") { listener.topRowUp == 1 && outside.isFocused }
        assertFalse(host.onMain { view.cardAreaHasFocus })
    }

    @Test
    fun `the last row going down is swallowed`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, 3)
        waitCard(CAUGHT, 0)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        SystemClock.sleep(200)
        assertEquals(CAUGHT to 0, card())
    }

    @Test
    fun `the row start going left leaves the page and the row end going right is swallowed`() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("出页面") { listener.exitLeft == 1 }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(TODAY, 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
        waitCard(TODAY, 2)
        SystemClock.sleep(200)
        assertEquals(TODAY to 2, card())
        assertEquals(1, listener.exitLeft)
    }

    @Test
    fun `confirm on a card is handed to the page right away`() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(NEW, 1)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("交给页面") { listener.cardClicks == listOf(NEW to 1) }
    }

    /** 换成 10 行、每行 12 张 (第一行离得远, 回首卡要远跳), 停在最后一行第 10 张; 之后的滚动带动画. */
    private fun restOnFarRow() {
        host.onMain {
            view.setItems((0 until 10).flatMap { listOf(TvNativeExploreItem.Header("h-$it", "第 $it 组"), TvNativeExploreItem.Row(farRow(it), testCards(12, "第 $it 行"))) })
            view.focusCard(farRow(9), 9)
        }
        waitCard(farRow(9), 9)
        host.onMain { view.animatedScroll = true }
    }

    private fun farRow(index: Int) = "far-row-$index"

    @Test
    fun `back to the first card from a far row lands there and a confirm on the way acts on it`() {
        restOnFarRow()
        val clicksOnTheWay = host.onMain {
            view.focusCard(farRow(0), 0, far = true)
            pressOnView(KeyEvent.KEYCODE_DPAD_CENTER)
            listener.cardClicks.toList()
        }
        assertEquals(emptyList(), clicksOnTheWay)
        waitCard(farRow(0), 0)
        host.waitUntil("落地后点了目标") { listener.cardClicks == listOf(farRow(0) to 0) }
    }

    @Test
    fun `back again during the far jump lands right away`() {
        restOnFarRow()
        val landed = host.onMain {
            view.focusCard(farRow(0), 0, far = true)
            view.landFarJump()
        }
        assertTrue(landed)
        waitCard(farRow(0), 0)
        assertFalse(host.onMain { view.landFarJump() })
    }

    @Test
    fun `a row taken out by new data hands focus to the row that moves up`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(TODAY, 1)
        // 「今天」那三部都播了 (挪进了有新集): 这一组没了, 焦点交给顶上来的「本周」同一列
        host.onMain { view.setItems(defaultItems().filter { it.key != TODAY && it.key != "h-today" }) }
        waitCard(WEEK, 1)
    }

    @Test
    fun `focus coming back from outside the page lands on the last card`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
        waitCard(WEEK, 3)
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused && !view.cardAreaHasFocus }
        host.onMain { view.requestFocus() }
        waitCard(WEEK, 3)
    }

    private companion object {
        const val NEW = "row-new"
        const val TODAY = "row-today"
        const val WEEK = "row-week"
        const val CAUGHT = "row-caught"
    }
}
