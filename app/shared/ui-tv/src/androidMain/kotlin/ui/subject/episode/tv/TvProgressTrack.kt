/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressFramePreviewState
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressSliderColors
import me.him188.ani.app.videoplayer.ui.progress.PlayerProgressSliderState
import me.him188.ani.app.videoplayer.ui.progress.ProgressSliderPreviewBubble
import me.him188.ani.app.videoplayer.ui.progress.ProgressSliderPreviewStyle
import kotlin.math.roundToInt

/**
 * 播放器进度条行中间的那一段: 原生自绘的进度条 ([TvProgressTrackView]) + 拖动预览时圆点上方的浮窗.
 *
 * 遥控器拖动时每挪一步, 进度条只重画自己 (位置直接推给视图, 不经组合); 浮窗画在本窗口里, 位置读在摆放阶段,
 * 挪一步只重新摆放, 不像弹出窗口那样要系统搬窗口、主线程再把它重排重画一遍.
 *
 * @param cacheProgress 缓存进度; null = 不订阅 (控制层不可见时, 下载期间它刷得很勤)
 * @param live 为 false 时进度条停在最后一次的位置 (控制层淡出途中冻在离开那一刻, 不可见时不跟着播放位置重画)
 * @param framePreview 拖动预览的画面来源; null = 浮窗只有时间
 */
@Composable
internal fun TvProgressTrack(
    state: PlayerProgressSliderState,
    cacheProgress: Flow<MediaCacheProgressInfo>?,
    colors: MediaProgressSliderColors,
    thumbVisible: Boolean,
    live: Boolean,
    framePreview: MediaProgressFramePreviewState?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = remember(context) { TvProgressTrackView(context) }
    Box(modifier.fillMaxWidth().height(TvProgressTrackView.HEIGHT_DP.dp)) {
        AndroidView(
            factory = { view },
            modifier = Modifier.matchParentSize(),
            update = {
                it.setColors(
                    trackBackground = colors.trackBackgroundColor.toArgb(),
                    trackProgress = colors.trackProgressColor.toArgb(),
                    cached = colors.cachedProgressColor.toArgb(),
                    downloading = colors.downloadingColor.toArgb(),
                    notAvailable = colors.notAvailableColor.toArgb(),
                    chapter = colors.chapterColor.toArgb(),
                    thumb = colors.thumbColor.toArgb(),
                )
                it.setThumbVisible(thumbVisible)
            },
        )
        if (live) {
            LaunchedEffect(view, state) {
                snapshotFlow { state.trackPositionRatio to state.displayPositionRatio }
                    .collect { (play, thumb) -> view.setPositions(play, thumb) }
            }
            LaunchedEffect(view, state) {
                snapshotFlow { chapterRatios(state) }
                    .distinctUntilChanged { a, b -> a.contentEquals(b) }
                    .collect { view.setChapterRatios(it) }
            }
        }
        if (cacheProgress != null) {
            LaunchedEffect(view, cacheProgress) {
                cacheProgress.collect { view.setCacheProgress(it) }
            }
        }
        if (state.isPreviewing) {
            TvProgressPreviewBubble(state, framePreview, colors)
        }
    }
}

/**
 * 圆点上方的浮窗, 摆法与原来那个弹出窗口相同: 底边在进度条上方 8dp, 水平居中在圆点占位的左缘, 左右不出窗口.
 * 位置读在摆放阶段: 挪一步只重新摆放这一块.
 */
@Composable
private fun BoxScope.TvProgressPreviewBubble(
    state: PlayerProgressSliderState,
    framePreview: MediaProgressFramePreviewState?,
    colors: MediaProgressSliderColors,
) {
    val rootView = LocalView.current.rootView
    ProgressSliderPreviewBubble(
        state = state,
        framePreview = framePreview,
        colors = colors,
        // 只有画面, 时间叠在画面底部居中 (Prime 风格): 卡片样式那一圈底色 + 帧下方另占一行的文字, 在电视上比画面本身还显眼
        previewStyle = ProgressSliderPreviewStyle.FrameOnly,
        modifier = Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val bubble = measurable.measure(Constraints())
                val gap = 8.dp.roundToPx()
                val thumbSlot = 12.dp.toPx()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val anchorX = (constraints.maxWidth - thumbSlot) * state.displayPositionRatio
                    val origin = coordinates?.positionInWindow() ?: Offset.Zero
                    val maxWindowX = (rootView.width - bubble.width).coerceAtLeast(0).toFloat()
                    val windowX = (origin.x + anchorX - bubble.width / 2f).coerceIn(0f, maxWindowX)
                    bubble.place((windowX - origin.x).roundToInt(), -gap - bubble.height)
                }
            },
    )
}

/** 章节起止点在进度条上的比例 (起点; 终点不是另一章起点时也画). */
private fun chapterRatios(state: PlayerProgressSliderState): FloatArray {
    val total = state.totalDurationMillis
    if (total <= 0L) return FloatArray(0)
    val chapters = state.chapters
    val points = ArrayList<Float>(chapters.size * 2)
    for (chapter in chapters) {
        points += chapter.offsetMillis.toFloat() / total
        val end = chapter.offsetMillis + chapter.durationMillis
        if (chapters.none { it.offsetMillis == end }) points += end.toFloat() / total
    }
    return points.toFloatArray()
}
