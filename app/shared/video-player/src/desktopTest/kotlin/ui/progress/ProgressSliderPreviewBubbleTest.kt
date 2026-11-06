/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import me.him188.ani.app.ui.framework.exists
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 电视进度条上方的浮窗 ([ProgressSliderPreviewBubble]): 画面位只给播放器画进来的预览画面, 没有时 (全屏预览、边播边拖) 只显示时间.
 */
@OptIn(ExperimentalTestApi::class)
class ProgressSliderPreviewBubbleTest {
    private fun sliderState() = PlayerProgressSliderState(
        currentPositionMillis = { 30_000L },
        totalDurationMillis = { 100_000L },
        chapters = { emptyList() },
        onPreview = {},
        onPreviewFinished = {},
    )

    /** 同电视拖动预览的状态 (PlayerSeekPreviewState): 自己不取缩略图, 画面只由播放器画进来. */
    private fun playerDrawnPreview() = MediaProgressFramePreviewState(
        fetchFrame = { null },
        debounceMillis = 0,
        fetchesUncachedPositions = { true },
        reportsLoadStatus = true,
        isSupported = { false },
    )

    @Test
    fun `bubble shows only the time when the player draws no preview frame`() = runAniComposeUiTest {
        val framePreview = playerDrawnPreview()
        setContent {
            ProgressSliderPreviewBubble(
                sliderState(), framePreview, MediaProgressSliderDefaults.colors(), ProgressSliderPreviewStyle.FrameOnly,
            )
        }
        waitForIdle()
        assertTrue(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP).exists())
        assertFalse(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME).exists())
        // 只有时间的胶囊; 留着画面位时有 90dp 高的一块
        assertTrue(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP).getBoundsInRoot().height < 60.dp)
    }

    @Test
    fun `bubble shows the frame area while the player draws the preview into it`() = runAniComposeUiTest {
        val framePreview = playerDrawnPreview()
        setContent {
            ProgressSliderPreviewBubble(
                sliderState(), framePreview, MediaProgressSliderDefaults.colors(), ProgressSliderPreviewStyle.FrameOnly,
            )
        }
        runOnUiThread {
            framePreview.liveFrame = { modifier -> Box(modifier.background(Color.Green)) }
        }
        waitForIdle()
        assertTrue(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME).exists())

        runOnUiThread { framePreview.liveFrame = null }
        waitForIdle()
        assertFalse(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME).exists())
        assertTrue(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP).exists())
    }
}
