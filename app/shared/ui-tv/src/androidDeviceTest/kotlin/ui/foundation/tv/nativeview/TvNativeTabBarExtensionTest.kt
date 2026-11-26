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
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 网格页顶上的玻璃标签栏 ([TvNativeTabBarExtension]), 按新番时间表的日期行摆: 每天一枚胶囊、比页面宽, 横向滚到选中 / 焦点所在的签居中.
 * 测: 焦点在海报墙里时选中随字一起推进来 (海报墙跨天) 也滚到居中; 签上左右移焦点并报移动、第一个签按左出去、按下进网格、首行按上回选中的签;
 * 从签上离开页面再回来落回选中的签.
 */
class TvNativeTabBarExtensionTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private lateinit var page: TvNativeGridPageView
    private lateinit var tabBar: TvNativeTabBarExtension
    private lateinit var outside: View
    private val events = mutableListOf<String>()
    private val location = IntArray(2)

    private val style = TvNativeGlassTabStyle(
        label = testTextStyle(28f, 36),
        detail = testTextStyle(20f, 28),
        barHeightPx = 50,
        barPaddingPx = 0,
        gapPx = 12,
        paddingHPx = 20,
        paddingVPx = 6,
        detailGapPx = 8,
        edgePx = 1f,
        dotPx = 8,
        dotOffsetPx = 2,
        fill = Color.DKGRAY,
        edge = Color.GRAY,
        idleContent = Color.LTGRAY,
        selectedPlatter = Color.GRAY,
        selectedContent = Color.WHITE,
        focusedPlatter = Color.WHITE,
        focusedContent = Color.BLACK,
        dot = Color.RED,
        focusScale = 1.08f,
        focusElevationPx = 8f,
        focusMillis = 150,
        unfocusMillis = 90,
        // 演滚动: 滚到居中的途中排版 (海报墙跨天换网格) 不能把它停在半路
        animated = true,
        touch = false,
        pills = true,
    )

    private val days = List(DAYS) { TvNativeGlassTab(id = "$it", label = "第 $it 天", detail = "11/${it + 1}") }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            outside = View(host.activity).apply {
                isFocusable = true
                isFocusableInTouchMode = true
            }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
            tabBar = TvNativeTabBarExtension(host.activity, style)
            tabBar.callbacks = object : TvNativeTabBarExtension.Callbacks {
                override fun onMoved(index: Int) {
                    events += "moved $index"
                    tabBar.setSelected(index)
                }

                override fun onClick(index: Int) {
                    events += "click $index"
                }

                override fun onDown() {
                    events += "down"
                    page.focusItem(0)
                }

                override fun onExitLeft() {
                    events += "exit left"
                    outside.requestFocus()
                }
            }
            page = TvNativeGridPageView(host.activity, host.sketch, scope, testWallStyle(), testGridPageMetrics(), testHeroTextStyle())
            page.transitions = false
            page.animatedScroll = false
            page.listener = object : TvNativeGridPageListener {
                override fun onFocused(index: Int) = Unit
                override fun onClick(index: Int) = Unit
                override fun onTopRowUp(): Boolean = tabBar.focusTab(tabBar.bar.selectedIndex)
                override fun onHeroActiveChanged(active: Boolean) = Unit
                override fun onToneChanged(tone: Float) = Unit
                override fun onScrollingChanged(scrolling: Boolean) = Unit
                override fun onGridFocusChanged(hasFocus: Boolean) = Unit
                override fun onFocusParkedChanged(parked: Boolean) = Unit
            }
            page.addExtension(tabBar)
            tabBar.setTabs(days, 0, style)
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, testCards(24))
        }
        host.waitUntil("卡片排出来") { page.isLaidOut && (page.grid?.childCount ?: 0) > 0 }
        // 签那一排摆在页面顶上 20px 处, 在整页宽里横向滚
        host.onMain {
            page.getLocationInWindow(location)
            tabBar.setAnchor(location[0], location[1] + 20, page.width)
        }
        host.waitUntil("签排出来") { tabBar.bar.isLaidOut && tabBar.bar.tabView(DAYS - 1)?.isLaidOut == true }
        assertTrue(host.onMain { tabBar.bar.width > page.width }, "签那一排得比页面宽")
    }

    @AfterTest
    fun tearDown() {
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun press(keyCode: Int) = host.press(keyCode)

    /** 第 [index] 个签的中线与页面中线差多少 (px, 在主线程上读; 按排版位置量, 不算聚焦放大) */
    private fun offCenter(index: Int): Float {
        val tab = tabBar.bar.tabView(index) ?: return Float.MAX_VALUE
        tabBar.bar.getLocationInWindow(location)
        val tabCenter = location[0] + tab.left + tab.width / 2f
        page.getLocationInWindow(location)
        return tabCenter - (location[0] + page.width / 2f)
    }

    private fun waitCentered(index: Int) {
        val deadline = SystemClock.uptimeMillis() + 3000
        while (!host.onMain { abs(offCenter(index)) <= 1f }) {
            if (SystemClock.uptimeMillis() > deadline) {
                val state = host.onMain {
                    val tab = tabBar.bar.tabView(index)
                    "差 ${offCenter(index)}, 签 left=${tab?.left} 宽=${tab?.width}, 签栏 left=${tabBar.bar.left} 宽=${tabBar.bar.width} " +
                        "平移=${tabBar.bar.translationX} 要排版=${tabBar.bar.isLayoutRequested}, 页面宽=${page.width}"
                }
                fail("第 $index 个签没居中: $state")
            }
            Thread.sleep(20)
        }
    }

    private fun waitTabFocused(index: Int) = host.waitUntil("焦点到第 $index 个签") { tabBar.focusedIndex() == index }

    private fun gridFocused(): Int? {
        val grid = page.grid ?: return null
        return grid.focusedChild?.let { grid.getChildAdapterPosition(it) }
    }

    @Test
    fun `the selected tab scrolls to the center when the selection arrives together with the tabs`() {
        host.onMain { page.focusItem(3) }
        host.waitUntil("焦点到第 3 张") { gridFocused() == 3 }
        // 海报墙跨到第 7 天: 页面组合当帧连字带选中一起推进来, 之后再推一次选中 (同一天)
        host.onMain {
            tabBar.setTabs(days, 7, style)
            tabBar.setSelected(7)
        }
        // 滚过去的途中网格换了、页面又排了几次版
        repeat(3) {
            Thread.sleep(40)
            host.onMain { page.requestLayout() }
        }
        waitCentered(7)
        assertEquals(3, host.onMain { gridFocused() })
        // 只推选中也一样
        host.onMain { tabBar.setSelected(8) }
        waitCentered(8)
    }

    @Test
    fun `tab keys move along the row and go down into the grid and back up to the selected tab`() {
        // 中间几天 (两头的签滚到头就夹住了, 居中不了)
        host.onMain { tabBar.focusTab(6) }
        waitTabFocused(6)
        waitCentered(6)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitTabFocused(7)
        waitCentered(7)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitTabFocused(6)
        waitCentered(6)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("进了网格第一张") { gridFocused() == 0 }
        press(KeyEvent.KEYCODE_DPAD_UP)
        waitTabFocused(6)
        assertEquals(listOf("moved 7", "moved 6", "down"), host.onMain { events.toList() })
    }

    @Test
    fun `left on the first tab leaves the page and focus coming back lands on the selected tab`() {
        host.onMain { tabBar.focusTab(1) }
        waitTabFocused(1)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitTabFocused(0)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("焦点出了页面") { outside.isFocused }
        assertEquals(listOf("moved 0", "exit left"), host.onMain { events.toList() })
        // 从外面 (侧边栏) 回来: 落回选中的签, 不落到卡上
        host.onMain { page.requestFocus() }
        waitTabFocused(0)
        assertEquals(null, host.onMain { gridFocused() })
    }

    private companion object {
        const val DAYS = 15
    }
}
