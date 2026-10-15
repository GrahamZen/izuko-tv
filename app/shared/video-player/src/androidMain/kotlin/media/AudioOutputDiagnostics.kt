/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 「有画面没声音」时留下线索, 事后从 app.log 搜 `[Audio]`: 每换一次媒体记一遍音轨与各自能不能放, 再记音频走的是哪个解码器
 * (系统 MediaCodec 的名字, 或 FFmpeg 软解, 见 [FfmpegAudioFallback])、交给系统的输出格式 (PCM / 直通的编码、采样率、声道、
 * 是否隧道 / offload)、当时的媒体音量与声音往哪个设备出 (直通只在外接设备上放行, 见 [AudioPassthroughGate]), 声音真的开始走了记一行, 出错与断流也记.
 *
 * 判读: 没有「decoder」行 = 没有音轨被选上 (看 tracks 行里各条能不能放); 有 output 却一直没有「playing」行 = 交给系统后系统没在放
 * (直通给了不认这种编码的功放 / 电视最常见); 媒体音量 0 或静音 = 设备侧的问题.
 *
 * 每个媒体只有几行. 回调都在播放器的应用线程 (主线程) 上; 查系统音量是一次跨进程调用, 放在 [ioDispatcher] 上.
 */
@OptIn(UnstableApi::class)
internal class AudioOutputDiagnostics(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AnalyticsListener {
    private var underruns = 0
    private var advancingLogged = false

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty()) return
        val line = audio.joinToString(" | ", prefix = "[Audio] tracks: ") { group ->
            (0 until group.length).joinToString("; ") { i ->
                val selected = if (group.isTrackSelected(i)) " selected" else ""
                "${group.mediaTrackGroup.id} ${describe(group.getTrackFormat(i))} ${supportName(group.getTrackSupport(i))}$selected"
            }
        }
        logger.info { line }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        logger.info {
            // 解码的是哪条看前面的 tracks 行: 系统解码器先报初始化、后报输入格式, 这里拿不到准的格式
            "[Audio] decoder $decoderName (${initializationDurationMs} ms)" +
                if (deviceLogged) "" else "; device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
        deviceLogged = true
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        underruns = 0
        advancingLogged = false
        val config = audioTrackConfig
        val head = "[Audio] output ${encodingName(config.encoding)} ${config.sampleRate} Hz, channel mask 0x${Integer.toHexString(config.channelConfig)}" +
            (if (config.tunneling) ", tunneling" else "") + (if (config.offload) ", offload" else "") + ", buffer ${config.bufferSize} B"
        scope.launch(ioDispatcher) {
            logger.info { head + "; " + describeVolume() + "; " + AudioOutputRoute.describe(context) }
        }
    }

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        if (advancingLogged) return
        advancingLogged = true
        logger.info { "[Audio] playing (system reports the audio position advancing)" }
    }

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        underruns++
        if (underruns == 1 || underruns % UNDERRUN_REPORT_EVERY == 0) {
            logger.info { "[Audio] underrun #$underruns (buffer $bufferSizeMs ms, last feed $elapsedSinceLastFeedMs ms ago)" }
        }
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        logger.warn(audioSinkError) { "[Audio] output error" }
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        logger.warn(audioCodecError) { "[Audio] decoder error" }
    }

    override fun onVolumeChanged(eventTime: AnalyticsListener.EventTime, volume: Float) {
        logger.info { "[Audio] player volume $volume" }
    }

    /** 系统媒体音量与静音: 用户调没调成 0 一眼可见. 跨进程调用, 在 [ioDispatcher] 上调. */
    private fun describeVolume(): String = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return "media volume unknown"
        val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audio.isStreamMute(AudioManager.STREAM_MUSIC)) ", muted" else ""
        "media volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}$muted"
    }.getOrElse { "media volume unknown" }

    private companion object {
        private val logger = logger<AudioOutputDiagnostics>()

        /** 设备型号每个进程只记一次. */
        @Volatile
        private var deviceLogged = false

        /** 断流每这么多次记一行. */
        const val UNDERRUN_REPORT_EVERY = 20

        fun describe(format: Format): String = buildString {
            append(format.sampleMimeType ?: "unknown")
            format.codecs?.let { append(' ').append(it) }
            if (format.sampleRate != Format.NO_VALUE) append(' ').append(format.sampleRate).append(" Hz")
            if (format.channelCount != Format.NO_VALUE) append(' ').append(format.channelCount).append(" ch")
            if (format.pcmEncoding != Format.NO_VALUE) append(" pcm ").append(encodingName(format.pcmEncoding))
            format.language?.let { append(' ').append(it) }
        }

        fun supportName(support: Int): String = when (support) {
            C.FORMAT_HANDLED -> "handled"
            C.FORMAT_EXCEEDS_CAPABILITIES -> "exceeds-capabilities"
            C.FORMAT_UNSUPPORTED_DRM -> "unsupported-drm"
            C.FORMAT_UNSUPPORTED_SUBTYPE -> "unsupported-subtype"
            C.FORMAT_UNSUPPORTED_TYPE -> "unsupported-type"
            else -> "support $support"
        }

        fun encodingName(encoding: Int): String = when (encoding) {
            C.ENCODING_PCM_16BIT -> "pcm16"
            C.ENCODING_PCM_24BIT -> "pcm24"
            C.ENCODING_PCM_32BIT -> "pcm32"
            C.ENCODING_PCM_FLOAT -> "pcm-float"
            C.ENCODING_PCM_8BIT -> "pcm8"
            C.ENCODING_AC3 -> "ac3 passthrough"
            C.ENCODING_E_AC3 -> "e-ac3 passthrough"
            C.ENCODING_E_AC3_JOC -> "e-ac3-joc passthrough"
            C.ENCODING_DTS -> "dts passthrough"
            C.ENCODING_DTS_HD -> "dts-hd passthrough"
            C.ENCODING_DOLBY_TRUEHD -> "truehd passthrough"
            C.ENCODING_AAC_LC -> "aac-lc offload"
            else -> "encoding $encoding"
        }
    }
}
