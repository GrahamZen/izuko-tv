/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import org.openani.mediamp.MediampPlayer


/**
 * Displays a video player itself. There is no control bar or any other UI elements.
 *
 * The size of the video player is undefined by default. It may take the entire screen or vise versa.
 * Please apply a size [Modifier] to control the size of the video player.
 */
/**
 * 画面上被播放器控件挡住的那一块的上沿 (window 坐标, 像素), 没有挡住时为 null. [VideoPlayer] 把播放器自己画的字幕
 * (SRT / WebVTT 这类没指定位置的) 挪到它上面, 控件收起时回到原位; 画进画面里的 ASS 不受影响. 在 snapshotFlow 里读.
 */
val LocalSubtitleObstructionTop: ProvidableCompositionLocal<() -> Float?> = staticCompositionLocalOf { { null } }

@Composable
expect fun VideoPlayer(
    player: MediampPlayer,
    modifier: Modifier,
)
