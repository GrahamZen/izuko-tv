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
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 探索页在触屏设备上的轮播 (见 [TvNativeExploreView.touchInput]): hero 按钮第一下点就是点击, 轮播区左右滑翻轮播;
 * 触摸开关关着 (电视) 时这些都不装. 触摸经 Instrumentation.sendPointerSync 走窗口的输入派发 (同真触摸).
 */
class TvNativeExploreTouchTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = TouchListener()
    private lateinit var view: TvNativeExploreView

    private class TouchListener : TvNativeExploreListener {
        val heroButtonClicked = mutableListOf<Int>()
        val switchCarousel = mutableListOf<Int>()
        val cardClicks = mutableListOf<Pair<String, Int>>()

        override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit
        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) = Unit
        override fun onHeroButtonClick(button: Int) {
            heroButtonClicked += button
        }

        override fun onSwitchCarousel(delta: Int): Boolean {
            switchCarousel += delta
            return true
        }

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
            view.setItems(
                listOf(
                    TvNativeExploreItem.Spacer("spacer"),
                    TvNativeExploreItem.Header("rec-header", "推荐"),
                    TvNativeExploreItem.Row(REC, testCards(12, "推荐")),
                ),
            )
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusHeroButton(0) }
        host.instrumentation.waitForIdleSync()
    }

    @AfterTest
    fun tearDown() {
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun heroButtons(): List<TvNativeHeroButton> = host.onMain {
        val found = mutableListOf<TvNativeHeroButton>()
        fun walk(v: View) {
            if (v is TvNativeHeroButton) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        found
    }

    /** 控件中心的屏幕坐标. */
    private fun centerOf(target: View): Pair<Float, Float> = host.onMain {
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        location[0] + target.width / 2f to location[1] + target.height / 2f
    }

    /** 一笔触摸: 第一点按下、中间各点移动、最后一点抬起, 相邻两点隔 [stepMillis]. */
    private fun stroke(points: List<Pair<Float, Float>>, stepMillis: Long = 16) {
        val start = SystemClock.uptimeMillis() - points.size * stepMillis
        points.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(start, start + i * stepMillis, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            host.instrumentation.sendPointerSync(event)
            event.recycle()
        }
        host.instrumentation.waitForIdleSync()
    }

    private fun tap(target: View) {
        val (x, y) = centerOf(target)
        stroke(listOf(x to y, x to y))
    }

    /** 从 [fromX] 横着滑到 [toX] (纵坐标 [y]), 分 [steps] 步. */
    private fun swipe(fromX: Float, toX: Float, y: Float, steps: Int = 10, stepMillis: Long = 16) =
        stroke((0..steps).map { fromX + (toX - fromX) * it / steps to y }, stepMillis)

    private fun width(): Float = host.onMain { view.width.toFloat() }

    @Test
    fun `tapping an unfocused hero button clicks it on the first tap`() {
        host.onMain { view.touchInput = true }
        val (_, schedule) = heroButtons()
        tap(schedule)
        assertEquals(listOf(1), listener.heroButtonClicked)
        tap(heroButtons()[0])
        assertEquals(listOf(1, 0), listener.heroButtonClicked)
    }

    @Test
    fun `swiping left on the carousel shows the next item`() {
        host.onMain { view.touchInput = true }
        val (_, y) = centerOf(heroButtons()[0])
        swipe(width() * 0.7f, width() * 0.2f, y)
        assertEquals(listOf(1), listener.switchCarousel)
    }

    @Test
    fun `swiping right from a hero button shows the previous item without clicking`() {
        host.onMain { view.touchInput = true }
        val (x, y) = centerOf(heroButtons()[1])
        swipe(x, x + width() * 0.5f, y)
        assertEquals(listOf(-1), listener.switchCarousel)
        assertEquals(emptyList(), listener.heroButtonClicked)
    }

    @Test
    fun `a short slow horizontal drag does not switch`() {
        host.onMain { view.touchInput = true }
        val (x, y) = centerOf(heroButtons()[0])
        val density = host.onMain { view.resources.displayMetrics.density }
        swipe(x + 300f, x + 300f - 30f * density, y, steps = 10, stepMillis = 60)
        assertEquals(emptyList(), listener.switchCarousel)
    }

    @Test
    fun `without touch input the hero handles no touches`() {
        val (_, schedule) = heroButtons()
        tap(schedule)
        val (_, y) = centerOf(schedule)
        swipe(width() * 0.7f, width() * 0.2f, y)
        assertEquals(emptyList(), listener.heroButtonClicked)
        assertEquals(emptyList(), listener.switchCarousel)
    }

    private companion object {
        const val REC = "rec-row"
    }
}
