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
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.focus.tvBringIntoViewOnFocus
import me.him188.ani.app.ui.foundation.focus.tvContainDirectionalKeys
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.subject.person.PeopleRowItem
import me.him188.ani.app.ui.subject.person.PosterRowItem
import me.him188.ani.app.ui.subject.person.TvPeoplePreviewRows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 人物预览弹窗里的原生横滑行 ([TvPeoplePreviewRows]): 弹窗是一列纵向滚动的内容, 方向键在弹窗里走完 ([tvContainDirectionalKeys]),
 * 装原生行的块在焦点进来时滚进可见范围 ([tvBringIntoViewOnFocus]). 这里照弹窗搭: 560dp 宽的一列, 顶上一个按钮 (打开完整页面),
 * 一大段内容把两行挤到屏幕下面, 下面是圆头像行 (声优) 与海报行 (出演作品).
 */
class TvPeoplePreviewRowsTest {
    private val host = TvNativeTestHost()
    private val top = FocusRequester()
    private var topFocused = false
    private val clicked = mutableListOf<Int>()
    private lateinit var compose: ComposeView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(
                    LocalSketch provides host.sketch,
                    LocalThemeSettings provides ThemeSettings.Default,
                ) {
                    // 外面再套一层: 窗口根上的那一层拿到的是整个窗口的定宽约束, 在它上面设宽度不起作用 (弹窗的 Surface 也是放在一个 Box 里)
                    Box(Modifier.fillMaxSize()) {
                    Box(Modifier.width(560.dp).fillMaxHeight().tvContainDirectionalKeys()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            // 头部按钮在右上角 (同预览弹窗的「打开完整页面」)
                            Box(
                                Modifier.align(Alignment.End).size(48.dp)
                                    .focusRequester(top)
                                    .onFocusChanged { topFocused = it.isFocused }
                                    .focusable(),
                            )
                            Spacer(Modifier.height(600.dp))
                            Column(Modifier.tvBringIntoViewOnFocus()) {
                                Text("声优")
                                TvPeoplePreviewRows.PeopleRow(
                                    List(8) { PeopleRowItem(imageUrl = null, name = "人物 $it", subtitle = "") },
                                    onClick = { clicked += it },
                                    onBind = {},
                                    contentPadding = 16.dp,
                                    modifier = Modifier,
                                )
                            }
                            Column(Modifier.tvBringIntoViewOnFocus()) {
                                Text("出演作品")
                                TvPeoplePreviewRows.PosterRow(
                                    List(10) { PosterRowItem(imageUrl = null, title = "作品 $it", subtitle = "主角") },
                                    onClick = {},
                                    onBind = {},
                                    contentPadding = 16.dp,
                                    modifier = Modifier,
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
        host.waitUntil("两行都排出来") { rows().size == 2 && rows().all { it.childCount > 0 } }
        host.onMain { top.requestFocus() }
        host.waitUntil("焦点在头部按钮") { topFocused }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun rows(): List<TvNativeStripView> = findStrips(host.root)
    private fun peopleRow() = rows().first { it is TvNativeMonogramRowView }
    private fun posterRow() = rows().first { it is TvNativeRowView }

    private fun focusedIndex(row: TvNativeStripView): Int = row.focusedChild?.let { row.getChildAdapterPosition(it) } ?: -1

    /** [view] 在屏幕上的框 (整个视图, 不管裁剪). */
    private fun screenRect(view: View): Rect {
        val xy = IntArray(2)
        view.getLocationOnScreen(xy)
        return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }

    /** 行在布局里占的那一块 (视图上下各多出 32dp, 画聚焦放大伸出去的部分, 不占布局). */
    private fun layoutRect(row: View): Rect {
        val bleed = (32 * row.resources.displayMetrics.density).toInt()
        return screenRect(row).apply { inset(0, bleed) }
    }

    private fun inScreen(row: View): Boolean {
        val rect = layoutRect(row)
        val screen = screenRect(compose)
        return rect.top >= screen.top && rect.bottom <= screen.bottom
    }

    @Test
    fun `down from the top enters the people row at its start and scrolls it into view`() {
        // 头部按钮在右上角, 正下方是第四格; 头一回进行落到行首那格
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("焦点进了圆头像行的第一格") { focusedIndex(peopleRow()) == 0 }
        // 滚动动画走完: 行整个落在屏幕里 (起初它在 600dp 的内容下面, 屏幕外)
        host.waitUntil("圆头像行滚进屏幕") { inScreen(peopleRow()) }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("再按下进了海报行") { focusedIndex(posterRow()) == 0 }
        host.waitUntil("海报行滚进屏幕") { inScreen(posterRow()) }
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        host.waitUntil("按上回到圆头像行") { focusedIndex(peopleRow()) == 0 }
    }

    @Test
    fun `sideways keys on the header do not land in a native row`() {
        // 头部按钮左边、右边都没有 Compose 目标: 这一下弹窗吞掉, 不交给系统 —— 系统会按位置挑中下面行里的卡
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(host.onMain { topFocused }, "焦点应还在头部按钮")
        assertFalse(host.onMain { rows().any { it.hasFocus() } }, "焦点跳进了原生行")
    }

    @Test
    fun `the rows bleed to the edges of the popup and start at the content padding`() {
        host.onMain {
            val container = screenRect(compose)
            for (row in rows()) {
                val rect = screenRect(row)
                assertEquals(container.left, rect.left, "行左缘应到弹窗边")
                assertEquals((560 * row.resources.displayMetrics.density).toInt(), rect.width(), "行宽应是整个弹窗")
                // 第一格从 16dp 的留白线排起
                val first = row.findViewHolderForAdapterPosition(0)!!.itemView
                assertEquals((16 * row.resources.displayMetrics.density).toInt(), screenRect(first).left - rect.left)
            }
        }
    }

    @Test
    fun `holding confirm on a person still counts as a click`() {
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("焦点进了圆头像行") { focusedIndex(peopleRow()) == 0 }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0), clicked)
        // 弹窗里没有放大看图: 按住确定键到长按阈值也照样算一次点击
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(450)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(0, 0), clicked)
    }
}

private fun findStrips(v: View): List<TvNativeStripView> = when (v) {
    is TvNativeStripView -> listOf(v)
    is ViewGroup -> (0 until v.childCount).flatMap { findStrips(v.getChildAt(it)) }
    else -> emptyList()
}
