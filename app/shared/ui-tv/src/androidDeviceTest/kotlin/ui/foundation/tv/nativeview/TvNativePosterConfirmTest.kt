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
import android.view.KeyEvent
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
 * 「海报上按确定」不是先看简介时 (页面给 heroEnabled = false): 探索页与网格页的卡片上按确定直接交给页面, 不进 hero 态; 停在 hero 态时关掉就当场回卡片墙;
 * 新番时间表选直接播放时 ([TvNativeGridPageView.confirmFocusesWall] = false) 按确定不先对焦, 当场交给页面.
 */
class TvNativePosterConfirmTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()

    @BeforeTest
    fun setUp() {
        host.launch()
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    // ------------------------------------------------------------------
    // 探索页
    // ------------------------------------------------------------------

    private class ExploreListener : TvNativeExploreListener {
        val cardClicks = mutableListOf<Pair<String, Int>>()
        var heroActive = false

        override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit
        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) = Unit
        override fun onHeroButtonClick(button: Int) = Unit
        override fun onSwitchCarousel(delta: Int): Boolean = false
        override fun onExitLeft() = Unit
        override fun onHeroActiveChanged(active: Boolean) {
            heroActive = active
        }

        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onToneChanged(tone: Float, splitY: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
    }

    private fun explore(listener: ExploreListener): TvNativeExploreView {
        val view = host.onMain {
            val style = testWallStyle()
            TvNativeExploreView(
                host.activity, host.sketch, scope, style, testExploreMetrics(style),
                testHeroTextStyle(), testHeroButtonStyle(), testTextStyle(32f, 44),
            ).also { view ->
                view.animatedScroll = false
                view.listener = listener
                view.setButtons("立即观看", null, "新番时间表", null)
                view.setCarousel(3, 0, Color.WHITE)
                view.setItems(
                    listOf(
                        TvNativeExploreItem.Spacer("spacer"),
                        TvNativeExploreItem.Header("followed-header", "继续观看"),
                        TvNativeExploreItem.Row(FOLLOWED, testCards(12, "在看")),
                    ),
                )
                host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusCard(FOLLOWED, 0) }
        host.waitUntil("焦点到第 0 张") { view.cardAreaHasFocus && view.focusedRowKey == FOLLOWED && view.focusedCardIndex == 0 }
        return view
    }

    @Test
    fun `explore without the hero state hands the confirm to the page at once`() {
        val listener = ExploreListener()
        val view = explore(listener)
        host.onMain { view.heroEnabled = false }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.onMain {
            assertEquals(listOf(FOLLOWED to 0), listener.cardClicks)
            assertFalse(view.heroActive)
            // 页面恢复 hero 态也不进
            view.setHeroActive(true)
            assertFalse(view.heroActive)
        }
    }

    @Test
    fun `explore turning the hero state off while in it goes back to the card wall`() {
        val listener = ExploreListener()
        val view = explore(listener)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.onMain {
            assertTrue(view.heroActive)
            assertEquals(emptyList(), listener.cardClicks)
            view.heroEnabled = false
            assertFalse(view.heroActive)
            assertFalse(listener.heroActive)
        }
    }

    // ------------------------------------------------------------------
    // 网格页
    // ------------------------------------------------------------------

    private class GridListener : TvNativeGridPageListener {
        val clicked = mutableListOf<Int>()
        val opened = mutableListOf<Int>()
        var heroActive = false

        override fun onFocused(index: Int) = Unit
        override fun onClick(index: Int) {
            clicked += index
        }

        override fun onLongPress(index: Int, anchor: Rect) = Unit
        override fun onTopRowUp(): Boolean = true
        override fun onHeroActiveChanged(active: Boolean) {
            heroActive = active
        }

        override fun onToneChanged(tone: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onGridFocusChanged(hasFocus: Boolean) = Unit
        override fun onFocusParkedChanged(parked: Boolean) = Unit
        override fun onWallOpened(index: Int) {
            opened += index
        }
    }

    private fun grid(listener: GridListener, setup: (TvNativeGridPageView) -> Unit = {}): TvNativeGridPageView {
        val page = host.onMain {
            val grid = TvNativeGridMetrics(
                columns = 6,
                startPx = 48,
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
                gridTopPx = 160,
                grid = grid,
                backdropWidthPx = 1280,
                backdropHeightPx = 720,
                heroLeftPx = 48,
                heroTopPx = 60,
                heroWidthPx = 1200,
                heroHeightPx = 320,
                titleWidthPx = 800,
                summaryWidthPx = 800,
            )
            TvNativeGridPageView(host.activity, host.sketch, scope, testWallStyle(), metrics, testHeroTextStyle()).also { page ->
                page.animatedScroll = false
                page.listener = listener
                setup(page)
                host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                page.showGrid(0, direction = 0, animated = false)
                page.setCards(0, List(12) { TvNativeCard(imageUrl = null, title = "卡 $it", subjectId = SUBJECT_BASE + it) })
            }
        }
        host.waitUntil("卡片排出来") { (page.grid?.childCount ?: 0) > 1 }
        host.onMain { page.focusItem(0) }
        host.waitUntil("焦点到第 0 张") { page.grid?.focusedChild?.let { page.grid?.getChildAdapterPosition(it) } == 0 }
        return page
    }

    @Test
    fun `grid without the hero state hands the confirm to the page at once`() {
        val listener = GridListener()
        val page = grid(listener) { it.heroEnabled = false }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.onMain {
            assertEquals(listOf(0), listener.clicked)
            assertFalse(page.heroActive)
            page.setHeroActive(true, animated = false)
            assertFalse(page.heroActive)
        }
    }

    @Test
    fun `grid turning the hero state off while in it goes back to the card wall`() {
        val listener = GridListener()
        val page = grid(listener)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.onMain {
            assertTrue(page.heroActive)
            assertEquals(emptyList(), listener.clicked)
            page.heroEnabled = false
            assertFalse(page.heroActive)
            assertFalse(listener.heroActive)
        }
    }

    @Test
    fun `schedule set to play right away skips the backdrop focus`() {
        val listener = GridListener()
        val page = grid(listener) { page ->
            // 新番时间表: 没有 hero 态, 底下铺整屏背景
            page.heroEnabled = false
            page.enableWallBackdrop().maskColor = 0x40000000
            page.confirmFocusesWall = false
        }
        host.onMain { page.setWallTarget(TvNativeWallBackdropTarget(host.testImage("confirm-play"), SUBJECT_BASE, sharp = true)) }
        host.waitUntil("清晰图解好") { page.wallBackdrop!!.sharpReady }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.onMain {
            // 当场交给页面, 没有走对焦
            assertEquals(listOf(0), listener.clicked)
            assertEquals(emptyList(), listener.opened)
        }
    }

    private companion object {
        const val FOLLOWED = "followed"
        const val SUBJECT_BASE = 800
    }
}
