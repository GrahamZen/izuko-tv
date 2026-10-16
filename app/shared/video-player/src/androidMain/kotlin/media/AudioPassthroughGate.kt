/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import me.him188.ani.app.videoplayer.audio.AudioPassthroughMode

/**
 * 决定杜比、DTS 这类编码音轨能不能直通 (原样交给系统, 由后面的设备解码), 见 [AudioPassthroughMode].
 *
 * 「自动」: 有的电视 (见过小米电视, Android 14, 声音走蓝牙) 声称能直通 AC3, 播放器照做后系统一切照常 (位置在走、不报错) 却没有声音,
 * 应用这边察觉不到, 所以不等出问题:
 * - 立体声与单声道一律解码: 直通它们没有任何好处;
 * - 多声道只在送到外接设备时直通 ([AudioOutputRoute.isExternal]): 盒子的 HDMI、电视的 ARC / eARC / 光纤; 自带喇叭、蓝牙等解码.
 */
internal class AudioPassthroughGate(
    context: Context,
    private val mode: () -> AudioPassthroughMode,
) {
    private val context = context.applicationContext

    /** 播放器问「这种编码能不能直通」时调; 只在选音轨、换媒体时问, 多声道时查一下当前输出 (一次跨进程调用). */
    fun allowsPassthrough(format: Format): Boolean = when (mode()) {
        AudioPassthroughMode.ALWAYS -> true
        AudioPassthroughMode.OFF -> false
        AudioPassthroughMode.AUTO -> format.channelCount > 2 && runCatching { AudioOutputRoute.isExternal(context) }.getOrDefault(true)
    }
}

/**
 * 媒体声音往哪个设备出. Android 13 起能查到实际走的设备; 更早的版本只能看接着哪些设备.
 */
internal object AudioOutputRoute {
    /** 声音送到外接设备 (直通才有人解码), 见 [isExternal] 的另一个重载. */
    fun isExternal(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return true
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Api33.routedDevices(manager).map { it.type } else null
        return isExternal(routed, manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
    }

    /**
     * @param routed 媒体声音实际走的设备类型 (Android 13 起); 查得到时只认 [EXTERNAL_TYPES] (蓝牙传不了直通的码流)
     * @param outputs 接着的全部输出设备类型; [routed] 为 null 或空时按它推断: 只有「有自带喇叭、且没有任何能接外部设备的输出」算不是外接
     *   (Shield 这类盒子的 HDMI 不一定报成 HDMI, 见过 总线 + USB + 喇叭), 其余看不出来的照旧当外接
     */
    fun isExternal(routed: List<Int>?, outputs: List<Int>): Boolean {
        if (!routed.isNullOrEmpty()) return routed.any { it in EXTERNAL_TYPES }
        return !(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in outputs && outputs.none { it in EXTERNAL_TYPES })
    }

    /** 诊断日志用 (见 [AudioOutputDiagnostics]): Android 13 起是实际走的设备 (`route`), 更早是接着的全部输出 (`outputs`). */
    fun describe(context: Context): String = runCatching {
        val manager = context.getSystemService(AudioManager::class.java) ?: return "route unknown"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "route " + Api33.routedDevices(manager).joinToString { typeName(it.type) }
        } else {
            "outputs " + manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { typeName(it.type) }
        }
    }.getOrDefault("route unknown")

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        private val MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        fun routedDevices(manager: AudioManager): List<AudioDeviceInfo> = manager.getAudioDevicesForAttributes(MEDIA)
    }

    /** 能接外部设备的输出: HDMI (盒子)、ARC / eARC / 光纤 (电视接回音壁、功放)、总线与 USB (盒子的输出有时报成这两种). */
    @SuppressLint("InlinedApi") // 常量, 旧系统上只是永远对不上
    private val EXTERNAL_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_BUS, AudioDeviceInfo.TYPE_USB_DEVICE,
    )

    @SuppressLint("InlinedApi")
    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi-arc"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi-earc"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "optical"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "line"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        AudioDeviceInfo.TYPE_BUS -> "bus"
        else -> "type $type"
    }
}

/**
 * 编码音轨 (非 PCM) 在 [AudioPassthroughGate] 不放行时回答「不支持」: 播放器于是改用系统解码器 (没有就用 FFmpeg, 见 [FfmpegAudioFallback])
 * 解成 PCM 再交过来. PCM 一律照原样.
 */
@OptIn(UnstableApi::class)
internal class PassthroughGatedAudioSink(
    sink: AudioSink,
    private val allowsPassthrough: (Format) -> Boolean,
) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean = !blocked(format) && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (blocked(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    private fun blocked(format: Format): Boolean = format.sampleMimeType != MimeTypes.AUDIO_RAW && !allowsPassthrough(format)
}

/**
 * 音频输出带 [PassthroughGatedAudioSink] 的渲染器工厂. [processorChain] 非空时挂在输出上 (mediamp 的 WSOLA 变速链, 见 [FfmpegAudioFallback]),
 * 建法与 mediamp 的 WSOLA 工厂、media3 默认工厂相同, 只多外面一层. 音频渲染器的解码器选择换成 [FfmpegForDolbyCodecSelector].
 */
@OptIn(UnstableApi::class)
internal class PassthroughGatedRenderersFactory(
    context: Context,
    private val processorChain: AudioProcessorChain?,
    private val gate: AudioPassthroughGate,
) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val selector = FfmpegForDolbyCodecSelector(mediaCodecSelector, FfmpegLibrary::supportsFormat)
        super.buildAudioRenderers(context, extensionRendererMode, selector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        UndecodableProfileGuardingVideoRenderer.replaceIn(
            out, context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs,
        )
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        processorChain?.let(builder::setAudioProcessorChain)
        return PassthroughGatedAudioSink(builder.build(), gate::allowsPassthrough)
    }
}

/**
 * 杜比 (AC3 / E-AC3 / TrueHD)、DTS 不用设备自带的解码器, 交给 FFmpeg ([FfmpegAudioFallback]): 有的设备带着这类解码器却解出静音
 * (见过小米电视的 `c2.amlogic.audio.decoder.ac3`, 系统照常报告在放, 没有声音; 多半是没有杜比授权的空壳), 应用察觉不到.
 * 直通不经过这里 (播放器直通时用的是只转发的解码器), 不受影响. [ffmpegCanDecode] 为假的格式照常用设备的解码器.
 */
@OptIn(UnstableApi::class)
internal class FfmpegForDolbyCodecSelector(
    private val delegate: MediaCodecSelector,
    private val ffmpegCanDecode: (String) -> Boolean,
) : MediaCodecSelector {
    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> =
        if (mimeType in DOLBY_DTS && ffmpegCanDecode(mimeType)) emptyList()
        else delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)

    private companion object {
        val DOLBY_DTS = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD,
        )
    }
}
