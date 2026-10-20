/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.video.loading

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.him188.ani.app.domain.player.UnsupportedFormat
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_video_loading_unsupported_audio
import me.him188.ani.app.ui.lang.subject_episode_video_loading_unsupported_video
import org.jetbrains.compose.resources.stringResource
import org.openani.mediamp.MediaStatus

/** 播放器报错时是不是设备放不了的格式 (见 [UnsupportedFormat]); 错误换了才重新认. */
@Composable
internal fun unsupportedFormatOf(status: MediaStatus): UnsupportedFormat? {
    val error = (status as? MediaStatus.Error)?.error
    return remember(error) { UnsupportedFormat.of(error) }
}

/**
 * 播放器报错时认得出的原因 (连不上网、服务器回了 HTTP 错误、地址回的不是视频…, 见 [knownCauseOf]), 提示里代替笼统的「请更换数据源」;
 * 没报错或认不出为 null. 播到一半链接过期 (403)、断网都在这里说清.
 */
@Composable
internal fun playerErrorCauseOf(status: MediaStatus): String? {
    val error = (status as? MediaStatus.Error)?.error ?: return null
    val labels = videoLoadingCauseLabels()
    return remember(error, labels) { knownCauseOf(error, labels) }
}

/** 播放器报错时代替「播放失败」: 这台电视放不了这个视频的音频 / 画面格式. */
@Composable
internal fun unsupportedFormatError(format: UnsupportedFormat): String =
    if (format.isAudio) {
        stringResource(Lang.subject_episode_video_loading_unsupported_audio, format.name)
    } else {
        stringResource(Lang.subject_episode_video_loading_unsupported_video, format.name)
    }
