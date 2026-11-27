/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.domain.notice.NoticeEntry
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.dialogs.LocalStackedDialog
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog

/**
 * 通知弹窗里一条的操作开的小窗 ([TvNoticeDestination.Dialog]) 叠在通知弹窗上 (同 TvNoticeHost): 通知弹窗不收起、不重建, 小窗这一层不压暗,
 * 关掉后焦点还在打开它的那颗按钮上.
 */
class TvNoticeOverlayTest {
    private val host = TvNativeTestHost()
    private var overlayShown by mutableStateOf(false)
    private var noticeRoot: View? = null
    private var overlayRoot: View? = null
    private val state = TvNoticeDialogState(fresh = emptySet(), focusKey = null, focusIndex = 0)
    private val refreshUi = TvNoticeRefreshUi(label = "Refresh", status = null, statusHighlight = false, busy = false, cooling = false)
    private val notices = listOf(
        TvShownNotice(
            NoticeEntry(Notice(kind = TestKind.id, key = NOTICE, atMillis = 1), pending = false, seen = true),
            NOTICE,
            TestKind,
            actions = listOf(TvNoticeAction(DETAILS, TvNoticeDestination.Dialog { }), TvNoticeAction(OTHER, TvNoticeDestination.Run {})),
        ),
    )

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            val compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                // 签那一排读视觉效果档位, 封面要图片加载器
                CompositionLocalProvider(LocalThemeSettings provides ThemeSettings.Default, LocalSketch provides host.sketch) {
                    Body()
                }
            }
        }
        host.waitUntil("一打开焦点在那一条 (现在在 ${focusedText()})") { focusedText() == NOTICE }
    }

    @Composable
    private fun Body() {
        AniCenteredPanelDialog(onDismissRequest = {}, heightFraction = null, maxWidth = 880.dp) {
            val view = LocalView.current
            SideEffect { noticeRoot = view.rootView }
            TvNoticeNativeBody(
                notices,
                state,
                tabs = emptyList(),
                onAction = { _, _, action -> if (action.label == DETAILS) overlayShown = true },
                onRead = {},
                onSelectTab = { _, _, _ -> },
                refreshUi = { refreshUi },
                onRefresh = {},
            )
        }
        if (overlayShown) {
            CompositionLocalProvider(LocalStackedDialog provides true) {
                AniCenteredPanelDialog(onDismissRequest = { overlayShown = false }) {
                    val view = LocalView.current
                    SideEffect { overlayRoot = view.rootView }
                    Text(OVERLAY)
                }
            }
        }
    }

    @AfterTest
    fun tearDown() = host.close()

    @Test
    fun `an overlay stacks on the notice dialog and focus stays on its button`() {
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定键进右栏第一颗 (现在在 ${focusedText()})") { focusedText() == DETAILS }
        val before = host.onMain { noticeView() }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("小窗出来了, 拿到窗口焦点") { overlayRoot?.hasWindowFocus() == true }
        val dim = host.onMain { (overlayRoot?.layoutParams as? WindowManager.LayoutParams)?.dimAmount }
        assertEquals(0f, dim, "叠上去的小窗不再压暗")
        assertSame(before, host.onMain { noticeView() }, "通知弹窗没收起")
        host.waitUntil("小窗开着时下面不画聚焦的样子") { noticeView()?.coveredForTest == true }
        host.press(KeyEvent.KEYCODE_BACK)
        host.waitUntil("返回键关掉小窗") { !overlayShown && noticeRoot?.hasWindowFocus() == true }
        host.waitUntil("焦点还在那颗按钮上, 聚焦的样子画回来 (现在在 ${focusedText()})") {
            focusedText() == DETAILS && noticeView()?.coveredForTest == false
        }
        assertSame(before, host.onMain { noticeView() }, "通知弹窗没重建")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        host.waitUntil("关掉小窗后照样能按 (现在在 ${focusedText()})") { focusedText() == OTHER }
    }

    private fun noticeView(): TvNoticeView? = noticeRoot?.let { find(it) }

    private fun focusedText(): String? = noticeView()?.focusedText()

    private fun find(view: View): TvNoticeView? {
        if (view is TvNoticeView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }

    private object TestKind : TvNoticeKind {
        override val id: String = "test"
        override val category: TvNoticeCategory = TvNoticeCategory.Announcement
        override suspend fun title(notice: Notice): String = notice.key
        override fun destination(notice: Notice): TvNoticeDestination? = null
    }

    private companion object {
        const val NOTICE = "Notice with details"
        const val DETAILS = "Details"
        const val OTHER = "Other"
        const val OVERLAY = "Overlay"
    }
}
