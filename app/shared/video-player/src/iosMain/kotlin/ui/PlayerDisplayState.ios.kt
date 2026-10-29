/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

package me.him188.ani.app.videoplayer.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlayerState
import org.openani.mediamp.avkit.AVKitMediampPlayer
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.currentItem
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.rate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.timebase
import platform.CoreMedia.CMTimebaseGetRate

/**
 * mediamp 报在缓冲时, 隔 [POLL_MILLIS] 看一次 AVPlayer 是不是在往前播 (在播且 item 时钟在走), 或暂停着而当前位置数据够播;
 * 不报缓冲时不看.
 */
actual fun MediampPlayer.displayStateFlow(): Flow<PlayerState> {
    val avPlayer = (this as? AVKitMediampPlayer)?.impl ?: return state
    return state.flatMapLatest { reported ->
        if (!reported.isBuffering) {
            flowOf(reported)
        } else {
            flow {
                while (true) {
                    emit(correctBuffering(reported, avPlayer.isAdvancing(), avPlayer.isPausedWithData()))
                    delay(POLL_MILLIS)
                }
            }.flowOn(Dispatchers.Main)
        }
    }.distinctUntilChanged()
}

private fun AVPlayer.isAdvancing(): Boolean =
    timeControlStatus == AVPlayerTimeControlStatusPlaying && rate > 0f &&
        (currentItem?.timebase?.let { CMTimebaseGetRate(it) } ?: 0.0) > 0.0

private fun AVPlayer.isPausedWithData(): Boolean {
    val item = currentItem ?: return false
    return timeControlStatus == AVPlayerTimeControlStatusPaused &&
        item.status == AVPlayerItemStatusReadyToPlay && item.playbackLikelyToKeepUp
}

private const val POLL_MILLIS = 250L
