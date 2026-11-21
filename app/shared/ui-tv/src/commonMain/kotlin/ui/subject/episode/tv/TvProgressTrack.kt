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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressFramePreviewState
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressSliderColors
import me.him188.ani.app.videoplayer.ui.progress.PlayerProgressSliderState
import me.him188.ani.app.videoplayer.ui.progress.ProgressSliderPreviewBubble
import me.him188.ani.app.videoplayer.ui.progress.ProgressSliderPreviewStyle
import kotlin.math.roundToInt

/**
 * 播放器进度条行中间的那一段: 进度条本体 ([TvProgressTrackBar]: 安卓是原生自绘视图, iOS 是 Canvas) + 拖动预览时圆点上方的浮窗.
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
    Box(modifier.fillMaxWidth().height(TV_PROGRESS_TRACK_HEIGHT_DP.dp)) {
        TvProgressTrackBar(state, cacheProgress, colors, thumbVisible, live, Modifier.matchParentSize())
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
    val windowWidth = LocalWindowInfo.current.containerSize.width
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
                    val maxWindowX = (windowWidth - bubble.width).coerceAtLeast(0).toFloat()
                    val windowX = (origin.x + anchorX - bubble.width / 2f).coerceIn(0f, maxWindowX)
                    bubble.place((windowX - origin.x).roundToInt(), -gap - bubble.height)
                }
            },
    )
}

/** 章节起止点在进度条上的比例 (起点; 终点不是另一章起点时也画). */
internal fun tvChapterRatios(state: PlayerProgressSliderState): FloatArray {
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

/** 进度条这一行的高度 (轨道 6dp 居中, 圆点半径 8dp). */
internal const val TV_PROGRESS_TRACK_HEIGHT_DP = 24

/**
 * 进度条本体: 底轨、缓存段 (下载中 / 已缓存 / 不可用)、已播放段、章节点、圆点. 不接焦点也不接触摸.
 * 安卓是原生自绘视图 (遥控器拖动时每挪一步只重画自己, 不经组合), iOS 用 Canvas 画同样的东西.
 * [live] 为 false 时停在最后一次的位置; [cacheProgress] 为 null 时不画缓存段.
 */
@Composable
internal expect fun TvProgressTrackBar(
    state: PlayerProgressSliderState,
    cacheProgress: Flow<MediaCacheProgressInfo>?,
    colors: MediaProgressSliderColors,
    thumbVisible: Boolean,
    live: Boolean,
    modifier: Modifier,
)
