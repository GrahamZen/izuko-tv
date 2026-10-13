/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.ValueAnimator
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
 * 海报墙卡片的聚焦提示 ([TvNativeFocusCues]): 高光跟着聚焦程度原地淡入、失焦慢慢缩回的开关、停手后其余卡压暗而聚焦那张放大 (确定键不撤)、
 * hero 态一直压暗, 以及压暗开关 ([TvNativeFocusCues.dimming]). 网格自己当「页面」挂上 (真实页面里是探索页 / 网格页视图), 按键直接报给 [TvNativeFocusCues.onKey].
 */
class TvNativeFocusCuesTest {
    private val host = TvNativeTestHost()
    private lateinit var grid: TvNativeGridView
    private val defaultIdleMillis = TvNativeFocusCues.idleMillis

    @BeforeTest
    fun setUp() {
        TvNativeFocusCues.idleMillis = 500L
        host.launch()
        host.onMain {
            val metrics = TvNativeGridMetrics(
                columns = 6,
                startPx = 128,
                endPx = 48,
                topBleedPx = 0,
                bottomBleedPx = 0,
                endMarginPx = 60,
                heroLinePx = 400,
                fadeDistancePx = 128f,
            )
            // 聚焦动画要有时长: 失焦缩回的快慢才量得出
            val style = testWallStyle().copy(
                focusMillis = 150L,
                focusCues = TvNativeFocusCueStyle(
                    enabled = true, animated = true, idleDim = 0.45f,
                ),
            )
            grid = TvNativeGridView(host.activity, style, host.sketch, metrics)
            grid.animatedScroll = false
            host.root.addView(grid, FrameLayout.LayoutParams(1920, 1000))
            TvNativeFocusCues.attachPage(grid)
            grid.cards.submit(testCards(60)) { it.toLong() }
        }
        host.waitUntil("卡片排出来") { grid.childCount > 0 }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { TvNativeFocusCues.detachPage(grid) }
        host.close()
        TvNativeFocusCues.idleMillis = defaultIdleMillis
        host.onMain { TvNativeFocusCues.dimming = true }
    }

    private fun card(index: Int): TvNativeCardView? = grid.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeCardView

    private fun key(action: Int, code: Int, repeat: Int = 0) {
        val now = SystemClock.uptimeMillis()
        host.onMain { TvNativeFocusCues.onKey(grid, KeyEvent(now, now, action, code, repeat)) }
    }

    @Test
    fun `idle dims the other cards and lifts the focused one until a key is pressed`() {
        // 窗口没焦点 (别的窗口压着) 时不压暗: 先确认测试窗口拿到了
        host.waitUntil("测试窗口拿到窗口焦点") { grid.hasWindowFocus() }
        host.onMain { grid.focusItem(3) }
        host.waitUntil("第 3 张拿到焦点") { card(3)?.isFocused == true }
        host.waitUntil("停手后压暗到底", timeoutMillis = 4000) { card(4)?.cues?.idle == 1f }
        host.onMain {
            assertTrue(card(3)!!.cues.idleFocused, "聚焦那张不压暗")
            assertTrue(card(3)!!.cues.idleScale(1f) > 1f, "聚焦那张再放大一点")
            assertFalse(card(4)!!.cues.idleFocused)
            assertTrue(card(4)!!.cues.labelAlpha() < 1f, "其余卡的番名跟着暗")
        }
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("按键后压暗撤掉") { card(4)?.cues?.idle == 0f && card(3)?.cues?.idle == 0f }
        host.onMain { assertEquals(1f, card(3)!!.cues.idleScale(1f)) }
    }

    @Test
    fun `sheen follows the focus progress in place`() {
        host.onMain { grid.focusItem(3) }
        host.waitUntil("第 3 张聚焦到位") { card(3)?.focusProgress == 1f }
        host.onMain {
            val full = card(3)!!.cues.sheenStrength(1f)
            assertTrue(full > 0f)
            assertEquals(full, card(3)!!.cues.sheenStrength(card(3)!!.focusProgress), "聚焦到位时高光全亮")
            assertEquals(0f, card(4)!!.cues.sheenStrength(card(4)!!.focusProgress), "没聚焦的卡没有高光")
        }
    }

    @Test
    fun `slow focus out keeps the departing card shrinking longer`() {
        assertTrue(ValueAnimator.areAnimatorsEnabled(), "要开着系统动画跑")
        fun departingProgressAfter(millis: Long): Float {
            host.onMain { grid.focusItem(3) }
            host.waitUntil("第 3 张聚焦到位") { card(3)?.focusProgress == 1f }
            host.onMain { grid.focusItem(4) }
            SystemClock.sleep(millis)
            return host.onMain { card(3)!!.focusProgress }
        }
        val before = host.onMain { TvNativeFocusCues.slowFocusOut }
        try {
            host.onMain { TvNativeFocusCues.slowFocusOut = false }
            assertEquals(0f, departingProgressAfter(250), "关着: 0.15 秒就缩回")
            host.onMain { TvNativeFocusCues.slowFocusOut = true }
            assertTrue(departingProgressAfter(250) > 0f, "开着: 0.25 秒时还在缩")
        } finally {
            host.onMain { TvNativeFocusCues.slowFocusOut = before }
        }
    }

    @Test
    fun `confirm key keeps the idle dimming`() {
        host.onMain { grid.focusItem(3) }
        host.waitUntil("第 3 张拿到焦点") { card(3)?.isFocused == true }
        host.waitUntil("停手后压暗到底", timeoutMillis = 4000) { card(4)?.cues?.idle == 1f }
        // 按确定是「就要这一张」(进 hero 态 / 点开): 不亮回来
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(300)
        host.onMain { assertEquals(1f, card(4)!!.cues.idle) }
    }

    @Test
    fun `hero spotlight dims at once and follows the focus until it ends`() {
        // 停手计时拉长: 这里只看 hero 态自己的压暗
        TvNativeFocusCues.idleMillis = 15_000L
        host.onMain { grid.focusItem(3) }
        host.waitUntil("第 3 张拿到焦点") { card(3)?.isFocused == true }
        host.onMain {
            TvNativeFocusCues.setSpotlight(grid, on = true, animated = false)
            assertEquals(1f, card(4)!!.cues.idle, "进 hero 态当场压暗")
            assertTrue(card(3)!!.cues.idleFocused)
        }
        // hero 态里按方向键不撤, 亮的那张跟着焦点换
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        host.onMain { grid.focusItem(4) }
        host.waitUntil("亮的那张换到第 4 张") {
            card(4)?.cues?.idleFocused == true && card(3)?.cues?.idleFocused == false
        }
        // 两张交接走完 (同聚焦动画的时长): 新聚焦那张不暗, 离开那张暗下去
        host.waitUntil("交接走完") { card(4)!!.cues.labelAlpha() == 1f && card(3)!!.cues.labelAlpha() < 1f }
        host.onMain { assertEquals(1f, card(3)!!.cues.idle) }
        // 出 hero 态: 撤掉
        host.onMain { TvNativeFocusCues.setSpotlight(grid, on = false, animated = false) }
        host.onMain {
            assertEquals(0f, card(3)!!.cues.idle)
            assertEquals(0f, card(5)!!.cues.idle)
        }
    }

    @Test
    fun `no idle dimming while the focus is not on a card`() {
        host.onMain { grid.focusItem(3) }
        host.waitUntil("第 3 张拿到焦点") { card(3)?.isFocused == true }
        val outside = host.onMain {
            View(host.activity).apply { isFocusable = true }.also {
                host.root.addView(it, FrameLayout.LayoutParams(10, 10))
                it.requestFocus()
            }
        }
        host.waitUntil("焦点离开网格") { outside.isFocused }
        SystemClock.sleep(1200)
        host.onMain { assertEquals(0f, card(4)!!.cues.idle) }
    }

    @Test
    fun `dimming switch turns off idle and hero dimming`() {
        host.waitUntil("测试窗口拿到窗口焦点") { grid.hasWindowFocus() }
        host.onMain { grid.focusItem(3) }
        host.waitUntil("第 3 张拿到焦点") { card(3)?.isFocused == true }
        host.waitUntil("停手后压暗到底", timeoutMillis = 4000) { card(4)?.cues?.idle == 1f }
        // 关掉: 正暗着的撤掉
        host.onMain { TvNativeFocusCues.dimming = false }
        host.waitUntil("关掉后撤掉") { card(4)?.cues?.idle == 0f }
        // 关着: 停手不压暗, 进 hero 态也不压暗
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        SystemClock.sleep(1200)
        host.onMain {
            assertEquals(0f, card(4)!!.cues.idle, "关着时停手不压暗")
            TvNativeFocusCues.setSpotlight(grid, on = true, animated = false)
            assertEquals(0f, card(4)!!.cues.idle, "关着时 hero 态不压暗")
            TvNativeFocusCues.setSpotlight(grid, on = false, animated = false)
        }
    }
}
