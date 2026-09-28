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
 * 探索页海报墙 ([TvNativeExploreView]) 的导航: 轮播按钮 ↔ 首行、行内按需挪、上下落到屏上同一列 (行短了落到最后一张)、行首按左出页面、
 * 行尾按右吞掉、确定键两步 (先进 hero 态, 再进详情页)、轮播按钮左右翻页、远跳、从页面外进来回到上次那张.
 *
 * 列表: [hero 占位] [继续观看标题] 继续观看 20 张 [推荐标题] 推荐 12 张 / 3 张 / 12 张. 1080p, 一屏完整放得下 6 张.
 */
class TvNativeExploreNavigationTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = ExploreListener()
    private lateinit var view: TvNativeExploreView
    private lateinit var outside: View

    private class ExploreListener : TvNativeExploreListener {
        val cardFocused = mutableListOf<Pair<String, Int>>()
        val heroButtonFocused = mutableListOf<Int>()
        val heroButtonClicked = mutableListOf<Int>()
        val cardClicks = mutableListOf<Pair<String, Int>>()
        val heroActive = mutableListOf<Boolean>()
        val switchCarousel = mutableListOf<Int>()
        var exitLeft = 0

        override fun onCardFocused(rowKey: String, index: Int, column: Int) {
            cardFocused += rowKey to index
        }
        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) {
            heroButtonFocused += button
        }

        override fun onHeroButtonClick(button: Int) {
            heroButtonClicked += button
        }

        // 只有一页往右翻得动: 第一项按左翻不动, 交给侧边栏
        override fun onSwitchCarousel(delta: Int): Boolean {
            switchCarousel += delta
            return delta > 0
        }

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
                    TvNativeExploreItem.Header("followed-header", "继续观看"),
                    TvNativeExploreItem.Row(FOLLOWED, testCards(20, "在看")),
                    TvNativeExploreItem.Header("rec-header", "推荐"),
                    TvNativeExploreItem.Row(REC0, testCards(12, "推荐")),
                    TvNativeExploreItem.Row(REC1, testCards(3, "短")),
                    TvNativeExploreItem.Row(REC2, testCards(12, "再推荐")),
                ),
            )
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // 页面外的一个落点 (代替侧边栏), 用来测从页面外进来
            outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("焦点在立即观看") { listener.heroButtonFocused.lastOrNull() == 0 }
    }

    @AfterTest
    fun tearDown() {
        host.close()
        scope.cancel()
    }

    private fun card(): Pair<String?, Int> = host.onMain { (if (view.cardAreaHasFocus) view.focusedRowKey else null) to view.focusedCardIndex }

    private fun waitCard(rowKey: String, index: Int) =
        host.waitUntil("焦点到 $rowKey 第 $index 张") { view.cardAreaHasFocus && view.focusedRowKey == rowKey && view.focusedCardIndex == index }

    private fun press(keyCode: Int, times: Int = 1) = repeat(times) { host.press(keyCode) }

    /** 从立即观看下到首行第一张. */
    private fun enterFirstRow() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        waitCard(FOLLOWED, 0)
    }

    @Test
    fun `hero buttons go down into the first row and the first row goes up to the schedule button`() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("焦点在新番时间表") { listener.heroButtonFocused.lastOrNull() == 1 }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(FOLLOWED, 0)
        press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("回到新番时间表") { !view.cardAreaHasFocus && listener.heroButtonFocused.lastOrNull() == 1 }
    }

    @Test
    fun `cards move on demand within a row`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 5)
        waitCard(FOLLOWED, 5)
        assertEquals(0, host.onMain { view.rowLeftIndex[FOLLOWED] ?: 0 })
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(FOLLOWED, 6)
        assertEquals(1, host.onMain { view.rowLeftIndex[FOLLOWED] })
    }

    @Test
    fun `up and down land on the same screen column and a short row lands on its last card`() {
        enterFirstRow()
        // 行首挪到第 1 张, 聚焦卡在屏上第 5 列
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 6)
        waitCard(FOLLOWED, 6)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC0, 5)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC1, 2)
        // 短行里停在第 2 列, 往上按第 2 列落
        press(KeyEvent.KEYCODE_DPAD_UP)
        waitCard(REC0, 2)
    }

    @Test
    fun `row start left leaves the page and row end right is swallowed`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, listener.exitLeft)
        assertEquals(FOLLOWED to 0, card())

        host.onMain { view.focusCard(REC1, 2) }
        waitCard(REC1, 2)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(REC1 to 2, card())
    }

    @Test
    fun `confirm enters the hero state first and opens details on the second press`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC0, 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(REC0, 1)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(true), listener.heroActive)
        assertTrue(host.onMain { view.heroActive })
        assertTrue(listener.cardClicks.isEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(REC0 to 1), listener.cardClicks)
    }

    @Test
    fun `hero buttons page the carousel and the first item left leaves the page`() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(1), listener.switchCarousel)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(1, -1), listener.switchCarousel)
        assertEquals(1, listener.exitLeft)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0), listener.heroButtonClicked)
    }

    @Test
    fun `far jump lands on the target row`() {
        host.onMain { view.focusCard(REC2, 3) }
        waitCard(REC2, 3)
        host.onMain { view.focusCard(FOLLOWED, 0, far = true) }
        waitCard(FOLLOWED, 0)
    }

    @Test
    fun `confirm during a far jump acts on the target once it lands`() {
        host.onMain { view.focusCard(REC2, 3) }
        waitCard(REC2, 3)
        host.onMain {
            view.animatedScroll = true
            view.focusCard(FOLLOWED, 0, far = true)
        }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitCard(FOLLOWED, 0)
        // 卡片墙上按确认 = 这张进 hero 态: 点在目标上, 焦点仍在目标
        host.waitUntil("落地后点了目标") { listener.heroActive == listOf(true) }
        assertEquals(FOLLOWED to 0, card())
    }

    @Test
    fun `a direction key during a far jump drops the queued confirm`() {
        host.onMain { view.focusCard(REC2, 3) }
        waitCard(REC2, 3)
        host.onMain {
            view.animatedScroll = true
            view.focusCard(FOLLOWED, 0, far = true)
        }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(FOLLOWED, 0)
        SystemClock.sleep(300)
        assertEquals(emptyList(), listener.heroActive)
    }

    @Test
    fun `focus coming back from outside the page lands on the last card`() {
        host.onMain { view.focusCard(REC0, 4) }
        waitCard(REC0, 4)
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点到页面外") { outside.isFocused && !view.cardAreaHasFocus }
        host.onMain { view.requestFocus() }
        waitCard(REC0, 4)
        assertFalse(host.onMain { outside.isFocused })
    }

    @Test
    fun `a request for the card that already has focus is reported as arrived`() {
        host.onMain { view.focusCard(REC0, 3) }
        waitCard(REC0, 3)
        val before = listener.cardFocused.size
        // 页面的落点请求靠这一下回报清掉 (进页恢复 / 继续观看整行回来时补发的请求常常就指着焦点所在的这张)
        host.onMain { view.focusCard(REC0, 3) }
        host.waitUntil("再报一次到位") { listener.cardFocused.size == before + 1 }
        assertEquals(REC0 to 3, listener.cardFocused.last())

        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("焦点在立即观看") { listener.heroButtonFocused.lastOrNull() == 0 }
        val buttonsBefore = listener.heroButtonFocused.size
        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("按钮也再报一次") { listener.heroButtonFocused.size == buttonsBefore + 1 }
    }

    private companion object {
        const val FOLLOWED = "followed-row"
        const val REC0 = "rec-row-0"
        const val REC1 = "rec-row-1"
        const val REC2 = "rec-row-2"
    }
}

/** 1080p 探索页的几何 (TvExplorationPage 按 960 × 540 dp、320dpi 算出来的量级). */
internal fun testExploreMetrics(style: TvNativeWallStyle): TvNativeExploreMetrics = TvNativeExploreMetrics(
    pageWidthPx = 1824,
    pageHeightPx = 1080,
    bleedLeftPx = 96,
    columns = 6,
    listTopPx = 48,
    listTopBleedPx = 240,
    listBottomBleedPx = 256,
    spacerPx = 480,
    headerPx = 72,
    rowPx = style.cardBlockHeightPx + style.rowSpacingPx,
    rowGapPx = style.rowSpacingPx,
    viewportPx = 1032,
    endMarginPx = 80,
    heroHeaderTopPx = 488,
    rowStartPx = 128,
    endPadPx = 48,
    fadeDistancePx = 128f,
    carouselBottomPx = 820f,
    overhangPx = 100,
    backdropWidthPx = 1458,
    backdropHeightPx = 820,
    cardBackdropScale = 0.8f,
    heroStartPx = 32,
    heroTopPx = 56,
    heroEndPadPx = 48,
    heroBlockPx = 480,
    heroBlockExpandedPx = 528,
    titleWidthPx = 1000,
    carouselSummaryWidthPx = 900,
    cardSummaryWidthPx = 900,
    buttonsTopGapPx = 12,
    buttonGapPx = 8,
    dotsCenterYPx = 900,
    dotPx = 12f,
    dotSelectedWidthPx = 40f,
    dotGapPx = 16f,
    dotInactiveAlpha = 0.4f,
)

internal fun testHeroTextStyle(): TvNativeHeroTextStyle = TvNativeHeroTextStyle(
    title = testTextStyle(56f, 72),
    titleMaxLines = 1,
    rating = testTextStyle(32f, 44),
    meta = testTextStyle(28f, 40),
    status = testTextStyle(28f, 40),
    summary = testTextStyle(28f, 40),
    star = null,
    starSizePx = 36,
    starGapPx = 8,
    metaGapPx = 32,
    lineSpacingPx = 16,
    statusHeightPx = 0,
    slidePx = 28,
    stagger = false,
    animated = false,
    marqueeRepeat = 0,
)

internal fun testHeroButtonStyle(): TvNativeHeroButtonStyle = TvNativeHeroButtonStyle(
    text = testTextStyle(26f, 36),
    iconSizePx = 36,
    iconGapPx = 14,
    paddingHorizontalPx = 25,
    paddingVerticalPx = 14,
    cornerPx = 16f,
    outlineWidthPx = 1f,
    outlineColor = Color.GRAY,
    filledColor = Color.DKGRAY,
    unfilledColor = Color.BLACK,
    focusedColor = Color.WHITE,
    contentColor = Color.WHITE,
    focusedContentColor = Color.BLACK,
)
