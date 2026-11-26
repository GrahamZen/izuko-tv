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
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 海报墙的扩展 ([TvNativeWallExtension]) 挂在两种墙上, 墙对它做的事一样: 视图量好排好、每帧都跟、进出 hero 态告诉它; hero 态里聚焦卡上的上下键 /
 * 确认键先给扩展, 扩展接了墙就不换行、不进详情页; 焦点在扩展里时方向键给扩展; 焦点进出时告诉扩展, 扩展要按住聚焦态时那张卡一直画成聚焦态,
 * 送回卡上接得上; 扩展退出 hero 态.
 * 网格页另测只留一张的 hero 态: 别的卡淡没, 聚焦那张横移到第一列, 左右按顺序翻 (跨行).
 */
class TvNativeWallExtensionTest {
    private val host = TvNativeTestHost()
    private val scope = MainScope()
    private lateinit var extension: TestExtension

    /** 页面外的一个落点 (代替侧边栏) */
    private lateinit var outside: View

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            extension = TestExtension(host.activity)
            outside = View(host.activity).apply { isFocusable = true }
            host.root.addView(outside, FrameLayout.LayoutParams(10, 10))
        }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { scope.cancel() }
        host.close()
    }

    private fun press(keyCode: Int, times: Int = 1) = repeat(times) { host.press(keyCode) }

    private fun waitInside(inside: Boolean) =
        host.waitUntil(if (inside) "焦点进了扩展" else "焦点离开扩展") { extension.inside == inside && extension.box.isFocused == inside }

    // ---- 网格页 ----

    private lateinit var page: TvNativeGridPageView
    private val pageClicks = mutableListOf<Int>()

    private fun setUpGrid() {
        host.onMain {
            // 聚焦动画放慢: 翻卡时测得出有没有先缩回再放大
            page = TvNativeGridPageView(host.activity, host.sketch, scope, testWallStyle().copy(focusMillis = 300L), testGridPageMetrics(), testHeroTextStyle())
            page.transitions = false
            page.animatedScroll = false
            page.heroSingleCard = true
            page.listener = object : TvNativeGridPageListener {
                override fun onFocused(index: Int) = Unit
                override fun onClick(index: Int) {
                    pageClicks += index
                }

                override fun onTopRowUp(): Boolean = false
                override fun onHeroActiveChanged(active: Boolean) = Unit
                override fun onToneChanged(tone: Float) = Unit
                override fun onScrollingChanged(scrolling: Boolean) = Unit
                override fun onGridFocusChanged(hasFocus: Boolean) = Unit
                override fun onFocusParkedChanged(parked: Boolean) = Unit
            }
            page.addExtension(extension)
            host.root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            page.showGrid(0, direction = 0, animated = false)
            page.setCards(0, testCards(24))
        }
        host.waitUntil("卡片排出来") { (page.grid?.childCount ?: 0) > 0 }
    }

    /** 焦点所在的那张 (在主线程上读, 用在 waitUntil 里). */
    private fun gridFocused(): Int? {
        val grid = page.grid ?: return null
        return grid.focusedChild?.let { grid.getChildAdapterPosition(it) }
    }

    private fun focusGridCard(index: Int) {
        host.onMain { page.focusItem(index) }
        host.waitUntil("焦点到第 $index 张") { gridFocused() == index }
    }

    @Test
    fun `grid hero keys go to the extension and focus inside it holds the card look`() {
        setUpGrid()
        assertTrue(host.onMain { extension.box.isLaidOut && extension.frames > 0 })
        focusGridCard(7)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态") { page.heroActive }

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitInside(true)
        assertEquals(7, host.onMain { page.grid?.cards?.heldFocusIndex })
        // 焦点在扩展里: 方向键给扩展 (左右它吞掉, 按上它送回卡上)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), host.onMain { extension.keys.toList() })
        assertTrue(host.onMain { extension.box.isFocused })
        press(KeyEvent.KEYCODE_DPAD_UP)
        waitInside(false)
        host.waitUntil("回到那张卡") { gridFocused() == 7 }
        assertTrue(host.onMain { page.heroActive })

        // hero 态里按确定: 扩展接走, 不交给页面进详情页
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitInside(true)
        assertEquals(1, host.onMain { extension.heroConfirms })
        assertEquals(emptyList(), host.onMain { pageClicks.toList() })
        // 扩展退出 hero 态
        host.onMain { extension.wall?.exitHero() }
        host.waitUntil("退出了 hero 态") { !page.heroActive }
        assertEquals(listOf(true, false), host.onMain { extension.heroChanges.toList() })
    }

    @Test
    fun `grid single card hero keeps the focused card in the first column and flips in order`() {
        setUpGrid()
        val pitch = testWallStyle().let { it.cardWidthPx + it.columnSpacingPx }.toFloat()
        focusGridCard(7)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态") { page.heroActive }
        host.waitUntil("只留聚焦那张, 挪到第一列") { shown(7) && hidden(6) && hidden(13) && shiftIs(7, -pitch) }
        assertTrue(ValueAnimator.areAnimatorsEnabled(), "要开着系统动画跑: 关着时状态动画当场到位, 测不出翻卡时的放大")
        // 翻卡不带动画: 在主线程上当场派发这一下, 处理完的那一刻 (还没到下一帧) 新那张就是聚焦到位的样子 (没有先小后大)
        val (progress, held) = host.onMain {
            val now = SystemClock.uptimeMillis()
            page.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            page.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            gridCard(8)?.focusProgress to gridCard(8)?.focusLookHeld
        }
        assertEquals(1f, progress, "翻过去那张还在放大")
        assertEquals(false, held)
        host.waitUntil("翻到下一张") { gridFocused() == 8 && shown(8) && shiftIs(8, -2 * pitch) && hidden(7) && shiftIs(7, 0f) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
        host.waitUntil("到行尾") { gridFocused() == 11 }
        // 行尾按右接下一行行首, 按左回来
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("接下一行行首") { gridFocused() == 12 && page.heroActive && shown(12) && shiftIs(12, 0f) }
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("回到上一行行尾") { gridFocused() == 11 && shiftIs(11, -5 * pitch) }
        // 退回卡片墙: 都回来, 那张回到自己那一列
        host.onMain { page.setHeroActive(false, animated = false) }
        host.waitUntil("都回来") { shown(6) && shown(11) && shiftIs(11, 0f) }
    }

    /** 第 [index] 张卡 (在主线程上读, 用在 waitUntil 里). */
    private fun gridCard(index: Int): TvNativeCardView? = page.grid?.findViewHolderForAdapterPosition(index)?.itemView as? TvNativeCardView

    private fun shown(index: Int): Boolean = (gridCard(index)?.dim ?: 0f) > 0.99f

    private fun hidden(index: Int): Boolean = gridCard(index)?.dim == 0f

    private fun shiftIs(index: Int, px: Float): Boolean = gridCard(index)?.let { abs(it.translationX - px) < 1f } == true

    // ---- 行式海报墙 ----

    private lateinit var wall: TvNativeRowWallView
    private val wallClicks = mutableListOf<Pair<String, Int>>()

    private fun setUpRows() {
        host.onMain {
            val style = testWallStyle()
            wall = TvNativeRowWallView(host.activity, host.sketch, scope, style, testExploreMetrics(style).copy(spacerPx = 0), testHeroTextStyle(), testTextStyle(32f, 44))
            wall.transitions = false
            wall.animatedScroll = false
            wall.hideRowsBelowInHero = true
            wall.openFocusesWall = false
            wall.rowListener = object : TvNativeRowWallListener {
                override fun onCardFocused(rowKey: String, index: Int, column: Int) = Unit
                override fun onCardClick(rowKey: String, index: Int) {
                    wallClicks += rowKey to index
                }

                override fun onCardLongPress(rowKey: String, index: Int, anchor: Rect) = Unit
                override fun onBindCard(rowKey: String, index: Int) = Unit
                override fun onExitLeft() = Unit
                override fun onHeroActiveChanged(active: Boolean) = Unit
                override fun onCardAreaFocusChanged(hasFocus: Boolean) = Unit
                override fun onToneChanged(tone: Float, splitY: Float) = Unit
                override fun onScrollingChanged(scrolling: Boolean) = Unit
            }
            wall.setItems(
                listOf(
                    TvNativeExploreItem.Row(ROW_A, testCards(8, "a")),
                    TvNativeExploreItem.Row(ROW_B, testCards(8, "b")),
                ),
            )
            wall.addExtension(extension)
            host.root.addView(wall, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        host.waitUntil("页面排好") { wall.isLaidOut }
        host.onMain { wall.requestFocus() }
        waitWallCard(ROW_A, 0)
    }

    private fun waitWallCard(rowKey: String, index: Int) =
        host.waitUntil("焦点到 $rowKey 第 $index 张") { wall.cardAreaHasFocus && wall.focusedRowKey == rowKey && wall.focusedCardIndex == index }

    @Test
    fun `row wall hero keys go to the extension and focus returns to the card`() {
        setUpRows()
        assertTrue(host.onMain { extension.box.isLaidOut && extension.frames > 0 })
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitWallCard(ROW_A, 1)
        // 卡片墙上的上下照常换行
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitWallCard(ROW_B, 1)
        assertFalse(host.onMain { extension.inside })
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("进了 hero 态") { wall.heroActive }

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitInside(true)
        assertFalse(host.onMain { wall.cardAreaHasFocus })
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), host.onMain { extension.keys.toList() })
        press(KeyEvent.KEYCODE_DPAD_UP)
        waitInside(false)
        waitWallCard(ROW_B, 1)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitInside(true)
        assertEquals(1, host.onMain { extension.heroConfirms })
        assertEquals(emptyList(), host.onMain { wallClicks.toList() })
        // 焦点离开墙 (去侧边栏): 告诉扩展
        host.onMain { outside.requestFocus() }
        waitInside(false)
        host.onMain { extension.wall?.exitHero() }
        host.waitUntil("退出了 hero 态") { !wall.heroActive }
        assertEquals(listOf(true, false), host.onMain { extension.heroChanges.toList() })
    }

    private companion object {
        const val ROW_A = "row-a"
        const val ROW_B = "row-b"
    }
}

/** 测试用的扩展: 一块能聚焦的方块, 记下墙给它的回调. hero 态里按下 / 按确定把焦点拿进来, 里面按上送回卡上, 其余方向键吞掉. */
private class TestExtension(context: Context) : TvNativeWallExtension {
    val box = View(context).apply { isFocusable = true }
    override val views: List<View> = listOf(box)

    var wall: TvNativeWallHost? = null
    var frames = 0
    var heroConfirms = 0
    var inside = false
    val keys = mutableListOf<Int>()
    val heroChanges = mutableListOf<Boolean>()

    override fun onAttach(wall: TvNativeWallHost) {
        this.wall = wall
    }

    override fun onMeasure(wall: TvNativeWallHost) {
        box.measure(MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY))
    }

    override fun onLayout(wall: TvNativeWallHost) {
        val top = wall.pageHeightPx - 200
        box.layout(wall.cardsStartPx, top, wall.cardsStartPx + box.measuredWidth, top + box.measuredHeight)
    }

    override fun onFrame(wall: TvNativeWallHost) {
        frames++
    }

    override fun onHeroActiveChanged(wall: TvNativeWallHost, active: Boolean) {
        heroChanges += active
    }

    override fun onKey(wall: TvNativeWallHost, focused: View, keyCode: Int): Boolean {
        keys += keyCode
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) wall.focusCurrentCard()
        return true
    }

    override fun onHeroVerticalKey(wall: TvNativeWallHost, down: Boolean): Boolean {
        if (down) box.requestFocus()
        return true
    }

    override fun onHeroConfirm(wall: TvNativeWallHost): Boolean {
        heroConfirms++
        box.requestFocus()
        return true
    }

    override fun onFocusInside(wall: TvNativeWallHost, inside: Boolean) {
        this.inside = inside
        wall.holdCardLook(inside)
    }
}

/** 1080p 的网格页几何 (6 列, hero 线在网格顶线下 400). */
internal fun testGridPageMetrics(heroLinePx: Int = 400): TvNativeGridPageMetrics = TvNativeGridPageMetrics(
    pageWidthPx = 1920,
    pageHeightPx = 1080,
    gridTopPx = 120,
    grid = TvNativeGridMetrics(
        columns = 6,
        startPx = 128,
        endPx = 48,
        topBleedPx = 0,
        bottomBleedPx = 0,
        endMarginPx = 60,
        heroLinePx = heroLinePx,
        fadeDistancePx = 128f,
    ),
    backdropWidthPx = 1344,
    backdropHeightPx = 756,
    heroLeftPx = 128,
    heroTopPx = 120,
    heroWidthPx = 900,
    heroHeightPx = 300,
    titleWidthPx = 900,
    summaryWidthPx = 800,
)
