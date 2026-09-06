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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import kotlinx.coroutines.awaitCancellation
import me.him188.ani.app.domain.media.player.ChunkState
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.ui.framework.exists
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 手动检查预览浮窗渲染: 输出 PNG 供人工/agent 检查布局 (图片应完整在气泡内).
 */
@OptIn(ExperimentalTestApi::class)
class PreviewPopupScreenshotTest {

    private fun solidFrame(color: Color, width: Int = 160, height: Int = 90): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.color = color })
        return bitmap
    }

    @Test
    fun `dump popup rendering`() = runAniComposeUiTest {
        val framePreview = MediaProgressFramePreviewState(
            fetchFrame = { solidFrame(Color.Green) },
            debounceMillis = 0,
        )
        setContent {
            MediaProgressSlider(
                PlayerProgressSliderState(
                    currentPositionMillis = { 30_000L },
                    totalDurationMillis = { 100_000L },
                    chapters = { emptyList() },
                    onPreview = {},
                    onPreviewFinished = {},
                ),
                cacheProgressInfoFlow = { null },
                framePreview = framePreview,
            )
        }
        waitForIdle()
        runOnUiThread {
            onNodeWithTag(TAG_PROGRESS_SLIDER).performMouseInput { moveTo(center) }
        }
        runOnIdle {
            waitUntil(timeoutMillis = 5_000) {
                onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true).exists()
            }
        }
        // 等 animateContentSize 完成
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val popupNode = onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP, useUnmergedTree = true)
        val image = popupNode.captureToImage()
        val out = File(System.getProperty("java.io.tmpdir"), "preview-popup.png")
        ImageIO.write(image.toAwtImage(), "png", out)
        println("POPUP_PNG=${out.absolutePath} size=${image.width}x${image.height}")

        // 图片必须完整落在浮窗内 (回归: clip 形状错误曾把图片顶部裁出气泡).
        val popupBounds = popupNode.fetchSemanticsNode().boundsInWindow
        val frameBounds = onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_FRAME, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        assertTrue(
            frameBounds.top >= popupBounds.top && frameBounds.bottom <= popupBounds.bottom &&
                frameBounds.left >= popupBounds.left && frameBounds.right <= popupBounds.right,
            "frame $frameBounds must be inside popup $popupBounds",
        )
    }

    @Test
    fun `dump frame-only popup load states`() {
        dumpFrameOnlyPopup(
            name = "loading",
            framePreview = loadStatusPreview { awaitCancellation() },
            stateTag = TAG_PROGRESS_SLIDER_PREVIEW_LOADING,
        )
        var fetches = 0
        dumpFrameOnlyPopup(
            name = "loading-previous-frame",
            framePreview = loadStatusPreview { if (fetches++ == 0) solidFrame(Color.Green) else awaitCancellation() },
            stateTag = TAG_PROGRESS_SLIDER_PREVIEW_LOADING,
            previewAt = listOf(0.5f, 0.8f),
        )
        dumpFrameOnlyPopup(
            name = "failed",
            framePreview = loadStatusPreview { null },
            stateTag = TAG_PROGRESS_SLIDER_PREVIEW_FAILED,
        )
        dumpFrameOnlyPopup(
            name = "not-downloaded",
            framePreview = loadStatusPreview { solidFrame(Color.Red) },
            stateTag = TAG_PROGRESS_SLIDER_PREVIEW_NOT_DOWNLOADED,
            cacheProgressInfo = MediaCacheProgressInfo(
                chunkWeights = floatListOf(1f),
                chunkStates = listOf(ChunkState.NONE),
            ),
        )
    }

    private fun loadStatusPreview(fetchFrame: suspend (Long) -> ImageBitmap?) = MediaProgressFramePreviewState(
        fetchFrame = fetchFrame,
        debounceMillis = 0,
        reportsLoadStatus = true,
    )

    /**
     * 圆点依次挪到 [previewAt] 各处 (TV 的拖拽预览, 不用鼠标悬停: 小窗口里悬停浮窗会盖住指针, 悬停态来回翻转),
     * 最后一处出现 [stateTag] 后把浮窗截成 PNG. 前面几处要等帧出来再挪, 用来造出「上一个位置的帧」.
     */
    private fun dumpFrameOnlyPopup(
        name: String,
        framePreview: MediaProgressFramePreviewState,
        stateTag: String,
        cacheProgressInfo: MediaCacheProgressInfo? = null,
        previewAt: List<Float> = listOf(0.5f),
    ) = runAniComposeUiTest {
        val sliderState = PlayerProgressSliderState(
            currentPositionMillis = { 30_000L },
            totalDurationMillis = { 100_000L },
            chapters = { emptyList() },
            onPreview = {},
            onPreviewFinished = {},
        )
        setContent {
            MediaProgressSlider(
                sliderState,
                cacheProgressInfoFlow = { cacheProgressInfo },
                framePreview = framePreview,
                previewStyle = ProgressSliderPreviewStyle.FrameOnly,
            )
        }
        previewAt.forEachIndexed { index, ratio ->
            runOnUiThread { sliderState.previewPositionRatio(ratio) }
            if (index < previewAt.lastIndex) {
                waitUntil(timeoutMillis = 5_000) {
                    framePreview.loadStatus == FramePreviewLoadStatus.Idle && framePreview.frame != null
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onNodeWithTag(stateTag, useUnmergedTree = true).exists() }
        // 进度按真实时间估算: 等一秒让进度环走出一截; 再推进时钟跑完 animateContentSize 与进度刷新
        Thread.sleep(1_000)
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val image = onNodeWithTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP, useUnmergedTree = true).captureToImage()
        val out = File(System.getProperty("java.io.tmpdir"), "preview-popup-$name.png")
        ImageIO.write(image.toAwtImage(), "png", out)
        println("POPUP_PNG=${out.absolutePath} size=${image.width}x${image.height}")
    }
}
