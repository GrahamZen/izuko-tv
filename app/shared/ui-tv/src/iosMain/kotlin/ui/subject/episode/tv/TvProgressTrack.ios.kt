/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.domain.media.player.ChunkState
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressSliderColors
import me.him188.ani.app.videoplayer.ui.progress.PlayerProgressSliderState

/**
 * Canvas 画的进度条, 几何同安卓的原生视图 (TvProgressTrackView): 轨道高 6dp、两端各收 2dp、两头全圆;
 * 圆点占位宽 12dp、半径 8dp; 章节点半径 2dp; 缓存段连续同状态的连着画, 不在区块之间留缝.
 * 位置与章节在绘制阶段读, 挪一步只重画这一块. [live] 为 false 时停在最后一次的位置.
 */
@Composable
internal actual fun TvProgressTrackBar(
    state: PlayerProgressSliderState,
    cacheProgress: Flow<MediaCacheProgressInfo>?,
    colors: MediaProgressSliderColors,
    thumbVisible: Boolean,
    live: Boolean,
    modifier: Modifier,
) {
    val cache by remember(cacheProgress) { cacheProgress ?: flowOf(null) }.collectAsState(null)
    // 不跟播放位置时停在最后一次画的位置
    val frozen = remember { FrozenTrackPositions() }
    Canvas(modifier) {
        val positions = if (live) {
            TrackPositions(state.trackPositionRatio, state.displayPositionRatio, tvChapterRatios(state)).also { frozen.last = it }
        } else {
            frozen.last
        }
        drawTrack(positions, cache, colors, thumbVisible)
    }
}

private class TrackPositions(val play: Float, val thumb: Float, val chapters: FloatArray)

private class FrozenTrackPositions {
    var last = TrackPositions(0f, 0f, FloatArray(0))
}

private fun DrawScope.drawTrack(
    positions: TrackPositions,
    cache: MediaCacheProgressInfo?,
    colors: MediaProgressSliderColors,
    thumbVisible: Boolean,
) {
    val trackHeight = 6.dp.toPx()
    val trackInset = 2.dp.toPx()
    val thumbSlotWidth = 12.dp.toPx()
    val thumbRadius = 8.dp.toPx()
    val chapterRadius = 2.dp.toPx()
    val top = (size.height - trackHeight) / 2
    val left = trackInset
    val trackWidth = size.width - trackInset * 2
    if (trackWidth > 0f) {
        val clip = Path().apply {
            addRoundRect(
                RoundRect(
                    left, top, left + trackWidth, top + trackHeight,
                    CornerRadius(trackHeight / 2, trackHeight / 2),
                ),
            )
        }
        clipPath(clip) {
            drawRect(colors.trackBackgroundColor, Offset(left, top), Size(trackWidth, trackHeight))
            if (cache != null && !cache.isEmpty()) {
                var x = left
                var runState = cache.chunkStates[0]
                var runWeight = 0f
                for (i in 0..cache.lastIndex) {
                    val chunk = cache.chunkStates[i]
                    if (chunk != runState) {
                        x = drawChunkRun(x, top, trackHeight, runState, runWeight * trackWidth, colors)
                        runState = chunk
                        runWeight = 0f
                    }
                    runWeight += cache.chunkWeights[i]
                }
                drawChunkRun(x, top, trackHeight, runState, runWeight * trackWidth, colors)
            }
            drawRect(colors.trackProgressColor, Offset(left, top), Size(trackWidth * positions.play, trackHeight))
            for (ratio in positions.chapters) {
                drawCircle(colors.chapterColor, chapterRadius, Offset(left + trackWidth * ratio, top + trackHeight / 2))
            }
        }
    }
    if (thumbVisible) {
        drawCircle(colors.thumbColor, thumbRadius, Offset(thumbSlotWidth / 2 + (size.width - thumbSlotWidth) * positions.thumb, size.height / 2))
    }
}

private fun DrawScope.drawChunkRun(
    x: Float,
    top: Float,
    height: Float,
    state: ChunkState,
    width: Float,
    colors: MediaProgressSliderColors,
): Float {
    val color: Color? = when (state) {
        ChunkState.NONE -> null
        ChunkState.DOWNLOADING -> colors.downloadingColor
        ChunkState.DONE -> colors.cachedProgressColor
        ChunkState.NOT_AVAILABLE -> colors.notAvailableColor
    }
    if (color != null) drawRect(color, Offset(x, top), Size(width, height))
    return x + width
}
