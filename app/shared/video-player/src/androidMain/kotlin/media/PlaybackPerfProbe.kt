/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.app.platform.perf.PerfTimeline
import java.util.Locale

/**
 * 给性能诊断记下播放器这一侧 (见 [PerfTimeline]), 画面一顿一顿时分清是出帧跟不上 (丢帧、跳帧、出帧晚)、数据没喂上 (缓冲) 还是声音断了.
 *
 * - 在播 / 缓冲时每 [SAMPLE_MILLIS] 读一次视频解码计数 ([DecoderCounters]): 出了几帧、丢了几帧 (解码出来时已经太晚)、跳了几帧与出帧提前量.
 *   计数对象在 [onVideoEnabled] 拿到, 按它的约定 ([DecoderCounters.ensureUpdated]) 在自己的低优先级线程上读, 不占播放器的线程;
 *   缓冲余量只能在播放器的应用线程读, 每次往那边投一个只读一个数的任务.
 * - 状态变化 (缓冲 / 就绪 / 开始播放)、首帧、解码器 (名字看得出硬解还是软解)、视频格式、倍速、音频断流、拖动、出错各记一条.
 *
 * 没有接收者时 (手机版) 回调里只读一次 [PerfTimeline.sink] 就返回, 也不采样; 暂停时不采样.
 */
internal class PlaybackPerfProbe(
    private val player: ExoPlayer,
    scope: CoroutineScope,
) : AnalyticsListener {
    private val playerHandler = Handler(player.applicationLooper)

    @Volatile
    private var counters: DecoderCounters? = null

    @Volatile
    private var playing = false

    @Volatile
    private var buffering = false

    @Volatile
    private var active = false

    @Volatile
    private var released = false

    // 只在采样线程上读写
    private var sampling = false
    private var lastSampleNs = 0L
    private var sampledCounters: DecoderCounters? = null
    private var rendered = 0
    private var dropped = 0
    private var skipped = 0
    private var offsetUs = 0L
    private var offsetFrames = 0

    private val sampleTick = object : Runnable {
        override fun run() {
            if (!shouldSample()) {
                sampling = false
                return
            }
            sample()
            sampler.postDelayed(this, SAMPLE_MILLIS)
        }
    }

    private val readBuffered = Runnable {
        if (!released && player.playWhenReady && player.playbackState != Player.STATE_IDLE) {
            PerfTimeline.gauge(FRAMES, BUFFERED_MS, player.totalBufferedDuration)
        }
    }

    init {
        scope.launch {
            PerfTimeline.active.collect {
                active = it
                updateSampling()
            }
        }
    }

    private fun shouldSample() = active && !released && (playing || buffering)

    private fun updateSampling() {
        sampler.post {
            if (shouldSample() && !sampling) {
                sampling = true
                lastSampleNs = 0
                sampleTick.run()
            }
        }
    }

    private fun sample() {
        // 两拍间隔不是正好半秒 (线程调度), 增量按实际间隔分摊到各秒, 免得一秒两拍、下一秒一拍
        val now = System.nanoTime()
        val span = if (lastSampleNs == 0L) 0L else now - lastSampleNs
        lastSampleNs = now
        val c = counters
        if (c != null) {
            c.ensureUpdated()
            if (c !== sampledCounters) {
                // 新的解码器 (开播、换片源): 计数从 0 起
                sampledCounters = c
                rendered = 0
                dropped = 0
                skipped = 0
                offsetUs = 0
                offsetFrames = 0
            }
            PerfTimeline.count(FRAMES, RENDERED, (c.renderedOutputBufferCount - rendered).toLong(), span)
            PerfTimeline.count(FRAMES, DROPPED, (c.droppedBufferCount - dropped).toLong(), span)
            PerfTimeline.count(FRAMES, SKIPPED, (c.skippedOutputBufferCount - skipped).toLong(), span)
            PerfTimeline.count(FRAMES, OFFSET_US, c.totalVideoFrameProcessingOffsetUs - offsetUs, span)
            PerfTimeline.count(FRAMES, OFFSET_FRAMES, (c.videoFrameProcessingOffsetCount - offsetFrames).toLong(), span)
            rendered = c.renderedOutputBufferCount
            dropped = c.droppedBufferCount
            skipped = c.skippedOutputBufferCount
            offsetUs = c.totalVideoFrameProcessingOffsetUs
            offsetFrames = c.videoFrameProcessingOffsetCount
        }
        PerfTimeline.count(FRAMES, SAMPLES, 1)
        playerHandler.post(readBuffered)
    }

    override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        counters = decoderCounters
    }

    override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
        released = true
        counters = null
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        buffering = state == Player.STATE_BUFFERING
        updateSampling()
        PerfTimeline.mark(
            EVENTS,
            when (state) {
                Player.STATE_BUFFERING -> "缓冲"
                Player.STATE_READY -> "就绪"
                Player.STATE_ENDED -> "播完"
                else -> "空闲"
            },
        ) { "位置 ${eventTime.currentPlaybackPositionMs / 1000} 秒 · 缓冲余量 ${eventTime.totalBufferedDurationMs} 毫秒" }
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        playing = isPlaying
        updateSampling()
        PerfTimeline.mark(EVENTS, if (isPlaying) "开始播放" else "停止播放")
    }

    override fun onPlaybackParametersChanged(eventTime: AnalyticsListener.EventTime, playbackParameters: PlaybackParameters) {
        PerfTimeline.mark(EVENTS, SPEED) { String.format(Locale.ROOT, "%.2f", playbackParameters.speed) }
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        PerfTimeline.mark(EVENTS, "首帧")
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        PerfTimeline.mark(EVENTS, "视频解码器") { "$decoderName · 初始化 $initializationDurationMs 毫秒" }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        PerfTimeline.mark(EVENTS, "音频解码器") { "$decoderName · 初始化 $initializationDurationMs 毫秒" }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        PerfTimeline.mark(EVENTS, "视频格式") { describe(format) }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        PerfTimeline.mark(EVENTS, "音频断流") { "音频缓冲 $bufferSizeMs 毫秒 · 距上次送数据 $elapsedSinceLastFeedMs 毫秒" }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        PerfTimeline.mark(EVENTS, "丢帧") { "$droppedFrames 帧 / $elapsedMs 毫秒" }
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            PerfTimeline.mark(EVENTS, "拖动") { "${oldPosition.positionMs / 1000} → ${newPosition.positionMs / 1000} 秒" }
        }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        PerfTimeline.mark(EVENTS, "出错") { error.errorCodeName }
    }

    private fun describe(format: Format): String = buildString {
        append(format.codecs ?: format.sampleMimeType ?: "?")
        if (format.width != Format.NO_VALUE) append(' ').append(format.width).append('x').append(format.height)
        if (format.frameRate > 0) append(' ').append(String.format(Locale.ROOT, "%.3f", format.frameRate)).append(" fps")
        val bitrate = listOf(format.averageBitrate, format.bitrate, format.peakBitrate).firstOrNull { it != Format.NO_VALUE }
        if (bitrate != null) append(' ').append(String.format(Locale.ROOT, "%.1f", bitrate / 1_000_000.0)).append(" Mbps")
    }

    companion object {
        /** 计数与读数的大类 (性能诊断按这些名字读, 见 ui-tv 的 PerfPlayback). */
        const val FRAMES = "画面"
        const val EVENTS = "播放"
        const val RENDERED = "出帧"
        const val DROPPED = "丢帧"
        const val SKIPPED = "跳帧"
        const val OFFSET_US = "提前量微秒"
        const val OFFSET_FRAMES = "提前量帧数"
        const val BUFFERED_MS = "缓冲余量毫秒"
        const val SPEED = "倍速"

        /** 每采一次记 1: 采样线程被拖住时一秒里会少, 分析据此不把「没采到」当成「没出帧」. */
        const val SAMPLES = "采样"

        private const val SAMPLE_MILLIS = 500L

        /** 所有播放器共用的采样线程 (没在播时闲着). 名字以 ani-perf 开头, 性能诊断算 CPU 时把它除外. */
        private val sampler: Handler by lazy {
            val thread = HandlerThread("ani-perf-probe", Process.THREAD_PRIORITY_BACKGROUND)
            thread.start()
            Handler(thread.looper)
        }
    }
}
