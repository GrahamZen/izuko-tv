/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlinx.coroutines.flow.Flow
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlayerState

/**
 * 界面显示用的播放器状态: [MediampPlayer.state] 经平台校正, 加载提示、小窗转圈与播放会话状态读它.
 *
 * iOS 上 AVPlayer 在 HLS 里拖动之后 `playbackBufferEmpty` 会卡在 true (同时还报缓冲已满、数据够播), mediamp 据此一直报在缓冲,
 * 而 AVPlayer 其实在播、时钟在走, 界面就一直挂着「缓冲中」. iOS 实现在 mediamp 报缓冲时看 AVPlayer 自己是不是在往前播, 是就不算缓冲.
 * 其他平台原样返回 [MediampPlayer.state].
 */
expect fun MediampPlayer.displayStateFlow(): Flow<PlayerState>

/** mediamp 报在缓冲、播放器自己却确实在往前播 ([advancing]) 时, 不算缓冲. */
internal fun correctBuffering(reported: PlayerState, advancing: Boolean): PlayerState =
    if (reported.isBuffering && advancing) reported.copy(isBuffering = false) else reported
