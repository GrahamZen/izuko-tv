/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import android.os.SystemClock
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
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.LONG_PRESS_MIN_HOLD
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
 * 右上角的通知横幅 ([TvNoticeBubbleHost]): 出来时焦点在横幅上; 按确定做它的操作, 按返回、方向键关掉.
 */
class TvNoticeBannerTest {
    private val host = TvNativeTestHost()
    private lateinit var compose: ComposeView
    private val pageFocus = FocusRequester()
    private var actions = 0
    private var dismissals = 0
    private var longActions = 0
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
                    // 横幅第一行的走马灯读视觉效果档位
                    LocalThemeSettings provides ThemeSettings.Default,
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
            onLongAction = {
                longActions++
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
    fun `an arrow key closes the banner`() {
        show()
        host.waitUntil("横幅出来, 焦点在它身上 (现在在 ${focused()})") { focused() == TV_NOTICE_BANNER_TAG }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        host.waitUntil("方向键关掉横幅 (同返回)") { dismissals == 1 }
        assertEquals(0, host.onMain { actions })
        assertEquals(0, host.onMain { longActions })
    }

    @Test
    fun `the banner takes focus and ok does its action`() {
        show()
        host.waitUntil("横幅出来, 焦点在它身上 (现在在 ${focused()})") { focused() == TV_NOTICE_BANNER_TAG }
        // 刚出来那一下的确定不算 (见下一条), 过了那段再按
        SystemClock.sleep(TV_NOTICE_BANNER_CONFIRM_GUARD.inWholeMilliseconds)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("按确定做它的操作") { actions == 1 }
        assertEquals(0, host.onMain { dismissals })
        assertEquals(0, host.onMain { longActions })
    }

    @Test
    fun `ok right after the banner appears does nothing`() {
        show()
        host.waitUntil("横幅出来") { focused() == TV_NOTICE_BANNER_TAG }
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, host.onMain { actions }, "横幅刚出来那一下的确定多半是按给下面页面的, 不算")
        assertEquals(TV_NOTICE_BANNER_TAG, host.onMain { focused() }, "横幅还在")
        SystemClock.sleep(TV_NOTICE_BANNER_CONFIRM_GUARD.inWholeMilliseconds)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("过了那段再按就算") { actions == 1 }
    }

    @Test
    fun `holding ok does the long action instead of the short one`() {
        show()
        host.waitUntil("横幅出来") { focused() == TV_NOTICE_BANNER_TAG }
        SystemClock.sleep(TV_NOTICE_BANNER_CONFIRM_GUARD.inWholeMilliseconds)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(LONG_PRESS_MIN_HOLD.inWholeMilliseconds + 100)
        for (repeat in 1..3) {
            host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = repeat)
            SystemClock.sleep(50)
        }
        host.waitUntil("按住到长按的门槛当场做长按的操作 (不等松手)") { longActions == 1 }
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(200)
        assertEquals(0, host.onMain { actions }, "松手不再算短按")
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
