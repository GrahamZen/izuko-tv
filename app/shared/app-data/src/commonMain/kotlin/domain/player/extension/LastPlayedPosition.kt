/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer

/**
 * 播放器里这一集最后播到的位置: 同一集换源时新资源从这里打开 (见 [RememberPlayProgressExtension.startPositionMillis]).
 *
 * 不用存下的进度: 换源前存进度 (onBeforeSelect) 与新资源打开前读进度没有先后保证, 读到的可能是一分钟前的;
 * 也不能到打开新资源时再问播放器: 选中新资源时旧的已经停了, 位置归零. 所以播放时一直记着.
 * 只在资源打开着 (就绪、播完) 且位置大于 0 时记: 停止、打开中报的 0 不算.
 */
internal class LastPlayedPosition {
    /** 哪一集, 播到哪 (毫秒). */
    private val played = MutableStateFlow<Pair<Int, Long>?>(null)

    /** 跟着 [player] 记位置, [loadedEpisodeId] 是播放器里装的是哪一集. 一直挂着. */
    suspend fun track(player: MediampPlayer, loadedEpisodeId: StateFlow<Int?>) {
        combine(player.state, player.currentPositionMillis, loadedEpisodeId) { state, position, episode ->
            val open = state.mediaStatus == MediaStatus.Ready || state.mediaStatus == MediaStatus.Ended
            if (open && position > 0 && episode != null) episode to position else null
        }.collect { latest ->
            if (latest != null) played.value = latest
        }
    }

    /** [episodeId] 最后播到的位置; 没播过这一集为 null. */
    fun of(episodeId: Int): Long? = played.value?.takeIf { it.first == episodeId }?.second
}
