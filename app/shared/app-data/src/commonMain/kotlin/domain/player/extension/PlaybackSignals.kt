/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.him188.ani.datasources.api.Media
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackException

/**
 * 时钟开始走的每一刻 (`isPlaying` 从假变真). 开始收集时已经在播的不算: 换了查询会话 (如重新搜索) 后重新挂上时,
 * 视频本来就在播, 不是开始播放.
 */
fun MediampPlayer.playbackStarts(): Flow<Unit> =
    state.map { it.isPlaying }.distinctUntilChanged().drop(1).filter { it }.map { }

/** 播放器报出的每个错误, 同一个错误只出现一次. */
fun MediampPlayer.playerErrors(): Flow<PlaybackException> =
    state.map { (it.mediaStatus as? MediaStatus.Error)?.error }.distinctUntilChanged().filterNotNull()

/** 当前这一集选源器选中的资源, 换集、换查询会话时跟着换. */
@OptIn(ExperimentalCoroutinesApi::class)
fun PlayerExtensionContext.selectedMedia(): Flow<Media?> =
    sessionFlow.flatMapLatest { it.fetchSelectFlow }.flatMapLatest { it?.mediaSelector?.selected ?: flowOf(null) }
