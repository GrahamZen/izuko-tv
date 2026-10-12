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
import kotlinx.coroutines.flow.filterNotNull
import me.him188.ani.app.domain.player.tracks.AudioTrackSupport
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_audio_all_unplayable_toast
import me.him188.ani.app.ui.lang.video_player_audio_all_unplayable_toast_unknown
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.MediampPlayer

/**
 * 当前视频的音轨这台电视一条也放不了 (见 [AudioTrackSupport.allAudioUnplayable]) 时弹一次提示: 播放器照常放画面、不报错,
 * 不提示的话用户只会觉得没声音. 不自动换源, 让用户自己挑.
 */
@Composable
internal fun TvUnplayableAudioNotice(player: MediampPlayer) {
    val support = player as? AudioTrackSupport ?: return
    val toaster = LocalToaster.current
    val withFormats = stringResource(Lang.video_player_audio_all_unplayable_toast)
    val unknown = stringResource(Lang.video_player_audio_all_unplayable_toast_unknown)
    LaunchedEffect(support) {
        support.allAudioUnplayable.filterNotNull().collect { formats ->
            toaster.toast(if (formats.isEmpty()) unknown else withFormats.format(formats.joinToString(" / ")))
        }
    }
}
