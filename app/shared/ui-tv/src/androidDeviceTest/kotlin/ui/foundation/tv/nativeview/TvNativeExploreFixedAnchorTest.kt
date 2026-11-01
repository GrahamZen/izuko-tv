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
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Rect
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 探索页钉死锚点版式 ([TvNativeRowWallView.fixedAnchor]): 焦点落到卡上就进 hero 态, 聚焦卡恒停在行首停靠线 (整行从固定聚焦框下滑过), 上下落到各行
 * 上次停的那张, 确定一下就打开, 行首按左出页面, 循环行按右接回第一张 (报给页面的是条目下标, 每一圈的第一张按左出页面), hero 线下面的行压暗.
 *
 * 列表: [hero 占位] [继续观看标题] 继续观看 20 张 [推荐标题] 推荐 12 张 / 循环行 (4 张重复 50 遍). 1080p.
 */
class TvNativeExploreFixedAnchorTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = Listener()
    private lateinit var view: TvNativeExploreView

    private class Listener : TvNativeExploreListener {
        val cardFocused = mutableListOf<Pair<String, Int>>()
        val heroButtonFocused = mutableListOf<Int>()
        val cardClicks = mutableListOf<Pair<String, Int>>()
        val heroActive = mutableListOf<Boolean>()
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

        override fun onHeroButtonClick(button: Int) = Unit
        override fun onSwitchCarousel(delta: Int): Boolean = false
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
            view.fixedAnchor = true
            view.anchorRingStyle = TvNativeAnchorRingStyle(strokePx = 4f, cornerPx = 12f, startColor = Color.WHITE, endColor = Color.WHITE)
            view.transitions = false
            view.animatedScroll = false
            view.listener = listener
            view.setButtons("立即观看", null, "新番时间表", null)
            view.setCarousel(3, 0, Color.WHITE)
            val empty = TvNativeHeroSource(backdrop = null, dimming = false, rawSubjectId = null, text = null)
            view.setSources(empty, empty)
            val loopCards = testCards(LOOP_PERIOD, "循环")
            view.setItems(
                listOf(
                    TvNativeExploreItem.Spacer("spacer"),
                    TvNativeExploreItem.Header("followed-header", "继续观看"),
                    TvNativeExploreItem.Row(FOLLOWED, testCards(20, "在看")),
                    TvNativeExploreItem.Header("rec-header", "推荐"),
                    TvNativeExploreItem.Row(REC, testCards(12, "推荐")),
                    TvNativeExploreItem.Row(LOOP, List(LOOP_PERIOD * 50) { loopCards[it % LOOP_PERIOD] }, loopPeriod = LOOP_PERIOD),
                ),
            )
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("焦点在立即观看") { listener.heroButtonFocused.lastOrNull() == 0 }
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
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

    /** 聚焦那张停在它那一行的停靠线上, 行停稳了. 条件在主线程上跑. */
    private fun waitDocked() = host.waitUntil("聚焦卡停在停靠线上") {
        val card = view.findFocus()
        val row = card?.parent as? TvNativeRowView
        row != null && row.scrollState == RecyclerView.SCROLL_STATE_IDLE && card.left == row.paddingLeft
    }

    /** [key] 那一行此刻的整行淡化 (越线淡没 × hero 线下压暗); 没排出来时 -1. 在主线程上调. */
    private fun rowAlpha(key: String): Float = findRow(view, key)?.rowAlpha ?: -1f

    private fun findRow(group: ViewGroup, key: String): TvNativeRowView? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TvNativeRowView) {
                if (child.tag == key) return child
            } else if (child is ViewGroup) {
                findRow(child, key)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun `focusing a card enters the hero state right away`() {
        enterFirstRow()
        host.waitUntil("进 hero 态") { view.heroActive }
        assertEquals(listOf(true), listener.heroActive)
    }

    @Test
    fun `right slides the row and the focused card stays at the anchor`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
        waitCard(FOLLOWED, 3)
        waitDocked()
        assertEquals(3, host.onMain { view.rowLeftIndex[FOLLOWED] })
    }

    @Test
    fun `the fixed ring sits on the focused card and hides when focus leaves the cards`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 2)
        waitCard(FOLLOWED, 2)
        val cardXY = IntArray(2)
        val ringXY = IntArray(2)
        var last = ""
        try {
            host.waitUntil("固定框与聚焦卡的卡格重合") {
                val card = view.findFocus() ?: return@waitUntil false
                val ring = view.anchorRingView
                card.getLocationInWindow(cardXY)
                ring.getLocationInWindow(ringXY)
                last = "card=${cardXY.toList()} ${card.width}x${card.height} ring=${ringXY.toList()} ${ring.width}x${ring.height} alpha=${ring.alpha}"
                ring.alpha == 1f && cardXY.contentEquals(ringXY) && ring.width == view.style.cardWidthPx && ring.height == view.style.cardHeightPx
            }
        } catch (e: AssertionError) {
            throw AssertionError("$last; ${e.message}", e)
        }
        // 首行按上回轮播按钮
        press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("焦点回轮播按钮, 框隐藏") { !view.cardAreaHasFocus && view.anchorRingView.alpha == 0f }
    }

    @Test
    fun `up and down land on the card each row last stopped at`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 4)
        waitCard(FOLLOWED, 4)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC, 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 2)
        waitCard(REC, 2)
        press(KeyEvent.KEYCODE_DPAD_UP)
        waitCard(FOLLOWED, 4)
        waitDocked()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC, 2)
        waitDocked()
    }

    @Test
    fun `confirm opens the focused card in one press`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(FOLLOWED, 1)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("点开") { listener.cardClicks == listOf(FOLLOWED to 1) }
        assertEquals(listOf(true), listener.heroActive)
    }

    @Test
    fun `left at the row start exits the page`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("出页面") { listener.exitLeft == 1 }
        assertEquals(FOLLOWED to 0, card())
    }

    @Test
    fun `holding left leaves the page only after the row has slid back to its start`() {
        // 滚动带动画 (行在绑定时取, 换一份数据重新绑定): 按住时整行落后焦点两张多
        host.onMain {
            view.animatedScroll = true
            view.setItems(listOf(TvNativeExploreItem.Spacer("spacer"), TvNativeExploreItem.Row(HOLD, testCards(40, "长行"))))
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        waitCard(HOLD, 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 8)
        waitCard(HOLD, 8)
        waitDocked()
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT)
        for (i in 1..7) {
            SystemClock.sleep(50)
            host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = i)
        }
        assertEquals(HOLD to 0, card())
        // 焦点到了第一张、行还在往回滑: 这一发不出页面
        SystemClock.sleep(50)
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 8)
        assertEquals(0, listener.exitLeft)
        // 滑到行首停进框里之后, 下一发出页面
        waitDocked()
        host.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 9)
        host.keyUp(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, listener.exitLeft)
    }

    @Test
    fun `a looping row wraps to its first card and reports item indexes`() {
        enterFirstRow()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC, 0)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(LOOP, 0)
        // 按右走完一圈接回第一张: 行里是第二圈的第一张, 报给页面的是条目下标 0
        press(KeyEvent.KEYCODE_DPAD_RIGHT, LOOP_PERIOD)
        waitCard(LOOP, LOOP_PERIOD)
        waitDocked()
        assertEquals(LOOP to 0, host.onMain { listener.cardFocused.last() })
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(LOOP, LOOP_PERIOD + 1)
        assertEquals(LOOP to 1, host.onMain { listener.cardFocused.last() })
        // 页面按条目下标送焦: 落在这一行停的那一圈里
        host.onMain { view.focusCard(LOOP, 3) }
        waitCard(LOOP, LOOP_PERIOD + 3)
        // 每一圈的第一张按左出页面, 不往回接上一圈
        host.onMain { view.focusCard(LOOP, 0) }
        waitCard(LOOP, LOOP_PERIOD)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("出页面") { listener.exitLeft == 1 }
        assertEquals(LOOP to LOOP_PERIOD, card())
    }

    @Test
    fun `rows below the hero line are dimmed and light up when focused`() {
        enterFirstRow()
        host.waitUntil("下面一行压暗") { rowAlpha(FOLLOWED) == 1f && rowAlpha(REC) in 0f..0.6f }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitCard(REC, 0)
        host.waitUntil("滑上 hero 线后提亮") { rowAlpha(REC) == 1f }
    }

    private companion object {
        const val FOLLOWED = "followed-row"
        const val REC = "rec-row-0"
        const val LOOP = "loop-row"
        const val HOLD = "hold-row"
        const val LOOP_PERIOD = 4
    }
}
