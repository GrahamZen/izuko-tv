/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlayerState
import kotlin.time.TimeSource

/**
 * 拖动 (seek) 已经发出、播放器还没在新位置播起来的这段时间为 `true`. 缓冲提示把它当作在缓冲.
 *
 * AVPlayer 拖动时 `playbackBufferEmpty` / `playbackLikelyToKeepUp` 常常不变, mediamp 报的 [PlayerState.isBuffering] 一直是 `false`:
 * 画面停在原处又不转圈, 看着像卡住. 这里照 mediamp 状态机的行为判断: 拖动时它把 [MediampPlayer.currentPositionMillis] 直接设成目标、
 * 落定之前不再更新, 落定后发 [PlaybackEvent.SeekCompleted].
 * - 开始: 位置比照常播放 (最快 [MAX_PLAYBACK_SPEED] 倍速) 能走到的多出 [SEEK_JUMP_MILLIS] 以上, 或倒退超过 [SEEK_JUMP_MILLIS]
 * - 结束: [PlaybackEvent.SeekCompleted]、位置又往前走了一小步, 或媒体状态变了 (换片 / 出错 / 播完)
 *
 * ExoPlayer 拖动时自己就报缓冲, 两边同时为 `true` 也只是同一个提示.
 */
fun MediampPlayer.seekPendingFlow(timeSource: TimeSource = TimeSource.Monotonic): Flow<Boolean> = channelFlow {
    val pending = MutableStateFlow(false)
    launch {
        events.collect { if (it is PlaybackEvent.SeekCompleted) pending.value = false }
    }
    launch {
        state.map { it.mediaStatus }.distinctUntilChanged().collect { pending.value = false }
    }
    launch {
        var last: Long? = null
        var lastAt = timeSource.markNow()
        currentPositionMillis.collect { position ->
            val previous = last
            val elapsedMillis = lastAt.elapsedNow().inWholeMilliseconds
            last = position
            lastAt = timeSource.markNow()
            if (previous == null) return@collect
            val step = position - previous
            when {
                step > elapsedMillis * MAX_PLAYBACK_SPEED + SEEK_JUMP_MILLIS || step < -SEEK_JUMP_MILLIS -> pending.value = true
                step > 0 -> pending.value = false
            }
        }
    }
    pending.collect { send(it) }
}.distinctUntilChanged()

/** 位置跳开多少才算拖动. 更小的拖动用不着提示. */
private const val SEEK_JUMP_MILLIS = 1_500L

/** 照常播放时位置最快走多快 (倍速上限), 两次上报之间隔得久时不误当成拖动. */
private const val MAX_PLAYBACK_SPEED = 4
