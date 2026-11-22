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
import android.widget.FrameLayout
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.animation.AniMotionScheme
import me.him188.ani.app.ui.foundation.animation.LocalAniMotionScheme
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.tv.TvAniUiBehavior
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 右上角的通知横幅 ([TvNoticeBubbleHost]): 出来时焦点在横幅上, 方向键不动; 按确定做它的操作, 按返回关掉.
 */
class TvNoticeBannerTest {
    private val host = TvNativeTestHost()
    private lateinit var compose: ComposeView
    private val pageFocus = FocusRequester()
    private var actions = 0
    private var dismissals = 0
    private lateinit var banner: TvNoticeBubble

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            compose = ComposeView(host.activity)
            host.root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            compose.setContent {
                CompositionLocalProvider(
                    LocalAniUiBehavior provides TvAniUiBehavior,
                    LocalAniMotionScheme provides AniMotionScheme.calculate(LocalDensity.current),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.size(100.dp).focusRequester(pageFocus).testTag(PAGE).focusable())
                        TvNoticeBubbleHost()
                    }
                }
            }
        }
        host.waitUntil("页面先拿到焦点") { runCatching { pageFocus.requestFocus() }; focused() == PAGE }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { if (::banner.isInitialized) TvNoticeBubbles.hide(banner) }
        host.close()
    }

    private fun show() = host.onMain {
        banner = TvNoticeBubble(
            icon = Icons.Outlined.Feedback,
            title = "A notice",
            hint = "Press OK to view",
            onAction = {
                actions++
                TvNoticeBubbles.hide(banner)
            },
            onDismiss = {
                dismissals++
                TvNoticeBubbles.hide(banner)
            },
        )
        TvNoticeBubbles.show(banner)
    }

    @Test
    fun `the banner takes focus and arrows do not move it`() {
        show()
        host.waitUntil("横幅出来, 焦点在它身上 (现在在 ${focused()})") { focused() == TV_NOTICE_BANNER_TAG }
        for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)) host.press(key)
        assertEquals(TV_NOTICE_BANNER_TAG, host.onMain { focused() }, "方向键不动")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定做它的操作") { actions == 1 }
        assertEquals(0, host.onMain { dismissals })
    }

    @Test
    fun `back closes the banner`() {
        show()
        host.waitUntil("横幅出来") { focused() == TV_NOTICE_BANNER_TAG }
        host.press(KeyEvent.KEYCODE_BACK)
        host.waitUntil("返回键关掉") { dismissals == 1 }
        assertEquals(0, host.onMain { actions })
    }

    /** 焦点所在的节点的测试标记; 在主线程上调 */
    private fun focused(): String? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
        ?.config?.getOrNull(SemanticsProperties.TestTag)

    private fun nodes(): List<SemanticsNode> {
        val root = (compose.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return emptyList()
        return listOf(root) + root.descendants()
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private companion object {
        const val PAGE = "page"
    }
}
