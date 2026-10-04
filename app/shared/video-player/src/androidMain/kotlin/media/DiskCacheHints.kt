/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 「边下边播」([PlaybackDiskCache]) 关着时, 认出开了就不用等的情形 ([events]): 跳到播过的位置之后要联网重新下载, 缓冲了 [WAIT_MILLIS] 还没好.
 * 页面据此提醒去设置里开 (ui-tv 的 `TvDiskCacheHint`).
 *
 * 「播过的位置」: 普通跳转比跳之前的位置早 [BACKWARD_MIN_MILLIS] 以上; 拖动预览 (见 [SeekPreview]) 比开始拖时的位置 ([previewOrigin]) 早.
 * 内存里还留着的那段跳过去马上就好, 不算. 只在 [isApplicable] (当前媒体开了就会存、剩余空间够) 时发.
 *
 * 作为 ExoPlayer 的监听在主线程上收回调; [scope] 也在主线程上.
 */
internal class DiskCacheHints(
    private val scope: CoroutineScope,
    private val playbackState: () -> Int,
    private val previewOrigin: () -> Long?,
    private val isApplicable: () -> Boolean,
) : Player.Listener {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 每认出一次发一个. */
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** 等最近那次跳到播过的位置缓冲好; 又跳了、缓冲好了都取消. */
    private var waiting: Job? = null

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) onSeek(oldPosition.positionMs, newPosition.positionMs)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_BUFFERING) waiting?.cancel()
    }

    /** 从 [fromMillis] 跳到了 [toMillis]. */
    fun onSeek(fromMillis: Long, toMillis: Long) {
        waiting?.cancel()
        val reference = previewOrigin() ?: fromMillis
        if (toMillis > reference - BACKWARD_MIN_MILLIS) return
        waiting = scope.launch {
            delay(WAIT_MILLIS)
            if (playbackState() == Player.STATE_BUFFERING && isApplicable()) {
                logger.info { "Still buffering ${WAIT_MILLIS}ms after seeking back to $toMillis ms; the playback disk cache would have it" }
                _events.tryEmit(Unit)
            }
        }
    }

    companion object {
        /** 跳过去缓冲这么久还没好才算要联网重新下载. */
        const val WAIT_MILLIS = 2_000L

        /** 往回跳这么多以上才算跳到播过的位置: 关键帧对齐、跳回原处附近这类小挪动不算. */
        const val BACKWARD_MIN_MILLIS = 3_000L

        private val logger = logger<DiskCacheHints>()
    }
}
