/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.cancel
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.metadata.TrackGroup

/**
 * 字幕控件的 [SubtitleTrackState], 控件离开组合时停掉它的后台收集.
 *
 * [SubtitleTrackState] 一直把播放器纠正回「用户在这个控件里选的那条」, 而 remember 不会替它收尾 (只有 onCleared 才取消作用域):
 * 控件移出组合后旧实例还活着, 控件再建出来、用户换一条字幕时, 新旧实例各按自己记的那条去改; 之后换片源、切集载入新轨道时,
 * 旧实例照样把字幕改回它记的那条, 还被记成用户选的. 电视上焦点去胶囊行 / 选源面板时图标行就会移出组合.
 */
@Composable
internal fun rememberSubtitleTrackState(playerState: TrackGroup<SubtitleTrack>): SubtitleTrackState {
    val state = remember(playerState) {
        SubtitleTrackState(playerState.selected, playerState.candidates) { playerState.select(it) }
    }
    DisposableEffect(state) {
        onDispose { state.backgroundScope.cancel() }
    }
    return state
}
