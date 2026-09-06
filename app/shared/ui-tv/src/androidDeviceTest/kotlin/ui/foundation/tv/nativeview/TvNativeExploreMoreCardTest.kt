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
import kotlin.test.assertTrue

/**
 * 探索页种子行行尾的「更多」卡 ([TvNativeCard.more]): 按确定只回调、不进 hero 态; 页面接上新卡 (同一行换内容) 之后行不挪、焦点留在原来的
 * 位置上 (那里变成新接的第一张), 聚焦的卡还在屏上.
 *
 * 列表: [hero 占位] [标题] 种子行 12 张 + 「更多」 / 另一行 12 张. 1080p, 一屏完整放得下 6 张.
 */
class TvNativeExploreMoreCardTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = MoreListener()
    private lateinit var view: TvNativeExploreView

    private class MoreListener : TvNativeExploreListener {
        val moreClicks = mutableListOf<String>()
        val cardClicks = mutableListOf<Pair<String, Int>>()
        val heroActive = mutableListOf<Boolean>()
        var heroButton = -1

        override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit
        override fun onCardClick(rowKey: String, index: Int) {
            cardClicks += rowKey to index
        }

        override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
        override fun onMoreClick(rowKey: String) {
            moreClicks += rowKey
        }

        override fun onBindCard(rowKey: String, index: Int) = Unit
        override fun onHeroButtonFocused(button: Int) {
            heroButton = button
        }

        override fun onHeroButtonClick(button: Int) = Unit
        override fun onSwitchCarousel(delta: Int): Boolean = false
        override fun onExitLeft() = Unit
        override fun onHeroActiveChanged(active: Boolean) {
            heroActive += active
        }

        override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
        override fun onToneChanged(tone: Float, splitY: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
    }

    /** 种子行: [count] 张条目卡, [more] 非空时行尾再一张「更多」. */
    private fun seedCards(count: Int, more: TvNativeMore? = TvNativeMore.Idle): List<TvNativeCard?> =
        testCards(count, "相似") + listOfNotNull(more?.let { TvNativeCard(imageUrl = null, title = "更多相似的", more = it) })

    private fun items(seed: List<TvNativeCard?>) = listOf(
        TvNativeExploreItem.Spacer("spacer"),
        TvNativeExploreItem.Header("seed-header", "因为你喜欢"),
        TvNativeExploreItem.Row(SEED, seed),
        TvNativeExploreItem.Row(OTHER, testCards(12, "别的")),
    )

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
            view.setItems(items(seedCards(12)))
            host.root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        host.waitUntil("页面排好") { view.isLaidOut }
        host.onMain { view.focusHeroButton(0) }
        host.waitUntil("焦点在立即观看") { listener.heroButton == 0 }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun press(keyCode: Int, times: Int = 1) = repeat(times) { host.press(keyCode) }

    private fun waitCard(rowKey: String, index: Int) =
        host.waitUntil("焦点到 $rowKey 第 $index 张") { view.cardAreaHasFocus && view.focusedRowKey == rowKey && view.focusedCardIndex == index }

    /** 从立即观看下到种子行, 一路按右走到行尾的「更多」卡 (第 12 张). */
    private fun goToMoreCard() {
        press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
        waitCard(SEED, 0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 12)
        waitCard(SEED, 12)
    }

    /** 持焦的那张卡是不是完整地在屏上 (没被行滚出去、没被裁掉一截). */
    private fun focusedCardFullyOnScreen(): Boolean = host.onMain {
        val focused = view.findFocus() as? TvNativeCardView ?: return@onMain false
        val visible = Rect()
        focused.getGlobalVisibleRect(visible) && visible.width() == focused.width && visible.height() == focused.height
    }

    @Test
    fun `confirm on the more card reports a more click and stays on the card wall`() {
        goToMoreCard()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("报了更多") { listener.moreClicks == listOf(SEED) }
        assertEquals(emptyList(), listener.heroActive)
        assertEquals(emptyList(), listener.cardClicks)
    }

    @Test
    fun `appended cards land under the focus and the row stays where it was`() {
        goToMoreCard()
        val left = host.onMain { view.rowLeftIndex[SEED] }
        assertTrue(focusedCardFullyOnScreen(), "接之前聚焦的卡在屏上")

        host.onMain { view.setItems(items(seedCards(24))) }
        host.waitUntil("行重排完") { !view.isLayoutRequested }

        waitCard(SEED, 12)
        assertEquals(left, host.onMain { view.rowLeftIndex[SEED] })
        assertTrue(focusedCardFullyOnScreen(), "接上新卡之后聚焦的卡还在屏上")
        // 往右还能走到新接的卡上, 一直到新的行尾
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitCard(SEED, 13)
    }

    @Test
    fun `the more card turning into loading keeps the row and the focus in place`() {
        goToMoreCard()
        host.onMain { view.setItems(items(seedCards(12, TvNativeMore.Loading))) }
        host.waitUntil("行重排完") { !view.isLayoutRequested }

        waitCard(SEED, 12)
        assertTrue(focusedCardFullyOnScreen(), "变成加载中之后聚焦的卡还在屏上")
    }

    @Test
    fun `focus can be sent to the last card after the more card is gone`() {
        goToMoreCard()
        host.onMain {
            view.setItems(items(seedCards(12, more = null)))
            view.focusCard(SEED, 11)
        }
        waitCard(SEED, 11)
        assertTrue(focusedCardFullyOnScreen(), "收起之后焦点落在最后一张, 在屏上")
    }

    private companion object {
        const val SEED = "seed"
        const val OTHER = "other"
    }
}
