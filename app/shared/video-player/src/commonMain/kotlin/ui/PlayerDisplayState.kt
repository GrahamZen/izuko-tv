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
 * 而 AVPlayer 其实在播、时钟在走, 或者暂停着、当前位置的数据已经够播, 界面就一直挂着「缓冲中」.
 * iOS 实现在 mediamp 报缓冲时看 AVPlayer 自己的状态, 在往前播或暂停着数据够播都不算缓冲. 其他平台原样返回 [MediampPlayer.state].
 */
expect fun MediampPlayer.displayStateFlow(): Flow<PlayerState>

/**
 * mediamp 报在缓冲时按播放器自己的状态校正: 确实在往前播 ([advancing]), 或者暂停着 (`playWhenReady` 为 false)
 * 且当前位置的数据够播 ([pausedWithData]), 都不算缓冲.
 */
internal fun correctBuffering(reported: PlayerState, advancing: Boolean, pausedWithData: Boolean = false): PlayerState {
    if (!reported.isBuffering) return reported
    val notWaiting = advancing || (!reported.playWhenReady && pausedWithData)
    return if (notWaiting) reported.copy(isBuffering = false) else reported
}
