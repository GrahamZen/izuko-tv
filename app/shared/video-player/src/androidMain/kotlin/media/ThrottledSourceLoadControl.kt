/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.annotation.OptIn as AndroidxOptIn

/**
 * 正在播被限速的网盘直链时 ([throttled], 解析器给了并发提示, 见 `PlaybackRequestHints.PARALLEL_RANGE_HEADER`) 换一套缓冲策略, 其余源照 ExoPlayer 默认.
 *
 * 这种源的下载速度常常跟不上局部码率 (片头这类画面复杂的段落码率是平均的好几倍). 默认卡住后攒 2 秒就接着播, 播几秒又卡,
 * 看起来就是一直在缓冲; 这里:
 * - 卡住之后攒够 [REBUFFER_RESUME_US] 再播: 总的等待差不多, 卡的次数少得多;
 * - 缓冲上限放到 [THROTTLED_MAX_BUFFER_US]: 码率低的段落多攒些, 留给后面码率高的段落. 占用的内存仍受 [DefaultLoadControl] 按轨道算的字节上限约束.
 * 开播 / 跳转后照旧攒够 1 秒就播.
 *
 * 只转发 media3 1.9 实际调用的那组方法 (带 [PlayerId] / [LoadControl.Parameters] 的).
 */
@AndroidxOptIn(UnstableApi::class)
internal class ThrottledSourceLoadControl : LoadControl {
    /** 当前媒体是不是被限速的网盘直链; 在准备媒体时 (主线程) 设, 播放线程读. */
    @Volatile
    var throttled = false

    // 上下限都设成限速源要的上限; 普通源在 shouldContinueLoading 里按默认的 50 秒截住, 效果同默认 (默认上下限也都是 50 秒)
    private val delegate = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            (THROTTLED_MAX_BUFFER_US / 1000).toInt(),
            (THROTTLED_MAX_BUFFER_US / 1000).toInt(),
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (!throttled && parameters.bufferedDurationUs >= DEFAULT_MAX_BUFFER_US) return false
        return delegate.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (throttled && parameters.rebuffering) {
            val needed = (REBUFFER_RESUME_US / parameters.playbackSpeed.coerceAtLeast(0.1f)).toLong()
            // 字节先到了上限 (码率极高) 就攒不到那么多秒, 这时照样开播, 免得一直卡着
            return parameters.bufferedDurationUs >= needed || isBufferFull(parameters.playerId)
        }
        return delegate.shouldStartPlayback(parameters)
    }

    /** 缓冲占的内存已经到了视频轨的默认字节上限 (再加上音轨的就停止加载了), 再等也攒不下更多. */
    private fun isBufferFull(playerId: PlayerId): Boolean =
        delegate.getAllocator(playerId).totalBytesAllocated >= DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    companion object {
        /** 普通源的缓冲上限, 同 ExoPlayer 默认. */
        private const val DEFAULT_MAX_BUFFER_US = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * 1000L

        /** 限速源的缓冲上限. */
        const val THROTTLED_MAX_BUFFER_US = 120_000_000L

        /** 限速源卡住之后要攒多少才接着播. */
        const val REBUFFER_RESUME_US = 10_000_000L
    }
}
