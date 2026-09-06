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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 演职人员的圆头像横滑行 ([TvNativeMonogramRowView], 详情页角色 / 制作人员): 数据没到时的占位格接得住焦点、到了原地换不丢焦点,
 * 两头按键吞掉, 长按连发每一发都当场挪到下一格, 确定与长按. 1080p: 圆 260、间距 40, 行首 80, 一屏完整放得下 6 格.
 */
class TvNativeMonogramRowTest {
    private val host = TvNativeTestHost()
    private val listener = RecordingCardListener()
    private val style = testMonogramStyle()
    private lateinit var row: TvNativeMonogramRowView

    @BeforeTest
    fun setUp() {
        host.launch()
        row = host.onMain { newRow() }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun newRow(): TvNativeMonogramRowView =
        TvNativeMonogramRowView(host.activity, style, host.sketch, startPx = 80, endPx = 80, topPx = 0, bottomPx = 0).also {
            it.animatedScroll = false
            it.repeatMillis = 40
            it.cells.listener = listener
            it.cells.placeholderCount = 7
            host.root.addView(it, FrameLayout.LayoutParams(1920, style.cellHeightPx))
        }

    private fun focusedIndex(): Int = row.focusedChild?.let { row.getChildAdapterPosition(it) } ?: -1

    private fun dispatchDown(keyCode: Int, repeatCount: Int = 0): Boolean =
        host.onMain { row.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, repeatCount)) }

    private fun showPeople(count: Int, viewAll: Boolean = false) {
        host.onMain { row.bind(testPeople(count, viewAll), leftIndex = 0, columns = 6) }
        host.waitUntil("真数据排出来") { row.childCount > 0 && !row.cells.loading }
    }

    private fun focusFirst() {
        host.waitUntil("格子排出来") { row.childCount > 0 }
        host.onMain { row.focusCardIfLaidOut(0) }
        host.waitUntil("第 0 格拿到焦点") { focusedIndex() == 0 }
    }

    @Test
    fun `a placeholder row takes focus and keeps it on the same cell when data arrives`() {
        host.onMain { row.bind(null, leftIndex = 0, columns = 6) }
        host.waitUntil("占位格排出来") { row.childCount > 0 }
        assertTrue(host.onMain { row.requestFocus() }, "占位的行接得住焦点")
        host.waitUntil("焦点在第 0 格") { focusedIndex() == 0 }
        // 占位时左右键吞掉, 焦点不动
        assertTrue(dispatchDown(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(0, host.onMain { focusedIndex() })
        val cell = host.onMain { row.focusedChild }

        host.onMain { row.bind(testPeople(10), leftIndex = 0, columns = 6) }
        host.instrumentation.waitForIdleSync()
        assertEquals(0, host.onMain { focusedIndex() }, "换成真数据后焦点还在第 0 格")
        assertSame(cell, host.onMain { row.focusedChild }, "持焦的那格原地重绑, 没有拆掉重建")
        // 占位的字条是背景: 换回真数据后字区不能还留着字条的内缩 (姓名会被截窄、下半截被裁掉)
        for (text in host.onMain { textViewsOf(cell!!) }) {
            assertEquals(listOf(0, 0, 0, 0), listOf(text.paddingLeft, text.paddingTop, text.paddingRight, text.paddingBottom), "「${text.text}」的内边距")
        }
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("有数据后右键挪到第 1 格") { focusedIndex() == 1 }
    }

    @Test
    fun `a focus request before the first layout of a placeholder row lands on the first cell`() {
        lateinit var fresh: TvNativeMonogramRowView
        val took = host.onMain {
            host.root.removeView(row)
            fresh = newRow()
            fresh.bind(null, leftIndex = 0, columns = 6)
            fresh.requestFocus()
        }
        assertTrue(took, "还没排过的行接住了焦点")
        host.waitUntil("排完落到第 0 格") { fresh.focusedChild?.let { fresh.getChildAdapterPosition(it) } == 0 }
        assertFalse(host.onMain { fresh.isFocusable }, "格拿到焦点后行撤回不可聚焦")
    }

    @Test
    fun `both ends of the row swallow the key`() {
        showPeople(8)
        focusFirst()
        assertTrue(dispatchDown(KeyEvent.KEYCODE_DPAD_LEFT), "行首按左吞掉 (左边没有目标)")
        assertEquals(0, host.onMain { focusedIndex() })
        repeat(7) { host.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        host.waitUntil("焦点到最后一格") { focusedIndex() == 7 }
        assertTrue(dispatchDown(KeyEvent.KEYCODE_DPAD_RIGHT), "行尾按右吞掉")
        assertEquals(7, host.onMain { focusedIndex() })
    }

    @Test
    fun `reaching the partially visible cell shifts the row by one cell`() {
        showPeople(12)
        focusFirst()
        repeat(6) { host.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        host.waitUntil("焦点到第 6 格, 行首挪到第 1 格") { focusedIndex() == 6 && row.leftIndex() == 1 }
        // 贴齐: 行首那格正好在停靠线上
        assertEquals(80, host.onMain { row.findViewHolderForAdapterPosition(1)!!.itemView.left })
    }

    @Test
    fun `holding right moves focus on every repeat while the row glides`() {
        showPeople(40)
        host.onMain { row.animatedScroll = true }
        focusFirst()
        repeat(5) { host.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        host.waitUntil("焦点到第 5 格") { focusedIndex() == 5 }
        // 按住: 系统连发约 50ms 一发 (比行的限速 40ms 慢, 每一发都挪). 行一路平滑滚着, 落后焦点两格多;
        // 每一发的目标格都得已经排好、焦点当场过去, 否则会走 leanback 的平滑选中 (一下平滑一下瞬移)
        host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(6, host.onMain { focusedIndex() })
        for (i in 1..20) {
            SystemClock.sleep(50)
            host.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = i)
            assertEquals(6 + i, host.onMain { focusedIndex() }, "第 $i 发连发")
        }
        host.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("停稳时第 26 格贴在行尾") { row.scrollState == RecyclerView.SCROLL_STATE_IDLE && row.leftIndex() == 21 }
    }

    @Test
    fun `confirm clicks a person and holding it zooms instead`() {
        showPeople(6)
        focusFirst()
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("焦点到第 1 格") { focusedIndex() == 1 }
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
    fun `the view all cell has no long press and placeholders do not click`() {
        host.onMain { row.bind(null, leftIndex = 0, columns = 6) }
        focusFirst()
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(listener.clicked.isEmpty(), "占位格不派发点击")

        showPeople(3, viewAll = true)
        repeat(3) { host.press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        host.waitUntil("焦点到「查看全部」") { focusedIndex() == 3 }
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(450)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(listener.longPressed.isEmpty(), "「查看全部」没有长按")
        assertEquals(listOf(3), listener.clicked, "按住放开照样算点击")
    }
}

private fun textViewsOf(view: View): List<TextView> = when (view) {
    is TextView -> listOf(view)
    is ViewGroup -> (0 until view.childCount).flatMap { textViewsOf(view.getChildAt(it)) }
    else -> emptyList()
}

/** 1080p (320dpi) 上圆头像行的尺寸: 圆 130dp、间距 20dp; 姓名 14sp / 行高 20sp, 副标题 12sp / 16sp. 过渡关掉. */
internal fun testMonogramStyle(): TvNativeMonogramStyle = TvNativeMonogramStyle(
    sizePx = 260,
    spacingPx = 40,
    textGapPx = 4,
    lineGapPx = 4,
    name = testTextStyle(28f, 40),
    subtitle = testTextStyle(24f, 32),
    subtitleIdleAlpha = 0.6f,
    initials = testTextStyle(44f, 56),
    focusScale = 1.115f,
    ringWidthPx = 2f,
    ringColor = Color.CYAN,
    filledColor = Color.DKGRAY,
    placeholderColor = Color.GRAY,
    placeholderNameWidthPx = 156,
    placeholderSubtitleWidthPx = 104,
    placeholderBarInsetPx = 6,
    placeholderBarCornerPx = 8f,
    arrow = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888),
    arrowColor = Color.LTGRAY,
    viewAllLabel = "查看全部",
    focusMillis = 0L,
    crossfade = false,
)

/** [count] 个没有照片的人 (写首字, 不发图片请求), [viewAll] 时行末补一格「查看全部」. */
internal fun testPeople(count: Int, viewAll: Boolean = false): List<TvNativeMonogram> =
    List(count) { TvNativeMonogram.Person(imageUrl = null, name = "人物 $it", subtitle = "声优 $it", initials = "人") } +
        if (viewAll) listOf(TvNativeMonogram.ViewAll(remaining = 12)) else emptyList()
