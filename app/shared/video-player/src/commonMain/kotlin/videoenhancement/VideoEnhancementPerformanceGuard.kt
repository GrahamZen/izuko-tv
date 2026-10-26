/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.videoenhancement

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 画质增强开着、设备算不过来时 (电视的显卡多半如此, 画面每秒只出几帧而声音照常) 判出来.
 *
 * 每个采样窗口 (增强开着时每 2 秒一次, 见 Android 的增强控制器) 报一次实际出了几帧 ([onSample]).
 * 只看「正常在播」的窗口: 窗口结束时在播, 且播放位置这段时间照常往前走 (播放速度的 [MIN_ADVANCE] ~ [MAX_ADVANCE] 倍) ——
 * 开播攒缓冲、卡住、暂停、跳转的窗口位置不走或跳一大截, 不算数也不清零. 正常在播的窗口里出的帧不到片源帧率的 [SLOW_FRACTION],
 * 连续 [SLOW_WINDOWS] 个就算性能不够; 中间有一个够数的就从头数.
 *
 * 判不出两类: 一帧都出不来 (播放器一直停在缓冲, 位置不走) 和解码直接报错 —— 那种靠设置页选档时的警告.
 */
internal class VideoEnhancementPerformanceJudge {
    private var slowWindows = 0

    /**
     * @param renderedFrames 这个窗口里实际出的帧数
     * @param windowMillis 窗口长度
     * @param positionAdvanceMillis 这段时间播放位置走了多少
     * @param playing 窗口结束时是不是在播 (就绪且没暂停)
     * @param contentFrameRate 片源帧率, 不知道时传 0 或负数 (按 [FALLBACK_FRAME_RATE] 算)
     * @param playbackSpeed 播放速度
     * @return 这一次判定为性能不够
     */
    fun onSample(
        renderedFrames: Int,
        windowMillis: Long,
        positionAdvanceMillis: Long,
        playing: Boolean,
        contentFrameRate: Float,
        playbackSpeed: Float,
    ): Boolean {
        val speed = playbackSpeed.coerceAtLeast(0.1f)
        val expectedAdvance = windowMillis * speed
        val playingNormally = playing && renderedFrames >= 0 &&
            positionAdvanceMillis >= expectedAdvance * MIN_ADVANCE && positionAdvanceMillis <= expectedAdvance * MAX_ADVANCE
        if (!playingNormally) return false
        val frameRate = if (contentFrameRate > 0f) contentFrameRate.coerceAtMost(MAX_FRAME_RATE) else FALLBACK_FRAME_RATE
        val expectedFrames = frameRate * positionAdvanceMillis / 1000f
        if (renderedFrames >= expectedFrames * SLOW_FRACTION) {
            slowWindows = 0
            return false
        }
        slowWindows++
        return slowWindows >= SLOW_WINDOWS
    }

    fun reset() {
        slowWindows = 0
    }

    companion object {
        /** 出的帧不到片源帧率的这么多算慢. */
        const val SLOW_FRACTION = 0.5f

        /** 连续这么多个慢窗口 (每个 2 秒) 算性能不够. */
        const val SLOW_WINDOWS = 3

        const val MIN_ADVANCE = 0.75f
        const val MAX_ADVANCE = 1.5f

        /** 片源没报帧率时按动画常见的 24 帧算. */
        const val FALLBACK_FRAME_RATE = 24f

        /** 高于这个的帧率 (标错的、可变帧率的) 按它算, 免得把正常设备判成慢. */
        const val MAX_FRAME_RATE = 60f
    }
}

/**
 * 画质增强因为设备性能不够被自动关掉了 (见 [VideoEnhancementPerformanceJudge]): [mode] 是关之前的档位,
 * [measuredFps] / [contentFps] 是最后那个窗口实际出帧与片源帧率 (片源没报时为 0).
 */
data class VideoEnhancementFallbackEvent(
    val mode: VideoEnhancementMode,
    val measuredFps: Float,
    val contentFps: Float,
)

/**
 * 增强控制器自己关掉增强之后在这里报一次; 页面据此把「默认画质增强」写回关闭并提示 (电视: ui-tv 的 `TvVideoEnhancementFallback`).
 */
object VideoEnhancementFallback {
    private val _events = MutableSharedFlow<VideoEnhancementFallbackEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<VideoEnhancementFallbackEvent> get() = _events

    internal fun report(event: VideoEnhancementFallbackEvent) {
        _events.tryEmit(event)
    }
}
