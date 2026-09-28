/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 海报墙横滑行 ([TvNativeRowView], 详情页关联条目那样单独成行) 的导航: 按需挪、行首 / 行尾、跨导航恢复行首、确定与长按.
 * 1080p: 行首停靠线 128, 行尾留白 48, 一屏完整放得下 6 张.
 */
class TvNativeRowNavigationTest {
    private val host = TvNativeTestHost()
    private val listener = RecordingCardListener()
    private lateinit var row: TvNativeRowView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val style = testWallStyle()
            row = TvNativeRowView(host.activity, style, host.sketch, pool = null, startPx = 128, endPx = 48, fadeDistancePx = 128f)
            row.animatedScroll = false
            row.cards.listener = listener
            host.root.addView(row, FrameLayout.LayoutParams(1920, style.cardBlockHeightPx + style.rowSpacingPx))
            row.bind(testCards(12), { it.toLong() }, leftIndex = 0, columns = 6)
        }
        host.waitUntil("卡片排出来") { row.childCount > 0 }
        host.onMain { row.focusCardIfLaidOut(0) }
        host.waitUntil("第 0 张拿到焦点") { focusedIndex() == 0 }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun focusedIndex(): Int = row.focusedChild?.let { row.getChildAdapterPosition(it) } ?: -1

    private fun pressRight(times: Int) = repeat(times) { host.press(KeyEvent.KEYCODE_DPAD_RIGHT) }

    private fun dispatchDown(keyCode: Int, repeatCount: Int): Boolean =
        host.onMain { row.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, repeatCount)) }

    @Test
    fun `focus moves across fully visible cards without scrolling`() {
        pressRight(5)
        host.waitUntil("焦点到第 5 张") { focusedIndex() == 5 }
        assertEquals(0, host.onMain { row.leftIndex() })
    }

    @Test
    fun `reaching the partially visible card shifts the row by one`() {
        pressRight(6)
        host.waitUntil("焦点到第 6 张") { focusedIndex() == 6 }
        host.waitUntil("行首挪到第 1 张") { row.leftIndex() == 1 }
        // 往回在屏上几张之间走不挪, 走出左边才挪
        repeat(5) { host.press(KeyEvent.KEYCODE_DPAD_LEFT) }
        host.waitUntil("焦点回到第 1 张") { focusedIndex() == 1 }
        assertEquals(1, host.onMain { row.leftIndex() })
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("行首挪回第 0 张") { focusedIndex() == 0 && row.leftIndex() == 0 }
    }

    @Test
    fun `row start left is released on a fresh press and swallowed while held`() {
        assertFalse(dispatchDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 0))
        assertTrue(dispatchDown(KeyEvent.KEYCODE_DPAD_LEFT, repeatCount = 1))
        assertEquals(0, host.onMain { focusedIndex() })
    }

    @Test
    fun `row end right is swallowed`() {
        pressRight(11)
        host.waitUntil("焦点到最后一张") { focusedIndex() == 11 }
        assertTrue(dispatchDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 0))
        assertEquals(11, host.onMain { focusedIndex() })
    }

    @Test
    fun `the first bind of a row restores the saved left index`() {
        // 行第一次绑定就带着记下的行首 (回收重绑 / 跨导航重建, 这时行还没有焦点)
        val restored = host.onMain {
            val style = testWallStyle()
            TvNativeRowView(host.activity, style, host.sketch, pool = null, startPx = 128, endPx = 48, fadeDistancePx = 128f).also {
                it.animatedScroll = false
                host.root.addView(it, FrameLayout.LayoutParams(1920, style.cardBlockHeightPx + style.rowSpacingPx))
                it.bind(testCards(12, "新"), { i -> i.toLong() }, leftIndex = 4, columns = 6, focusIndex = 7)
            }
        }
        host.waitUntil("行首恢复到第 4 张") { restored.childCount > 0 && restored.leftIndex() == 4 }
        assertEquals(7, host.onMain { restored.selectedPosition })
    }

    @Test
    fun `holding right moves focus to the next card on every repeat while the row glides`() {
        host.onMain {
            row.animatedScroll = true
            row.bind(testCards(30), { it.toLong() }, leftIndex = 0, columns = 6)
        }
        host.waitUntil("换数据后第 0 张拿回焦点") { focusedIndex() == 0 || row.focusCardIfLaidOut(0) }
        pressRight(5)
        host.waitUntil("焦点到第 5 张") { focusedIndex() == 5 }
        // 按住: 新按下挪到露一截的第 6 张, 之后每 150ms 一发连发 (限速 8 次 / 秒下约是这个节奏), 行一路平滑滚着.
        // 每一发的目标卡都得已经排好、焦点当场过去; 没排出来就会走 leanback 的平滑选中, 焦点要等它滚到才给
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(6, host.onMain { focusedIndex() })
        for (i in 1..8) {
            SystemClock.sleep(150)
            host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = i)
            assertEquals(6 + i, host.onMain { focusedIndex() }, "第 $i 发连发")
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("停稳时第 14 张贴在行尾") { row.scrollState == RecyclerView.SCROLL_STATE_IDLE && row.leftIndex() == 9 }
    }

    @Test
    fun `confirm clicks and holding it opens the menu instead`() {
        pressRight(2)
        host.waitUntil("焦点到第 2 张") { focusedIndex() == 2 }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(2), listener.clicked)

        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(450)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(2), listener.longPressed)
        // 长按那一下抬起不再算点击
        assertEquals(listOf(2), listener.clicked)
    }
}
