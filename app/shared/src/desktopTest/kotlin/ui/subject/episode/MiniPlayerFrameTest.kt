/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 应用内小窗的框: 拖动吸角、捏合缩放、双击切两档. 区域 1000×600 (dp), 默认宽 0.28 = 280, 在右下角. */
class MiniPlayerFrameTest {
    private val corner = mutableIntStateOf(MINI_PLAYER_DEFAULT_CORNER)
    private val widthFraction = mutableFloatStateOf(MINI_PLAYER_SMALL_FRACTION)
    private var taps = 0

    private fun AniComposeUiTest.setFrame() {
        setContent {
            Box(Modifier.size(1000.dp, 600.dp)) {
                MiniPlayerFrame(corner, widthFraction, onTap = { taps++ }, video = {}, controls = {})
            }
        }
        waitForIdle()
    }

    private fun AniComposeUiTest.bounds() = onNodeWithTag(TOUCH_MINI_PLAYER_TAG).getUnclippedBoundsInRoot()

    @Test
    fun `starts in the bottom right corner at the small size`() = runAniComposeUiTest {
        setFrame()
        val b = bounds()
        assertEquals(280f, b.width.value, 1f)
        assertEquals(1000f, b.right.value, 1f)
        assertEquals(600f, b.bottom.value, 1f)
    }

    @Test
    fun `dragging toward the top left snaps there`() = runAniComposeUiTest {
        setFrame()
        onNodeWithTag(TOUCH_MINI_PLAYER_TAG).performTouchInput {
            swipe(center, center - Offset(width * 2f, height * 3f), durationMillis = 300)
        }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertEquals(0, corner.intValue)
        val b = bounds()
        assertEquals(0f, b.left.value, 1f)
        assertEquals(0f, b.top.value, 1f)
    }

    @Test
    fun `double tap toggles between the two sizes and stays in its corner`() = runAniComposeUiTest {
        setFrame()
        onNodeWithTag(TOUCH_MINI_PLAYER_TAG).performTouchInput { doubleClick() }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertEquals(MINI_PLAYER_LARGE_FRACTION, widthFraction.floatValue)
        val b = bounds()
        assertEquals(450f, b.width.value, 1f)
        assertEquals(1000f, b.right.value, 1f)
        assertEquals(600f, b.bottom.value, 1f)
        assertEquals(0, taps)

        onNodeWithTag(TOUCH_MINI_PLAYER_TAG).performTouchInput { doubleClick() }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertEquals(280f, bounds().width.value, 1f)
    }

    @Test
    fun `pinching out enlarges it within the limit`() = runAniComposeUiTest {
        setFrame()
        onNodeWithTag(TOUCH_MINI_PLAYER_TAG).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(200f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(200f, 0f),
                durationMillis = 400,
            )
        }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val b = bounds()
        assertTrue(b.width.value > 300f, "width ${b.width}")
        // 上限: 区域宽的一半
        assertTrue(b.width.value <= 500f + 1f, "width ${b.width}")
        assertEquals(b.width.value / 1000f, widthFraction.floatValue, 0.01f)
        // 松手吸回一个角
        assertTrue(b.right.value > 999f || b.left.value < 1f)
    }
}
