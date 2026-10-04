/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import androidx.collection.floatListOf
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.CompletableDeferred
import me.him188.ani.app.domain.media.player.ChunkState
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.ui.framework.exists
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import org.openani.mediamp.features.PreviewFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 测试悬浮进度条时, 预览帧浮窗的展示与缓存区域门控.
 */
@OptIn(ExperimentalTestApi::class)
class MediaProgressSliderFramePreviewTest {

    private fun createSliderState() = PlayerProgressSliderState(
        currentPositionMillis = { 30_000L },
        totalDurationMillis = { 100_000L },
        chapters = { emptyList() },
        onPreview = {},
        onPreviewFinished = {},
    )

    private fun solidFrame(color: Color, width: Int = 160, height: Int = 90): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.color = color })
        return bitmap
    }

    @Test
    fun `hover shows frame preview in popup`() = runAniComposeUiTest {
        val frame = solidFrame(Color.Green)
        val requestedPositions = mutableListOf<Long>()
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = { positionMillis ->
                requestedPositions.add(positionMillis)
                frame
            },
            debounceMillis = 0,
        )
        setContent {
            MediaProgressSlider(
                createSliderState(),
                cacheProgressInfoFlow = { null },
                framePreview = framePreview,
            )
        }

        waitForIdle()
        runOnUiThread {
            onNodeWithTag(TAG_PROGRESS_SLIDER).performMouseInput {
                moveTo(center)
            }
        }
        runOnIdle {
            waitUntil(timeoutMillis = 5_000) {
                onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).exists()
            }
        }
        onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP, useUnmergedTree = true).assertExists()
        assertTrue(requestedPositions.isNotEmpty(), "fetchFrame should have been called on hover")
    }

    @Test
    fun `drag requests frame at dragged position`() = runAniComposeUiTest {
        val frame = solidFrame(Color.Green)
        val requestedPositions = mutableListOf<Long>()
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = { positionMillis ->
                requestedPositions.add(positionMillis)
                frame
            },
            debounceMillis = 0,
        )
        setContent {
            MediaProgressSlider(
                createSliderState(),
                cacheProgressInfoFlow = { null },
                framePreview = framePreview,
            )
        }

        waitForIdle()
        runOnUiThread {
            onNodeWithTag(TAG_PROGRESS_SLIDER).performTouchInput {
                down(centerLeft)
                moveBy(Offset(width / 2f, 0f))
            }
        }
        runOnIdle {
            waitUntil(timeoutMillis = 5_000) {
                onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).exists()
            }
        }
        // 拖到中间, 请求的位置应当在总时长的一半附近.
        assertTrue(
            (requestedPositions.lastOrNull() ?: -1L) in 40_000L..60_000L,
            "requested positions $requestedPositions do not end near the dragged center",
        )
        runOnUiThread {
            onNodeWithTag(TAG_PROGRESS_SLIDER).performTouchInput {
                up()
            }
        }
    }

    @Test
    fun `uncached position does not request frame but still shows time popup`() = runAniComposeUiTest {
        var fetchCount = 0
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = {
                fetchCount++
                solidFrame(Color.Red)
            },
            debounceMillis = 0,
        )
        val uncachedInfo = MediaCacheProgressInfo(
            chunkWeights = floatListOf(1f),
            chunkStates = listOf(ChunkState.NONE),
        )
        setContent {
            MediaProgressSlider(
                createSliderState(),
                cacheProgressInfoFlow = { uncachedInfo },
                framePreview = framePreview,
            )
        }

        onNodeWithTag(TAG_PROGRESS_SLIDER).performMouseInput {
            moveTo(center)
        }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP, useUnmergedTree = true).exists()
        }
        waitForIdle()
        assertTrue(
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).exists().not(),
            "frame should not be shown for uncached position",
        )
        assertEquals(0, fetchCount, "fetchFrame should not be called for uncached position")
    }

    @Test
    fun `uncached position requests frame when the preview fetches data itself`() = runAniComposeUiTest {
        var fetchCount = 0
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = {
                fetchCount++
                solidFrame(Color.Red)
            },
            debounceMillis = 0,
            fetchesUncachedPositions = { true },
        )
        val uncachedInfo = MediaCacheProgressInfo(
            chunkWeights = floatListOf(1f),
            chunkStates = listOf(ChunkState.NONE),
        )
        setContent {
            MediaProgressSlider(
                createSliderState(),
                cacheProgressInfoFlow = { uncachedInfo },
                framePreview = framePreview,
            )
        }

        onNodeWithTag(TAG_PROGRESS_SLIDER).performMouseInput {
            moveTo(center)
        }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).exists()
        }
        assertTrue(fetchCount > 0, "fetchFrame should be called for uncached position of online media")
    }

    @Test
    fun `no frame preview state keeps time-only popup`() = runAniComposeUiTest {
        setContent {
            MediaProgressSlider(
                createSliderState(),
                cacheProgressInfoFlow = { null },
                framePreview = null,
            )
        }
        onNodeWithTag(TAG_PROGRESS_SLIDER).performMouseInput {
            moveTo(center)
        }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP, useUnmergedTree = true).exists()
        }
        assertTrue(
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).exists().not(),
            "frame should not be shown when framePreview is null",
        )
    }

    /**
     * TV 的用法: 圆点上方的浮窗, 由 [PlayerProgressSliderState.previewPositionRatio] 驱动.
     * 下面几个用例不用鼠标悬停 —— 测试窗口小, 悬停浮窗会被挤到顶上盖住指针, 指针在进度条与浮窗之间
     * 来回进出, 悬停态反复翻转, 每翻一次就重新取一次帧, 界面永远不空闲.
     */
    @Composable
    private fun FrameOnlySlider(
        framePreview: MediaProgressFramePreviewState,
        sliderState: PlayerProgressSliderState,
        cacheProgressInfo: MediaCacheProgressInfo? = null,
    ) {
        MediaProgressSlider(
            sliderState,
            cacheProgressInfoFlow = { cacheProgressInfo },
            framePreview = framePreview,
            previewStyle = ProgressSliderPreviewStyle.FrameOnly,
        )
    }

    @Test
    fun `loading indicator shows until the frame arrives`() = runAniComposeUiTest {
        val result = CompletableDeferred<ImageBitmap?>()
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = { result.await() },
            debounceMillis = 0,
            reportsLoadStatus = true,
        )
        val sliderState = createSliderState()
        setContent { FrameOnlySlider(framePreview, sliderState) }

        runOnUiThread { sliderState.previewPositionRatio(0.5f) }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_LOADING, useUnmergedTree = true).exists()
        }
        assertEquals(FramePreviewLoadStatus.Loading, framePreview.loadStatus)

        result.complete(solidFrame(Color.Green))
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).exists()
        }
        waitForIdle()
        assertFalse(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_LOADING, useUnmergedTree = true).exists())
        assertEquals(FramePreviewLoadStatus.Idle, framePreview.loadStatus)
    }

    @Test
    fun `previous frame stays under the loading indicator while the next position loads`() = runAniComposeUiTest {
        val second = CompletableDeferred<ImageBitmap?>()
        var fetches = 0
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = { if (fetches++ == 0) solidFrame(Color.Green) else second.await() },
            debounceMillis = 0,
            reportsLoadStatus = true,
        )
        val sliderState = createSliderState()
        setContent { FrameOnlySlider(framePreview, sliderState) }

        runOnUiThread { sliderState.previewPositionRatio(0.5f) }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).exists()
        }
        runOnUiThread { sliderState.previewPositionRatio(0.8f) }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_LOADING, useUnmergedTree = true).exists()
        }
        // 上一个位置的帧留着 (浮窗把它压暗), 没有被清成灰底
        onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).assertExists()

        second.complete(solidFrame(Color.Blue))
        waitUntil(timeoutMillis = 5_000) { framePreview.loadStatus == FramePreviewLoadStatus.Idle }
    }

    @Test
    fun `failed fetch keeps the frame slot and shows the failure`() = runAniComposeUiTest {
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = { null },
            debounceMillis = 0,
            reportsLoadStatus = true,
        )
        val sliderState = createSliderState()
        setContent { FrameOnlySlider(framePreview, sliderState) }

        runOnUiThread { sliderState.previewPositionRatio(0.5f) }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FAILED, useUnmergedTree = true).exists()
        }
        assertTrue(framePreview.framesAvailable, "a failed fetch should not collapse the frame slot")
    }

    @Test
    fun `failed fetch without load status falls back to time only`() = runAniComposeUiTest {
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = { null },
            debounceMillis = 0,
        )
        val sliderState = createSliderState()
        setContent { FrameOnlySlider(framePreview, sliderState) }

        runOnUiThread { sliderState.previewPositionRatio(0.5f) }
        waitUntil(timeoutMillis = 5_000) { !framePreview.framesAvailable }
        waitForIdle()
        onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP, useUnmergedTree = true).assertExists()
        assertFalse(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FAILED, useUnmergedTree = true).exists())
        assertEquals(FramePreviewLoadStatus.Idle, framePreview.loadStatus)
    }

    @Test
    fun `uncached position shows not downloaded when load status is reported`() = runAniComposeUiTest {
        var fetchCount = 0
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = {
                fetchCount++
                solidFrame(Color.Red)
            },
            debounceMillis = 0,
            reportsLoadStatus = true,
        )
        val uncachedInfo = MediaCacheProgressInfo(
            chunkWeights = floatListOf(1f),
            chunkStates = listOf(ChunkState.NONE),
        )
        val sliderState = createSliderState()
        setContent { FrameOnlySlider(framePreview, sliderState, uncachedInfo) }

        runOnUiThread { sliderState.previewPositionRatio(0.5f) }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_NOT_DOWNLOADED, useUnmergedTree = true).exists()
        }
        assertEquals(0, fetchCount, "fetchFrame should not be called for uncached position")
    }

    @Test
    fun `live frame is drawn instead of fetched frames and shows its status`() = runAniComposeUiTest {
        var fetchCount = 0
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = {
                fetchCount++
                null
            },
            debounceMillis = 0,
            reportsLoadStatus = true,
            // 播放器画进来的画面不受取帧能力影响 (TV 上这类媒体本来就不取帧)
            isSupported = { false },
        )
        framePreview.liveFrame = { modifier -> Box(modifier) { Box(Modifier.testTag("live-frame")) } }
        val sliderState = createSliderState()
        setContent { FrameOnlySlider(framePreview, sliderState) }

        runOnUiThread { sliderState.previewPositionRatio(0.5f) }
        waitUntil(timeoutMillis = 5_000) { onNodeWithTag("live-frame", useUnmergedTree = true).exists() }
        assertTrue(framePreview.framesAvailable)
        assertFalse(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_NOT_DOWNLOADED, useUnmergedTree = true).exists())

        runOnUiThread { framePreview.liveFrameStatus = FramePreviewLoadStatus.NotDownloaded }
        waitUntil(timeoutMillis = 5_000) {
            onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_NOT_DOWNLOADED, useUnmergedTree = true).exists()
        }
        runOnUiThread { framePreview.liveFrameStatus = FramePreviewLoadStatus.Loading }
        waitUntil(timeoutMillis = 5_000) { onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_LOADING, useUnmergedTree = true).exists() }
        assertEquals(0, fetchCount, "frames drawn by the player should not be fetched")
    }

    @Test
    fun `unsupported source shows time only without failure`() = runAniComposeUiTest {
        var fetchCount = 0
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = {
                fetchCount++
                null
            },
            debounceMillis = 0,
            reportsLoadStatus = true,
            isSupported = { false },
        )
        val sliderState = createSliderState()
        setContent { FrameOnlySlider(framePreview, sliderState) }

        runOnUiThread { sliderState.previewPositionRatio(0.5f) }
        waitUntil(timeoutMillis = 5_000) { !framePreview.framesAvailable }
        waitForIdle()
        onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP, useUnmergedTree = true).assertExists()
        assertFalse(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FAILED, useUnmergedTree = true).exists())
        assertFalse(onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_LOADING, useUnmergedTree = true).exists())
        assertEquals(0, fetchCount, "an unsupported source should not be asked for frames")
    }

    @Test
    fun `estimated load progress keeps rising but never completes`() {
        val state = MediaProgressFramePreviewState(fetchFrame = { null })
        // 基准耗时默认 3 秒: 到基准时 80%, 之后越走越慢
        assertEquals(0f, state.estimatedLoadProgress(0))
        assertEquals(0.8f, state.estimatedLoadProgress(3_000), 0.001f)
        val samples = listOf(500L, 1_500L, 3_000L, 6_000L, 15_000L, 30_000L).map { state.estimatedLoadProgress(it) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b > a }, "progress should keep rising: $samples")
        assertTrue(samples.all { it <= 0.99f }, "progress should never complete: $samples")
    }

    @Test
    fun `preview frame pixels convert to image bitmap`() {
        // 2x1: 左红右蓝
        val frame = PreviewFrame(
            positionMillis = 0,
            width = 2,
            height = 1,
            pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
        )
        val bitmap = frame.toImageBitmap()
        val pixels = bitmap.toPixelMap()
        assertEquals(Color(0xFFFF0000), pixels[0, 0])
        assertEquals(Color(0xFF0000FF), pixels[1, 0])
    }
}
