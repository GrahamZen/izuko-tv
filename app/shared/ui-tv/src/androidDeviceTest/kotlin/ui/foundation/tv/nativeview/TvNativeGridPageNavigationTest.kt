/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import me.him188.ani.app.ui.foundation.focus.TV_TRANSIT_PARK_KEY_GRACE_MILLIS
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 网格页 ([TvNativeGridPageView], 追番页) 行末按右跨标签: 新标签的卡还没到时焦点停放在网格页上 —— 不丢 (丢了全局兜底会把它塞给首个
 * 标签), 按住的连发不再连跨标签; 卡到了落到新那份网格上. 页面的换标签由测试里的监听器照追番页的做法模拟 (行缘按右 → 换下一份网格,
 * 数据晚到, 到了再送焦).
 */
class TvNativeGridPageNavigationTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = PageListener()
    private lateinit var page: TvNativeGridPageView
    private lateinit var outside: View

    private inner class PageListener : TvNativeGridPageListener {
        val rowEdges = mutableListOf<Pair<Int, Int>>()
        val parked = mutableListOf<Boolean>()
        var topRowUp = 0
        var tab = 0

        override fun onFocused(index: Int) = Unit
        override fun onClick(index: Int) = Unit
        override fun onTopRowUp(): Boolean {
            topRowUp++
            // 追番页: 回选中的标签 (这里用页面外的一个视图代替)
            outside.requestFocus()
            return true
        }

        override fun onRowEdge(direction: Int, row: Int): Boolean {
            rowEdges += direction to row
            if (direction > 0) {
                tab++
                // 换到下一个标签: 新那份网格先空着 (数据要等)
                page.showGrid(tab, direction = 1, animated = false)
            }
            return true
        }

        override fun onHeroActiveChanged(active: Boolean) = Unit
        override fun onToneChanged(tone: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onGridFocusChanged(hasFocus: Boolean) = Unit
        override fun onFocusParkedChanged(parked: Boolean) {
            this.parked += parked
        }
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val style = testWallStyle()
            val grid = TvNativeGridMetrics(
                columns = 6,
                startPx = 128,
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
                gridTopPx = 120,
                grid = grid,
                backdropWidthPx = 1344,
                backdropHeightPx = 756,
                heroLeftPx = 128,
                heroTopPx = 120,
                heroWidthPx = 900,
                heroHeightPx = 300,
                titleWidthPx = 900,
                summaryWidthPx = 800,
            )
            page = TvNativeGridPageView(host.activity, host.sketch, scope, style, metrics, testHeroTextStyle())
            page.transitions = false
            page.animatedScroll = false
            page.listener = listener
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // 页面外的一个落点 (代替标签行)
            outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, testCards(12))
        }
        host.waitUntil("卡片排出来") { (page.grid?.childCount ?: 0) > 0 }
        // 首行最后一张 (6 列): 再按右就是行末跨标签
        host.onMain { page.focusItem(5) }
        host.waitUntil("焦点到第一份网格的第 5 张") { focusedCard() == (0 to 5) }
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    /** 焦点所在的 (网格 key, 下标); 不在卡上时 null. */
    private fun focusedCard(): Pair<Any?, Int>? {
        val grid = page.grid ?: return null
        val child = grid.focusedChild ?: return null
        return page.currentKey to grid.getChildAdapterPosition(child)
    }

    private fun windowFocus(): View? = host.onMain { host.activity.window.decorView.findFocus() }

    /** 页面给的 hero 内容: 条目 [subject] 的文字, 没有背景图. */
    private fun heroSource(subject: Int) = TvNativeHeroSource(
        backdrop = null,
        dimming = false,
        rawSubjectId = subject,
        text = TvNativeHeroText(subjectId = subject, title = "条目 $subject", infoReady = false),
    )

    /** 12 张带条目 id (100 + 下标) 的卡. */
    private fun subjectCards() = List(12) { TvNativeCard(imageUrl = null, title = "卡 $it", subjectId = 100 + it) }

    /** hero 文字此刻画出来的不透明度, 连同装它的那几层 (到网格页为止). */
    private fun heroTextAlpha(): Float {
        var alpha = 1f
        var v: View? = page.heroText
        while (v != null && v !== page) {
            alpha *= v.alpha
            v = v.parent as? View
        }
        return alpha
    }

    @Test
    fun `crossing a tab parks the focus instead of losing it and held repeats do not cross again`() {
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(1 to 0), listener.rowEdges)
        // 新标签的卡还没到: 焦点停在网格页上, 没丢
        assertTrue(host.onMain { page.isFocused }, "焦点停放在网格页上")
        assertEquals(listOf(true), listener.parked)
        // 按住的连发: 吞掉, 不再跨标签
        for (i in 1..6) {
            SystemClock.sleep(60)
            host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = i)
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(1 to 0), listener.rowEdges)
        assertTrue(host.onMain { page.isFocused }, "连发期间焦点仍停放在网格页上")
        // 新标签的卡到了, 页面送焦 (同 NativeSendFocusEffect): 落到新那份网格上, 解除停放
        host.onMain {
            page.setCards(1, testCards(12, "新"))
            page.focusItem(0)
        }
        host.waitUntil("焦点落到第二份网格的第 0 张") { focusedCard() == (1 to 0) }
        assertEquals(listOf(true, false), listener.parked)
    }

    @Test
    fun `a fresh press after the grace hands a parked focus back to the page`() {
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(host.onMain { page.isFocused }, "焦点停放在网格页上")
        // 过了驻留时限还没落地 (新标签是空的): 新按下的一下交给页面回标签行
        SystemClock.sleep(TV_TRANSIT_PARK_KEY_GRACE_MILLIS + 100)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, listener.topRowUp)
        assertTrue(windowFocus() === outside, "焦点回到页面给的落点")
        assertEquals(listOf(true, false), listener.parked)
    }

    @Test
    fun `the card opened from the hero state keeps its focused look after the focus leaves`() {
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进 hero 态") { page.heroActive }
        // hero 态里再按确认 = 进详情页; 焦点交给详情页 (这里用页面外的视图代替)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.onMain { outside.requestFocus() }
        host.waitUntil("焦点离开网格") { outside.isFocused }
        val card = host.onMain { page.grid?.findViewHolderForAdapterPosition(5)?.itemView as? TvNativeCardView }
        assertTrue(host.onMain { card?.focusLookHeld == true }, "焦点走了那张卡仍画成聚焦态")
        // 返回后焦点交还: 放开
        host.onMain { page.focusItem(5) }
        host.waitUntil("焦点回到第 5 张") { focusedCard() == (0 to 5) }
        assertEquals(false, host.onMain { card?.focusLookHeld })
    }

    @Test
    fun `entering the hero state waits for the content of the focused card`() {
        host.onMain {
            page.setCards(0, subjectCards())
            // 页面给的内容停在出发那张 (第 5 张)
            page.setSource(heroSource(105))
        }
        // 远跳落到首卡、排队的确认当场进 hero 态: 页面的内容还没跟上 (Compose 晚一两帧)
        host.onMain { page.focusItem(0) }
        host.waitUntil("焦点落到首卡") { focusedCard() == (0 to 0) }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进 hero 态") { page.heroActive }
        assertEquals(null, host.onMain { page.heroText.shownSubjectId }, "先换上了出发那张的文字")
        // 首卡的内容到了: 换上
        host.onMain { page.setSource(heroSource(100)) }
        assertEquals(100, host.onMain { page.heroText.shownSubjectId })
    }

    @Test
    fun `hero text arriving late while entering the hero state waits for its fade in`() {
        host.onMain {
            page.transitions = true
            page.setCards(0, subjectCards())
            page.setSource(heroSource(105))
        }
        host.onMain { page.focusItem(0) }
        host.waitUntil("焦点落到首卡") { focusedCard() == (0 to 0) }
        // 带过渡进 hero 态, 页面的内容还停在出发那张; 首卡的晚一两帧才到, 换上时文字那一段还没开始 (要等整屏压黑)
        val alpha = host.onMain {
            page.setHeroActive(true)
            page.setSource(heroSource(100))
            heroTextAlpha()
        }
        assertEquals(100, host.onMain { page.heroText.shownSubjectId })
        assertEquals(0f, alpha, "内容一到文字就整块露出来了")
    }

    @Test
    fun `the hero text does not come back when the focused card changes while leaving the hero state`() {
        host.onMain {
            page.transitions = true
            page.setCards(0, subjectCards())
            page.setSource(heroSource(105))
            page.setHeroActive(true, animated = false)
        }
        assertEquals(105, host.onMain { page.heroText.shownSubjectId })
        // 按返回退出 hero 态, 没退完就往旁边走了一张: 页面给的内容换成那一张
        host.onMain {
            page.setHeroActive(false)
            page.setSource(heroSource(104))
        }
        // 退出途中文字只会越来越淡, 不换成旁边那张重新进场
        var last = 1f
        val deadline = SystemClock.uptimeMillis() + 1200
        while (SystemClock.uptimeMillis() < deadline) {
            val (alpha, shown) = host.onMain { heroTextAlpha() to page.heroText.shownSubjectId }
            assertTrue(shown != 104, "退出途中换上了旁边那张的文字")
            assertTrue(alpha <= last + 1e-3f, "退出途中文字又变亮了: $last -> $alpha")
            last = alpha
            SystemClock.sleep(16)
        }
        assertEquals(0f, last)
        assertEquals(null, host.onMain { page.heroText.shownSubjectId })
    }

    @Test
    fun `the rows below stay painted while the grid is lifted for the hero transition`() {
        // 进 hero 态的头一帧: 网格整片往上抬, 抵掉 leanback 按 hero 线当场重排的那一跳 (见 TvNativeGridView.setHeroActive). 抬着的时候
        // 框底那一截照样画着下面那排卡 (抬 150 后第 3 排封面在屏上 982..1364), 不跟着网格自己的边界一起被抬上去裁掉
        host.onMain { page.setCards(0, testCards(30)) }
        host.waitUntil("第 3 排排出来") { page.grid?.findViewHolderForAdapterPosition(12) != null }
        val pixel = host.onMain {
            page.grid!!.translationY = -150f
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.draw(Canvas(bitmap))
            bitmap.getPixel(256, 1040)
        }
        assertTrue(Color.alpha(pixel) > 0, "框底那一截的卡被裁掉了")
    }

    @Test
    fun `up while parked goes to the top bar at once`() {
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, listener.topRowUp)
        assertTrue(windowFocus() === outside, "焦点回到页面给的落点")
    }
}
