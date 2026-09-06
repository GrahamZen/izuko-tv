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
import org.openani.mediamp.metadata.AudioTrack

@Immutable
class AudioPresentation(
    val audioTrack: AudioTrack,
    val displayName: String,
)

@Stable
val AudioTrack.audioName: String
    get() = name ?: labels.firstOrNull()?.value ?: internalId

/**
 * 片源里给这条音轨起了名字. 没起名时 [audioName] 只是播放器内部的轨道编号 (ExoPlayer 后端把编号填进 [AudioTrack.name]),
 * 界面上改写「音轨 N」.
 */
@Stable
val AudioTrack.isNamed: Boolean
    get() = labels.isNotEmpty() || (name != null && name != internalId)

