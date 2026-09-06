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
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 网格页 ([TvNativeGridPageView.setCards]) 换数据时焦点那一格换成了别的作品 (刷新拿掉了聚焦的那部): 卡原地重绑、焦点没动,
 * 也要再报一次聚焦, 页面的 hero 才跟得上; 数据没变就不报.
 */
class TvNativeGridPageFocusFollowTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private val listener = Listener()
    private lateinit var page: TvNativeGridPageView

    private class Listener : TvNativeGridPageListener {
        val focused: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        override fun onFocused(index: Int) {
            focused += index
        }

        override fun onClick(index: Int) = Unit
        override fun onLongPress(index: Int, anchor: Rect) = Unit
        override fun onTopRowUp(): Boolean = true
        override fun onHeroActiveChanged(active: Boolean) = Unit
        override fun onToneChanged(tone: Float) = Unit
        override fun onScrollingChanged(scrolling: Boolean) = Unit
        override fun onGridFocusChanged(hasFocus: Boolean) = Unit
        override fun onFocusParkedChanged(parked: Boolean) = Unit
    }

    private fun cards(subjects: List<Int>) = subjects.map { TvNativeCard(imageUrl = null, title = "卡 $it", subjectId = it) }

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
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, cards((100 until 112).toList()))
        }
        host.waitUntil("卡片排出来") { (page.grid?.childCount ?: 0) > 3 }
        host.onMain { page.focusItem(2) }
        host.waitUntil("焦点到第 2 张") { page.grid?.focusedChild?.let { page.grid?.getChildAdapterPosition(it) } == 2 }
        host.waitUntil("报过聚焦第 2 张") { 2 in listener.focused }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { scope.cancel() }
        host.close()
    }

    @Test
    fun `focus is reported again when the focused slot shows another subject`() {
        val before = listener.focused.size
        // 聚焦的那部 (102) 被刷新拿掉, 后面的往前挪一格
        host.onMain { page.setCards(0, cards((100 until 112).filter { it != 102 })) }

        host.waitUntil("补报聚焦第 2 张") { listener.focused.size > before }
        assertEquals(2, listener.focused.last())
        assertEquals(2, host.onMain { page.grid?.focusedChild?.let { page.grid?.getChildAdapterPosition(it) } })
    }

    @Test
    fun `focus is not reported again when the data did not change`() {
        val before = listener.focused.size
        host.onMain { page.setCards(0, cards((100 until 112).toList()) + cards(listOf(200))) }
        host.waitUntil("多出来的卡排上") { (page.grid?.adapter?.itemCount ?: 0) == 13 }
        // 给 post 出去的补报留一轮机会
        host.onMain { }
        host.onMain { }

        assertEquals(before, listener.focused.size)
    }
}
