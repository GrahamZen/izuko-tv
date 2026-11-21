/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressSliderColors
import me.him188.ani.app.videoplayer.ui.progress.PlayerProgressSliderState

/** 原生自绘的 [TvProgressTrackView]: 位置、章节、缓存进度直接推给视图, 不经组合. */
@Composable
internal actual fun TvProgressTrackBar(
    state: PlayerProgressSliderState,
    cacheProgress: Flow<MediaCacheProgressInfo>?,
    colors: MediaProgressSliderColors,
    thumbVisible: Boolean,
    live: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val view = remember(context) { TvProgressTrackView(context) }
    AndroidView(
        factory = { view },
        modifier = modifier,
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
            snapshotFlow { tvChapterRatios(state) }
                .distinctUntilChanged { a, b -> a.contentEquals(b) }
                .collect { view.setChapterRatios(it) }
        }
    }
    if (cacheProgress != null) {
        LaunchedEffect(view, cacheProgress) {
            cacheProgress.collect { view.setCacheProgress(it) }
        }
    }
}
