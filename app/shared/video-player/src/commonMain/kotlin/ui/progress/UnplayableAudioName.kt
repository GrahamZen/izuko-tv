/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.progress

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_audio_track_unplayable
import me.him188.ani.app.ui.lang.video_player_audio_track_unplayable_format
import org.jetbrains.compose.resources.stringResource

/** 设备放不了的音轨在列表里的透明度: 还看得清, 但一眼能和能放的分开. */
private const val UNPLAYABLE_ALPHA = 0.5f

/**
 * 音轨列表里的一项: [unplayableFormat] 不为 null 时是设备放不了的音轨, 名字后面注明 (带上格式名, 认不出为空串) 并压暗.
 */
@Composable
internal fun UnplayableAwareAudioName(name: String, unplayableFormat: String?) {
    if (unplayableFormat == null) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val text = if (unplayableFormat.isEmpty()) {
        stringResource(Lang.video_player_audio_track_unplayable, name)
    } else {
        stringResource(Lang.video_player_audio_track_unplayable_format, name, unplayableFormat)
    }
    Text(text, Modifier.alpha(UNPLAYABLE_ALPHA), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
