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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import me.him188.ani.app.domain.player.tracks.AudioTrackSupport
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_audio_track_unplayable_toast
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults
import me.him188.ani.app.videoplayer.ui.progress.AudioSwitcher
import me.him188.ani.app.videoplayer.ui.progress.AudioTrackState
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.TrackGroup

/**
 * 播放器底栏的「音轨」按钮. 设备放不了的音轨 (见 [AudioTrackSupport]) 在列表里注明, 选它时只弹提示、不切过去:
 * 切过去播放器就报错, 自动换源会把整个资源换掉.
 */
@Composable
internal fun TvAudioSwitcher(
    tracks: TrackGroup<AudioTrack>,
    support: AudioTrackSupport?,
    modifier: Modifier = Modifier,
    onExpandedChanged: (expanded: Boolean) -> Unit = {},
) {
    val state = remember(tracks) { AudioTrackState(tracks.selected, tracks.candidates) }
    val unplayableFlow = remember(support) { support?.unplayableAudioTracks ?: MutableStateFlow(emptyMap()) }
    val unplayable by unplayableFlow.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val rejected = stringResource(Lang.video_player_audio_track_unplayable_toast)
    PlayerControllerDefaults.AudioSwitcher(
        state,
        onSelect = { track ->
            if (track != null && track.id in unplayable) toaster.toast(rejected) else tracks.select(track)
        },
        modifier = modifier,
        onExpandedChanged = onExpandedChanged,
        unplayableFormatOf = { unplayable[it.id] },
    )
}
