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
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.subject.person.PeopleRowItem
import me.him188.ani.app.ui.subject.person.PosterRowItem
import me.him188.ani.app.ui.subject.person.TvPeoplePreviewRows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * 人物 / 角色弹窗与整页的原生横滑行 ([TvPeoplePreviewRows]) 横向按出血之后的宽度裁: 整页的中栏两旁是侧栏, 滑过行首的格、行外多排的那格
 * 与行尾露出的那截不画进侧栏; 往左的出血不超过格间距, 停稳时行首左边那格整格落在出血外, 不在边上露一窄条. 照整页搭: 左栏、中栏、右栏
 * 之间各隔一个栏距, 中栏的圆头像行与海报行按栏距出血. 两旁的栏涂成纯色、先画 (中栏的行画在它们上面, 伸出去的格会盖住纯色).
 * 两行都往右挪到行首滑出去几格, 截下本窗口看两旁的栏在行的高度上仍是纯色.
 */
class TvPeoplePreviewRowsClipTest {
    private val host = TvNativeTestHost()
    private lateinit var compose: ComposeView

    /** 两旁的栏在窗口里的框. */
    private val sides = arrayOfNulls<Rect>(2)

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
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(COLUMN_SPACING)) {
                        SideColumn(0)
                        Column(Modifier.weight(1f)) {
                            Spacer(Modifier.height(80.dp))
                            Text("声优")
                            TvPeoplePreviewRows.PeopleRow(
                                List(12) { PeopleRowItem(imageUrl = null, name = "人物 $it", subtitle = "") },
                                onClick = {},
                                onBind = {},
                                contentPadding = COLUMN_SPACING,
                                modifier = Modifier,
                            )
                            Text("出演作品")
                            TvPeoplePreviewRows.PosterRow(
                                List(12) { PosterRowItem(imageUrl = null, title = "作品 $it", subtitle = "主角") },
                                onClick = {},
                                onBind = {},
                                contentPadding = COLUMN_SPACING,
                                modifier = Modifier,
                            )
                        }
                        SideColumn(1)
                    }
                }
            }
        }
        host.waitUntil("两行都排出来") { rows().size == 2 && rows().all { it.childCount > 0 } && sides.all { it != null } }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Composable
    private fun SideColumn(index: Int) {
        Box(
            Modifier.width(SIDE_WIDTH).fillMaxHeight().zIndex(-1f).background(ComposeColor(SIDE_COLOR))
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    sides[index] = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                },
        )
    }

    private fun rows(): List<TvNativeStripView> = findStrips(host.root)

    @Test
    fun `cells past either end of a row are not drawn over the side columns`() {
        for (row in rows()) {
            host.onMain { row.getChildAt(0).requestFocus() }
            host.waitUntil("焦点进行") { row.hasFocus() }
            // 往右挪到行首滑出去几格
            repeat(16) {
                if (host.onMain { row.leftIndex() } < 3) host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            host.waitUntil("行首滑出去几格、停稳", timeoutMillis = 5000) {
                row.leftIndex() >= 3 && row.scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
            // 停稳时没有格跨在行的左边界上 (行首左边那格整格在出血外)
            val straddling = host.onMain { (0 until row.childCount).map { row.getChildAt(it) }.filter { it.left < 0 && it.right > 0 } }
            assertTrue(straddling.isEmpty(), "${row.javaClass.simpleName}: 行首左边不该露一窄条, 跨在边界上的格 ${straddling.map { it.left to it.right }}")
        }
        // 海报行在行外多排一张: 它整张在行的左边界外, 不裁的话画进左栏
        val posterRow = rows().first { it is TvNativeRowView }
        assertTrue(
            host.onMain { (0 until posterRow.childCount).any { posterRow.getChildAt(it).right <= 0 } },
            "海报行应有一张排在行的左边界外",
        )
        // 滚动停下之后再画一两帧
        SystemClock.sleep(200)
        host.instrumentation.waitForIdleSync()
        val shot = host.windowShot()
        for (row in rows()) {
            val rect = host.onMain { windowRect(row) }
            for ((i, side) in sides.withIndex()) {
                val s = side!!
                // 行的高度上、栏内缩两个点 (栏边的抗锯齿)
                val region = Rect(s.left + 2, maxOf(rect.top, s.top), s.right - 2, minOf(rect.bottom, s.bottom, shot.height))
                assertSolid(shot, region, "${row.javaClass.simpleName} ${if (i == 0) "左" else "右"}边的栏")
            }
        }
    }

    /** [region] 里全是两旁的栏的纯色 (隔一个点取一个). */
    private fun assertSolid(shot: Bitmap, region: Rect, what: String) {
        assertTrue(region.width() > 0 && region.height() > 0, "$what: 区域为空 $region")
        var bad = 0
        var sample = 0
        for (y in region.top until region.bottom step 2) {
            for (x in region.left until region.right step 2) {
                val c = shot.getPixel(x, y)
                if (Color.red(c) < 250 || Color.green(c) > 5 || Color.blue(c) > 5) {
                    if (bad == 0) sample = c
                    bad++
                }
            }
        }
        assertEquals(0, bad, "$what 里有 $bad 个点不是纯色 (例如 #${Integer.toHexString(sample)}), 区域 $region")
    }

    /** [view] 在窗口里的框 (整个视图, 含上下多出的那截). */
    private fun windowRect(view: View): Rect {
        val xy = IntArray(2)
        view.getLocationInWindow(xy)
        return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }

    private companion object {
        /** 两旁的栏宽. */
        val SIDE_WIDTH = 200.dp

        /** 栏距, 也是中栏的行往两侧出血的上限 (同整页 centerStrips 的 rowsPadding). */
        val COLUMN_SPACING = 24.dp

        const val SIDE_COLOR = 0xFFFF0000.toInt()
    }
}

private fun findStrips(v: View): List<TvNativeStripView> = when (v) {
    is TvNativeStripView -> listOf(v)
    is ViewGroup -> (0 until v.childCount).flatMap { findStrips(v.getChildAt(it)) }
    else -> emptyList()
}
