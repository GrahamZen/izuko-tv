/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Rect
import android.widget.FrameLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 网格页 ([TvNativeGridPageView]) 报告内容滚动 ([TvNativeGridPageView.contentScrollLimitPx], 新番时间表的日期行跟着滚走): 量第一行离开页顶的距离,
 * 夹在上限内; 第一行滚出排版范围按上限报; 上限为 0 不报. 忘掉某份网格的位置 ([TvNativeGridPageView.forgetPosition]) 后它从第一行排起.
 * 换网格时报的值跟着网格滑动一起滑到新那份的. 网格自己的滚动不带动画 (一步到位), 看的是报上来的值.
 */
class TvNativeGridPageContentScrollTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val scrolls = mutableListOf<Int>()
    private lateinit var page: TvNativeGridPageView

    private val listener = object : TvNativeGridPageListener {
        override fun onFocused(index: Int) = Unit
        override fun onClick(index: Int) = Unit
        override fun onLongPress(index: Int, anchor: Rect) = Unit
        override fun onTopRowUp(): Boolean = true
        override fun onHeroActiveChanged(active: Boolean) = Unit
        override fun onToneChanged(tone: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onGridFocusChanged(hasFocus: Boolean) = Unit
        override fun onFocusParkedChanged(parked: Boolean) = Unit
        override fun onContentScrolled(offsetPx: Int) {
            scrolls += offsetPx
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
                topBleedPx = 240,
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
            page.contentScrollLimitPx = LIMIT
            page.listener = listener
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, testCards(30))
        }
        host.waitUntil("卡片排出来") { (page.grid?.childCount ?: 0) > 1 }
        focus(0)
    }

    @AfterTest
    fun tearDown() {
        // 在主线程上取消: 原生视图的动画在取消回调里停 ValueAnimator, 只能在主线程上停
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun focus(index: Int) {
        host.onMain { page.focusItem(index) }
        host.waitUntil("焦点到第 $index 张") { page.grid?.focusedChild?.let { page.grid?.getChildAdapterPosition(it) } == index }
    }

    /** 最后报上来的值 (主线程上调: 在 waitUntil 的条件里用). */
    private fun lastScroll(): Int? = scrolls.lastOrNull()

    @Test
    fun `the content scroll follows the first row and is clamped`() {
        // 聚焦第一行: 停在页顶
        host.waitUntil("第一行在页顶时报 0") { lastScroll() == 0 }
        // 第二行居中: 第一行被推上去, 报的是它离开页顶的距离
        focus(6)
        host.waitUntil("第二行居中时往上滚了") { (lastScroll() ?: 0) > 0 }
        val second = host.onMain { scrolls.last() }
        assertTrue(second <= LIMIT, "夹在上限内: $second")
        // 往下很远: 第一行早就滚出排版范围, 按上限报
        focus(24)
        host.waitUntil("滚远了按上限报") { lastScroll() == LIMIT }
        // 回第一行: 回到 0
        focus(0)
        host.waitUntil("回第一行报 0") { lastScroll() == 0 }
    }

    @Test
    fun `nothing is reported without a limit`() {
        host.onMain {
            page.contentScrollLimitPx = 0
            scrolls.clear()
        }
        focus(24)
        focus(0)
        assertEquals(emptyList(), host.onMain { scrolls.toList() })
    }

    @Test
    fun `a forgotten position starts that grid at the first row`() {
        focus(24)
        host.waitUntil("滚远了") { lastScroll() == LIMIT }
        host.onMain {
            // 换到另一份网格再回来: 位置记着的话会回到第 24 张那一行
            page.showGrid(1, direction = 0, animated = false)
            page.setCards(1, testCards(30, "另一天"))
            page.forgetPosition(0)
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, testCards(30))
            assertEquals(0, page.grid?.selectedPosition)
        }
        host.waitUntil("从第一行排起, 报 0") { lastScroll() == 0 }
    }

    @Test
    fun `switching grids slides the reported scroll along with the grids`() {
        focus(24)
        host.waitUntil("滚远了") { lastScroll() == LIMIT }
        // 滚远了的一份换到停在第一行的一份 (时间表从第二行跨到下一天的第一张): 跟着网格滑动从上限滑到 0, 不一步跳过去
        host.onMain {
            scrolls.clear()
            page.showGrid(1, direction = 1, animated = true)
            page.setCards(1, testCards(30, "另一天"))
        }
        host.waitUntil("滑到新那份的第一行") { lastScroll() == 0 }
        val forward = host.onMain { scrolls.toList() }
        assertTrue(forward.any { it in 1 until LIMIT }, "途中应有中间值: $forward")
        assertEquals(forward.sortedDescending(), forward, "一路往下: $forward")
        // 反过来: 回到刚才那份 (停在第 24 张那一行), 从 0 滑回上限
        host.onMain {
            scrolls.clear()
            page.showGrid(0, direction = -1, animated = true)
            page.setCards(0, testCards(30))
        }
        host.waitUntil("滑回上限") { lastScroll() == LIMIT }
        val back = host.onMain { scrolls.toList() }
        assertTrue(back.any { it in 1 until LIMIT }, "途中应有中间值: $back")
        assertEquals(back.sorted(), back, "一路往上: $back")
    }

    private companion object {
        const val LIMIT = 200
    }
}
