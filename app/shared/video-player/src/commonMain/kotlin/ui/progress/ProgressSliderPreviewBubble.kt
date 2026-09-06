/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag

/**
 * 拖动预览时圆点上方的浮窗 (目标时间, 有预览画面时连同画面), 外观与 [MediaProgressSlider] 圆点上的浮窗相同,
 * 只是不自带弹出窗口: 由调用方摆进自己的窗口 (电视的进度条, 见 ui-tv 的 TvProgressTrack).
 * 弹出窗口每挪一步都要由系统搬一次窗口、再在主线程上把它重排重画一遍, 慢电视上一步要几十毫秒.
 */
@Composable
fun ProgressSliderPreviewBubble(
    state: PlayerProgressSliderState,
    framePreview: MediaProgressFramePreviewState?,
    colors: MediaProgressSliderColors,
    previewStyle: ProgressSliderPreviewStyle,
    modifier: Modifier = Modifier,
) {
    val text by remember(state) {
        derivedStateOf { renderPreviewTime(state, (state.totalDurationMillis * state.displayPositionRatio).toLong()) }
    }
    val showFrame = framePreview?.framesAvailable == true
    val frameOnly = showFrame && previewStyle == ProgressSliderPreviewStyle.FrameOnly
    Box(
        modifier
            .testTag(TAG_PROGRESS_SLIDER_PREVIEW_POPUP)
            .clip(previewPopupShape(showFrame))
            .background(popupBackgroundColor(frameOnly, colors))
            .animateContentSize(),
    ) {
        Box(Modifier.padding(popupContentPadding(frameOnly)), contentAlignment = Alignment.Center) {
            ProgressSliderPreviewContent(
                framePreview = framePreview,
                text = text,
                previewTimeTextColor = colors.previewTimeTextColor,
                showFrame = showFrame,
                frameOnly = frameOnly,
            )
        }
    }
}

/** 浮窗里的时间: 落在章节里时上面多一行章节名. 与 [MediaProgressSlider] 圆点浮窗的写法一致. */
private fun renderPreviewTime(state: PlayerProgressSliderState, previewTimeMillis: Long): String {
    val time = renderSeconds(previewTimeMillis / 1000, state.totalDurationMillis / 1000).substringBefore(" ")
    val chapter = state.chapters.find { previewTimeMillis in it.offsetMillis..<it.offsetMillis + it.durationMillis }
        ?: return time
    return if (chapter.name.isBlank()) time else chapter.name + "\n" + time
}
