/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.window.Dialog
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 小米电视上独立窗口的重录 ([TvSecondaryWindowLayerRefresh]): 模拟器上不看机型直接装上, 看弹窗画过之后重录了、静止后停住 (不能一直画下去),
 * 内容变了照样跟着重录, 主窗口不管.
 */
class TvSecondaryWindowLayerRefreshTest {
    private val host = TvNativeTestHost()
    private var uninstall: (() -> Unit)? = null
    private var showDialog by mutableStateOf(false)
    private var dialogText by mutableStateOf("取消")
    private var pageText by mutableStateOf("页面")

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            uninstall = TvSecondaryWindowLayerRefresh.install()
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                Text(pageText)
                if (showDialog) {
                    Dialog(onDismissRequest = {}) { Text(dialogText) }
                }
            }
        }
        host.waitUntil("页面排好") { host.root.width > 0 }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { uninstall?.invoke() }
        host.close()
    }

    private fun dialogRoot(): View? = WindowInspector.getGlobalWindowViews().firstOrNull {
        (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION
    }

    private fun rerecords(): Int = host.onMain { TvSecondaryWindowLayerRefresh.rerecordCount }

    /** [millis] 内 [window] 画了几次. */
    private fun drawsWithin(window: View, millis: Long): Int {
        var draws = 0
        val listener = ViewTreeObserver.OnDrawListener { draws++ }
        host.onMain { window.viewTreeObserver.addOnDrawListener(listener) }
        SystemClock.sleep(millis)
        return host.onMain {
            window.viewTreeObserver.removeOnDrawListener(listener)
            draws
        }
    }

    @Test
    fun `a dialog is rerecorded after it draws and then stops drawing`() {
        val before = rerecords()
        host.onMain { showDialog = true }
        host.waitUntil("弹窗出来") { dialogRoot() != null }
        host.waitUntil("画过之后重录") { TvSecondaryWindowLayerRefresh.rerecordCount > before }
        SystemClock.sleep(500)
        val window = host.onMain { dialogRoot()!! }
        val settled = rerecords()
        val draws = drawsWithin(window, 1000)
        assertTrue(draws <= 1, "静止的弹窗一秒画了 $draws 次, 重录停不下来")
        assertEquals(settled, rerecords(), "静止后还在重录")
        // 内容变了 (同挪焦点后的高亮): 画完照样重录
        host.onMain { dialogText = "重启" }
        host.waitUntil("改字后重录") { TvSecondaryWindowLayerRefresh.rerecordCount > settled }
    }

    @Test
    fun `the main window is left alone`() {
        val before = rerecords()
        repeat(3) {
            host.onMain { pageText = "页面 $it" }
            SystemClock.sleep(100)
        }
        SystemClock.sleep(300)
        assertEquals(before, rerecords(), "主窗口画了不该重录")
    }
}
