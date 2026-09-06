/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import me.him188.ani.app.ui.foundation.widgets.TvMenuOption
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuLook
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuRequest
import me.him188.ani.app.ui.foundation.widgets.TvNativeMenuSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import android.view.KeyEvent as AndroidKeyEvent

/**
 * 原生菜单 ([TvNativeMenuHost] + [TvNativeMenuLayerView]): 按键由宿主在根部截下 (这里直接调 [TvNativeMenuHost.onKey], 代替根部的修饰符),
 * 高亮、选中、收起、长列表只建看得见的行、摆位.
 */
class TvNativeMenuTest {
    private val host = TvNativeTestHost()
    private lateinit var layer: TvNativeMenuLayerView
    private lateinit var menus: TvNativeMenuHost
    private val selected = mutableListOf<Int>()
    private var dismissed = 0

    private val look = TvNativeMenuLook(
        density = Density(2f),
        text = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        container = Color(0xF2303030),
        content = Color.White,
        focusContainer = Color(0xFF8AB4F8),
        focusContent = Color.Black,
        divider = Color.Gray,
    )

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            layer = TvNativeMenuLayerView(host.activity)
            host.root.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            menus = TvNativeMenuHost(host.root)
            menus.attach(layer)
        }
        // 没开菜单时那一层是 GONE, 不参与排版; 等装它的根布局排好
        host.waitUntil("根布局排好") { host.root.width > 0 }
    }

    @AfterTest
    fun tearDown() = host.close()

    /** 锚点在窗口里的位置 (那一层的坐标加上它在窗口里的偏移). */
    private fun anchorAt(left: Int, top: Int, right: Int, bottom: Int): IntRect = host.onMain {
        val location = IntArray(2)
        layer.getLocationInWindow(location)
        IntRect(left + location[0], top + location[1], right + location[0], bottom + location[1])
    }

    private fun request(
        options: List<TvMenuOption>,
        anchor: IntRect = anchorAt(200, 100, 400, 160),
        initialIndex: Int = 0,
        onDismiss: () -> Unit = { dismissed++ },
    ) = TvNativeMenuRequest(
        anchorBounds = anchor,
        options = options,
        look = look,
        positionProvider = null,
        offset = DpOffset(0.dp, 0.dp),
        initialIndex = initialIndex,
        onSelect = { selected += it },
        onDismissRequest = onDismiss,
    )

    private fun open(request: TvNativeMenuRequest): TvNativeMenuSession {
        val session = host.onMain { menus.open(request) }
        host.waitUntil("菜单排出来") { !layer.isLayoutRequested && !list().isLayoutRequested && list().childCount > 0 }
        return session
    }

    private fun card(): View = layer.getChildAt(0)
    private fun list(): RecyclerView = (card() as ViewGroup).getChildAt(0) as RecyclerView

    private fun key(action: Int, code: Int, repeat: Int = 0): Boolean = host.onMain {
        val now = SystemClock.uptimeMillis()
        menus.onKey(KeyEvent(AndroidKeyEvent(now, now, action, code, repeat)), null)
    }

    private fun press(code: Int) {
        assertTrue(key(AndroidKeyEvent.ACTION_DOWN, code))
        assertTrue(key(AndroidKeyEvent.ACTION_UP, code))
    }

    private fun options(vararg texts: String) = texts.map { TvMenuOption(it) }

    @Test
    fun `opening highlights the first option and up down skip disabled ones`() {
        open(request(listOf(TvMenuOption("关"), TvMenuOption("不能选", enabled = false), TvMenuOption("中文"))))
        assertEquals(0, host.onMain { layer.highlight })
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(2, host.onMain { layer.highlight })
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(2, host.onMain { layer.highlight }, "到底了不再动")
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        assertEquals(0, host.onMain { layer.highlight })
    }

    @Test
    fun `the initial index opens on that option`() {
        open(request(options("A", "B", "C", "D"), initialIndex = 2))
        assertEquals(2, host.onMain { layer.highlight })
    }

    @Test
    fun `confirm selects the highlighted option and closes the menu`() {
        open(request(options("A", "B", "C")))
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(1), selected)
        assertFalse(host.onMain { menus.isOpen })
        host.waitUntil("淡出后藏起来") { layer.visibility == View.GONE }
    }

    @Test
    fun `a confirm release without its press does not select`() {
        // 长按卡片开的菜单: 开菜单那一下的抬起落在菜单里
        open(request(options("A", "B")))
        assertTrue(key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(emptyList(), selected)
        assertTrue(host.onMain { menus.isOpen })
    }

    @Test
    fun `back closes on release and the page never sees it`() {
        open(request(options("A", "B")))
        assertTrue(key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BACK))
        assertTrue(host.onMain { menus.isOpen }, "按下时还开着")
        assertTrue(key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_BACK))
        assertEquals(1, dismissed)
        assertFalse(host.onMain { menus.isOpen })
        assertFalse(key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_BACK), "收起之后的新按下交给页面")
    }

    @Test
    fun `keys pressed while open are swallowed until released after the menu closes`() {
        val session = open(request(options("A", "B", "C")))
        assertTrue(key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN))
        host.onMain { session.close() }
        assertTrue(key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeat = 1), "连发")
        assertTrue(key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN), "抬起")
        assertFalse(key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN), "下一次按下交给页面")
    }

    @Test
    fun `play pause passes through to the page`() {
        open(request(options("A", "B")))
        assertFalse(key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertFalse(key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(host.onMain { menus.isOpen })
    }

    @Test
    fun `a long list builds only the visible rows and keeps the highlight in view`() {
        open(request((1..60).map { TvMenuOption("字幕 $it") }))
        val built = host.onMain { list().childCount }
        assertTrue(built in 1..25, "只建看得见的行, 实际 $built 行")
        repeat(30) { press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals(30, host.onMain { layer.highlight })
        host.waitUntil("滚到高亮那一行", timeoutMillis = 5000) {
            val row = list().findViewHolderForAdapterPosition(30)?.itemView
            list().scrollState == RecyclerView.SCROLL_STATE_IDLE && row != null && row.top >= 0 && row.bottom <= list().height
        }
    }

    @Test
    fun `the menu opens below the anchor and flips above it near the bottom`() {
        val top = request(options("A", "B", "C"), anchor = anchorAt(200, 100, 400, 160))
        open(top)
        assertTrue(host.onMain { card().top } >= 160, "放在锚点下面")
        val height = host.onMain { layer.height }
        val bottom = request(options("A", "B", "C"), anchor = anchorAt(200, height - 80, 400, height - 20))
        host.onMain { menus.open(bottom) }
        host.waitUntil("换到底部的锚点") { card().bottom <= layer.height - 80 }
    }

    @Test
    fun `the width follows the widest option within the menu limits`() {
        open(request(options("A")))
        val narrow = host.onMain { card().width }
        assertEquals((112 + 16) * 2, narrow, "最窄 112dp 加左右内缩")
        open(request(options("很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长的字幕名")))
        val wide = host.onMain { card().width }
        assertEquals((280 + 16) * 2, wide, "最宽 280dp 加左右内缩")
    }

    @Test
    fun `updating the options keeps the highlight on the same row`() {
        val session = open(request(options("A", "B", "C")))
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        val more = request(options("A", "B", "C", "D", "E"))
        host.onMain { session.update(more) }
        assertEquals(1, host.onMain { layer.highlight })
        assertTrue(host.onMain { menus.isOpen })
    }

    @Test
    fun `opening another menu dismisses the previous one`() {
        open(request(options("A", "B")))
        open(request(options("C", "D"), onDismiss = {}))
        assertEquals(1, dismissed)
        assertTrue(host.onMain { menus.isOpen })
    }

    @Test
    fun `touching outside the card dismisses the menu`() {
        open(request(options("A", "B")))
        host.onMain {
            val now = SystemClock.uptimeMillis()
            val x = layer.width - 5f
            val y = layer.height - 5f
            layer.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
            layer.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
        }
        assertEquals(1, dismissed)
        assertFalse(host.onMain { menus.isOpen })
    }
}
