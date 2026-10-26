/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.videoenhancement

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer

/**
 * 增强控制器的帧率采样里顺带判性能够不够 (见 [VideoEnhancementPerformanceJudge]): 每个窗口读一次播放位置、片源帧率与播放速度.
 * 只在播放器的应用线程上用 (ExoPlayer 的这些读数只能在那条线程上取).
 *
 * 窗口长度按实际经过的时间算: 增强把显卡吃满时这条线程也慢, 名义 2 秒的采样实际隔三四秒 (索尼实测),
 * 按名义时长比的话播放位置走得「太多」, 会被当成跳转不算数.
 */
internal class VideoEnhancementPerformanceProbe(private val exoPlayer: ExoPlayer) {
    private val judge = VideoEnhancementPerformanceJudge()
    private var lastPositionMillis = exoPlayer.currentPosition
    private var lastSampleAt = SystemClock.elapsedRealtime()

    /** 最后一个窗口时片源报的帧率, 没报为 0. */
    var contentFrameRate = 0f
        private set

    /** 最后一个窗口实际多长 (毫秒). */
    var lastWindowMillis = 0L
        private set

    /** [renderedFrames]: 上次调用以来实际出的帧数. 返回 true = 判定为性能不够. */
    fun isTooSlow(renderedFrames: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val position = exoPlayer.currentPosition
        val window = now - lastSampleAt
        val advance = position - lastPositionMillis
        lastSampleAt = now
        lastPositionMillis = position
        lastWindowMillis = window
        contentFrameRate = exoPlayer.videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f
        return judge.onSample(
            renderedFrames = renderedFrames,
            windowMillis = window,
            positionAdvanceMillis = advance,
            playing = exoPlayer.isPlaying,
            contentFrameRate = contentFrameRate,
            playbackSpeed = exoPlayer.playbackParameters.speed,
        )
    }
}
