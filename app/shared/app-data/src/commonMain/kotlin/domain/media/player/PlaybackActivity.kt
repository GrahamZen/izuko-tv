/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 现在在播什么、播放器在当前位置之后缓冲了多少. 由播放页上报, 缓存下载据此给播放让路:
 * 同一集的缓存先停下 (见 `MediaDownloadManager.waitingForPlayback`), 别的缓存按缓冲余量限速 (见 `PlaybackYieldingGate`).
 */
class PlaybackActivity {
    private class Entry(val owner: Any, val playback: ActivePlayback)

    private val state = MutableStateFlow<Entry?>(null)

    /** 播放页开着的那一集 (在播、卡着在缓冲、暂停或还在加载); 没有时为 `null`. */
    val current: Flow<ActivePlayback?> = state.map { it?.playback }.distinctUntilChanged()

    private val _onScreen = MutableStateFlow(true)

    /**
     * 播放页在不在眼前. TV 保留播放会话时, 退出播放页后会话还开着 ([current] 仍是那一集, 暂停着), 这里为 `false`;
     * 由保留会话的持有者按导航状态上报, 没有保留会话的平台恒为 `true` (退出播放页即 [current] 变 `null`).
     */
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    fun setOnScreen(visible: Boolean) {
        _onScreen.value = visible
    }

    /** [current] 此刻的值. */
    val currentValue: ActivePlayback? get() = state.value?.playback

    /**
     * 由播放页 [owner] 上报: [playback] 为 `null` 表示它不在放这一集了 (退出、播完或出错). 播放页不止一个时 (切集的瞬间新旧并存),
     * 后上报正在播放的那个算数, 旧的报「不在播了」清不掉它的.
     */
    fun update(owner: Any, playback: ActivePlayback?) {
        state.update { current ->
            when {
                playback != null -> Entry(owner, playback)
                current?.owner === owner -> null
                else -> current
            }
        }
    }
}

data class ActivePlayback(
    val subjectId: Int,
    val episodeId: Int,
    /** 当前位置之后缓冲了多少毫秒; 播放器报不出、或没在走 (暂停、加载中, 不用让路) 时为 `null`. */
    val bufferedAheadMillis: Long?,
    /** 卡住了: 想播但在等缓冲. */
    val stalled: Boolean,
)
