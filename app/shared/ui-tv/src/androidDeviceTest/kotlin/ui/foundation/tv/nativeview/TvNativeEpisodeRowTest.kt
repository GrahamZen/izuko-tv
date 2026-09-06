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
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 原生选集行 ([TvNativeEpisodeRowLayout] / [TvNativeEpisodeRowView], 详情页选集轮播与播放器选集条): 固定锚点 (聚焦卡恒停在停靠线, 末集也是),
 * 两头按键吞掉, 行外进来落到展示中的那张 (远处的也是), 压暗分界, 固定聚焦框的显隐与按压缩放, 确定与长按, 换数据不丢焦点.
 * 1080p (320dpi): 卡 256 × 144dp、间距 16dp, 停靠线 48dp.
 */
class TvNativeEpisodeRowTest {
    private val host = TvNativeTestHost()
    private val listener = RecordingEpisodeListener()
    private val style = testEpisodeStyle()
    private lateinit var layout: TvNativeEpisodeRowLayout
    private lateinit var outside: Button
    private var entry = 0

    private val row get() = layout.row

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            outside = Button(host.activity).apply { text = "外面" }
            host.root.addView(outside, FrameLayout.LayoutParams(300, 100))
            layout = newLayout()
        }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun newLayout(): TvNativeEpisodeRowLayout =
        TvNativeEpisodeRowLayout(host.activity, style, host.sketch, startPx = START, bleedPx = BLEED).also {
            it.row.animatedScroll = false
            it.row.repeatMillis = 40
            it.row.entryIndex = { entry }
            it.listener = listener
            host.root.addView(
                it,
                FrameLayout.LayoutParams(1920, style.cardHeightPx + BLEED * 2).apply { topMargin = 200 },
            )
        }

    private fun focusedIndex(): Int = row.focusedChild?.let { row.getChildAdapterPosition(it) } ?: -1

    private fun cardAt(index: Int): View? = row.findViewHolderForAdapterPosition(index)?.itemView

    private fun ring(): View = layout.getChildAt(1)

    private fun show(count: Int, select: Int = 0) {
        host.onMain {
            row.cards.submit(testEpisodes(count))
            row.selectCard(select)
        }
        host.waitUntil("卡片排出来") { row.childCount > 0 }
    }

    private fun focus(index: Int) {
        host.onMain { row.focusCardIfLaidOut(index) }
        host.waitUntil("第 $index 张拿到焦点") { focusedIndex() == index }
    }

    private fun dockedIndex(): Int {
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child.left == START) return row.getChildAdapterPosition(child)
        }
        return -1
    }

    @Test
    fun `the focused card docks at the anchor under a fixed ring`() {
        show(12)
        focus(0)
        assertEquals(0, host.onMain { dockedIndex() }, "首卡停在停靠线上")
        val ringLeft = host.onMain { ring().left }
        assertEquals(START - style.ringOutsetPx, ringLeft, "框比卡大一圈, 左缘在停靠线左边一个空隙")
        repeat(3) { host.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        host.waitUntil("焦点到第 3 张并停进框里") { focusedIndex() == 3 && dockedIndex() == 3 }
        assertEquals(ringLeft, host.onMain { ring().left }, "框钉着不动")
        assertEquals(View.VISIBLE, host.onMain { ring().visibility })
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.waitUntil("往左也停进框里") { focusedIndex() == 2 && dockedIndex() == 2 }
    }

    @Test
    fun `the last card still docks at the anchor and both ends swallow the key`() {
        show(5)
        focus(0)
        assertTrue(host.onMain { row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }, "首集按左吞掉")
        assertEquals(0, host.onMain { focusedIndex() })
        repeat(4) { host.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        host.waitUntil("末集停进框里 (行尾留一整行空白)") { focusedIndex() == 4 && dockedIndex() == 4 }
        assertTrue(host.onMain { row.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }, "末集按右吞掉")
        assertEquals(4, host.onMain { focusedIndex() })
    }

    @Test
    fun `entering from outside lands on the displayed card even when it is not laid out`() {
        show(30, select = 2)
        host.onMain { outside.requestFocus() }
        entry = 3
        assertTrue(host.onMain { layout.requestFocus() })
        host.waitUntil("落到展示中的第 3 张并停进框里") { focusedIndex() == 3 && dockedIndex() == 3 }

        host.onMain { outside.requestFocus() }
        entry = 25
        assertTrue(host.onMain { layout.requestFocus() }, "远处那张还没排出来, 行先接住焦点")
        host.waitUntil("排完落到第 25 张并停进框里") { focusedIndex() == 25 && dockedIndex() == 25 }
    }

    @Test
    fun `the ring shows only while a card holds focus`() {
        host.onMain { outside.requestFocus() }
        show(6)
        assertTrue(host.onMain { outside.isFocused }, "排出来的卡不抢焦点 (此刻持焦的是 ${host.onMain { host.activity.currentFocus }})")
        assertEquals(View.INVISIBLE, host.onMain { ring().visibility }, "没焦点时不画框")
        focus(1)
        assertEquals(View.VISIBLE, host.onMain { ring().visibility })
        host.onMain { outside.requestFocus() }
        assertEquals(View.INVISIBLE, host.onMain { ring().visibility }, "焦点离开本行就熄灭")
        focus(2)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(View.VISIBLE, host.onMain { ring().visibility }, "行内移动不熄灭")
    }

    @Test
    fun `cards left of the pivot are dimmed and a restored pivot is drawn without animation`() {
        host.onMain { layout.setDimPivot(4, animate = false) }
        show(10, select = 4)
        host.waitUntil("第 3 张与第 4 张都排出来") { cardAt(3) != null && cardAt(4) != null }
        assertEquals(style.pastDimAlpha, host.onMain { cardAt(3)!!.alpha }, "恢复的分界第一次绑定就按它画")
        assertEquals(1f, host.onMain { cardAt(4)!!.alpha })
        focus(4)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("分界随焦点挪到第 5 张, 第 4 张渐暗") { focusedIndex() == 5 && cardAt(4)!!.alpha == style.pastDimAlpha }
        assertEquals(1f, host.onMain { cardAt(5)!!.alpha })
        host.onMain { outside.requestFocus() }
        assertEquals(style.pastDimAlpha, host.onMain { cardAt(4)!!.alpha }, "焦点离行时分界不清")
    }

    @Test
    fun `extra alpha multiplies the dim`() {
        show(8, select = 2)
        host.onMain { layout.setCardAlpha({ index -> if (index > 2) 0.25f else 1f }, animate = false) }
        assertEquals(0.25f, host.onMain { cardAt(3)!!.alpha })
        assertEquals(1f, host.onMain { cardAt(2)!!.alpha })
        host.onMain { layout.setCardAlpha(null, animate = true) }
        host.waitUntil("撤掉之后渐变回全亮") { cardAt(3)!!.alpha == 1f }
    }

    @Test
    fun `confirm clicks and holding it opens the long press`() {
        show(6)
        focus(0)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("焦点到第 1 张") { focusedIndex() == 1 }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(1), listener.clicked)

        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(450)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(1), listener.longPressed)
        assertEquals(listOf(1), listener.clicked, "长按那一下抬起不再算点击")
    }

    @Test
    fun `a held confirm key from elsewhere neither long presses nor clicks`() {
        show(6)
        focus(2)
        // 别处起手的那次按住: 这张卡没见过按下, 只收到连发与抬起
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 3)
        SystemClock.sleep(400)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 4)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(listener.longPressed.isEmpty(), "残余连发不触发长按")
        assertTrue(listener.clicked.isEmpty(), "残余抬起不算点击")
    }

    @Test
    fun `holding confirm shrinks the focused card and the ring together`() {
        show(6)
        focus(1)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按住时卡与框一起缩到 ${style.pressScale}") {
            cardAt(1)!!.scaleX == style.pressScale && ring().scaleX == style.pressScale
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("松开弹回") { cardAt(1)!!.scaleX == 1f && ring().scaleX == 1f }
        assertEquals(listOf(1), listener.clicked)
    }

    @Test
    fun `holding right moves focus on every repeat while the row glides`() {
        show(40)
        host.onMain { row.animatedScroll = true }
        focus(0)
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, host.onMain { focusedIndex() })
        for (i in 1..15) {
            SystemClock.sleep(50)
            host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = i)
            assertEquals(1 + i, host.onMain { focusedIndex() }, "第 $i 发连发")
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("停稳时第 16 张停在框里") { row.scrollState == RecyclerView.SCROLL_STATE_IDLE && dockedIndex() == 16 }
    }

    @Test
    fun `new data for the same episodes keeps the focused card in place`() {
        show(8)
        focus(3)
        val card = host.onMain { row.focusedChild }
        host.onMain {
            row.cards.submit(testEpisodes(8).mapIndexed { i, c -> if (i == 3) c.copy(progress = 0.5f, watched = true) else c })
        }
        host.instrumentation.waitForIdleSync()
        assertEquals(3, host.onMain { focusedIndex() })
        assertSame(card, host.onMain { row.focusedChild }, "持焦的卡原地重绑")
    }

    @Test
    fun `scrolling without focus moves the docked card and does not take focus`() {
        show(12, select = 2)
        host.onMain { outside.requestFocus() }
        host.onMain { row.scrollToCard(6, animated = true) }
        host.waitUntil("第 6 张停进框里") { row.scrollState == RecyclerView.SCROLL_STATE_IDLE && dockedIndex() == 6 }
        assertTrue(host.onMain { outside.isFocused }, "焦点留在原处")
        assertTrue(host.onMain { row.isCardOnScreen(6) && !row.isCardOnScreen(0) })
    }

    @Test
    fun `the play icon replaces the ink inset on the focused card`() {
        show(4)
        // 纯文字卡: 集号是卡里的第二个子视图 (剧照、集号、集名)
        val unfocusedLeft = host.onMain { (cardAt(1) as TvNativeEpisodeCardView).getChildAt(1).left }
        assertEquals(style.textSidePx + style.playInkInsetPx, unfocusedLeft, "没聚焦时补一个三角的内白")
        focus(1)
        host.waitUntil("聚焦后集号让到播放三角后面") {
            (cardAt(1) as TvNativeEpisodeCardView).getChildAt(1).left == style.textSidePx + style.playIconPx + style.textGapPx
        }
    }

    private companion object {
        const val START = 96
        const val BLEED = 16
    }
}

private class RecordingEpisodeListener : TvNativeEpisodeRowListener {
    val focused = mutableListOf<Int>()
    val clicked = mutableListOf<Int>()
    val longPressed = mutableListOf<Int>()

    override fun onFocused(index: Int) {
        focused += index
    }

    override fun onFocusLost(index: Int) = Unit

    override fun onClick(index: Int) {
        clicked += index
    }

    override fun onLongPress(index: Int) {
        longPressed += index
    }

    override fun onScrollingChanged(scrolling: Boolean) = Unit
}

/** [count] 集纯文字卡 (不发图片请求). */
private fun testEpisodes(count: Int): List<TvNativeEpisodeCard> =
    List(count) { TvNativeEpisodeCard(id = 1000 + it, sort = "${it + 1}", name = "第 ${it + 1} 集", stillUrl = null, playing = false, watched = false, progress = null) }

/** 1080p (320dpi) 上选集卡的尺寸: 256 × 144dp、间距 16dp; 集号 14sp / 20sp, 集名 12sp / 16sp. 动画压到最短, 测的是焦点与停位. */
private fun testEpisodeStyle(): TvNativeEpisodeStyle {
    val colors = TvNativeEpisodeColors(container = Color.DKGRAY, sort = Color.WHITE, name = Color.LTGRAY, progress = Color.RED, track = Color.GRAY)
    return TvNativeEpisodeStyle(
        cardWidthPx = 512,
        cardHeightPx = 288,
        spacingPx = 32,
        cornerPx = 12f,
        palette = List(4) { colors },
        imageNameColor = Color.LTGRAY,
        imageTrackColor = Color.GRAY,
        scrimStart = 0.5f,
        scrimColor = 0xD9000000.toInt(),
        sort = testTextStyle(28f, 40),
        name = testTextStyle(24f, 32),
        textSidePx = 6,
        textGapPx = 8,
        textTopPx = 10,
        textBottomWithBarPx = 16,
        textBottomNoBarPx = 10,
        playIcon = Bitmap.createBitmap(34, 34, Bitmap.Config.ARGB_8888),
        playIconPx = 34,
        playingIcon = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888),
        playingIconPx = 30,
        playInkInsetPx = 11,
        playingInkInsetPx = 8,
        iconBaselineOffsetPx = 10,
        barHeightPx = 6,
        barSidePx = 12,
        barBottomPx = 10,
        pastDimAlpha = 0.45f,
        dimMillis = 50L,
        pressScale = 0.94f,
        ringOutsetPx = 4,
        ringWidthPx = 520,
        ringHeightPx = 296,
        ringStrokePx = 4f,
        ringCornerPx = 16f,
        ringStartColor = Color.CYAN,
        ringEndColor = Color.MAGENTA,
        ringCountdownTrackAlpha = 0.4f,
        marqueeRepeat = 0,
        crossfade = false,
    )
}
