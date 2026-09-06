/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.openani.mediamp.metadata.SubtitleTrack

@Immutable
class SubtitlePresentation(
    val subtitleTrack: SubtitleTrack,
    val displayName: String,
)

@Stable
val SubtitleTrack.subtitleLanguage: String
    get() = language ?: labels.firstOrNull()?.value ?: internalId

/**
 * 片源里给这条字幕起了名字. 没起名时 [subtitleLanguage] 只是播放器内部的轨道编号 (ExoPlayer 后端把编号填进
 * [SubtitleTrack.language]), 界面上改写「字幕 N」.
 */
@Stable
val SubtitleTrack.isNamed: Boolean
    get() = labels.isNotEmpty() || (language != null && language != internalId)

