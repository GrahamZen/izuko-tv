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
import android.graphics.Rect
import android.os.SystemClock
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
 * 网格页 ([TvNativeGridPageView]) 的整屏背景对焦 (新番时间表): 点开 = 背景变清晰、卡片淡没, 到位才进详情页; 背景不是这张卡的就当场进;
 * 清晰图等不到就不对焦直接进 (卡片照样淡, 回来倒放); 长按 = 别的卡淡没、背景变清晰, 菜单关了倒放; 返回时恢复点开的样子再倒放;
 * 点开途中取消 / 按键吞掉.
 * 开着过渡跑 (模拟器开着系统动画), 看的是先后. 要在对焦途中 (200ms 内) 断言 / 取消 / 再按键的, 按键直接派发给页面、同一轮主线程消息里接着做
 * ([confirmThen]): 注入按键再回主线程的往返在忙的模拟器上会超过 200ms, 赌时序就是偶发失败.
 */
class TvNativeGridPageWallFocusTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = Listener()
    private lateinit var page: TvNativeGridPageView
    private lateinit var wall: TvNativeWallBackdropView

    private class Listener : TvNativeGridPageListener {
        val clicked = mutableListOf<Int>()
        val opened = mutableListOf<Int>()
        val longPressed = mutableListOf<Int>()
        var fade = 0f

        override fun onFocused(index: Int) = Unit
        override fun onClick(index: Int) {
            clicked += index
        }

        override fun onLongPress(index: Int, anchor: Rect) {
            longPressed += index
        }

        override fun onTopRowUp(): Boolean = true
        override fun onHeroActiveChanged(active: Boolean) = Unit
        override fun onToneChanged(tone: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onGridFocusChanged(hasFocus: Boolean) = Unit
        override fun onFocusParkedChanged(parked: Boolean) = Unit
        override fun onWallOpened(index: Int) {
            opened += index
        }

        override fun onWallFade(fade: Float) {
            this.fade = fade
        }
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val grid = TvNativeGridMetrics(
                columns = 6,
                startPx = 48,
                endPx = 48,
                topBleedPx = 0,
                bottomBleedPx = 0,
                endMarginPx = 60,
                heroLinePx = 0,
                fadeDistancePx = 128f,
            )
            val metrics = TvNativeGridPageMetrics(
                pageWidthPx = 1920,
                pageHeightPx = 1080,
                gridTopPx = 160,
                grid = grid,
                backdropWidthPx = 0,
                backdropHeightPx = 0,
                heroLeftPx = 0,
                heroTopPx = 0,
                heroWidthPx = 0,
                heroHeightPx = 0,
                titleWidthPx = 0,
                summaryWidthPx = 0,
            )
            page = TvNativeGridPageView(host.activity, host.sketch, scope, testWallStyle(), metrics, testHeroTextStyle())
            page.heroEnabled = false
            page.animatedScroll = false
            page.listener = listener
            wall = page.enableWallBackdrop()
            wall.maskColor = 0x40000000
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, List(12) { TvNativeCard(imageUrl = null, title = "卡 $it", subjectId = SUBJECT_BASE + it) })
        }
        host.waitUntil("卡片排出来") { (page.grid?.childCount ?: 0) > 1 }
        host.onMain { page.focusItem(0) }
        host.waitUntil("焦点到第 0 张") { page.grid?.focusedChild?.let { page.grid?.getChildAdapterPosition(it) } == 0 }
        assertTrue(ValueAnimator.areAnimatorsEnabled(), "要开着系统动画跑: 看的是对焦过渡的先后")
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    /** 第 [index] 张卡的淡化 (主线程上调). */
    private fun cardDim(index: Int): Float = (page.grid!!.findViewHolderForAdapterPosition(index)!!.itemView as TvNativeCardView).dim

    /** 清晰层的透明度 (主线程上调). */
    private fun sharpAlpha(): Float = wall.getChildAt(wall.childCount - 1).alpha

    /** 派发一下按键 (按下 + 抬起) 给页面 (主线程上调): 走焦点所在的卡, 同遥控器. */
    private fun dispatchPress(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        page.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        page.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** 在焦点所在的卡上按一下确定, 同一轮主线程消息里接着做 [then] (对焦还没走完). */
    private fun confirmThen(then: () -> Unit) = host.onMain {
        dispatchPress(KeyEvent.KEYCODE_DPAD_CENTER)
        then()
    }

    /** 背景换成第 [card] 张卡那部的图, 并等清晰图解好. */
    private fun wallOfCard(card: Int, name: String = "card$card") {
        host.onMain {
            page.setWallTarget(TvNativeWallBackdropTarget(host.testImage(name), SUBJECT_BASE + card, sharp = true))
            wall.prepareSharp()
        }
        host.waitUntil("清晰图解好") { wall.sharpReady }
    }

    @Test
    fun `confirm sharpens the backdrop and hides the cards before opening the card`() {
        wallOfCard(0)
        // 还在对焦, 没进
        confirmThen { assertEquals(emptyList(), listener.clicked) }
        host.waitUntil("对焦到位后进了第 0 张") { listener.clicked == listOf(0) }
        host.onMain {
            assertEquals(listOf(0), listener.opened)
            assertEquals(1f, sharpAlpha())
            assertEquals(0f, cardDim(0))
            assertEquals(0f, cardDim(1))
            // 顶栏跟着淡没
            assertEquals(1f, listener.fade)
        }
    }

    @Test
    fun `confirm opens at once when the backdrop belongs to another card`() {
        wallOfCard(1)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.onMain {
            assertEquals(listOf(0), listener.clicked)
            assertEquals(emptyList(), listener.opened)
            assertEquals(1f, cardDim(1))
            assertEquals(0f, sharpAlpha())
        }
    }

    @Test
    fun `confirm opens without sharpening when the sharp image never comes`() {
        // 地址指向不存在的文件: 清晰图解不出来
        host.onMain { page.setWallTarget(TvNativeWallBackdropTarget("file:///nonexistent/wall.png", SUBJECT_BASE, sharp = true)) }
        confirmThen { assertEquals(emptyList(), listener.clicked) }
        host.waitUntil("等不到清晰图也进了") { listener.clicked == listOf(0) }
        // 卡片照样淡没 (按下的反馈), 背景没对焦; 状态停在点开, 回来倒放
        host.waitUntil("卡片淡没") { cardDim(1) == 0f }
        host.onMain {
            assertEquals(listOf(0), listener.opened)
            assertEquals(0f, sharpAlpha())
            page.endWallOpen()
        }
        host.waitUntil("倒放回卡片墙") { cardDim(0) == 1f && cardDim(1) == 1f && listener.fade == 0f }
    }

    @Test
    fun `keys during the open are swallowed`() {
        wallOfCard(0)
        confirmThen {
            dispatchPress(KeyEvent.KEYCODE_DPAD_RIGHT)
            dispatchPress(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        host.waitUntil("对焦到位后进了第 0 张") { listener.clicked == listOf(0) }
        // 途中那一下确定没有另起一次点开 (没吞掉的话会当场进一次、到位再进一次)
        SystemClock.sleep(TV_WALL_BACKDROP_SHARPEN_MILLIS.toLong())
        assertEquals(listOf(0), host.onMain { listener.clicked.toList() })
        assertEquals(0, host.onMain { page.grid?.focusedChild?.let { page.grid?.getChildAdapterPosition(it) } })
    }

    @Test
    fun `cancelling during the open brings the wall back without opening`() {
        wallOfCard(0)
        confirmThen { page.cancelWallOpen() }
        SystemClock.sleep(TV_WALL_BACKDROP_SHARPEN_MILLIS * 2L)
        assertEquals(emptyList(), host.onMain { listener.clicked.toList() })
        host.waitUntil("倒放回卡片墙") { cardDim(1) == 1f && sharpAlpha() == 0f && listener.fade == 0f }
    }

    @Test
    fun `long press hides the other cards and sharpens until the menu is closed`() {
        wallOfCard(0)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(450)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0), host.onMain { listener.longPressed.toList() })
        host.waitUntil("别的卡淡没、背景清晰") { cardDim(1) == 0f && sharpAlpha() == 1f }
        host.onMain {
            assertEquals(1f, cardDim(0), "长按的那张留着")
            assertEquals(emptyList(), listener.clicked)
            // 长按时顶栏不淡
            assertEquals(0f, listener.fade)
            page.endWallPeek()
        }
        host.waitUntil("倒放回来") { cardDim(1) == 1f && sharpAlpha() == 0f }
    }

    @Test
    fun `a main thread stall right after the long press does not skip the backdrop transition`() {
        wallOfCard(0)
        // 长按: 按下, 按住过阈值, 自动重复那一下触发 (直接派发给页面: 时序由这里定)
        val down = SystemClock.uptimeMillis()
        host.onMain { page.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)) }
        SystemClock.sleep(450)
        host.onMain { page.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1)) }
        assertEquals(listOf(0), host.onMain { listener.longPressed.toList() })
        // 对焦起了步 (走过头几帧) 之后主线程卡 300ms: 同收藏菜单那个新窗口建窗口、头一回组合
        SystemClock.sleep(50)
        host.onMain { SystemClock.sleep(300) }
        // 卡住之后只往前推一两帧; 按墙钟算进度的话这时已过 350 / 400ms, 快满了
        val after = host.onMain { wall.sharpness }
        assertTrue(after < 0.5f, "卡顿后对焦跳到了 $after")
        host.onMain { page.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0)) }
        host.waitUntil("接着走满") { sharpAlpha() == 1f }
        host.onMain { page.endWallPeek() }
    }

    @Test
    fun `a restored open state shows the sharp image over hidden cards until it ends`() {
        host.onMain {
            page.restoreWallOpen()
            assertEquals(0f, cardDim(1))
            // 目标在视图建好之后才到: 清晰图当场解, 解好直接满着
            page.setWallTarget(TvNativeWallBackdropTarget(host.testImage("restored"), SUBJECT_BASE, sharp = true))
        }
        host.waitUntil("清晰图解好就直接满着") { wall.sharpReady && sharpAlpha() == 1f }
        host.onMain { page.endWallOpen() }
        host.waitUntil("倒放回卡片墙") { cardDim(0) == 1f && cardDim(1) == 1f && sharpAlpha() == 0f && listener.fade == 0f }
    }

    private companion object {
        const val SUBJECT_BASE = 500
    }
}
