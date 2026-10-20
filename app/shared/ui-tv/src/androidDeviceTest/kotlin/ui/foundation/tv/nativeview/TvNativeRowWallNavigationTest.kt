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
 * 行式海报墙 ([TvNativeRowWallView]) 照追番页「追更」那样用 (没有顶部区域) 时的导航: 上下落到屏上同一列 (行短了落最后一张)、
 * 首行按上交给页面、行首按左 / 行尾按右出页面、卡片墙上确定进 hero 态 (hero 态里确定交给页面, 首行整片下推到 hero 线)、长按交给页面、远跳回首卡与途中的确认、
 * 聚焦那一行被数据拿掉时交给下一行、从页面外进来回到上次那张 / 按屏上高度就近接住.
 *
 * 列表: [有新集] 10 张 [今天] 3 张 [本周] 12 张 [已追平] 12 张. 1080p, 一屏完整放得下 6 张.
 */
class TvNativeRowWallNavigationTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = WallListener()
    private lateinit var view: TvNativeRowWallView
    private lateinit var metrics: TvNativeExploreMetrics
    private lateinit var outside: View

    private inner class WallListener : TvNativeRowWallListener {
        val cardClicks = mutableListOf<Pair<String, Int>>()
        val longPresses = mutableListOf<Pair<String, Int>>()
        val heroActive = mutableListOf<Boolean>()
        var topRowUp = 0
        var exitLeft = 0
        var exitRight = 0

        override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit

        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) {
            longPresses += rowKey to index
        }

        override fun onBindCard(rowKey: String, index: Int) = Unit

        // 页面把焦点送回标签行 (这里用页面外的那个落点代替)
        override fun onTopRowUp(): Boolean {
            topRowUp++
            outside.requestFocus()
            return true
        }

        override fun onExitLeft() {
            exitLeft++
        }

        override fun onExitRight() {
            exitRight++
        }

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
            metrics = testExploreMetrics(style).copy(spacerPx = 0)
            view = TvNativeRowWallView(host.activity, host.sketch, scope, style, metrics, testHeroTextStyle(), testTextStyle(32f, 44))
            view.transitions = false
            view.animatedScroll = false
            view.rowListener = listener
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
        scope.cancel()
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
    fun `the row start going left and the row end going right leave the page`() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("左边出页面") { listener.exitLeft == 1 }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(TODAY, 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 2)
        waitCard(TODAY, 2)
        assertEquals(0, listener.exitRight)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("右边出页面") { listener.exitRight == 1 }
        assertEquals(TODAY to 2, card())
        assertEquals(1, listener.exitLeft)
    }

    @Test
    fun `coming in from outside near a height lands on the nearest row at its right end`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
        waitCard(WEEK, 3)
        // 聚焦卡的中线就是它那一行卡片块的中线 (差一两个像素的取整)
        val focusedY = host.onMain { view.focusedCardCenterYInWindow() }!!
        assertTrue(abs(focusedY - host.onMain { view.rowCenterYInWindow(WEEK) }!!) <= 2)
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused && !view.cardAreaHasFocus }
        assertEquals(null, host.onMain { view.focusedCardCenterYInWindow() })
        // 从右边标签的网格换过来时那边聚焦卡的高度 = 此刻「今天」那一行的中线附近: 落在那一行屏上最右边那张 (只有 3 张, 落最后一张)
        host.onMain {
            view.enterNearRow(view.rowCenterYInWindow(TODAY)!! + 5)
            view.requestFocus()
        }
        waitCard(TODAY, 2)
        // 用一次就复位: 再出去再进来回上次那张
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused && !view.cardAreaHasFocus }
        host.onMain { view.requestFocus() }
        waitCard(TODAY, 2)
    }

    @Test
    fun `the nearest row on a long row lands on the right end of what is on screen`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        waitCard(WEEK, 0)
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused && !view.cardAreaHasFocus }
        // 「有新集」10 张, 屏上完整放得下 6 张, 行首在第 0 张: 落第 5 张
        host.onMain {
            view.enterNearRow(view.rowCenterYInWindow(NEW)!!)
            view.requestFocus()
        }
        waitCard(NEW, 5)
    }

    @Test
    fun `confirm on a card enters the hero state and confirm in it is handed to the page`() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(NEW, 1)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态") { view.heroActive }
        assertEquals(listOf(true), listener.heroActive)
        assertEquals(emptyList(), listener.cardClicks)
        // hero 态里按确定交给页面 (进详情页), 不退 hero 态
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("交给页面") { listener.cardClicks == listOf(NEW to 1) }
        assertTrue(host.onMain { view.heroActive })
        // 页面按返回退出 hero 态
        host.onMain { view.setHeroActive(false) }
        assertEquals(listOf(true, false), listener.heroActive)
    }

    @Test
    fun `the first row in the hero state is pushed down onto the hero line and comes back after`() {
        val wallY = host.onMain { view.rowCenterYInWindow(NEW)!! }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态") { view.heroActive }
        // 列表停在顶也滚不到 hero 线: 整片往下推那一截, 首组标题落在 hero 线上 (卡片墙上它在列表顶)
        host.waitUntil("首行推到 hero 线") { view.rowCenterYInWindow(NEW)!! - wallY == metrics.heroHeaderTopPx }
        host.onMain { view.setHeroActive(false) }
        host.waitUntil("首行回原处") { view.rowCenterYInWindow(NEW) == wallY }
    }

    @Test
    fun `holding confirm is handed to the page as a long press on the wall and in the hero state`() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(NEW, 1)
        holdConfirm()
        host.waitUntil("交给页面") { listener.longPresses == listOf(NEW to 1) }
        assertFalse(host.onMain { view.heroActive })
        // 长按那一下不算点击
        assertEquals(emptyList(), listener.cardClicks)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态") { view.heroActive }
        holdConfirm()
        host.waitUntil("hero 态里也交给页面") { listener.longPresses == listOf(NEW to 1, NEW to 1) }
        assertEquals(emptyList(), listener.cardClicks)
    }

    private fun holdConfirm() {
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(450)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test
    fun `coming in at a row number lands on that row at its right end and past the end on the last row`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(TODAY, 0)
        // 只数行不数组标题
        assertEquals(1, host.onMain { view.focusedRowNumber() })
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态") { view.heroActive }
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused && !view.cardAreaHasFocus }
        // 从右边标签的网格第 3 行 (从 0 数第 2 行) 换过来: 落「本周」屏上最右边那张 (一屏完整放得下 6 张)
        host.onMain {
            view.enterRow(2)
            view.requestFocus()
        }
        waitCard(WEEK, 5)
        assertTrue(host.onMain { view.heroActive })
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused && !view.cardAreaHasFocus }
        host.onMain {
            view.enterRow(10)
            view.requestFocus()
        }
        waitCard(CAUGHT, 5)
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
    fun `back to the first card from a far row lands there and a confirm on the way enters the hero state on it`() {
        restOnFarRow()
        val heroOnTheWay = host.onMain {
            view.focusCard(farRow(0), 0, far = true)
            pressOnView(KeyEvent.KEYCODE_DPAD_CENTER)
            view.heroActive
        }
        assertFalse(heroOnTheWay)
        waitCard(farRow(0), 0)
        // 途中那一下确认落地后点在目标上: 卡片墙上的确认 = 进 hero 态, 不交给页面
        host.waitUntil("落地后在目标上进了 hero 态") { view.heroActive }
        assertEquals(farRow(0), host.onMain { view.focusedRowKey })
        assertEquals(emptyList(), listener.cardClicks)
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
