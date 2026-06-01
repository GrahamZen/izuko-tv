/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionStatus
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.playback_session_now_playing
import me.him188.ani.app.ui.lang.playback_session_status_needs_selection
import me.him188.ani.app.ui.lang.playback_session_status_no_media
import me.him188.ani.app.ui.lang.playback_session_status_player_error
import me.him188.ani.app.ui.lang.playback_session_status_preparing
import me.him188.ani.app.ui.lang.subject_episode_video_loading_buffering
import me.him188.ani.app.ui.lang.subject_episode_video_loading_failed_prefix
import me.him188.ani.app.ui.subject.episode.video.loading.renderCause
import me.him188.ani.app.ui.subject.episode.video.loading.videoLoadingCauseLabels
import org.jetbrains.compose.resources.stringResource

/** [PlaybackSessionStatusText] 的轻重, 决定入口面板上那行字用什么颜色. */
enum class PlaybackSessionStatusSeverity {
    /** 一切正常 (在准备 / 在缓冲 / 可以看了): 次要色, 不抢眼. */
    Normal,

    /** 要用户回去做点什么 (手选数据源) 才会继续. */
    Attention,

    /** 出错了, 再等也不会自己好. */
    Error,
}

/** 后台会话状态的成品文案 + 轻重. */
@Immutable
data class PlaybackSessionStatusText(
    val text: String,
    val severity: PlaybackSessionStatusSeverity,
)

/**
 * 把 [PlaybackSessionStatus] 翻成入口面板上那行字.
 *
 * 放在本模块而不是 ui-tv: 失败原因用的是播放画面上那套 ([renderCause]), 它是本模块 internal 的 ——
 * 同一件事在画面上和面板上说法一致, 也不必在两处各维护一份 `when` (与 [RetainedPlaybackNoticeTexts]
 * 同一个理由).
 *
 * `null` (没有会话) 与 [PlaybackSessionStatus.Ready] 都落到"正在播放": 没什么要报告的时候, 这行
 * 就是卡片原来那句标题. 用户此时要的信息是进度条上那些, 不是一句"已就绪".
 */
@Composable
fun playbackSessionStatusText(status: PlaybackSessionStatus?): PlaybackSessionStatusText = when (status) {
    null, PlaybackSessionStatus.Ready -> PlaybackSessionStatusText(
        stringResource(Lang.playback_session_now_playing),
        PlaybackSessionStatusSeverity.Normal,
    )

    PlaybackSessionStatus.Preparing -> PlaybackSessionStatusText(
        stringResource(Lang.playback_session_status_preparing),
        PlaybackSessionStatusSeverity.Normal,
    )

    PlaybackSessionStatus.Buffering -> PlaybackSessionStatusText(
        stringResource(Lang.subject_episode_video_loading_buffering),
        PlaybackSessionStatusSeverity.Normal,
    )

    PlaybackSessionStatus.NeedsSelection -> PlaybackSessionStatusText(
        stringResource(Lang.playback_session_status_needs_selection),
        PlaybackSessionStatusSeverity.Attention,
    )

    PlaybackSessionStatus.NoMedia -> PlaybackSessionStatusText(
        stringResource(Lang.playback_session_status_no_media),
        PlaybackSessionStatusSeverity.Error,
    )

    PlaybackSessionStatus.PlayerError -> PlaybackSessionStatusText(
        stringResource(Lang.playback_session_status_player_error),
        PlaybackSessionStatusSeverity.Error,
    )

    is PlaybackSessionStatus.LoadFailed -> PlaybackSessionStatusText(
        stringResource(Lang.subject_episode_video_loading_failed_prefix) +
                renderCause(status.cause, videoLoadingCauseLabels()),
        PlaybackSessionStatusSeverity.Error,
    )
}
