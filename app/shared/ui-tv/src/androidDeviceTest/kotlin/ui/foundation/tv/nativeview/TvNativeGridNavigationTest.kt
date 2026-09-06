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
import android.view.Choreographer
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
 * 海报墙网格 ([TvNativeGridView], 追番 / 搜索页) 的导航: 上下同列、下面没有同列时落到最后一张、首行按上与行缘左右交给页面、
 * 远跳、hero 态、确定. 40 张 6 列: 末行 (第 6 行) 只有 36..39 四张.
 */
class TvNativeGridNavigationTest {
    private val host = TvNativeTestHost()
    private val listener = GridListener()
    private lateinit var grid: TvNativeGridView

    private class GridListener : RecordingCardListener(), TvNativeGridListener {
        var topRowUp = 0
        val rowEdges = mutableListOf<Pair<Int, Int>>()

        override fun onTopRowUp(): Boolean {
            topRowUp++
            return true
        }

        override fun onRowEdge(direction: Int, row: Int): Boolean {
            rowEdges += direction to row
            return false
        }
    }

    @BeforeTest
    fun setUp() {
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
            grid = TvNativeGridView(host.activity, testWallStyle(), host.sketch, metrics)
            grid.animatedScroll = false
            grid.listener = listener
            host.root.addView(grid, FrameLayout.LayoutParams(1920, 1080))
            grid.cards.submit(testCards(40)) { it.toLong() }
        }
        host.waitUntil("卡片排出来") { grid.childCount > 0 }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun focusedIndex(): Int = grid.focusedChild?.let { grid.getChildAdapterPosition(it) } ?: -1

    private fun focus(index: Int) {
        host.onMain { grid.focusItem(index) }
        host.waitUntil("第 $index 张拿到焦点") { focusedIndex() == index }
    }

    @Test
    fun `up and down stay in the same column`() {
        focus(0)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("下到第 6 张") { focusedIndex() == 6 }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("下到第 12 张") { focusedIndex() == 12 }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("上回第 6 张") { focusedIndex() == 6 }
    }

    @Test
    fun `top row up is handed to the page`() {
        focus(2)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, listener.topRowUp)
        assertEquals(2, host.onMain { focusedIndex() })
    }

    @Test
    fun `down without a card in the same column lands on the last card and the last row swallows down`() {
        focus(34)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("落到最后一张") { focusedIndex() == 39 }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(39, host.onMain { focusedIndex() })
    }

    @Test
    fun `row edges are handed to the page`() {
        focus(5)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(1 to 0), listener.rowEdges)
        // 页面不处理时行末按右吞掉
        assertEquals(5, host.onMain { focusedIndex() })

        focus(6)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(1 to 0, -1 to 1), listener.rowEdges)
    }

    @Test
    fun `left and right move within the row`() {
        focus(7)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("右移到第 8 张") { focusedIndex() == 8 }
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("左移到第 6 张") { focusedIndex() == 6 }
        assertTrue(listener.rowEdges.isEmpty())
    }

    @Test
    fun `holding down moves one row per repeat while the grid glides`() {
        host.onMain {
            grid.animatedScroll = true
            grid.cards.submit(testCards(120)) { it.toLong() }
        }
        focus(0)
        // 按住: 照系统连发约 50ms 一发 (比上限 40ms 慢, 每一发都换行), 网格一路平滑滚着、落后焦点两行多;
        // 上下各多排一屏, 每一发的目标卡都已经排好、焦点当场过去
        host.keyDown(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(6, host.onMain { focusedIndex() })
        for (i in 1..15) {
            SystemClock.sleep(50)
            host.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, repeatCount = i)
            assertEquals(6 * (1 + i), host.onMain { focusedIndex() }, "第 $i 发连发")
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_DOWN)
    }

    @Test
    fun `far jump lands on the first card`() {
        focus(30)
        host.onMain { grid.farJumpTo(0) }
        host.waitUntil("远跳落到第 0 张") { focusedIndex() == 0 }
    }

    @Test
    fun `the card a far jump leaves is not drawn focused when the jump lands`() {
        // 聚焦动画要有时长才看得出来 (测试样式默认 0, 状态动画当场到终点)
        host.onMain {
            host.root.removeView(grid)
            val metrics = TvNativeGridMetrics(
                columns = 6, startPx = 128, endPx = 48, topBleedPx = 0, bottomBleedPx = 0, endMarginPx = 60,
                heroLinePx = 400, fadeDistancePx = 128f,
            )
            grid = TvNativeGridView(host.activity, testWallStyle().copy(focusMillis = 300L), host.sketch, metrics)
            grid.animatedScroll = true
            grid.listener = listener
            host.root.addView(grid, FrameLayout.LayoutParams(1920, 1080))
            grid.cards.submit(testCards(40)) { it.toLong() }
        }
        host.waitUntil("卡片排出来") { grid.childCount > 0 }
        focus(12)
        host.waitUntil("滚到第 12 张的停位") { grid.scrollState == RecyclerView.SCROLL_STATE_IDLE }
        var leftProgress: Float? = null
        val suppressedOnTheWay = host.onMain {
            val left = grid.findViewHolderForAdapterPosition(12)!!.itemView as TvNativeCardView
            grid.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null && newFocus.parent === grid && grid.getChildAdapterPosition(newFocus) == 0) {
                    // 落地 (焦点给到目标、放开聚焦效果) 之后的第一帧: 出发那张按它此刻的进度画
                    Choreographer.getInstance().postFrameCallback { leftProgress = left.focusProgress }
                }
            }
            grid.farJumpTo(0)
            left.focusEffectSuppressed
        }
        // 走的是一路滚上去的远跳 (途中压住聚焦效果), 不是就地落点
        assertTrue(suppressedOnTheWay)
        host.waitUntil("远跳落到第 0 张") { focusedIndex() == 0 }
        host.waitUntil("落地后第一帧记下了") { leftProgress != null }
        // 出发那张直接是失焦的样子, 不先按放大态画一帧再缩回
        assertEquals(0f, leftProgress)
    }

    @Test
    fun `a focus request before the target is laid out holds its focused look until it lands`() {
        // 返回本页重建时: 网格刚建出来、焦点在别处, 页面把焦点送回上次那张
        lateinit var fresh: TvNativeGridView
        val held = host.onMain {
            host.root.removeView(grid)
            val metrics = TvNativeGridMetrics(
                columns = 6, startPx = 128, endPx = 48, topBleedPx = 0, bottomBleedPx = 0, endMarginPx = 60,
                heroLinePx = 400, fadeDistancePx = 128f,
            )
            fresh = TvNativeGridView(host.activity, testWallStyle(), host.sketch, metrics)
            fresh.animatedScroll = false
            host.root.addView(fresh, FrameLayout.LayoutParams(1920, 1080))
            fresh.cards.submit(testCards(40)) { it.toLong() }
            fresh.focusItem(20)
            fresh.cards.heldFocusIndex
        }
        // 排出来之前就按住了: 第一帧就是放大的
        assertEquals(20, held)
        host.waitUntil("落到第 20 张") { fresh.focusedChild?.let { fresh.getChildAdapterPosition(it) } == 20 }
        assertEquals(-1, host.onMain { fresh.cards.heldFocusIndex })
    }

    /**
     * 在主线程上当场派发一次按下 + 抬起 (走网格自己的按键处理, 同出发那张持焦时的派发路径). 远跳途中按键不能用 [TvNativeTestHost.press]:
     * 它等主线程空闲才返回, 模拟器一慢就等到远跳落地之后, 这一下成了落地后按的.
     */
    private fun pressOnGrid(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        grid.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        grid.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    @Test
    fun `confirm during a far jump opens the target once it lands`() {
        focus(30)
        val clickedOnTheWay = host.onMain {
            grid.animatedScroll = true
            grid.farJumpTo(0)
            // 远跳途中按确认: 出发那张还持着焦点, 这一下却作用在目标上
            pressOnGrid(KeyEvent.KEYCODE_DPAD_CENTER)
            listener.clicked.toList()
        }
        assertEquals(emptyList(), clickedOnTheWay)
        host.waitUntil("远跳落到第 0 张") { focusedIndex() == 0 }
        host.waitUntil("落地后点的是第 0 张") { listener.clicked == listOf(0) }
    }

    @Test
    fun `a direction key during a far jump lands at once and drops the queued confirm`() {
        focus(30)
        val landed = host.onMain {
            grid.animatedScroll = true
            grid.farJumpTo(0)
            pressOnGrid(KeyEvent.KEYCODE_DPAD_CENTER)
            // 当场落到目标, 这一下吞掉 (不往下走), 排队的确认作废
            pressOnGrid(KeyEvent.KEYCODE_DPAD_DOWN)
            focusedIndex()
        }
        assertEquals(0, landed)
        SystemClock.sleep(300)
        assertEquals(emptyList(), listener.clicked)
        assertEquals(0, host.onMain { focusedIndex() })
    }

    @Test
    fun `two quick direction keys during a far jump land and then move on from the target`() {
        focus(30)
        host.onMain {
            grid.animatedScroll = true
            grid.farJumpTo(0)
        }
        SystemClock.sleep(80)
        // 走系统派发 (同遥控器), 两下紧挨着: 第一下当场落到第 0 张 (吞掉), 第二下从第 0 张往右
        pressNowSystem(KeyEvent.KEYCODE_DPAD_RIGHT)
        pressNowSystem(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("焦点落到第 1 张") { focusedIndex() == 1 }
        SystemClock.sleep(400)
        assertEquals(1, host.onMain { focusedIndex() })
    }

    @Test
    fun `holding a direction key during a far jump lands and stays near the target`() {
        focus(30)
        host.onMain {
            grid.animatedScroll = true
            grid.farJumpTo(0)
        }
        SystemClock.sleep(80)
        val down = SystemClock.uptimeMillis()
        for (repeat in 0..3) {
            host.instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, repeat))
        }
        host.instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        SystemClock.sleep(500)
        // 第一下落地, 后面的连发从第 0 张往右走 (连发有节流): 只会在第一行里
        val index = host.onMain { focusedIndex() }
        assertTrue(index in 0..3, "落地后跑远了: 第 $index 张")
    }

    /** 走系统的按键派发 (同遥控器) 按一下, 不等主线程空闲. */
    private fun pressNowSystem(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        host.instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        host.instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    @Test
    fun `back pressed again during a far jump lands at once and drops the queued confirm`() {
        focus(30)
        host.onMain {
            grid.animatedScroll = true
            grid.farJumpTo(0)
        }
        SystemClock.sleep(80)
        val landed = host.onMain {
            // 途中按了确认 (排队), 嫌慢又按了一下返回: 页面的返回分层调 landFarJump, 同按方向键当场落到目标
            pressOnGrid(KeyEvent.KEYCODE_DPAD_CENTER)
            grid.landFarJump() to focusedIndex()
        }
        assertEquals(true to 0, landed)
        SystemClock.sleep(300)
        assertEquals(emptyList(), listener.clicked)
        assertEquals(0, host.onMain { focusedIndex() })
        // 落地之后没有远跳在滚: 页面照常往下一层走
        assertFalse(host.onMain { grid.landFarJump() })
    }

    @Test
    fun `entering the hero state keeps the focused card`() {
        focus(13)
        host.onMain { grid.setHeroActive(true, animated = false) }
        host.waitUntil("hero 态") { grid.heroActive }
        assertEquals(13, host.onMain { focusedIndex() })
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("hero 态里照样同列往下") { focusedIndex() == 19 }
    }

    @Test
    fun `confirm opens the focused card`() {
        focus(7)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(7), listener.clicked)
    }
}
