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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import me.him188.ani.app.data.models.preference.seekPreviewOnFullScreen
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressFramePreviewState
import me.him188.ani.app.videoplayer.ui.progress.PlayerSeekPreviewState
import me.him188.ani.app.videoplayer.ui.progress.SeekPreviewFullScreenLayer
import me.him188.ani.app.videoplayer.ui.progress.rememberPlayerSeekPreviewState

@Composable
internal actual fun rememberTvSeekPreview(vm: EpisodeViewModel, enabled: Boolean): TvSeekPreview? {
    if (!enabled) return null
    val state = rememberPlayerSeekPreviewState(vm.player, vm.cacheProgressInfoFlow)
    return remember(state, vm) { PlayerTvSeekPreview(state, vm) }
}

/** 主播放器解预览画面 ([PlayerSeekPreviewState]); 画不画在全屏上每次进预览时按设置与隧道播放现算. */
private class PlayerTvSeekPreview(private val state: PlayerSeekPreviewState, private val vm: EpisodeViewModel) : TvSeekPreview {
    override val framePreview: MediaProgressFramePreviewState get() = state.framePreview
    override val holdsFrame: Boolean get() = state.holdsFrame
    override val hidesFullScreenExtras: Boolean get() = state.hidesFullScreenExtras
    override fun begin() = state.begin(onFullScreen = vm.videoScaffoldConfig.seekPreviewOnFullScreen)
    override fun seekTo(positionMillis: Long) = state.seekTo(positionMillis)
    override fun commit(positionMillis: Long) = state.commit(positionMillis)
    override fun cancel() = state.cancel()

    @Composable
    override fun FullScreenLayer(modifier: Modifier) {
        SeekPreviewFullScreenLayer(state, modifier)
    }
}
