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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import me.him188.ani.app.platform.PlaybackActivity

/**
 * 把这个播放器在不在播 (在播或缓冲中) 报给 [PlaybackActivity]. 回调都在播放器的应用线程上.
 */
internal class PlaybackActivityReporter : AnalyticsListener {
    private var playing = false
    private var buffering = false

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        playing = isPlaying
        report()
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        buffering = state == Player.STATE_BUFFERING
        report()
    }

    override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
        playing = false
        buffering = false
        report()
    }

    private fun report() = PlaybackActivity.report(this, playing || buffering)
}
