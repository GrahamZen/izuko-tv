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
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import android.media.MediaCodecInfo as PlatformCodecInfo

/**
 * 安卓设备的硬件解码器基本都解不了的 H.264 规格: High 10 (字幕组压制常见的 10-bit)、High 4:2:2、High 4:4:4 (含 Intra 与 CAVLC 4:4:4 Intra).
 *
 * ExoPlayer 在没有更合适的轨时照样选「超出解码器能力」的视频轨. 交给解码器后, 有的设备报错 (由出错换源接住),
 * 有的 (见过晶晨盒子) 不报错, 照样输出一片片错位的色块, 画面像被水泼过, 跳转也跟着乱 —— 应用察觉不到.
 * 所以这几种规格在没有解码器声明支持时不交给解码器, 见 [UndecodableProfileGuardingVideoRenderer].
 */
internal object UndecodableVideoProfiles {
    /** H.264 的 profile_idc: 44 CAVLC 4:4:4 Intra, 110 High 10, 122 High 4:2:2, 244 High 4:4:4 Predictive */
    private val GUARDED_AVC_PROFILES = setOf(44, 110, 122, 244)

    /** 视频格式 ([Format.sampleMimeType] 与 [Format.codecs], 如 `avc1.6E0029`) 是不是这几种规格. */
    fun isGuarded(sampleMimeType: String?, codecs: String?): Boolean {
        if (sampleMimeType != MimeTypes.VIDEO_H264) return false
        return avcProfileOf(codecs) in GUARDED_AVC_PROFILES
    }

    /** `avc1.PPCCLL` / `avc3.PPCCLL` 里的 profile_idc (PP, 十六进制); 认不出时为 null. */
    internal fun avcProfileOf(codecs: String?): Int? {
        val codec = codecs?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("avc1.") || it.startsWith("avc3.") } ?: return null
        return codec.substring(5).take(2).takeIf { it.length == 2 }?.toIntOrNull(16)
    }
}

/**
 * 系统解码器的视频渲染器, 但 [UndecodableVideoProfiles] 里的规格在没有解码器声明支持时不开解码器: 候选解码器清空,
 * 解码器初始化报错, 错误里带着 `format_supported=NO_EXCEEDS_CAPABILITIES`, 之后与其他设备放不了的格式一样提示并自动换源.
 * 声明了支持的解码器 (少数芯片能解 10-bit H.264) 照常用. 判断能不能选这条轨 (supportsFormat) 不经过这里, 选轨不变.
 * 根本没有解码器的画面格式则在 supportsFormat 里报成超出能力照样选上, 同样在开解码器时报错 (见 [UnplayableVideoTrack]).
 *
 * 另外按 [VideoDecoderParking.MSG_PARK] 停用解码器: 画面不在眼前、内存紧时释放, 画面回来再建;
 * 按 [SeekPreviewDecoderRebuild.MSG_REBUILD_ON_NEXT_OUTPUT] 在下一次换输出面时重建解码器.
 */
@OptIn(UnstableApi::class)
internal class UndecodableProfileGuardingVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
) : MediaCodecVideoRenderer(builder) {
    // 杜比视界 profile 7 没有解码器支持时当 HDR10 基础层放 (见 DolbyVisionBaseLayer): 选轨与找解码器都按基础层算.
    // 没有解码器的画面也选上, 开解码器时报错、提示并换源, 不只放声音 (见 UnplayableVideoTrack)
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        UnplayableVideoTrack.selectAnyway(format, super.supportsFormat(mediaCodecSelector, DolbyVisionBaseLayer.decodableFormat(format)))

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat = super.getMediaFormat(
        format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId,
    ).also { if (codecMimeType == MimeTypes.VIDEO_H265 && DolbyVisionBaseLayer.isProfile7(format)) DolbyVisionBaseLayer.configureBaseLayer(it, format) }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        requested: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val format = DolbyVisionBaseLayer.decodableFormat(requested)
        val infos = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        val supports = infos.map { runCatching { it.isFormatSupported(format) }.getOrDefault(false) }
        val guarded = UndecodableVideoProfiles.isGuarded(format.sampleMimeType, format.codecs)
        // 画面花屏、解码报错时先看这一行: 格式与各候选解码器认不认它 (见 UndecodableVideoProfiles)
        logger.info {
            "[VideoDecoder] ${requested.sampleMimeType} ${requested.codecs} ${format.width}x${format.height}" + (if (guarded) " (guarded profile)" else "") +
                    (if (format !== requested) " (Dolby Vision base layer as HDR10)" else "") + ": " +
                    infos.zip(supports).joinToString { (info, ok) ->
                        "${info.name} ${if (info.hardwareAccelerated) "hw" else "sw"} ${if (ok) "supported" else "exceeds"}"
                    }.ifEmpty { "no decoder" }
        }
        if (!guarded || supports.any { it }) return infos
        logger.warn { "No decoder declares support for ${format.codecs}, not decoding it" }
        return emptyList()
    }

    // 画面不在眼前、内存紧时释放解码器 (见 VideoDecoderParking). 两个字段只在播放线程上读写
    private var decoderParked = false
    private var hasDisplaySurface = false

    // 拖动预览换输出时重建解码器 (见 SeekPreviewDecoderRebuild). 只在播放线程上读写
    private var rebuildOnNextOutput = false

    override fun handleMessage(messageType: Int, message: Any?) {
        when (messageType) {
            MSG_SET_VIDEO_OUTPUT -> {
                hasDisplaySurface = message is Surface && message.isValid
                if (rebuildOnNextOutput) {
                    rebuildOnNextOutput = false
                    if (hasDisplaySurface && message !== surface && codec != null) {
                        rebuildCodecFor(message)
                        return
                    }
                }
            }

            SeekPreviewDecoderRebuild.MSG_REBUILD_ON_NEXT_OUTPUT -> {
                rebuildOnNextOutput = message == true
                return
            }

            VideoDecoderParking.MSG_PARK -> {
                decoderParked = message == true
                // 已经没有真的输出面 (解码器挂在占位输出面上) 就现在释放; 停用期间没有真的输出面不再建解码器
                if (decoderParked && !hasDisplaySurface && codec != null) releaseCodec()
                return
            }
        }
        super.handleMessage(messageType, message)
    }

    /** 释放解码器、换上输出面 [output] 后对着它重建 (同 media3 对 setOutputSurface 有问题的解码器的做法). */
    private fun rebuildCodecFor(output: Any?) {
        val name = codecInfo?.name
        val startedAt = System.nanoTime()
        releaseCodec()
        super.handleMessage(MSG_SET_VIDEO_OUTPUT, output)
        maybeInitCodecOrBypass()
        logger.info { "[SeekPreview] Rebuilt video decoder $name for the new output in ${(System.nanoTime() - startedAt) / 1_000_000}ms" }
    }

    override fun shouldUsePlaceholderSurface(codecInfo: MediaCodecInfo): Boolean =
        !decoderParked && super.shouldUsePlaceholderSurface(codecInfo)

    override fun shouldUseDetachedSurface(codecInfo: MediaCodecInfo): Boolean =
        !decoderParked && super.shouldUseDetachedSurface(codecInfo)

    companion object {
        private val logger = logger<UndecodableProfileGuardingVideoRenderer>()

        private val deviceDecodersLogged = AtomicBoolean(false)

        /** 每个进程记一次本机的视频解码器声明了什么 (最大分辨率、能不能解 10-bit): 一份日志就看得出这台设备放不了哪些格式 */
        private fun logDeviceDecodersOnce() {
            if (!deviceDecodersLogged.compareAndSet(false, true)) return
            val lines = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { !it.isEncoder }
                    .flatMap { info -> info.supportedTypes.filter { it.startsWith("video/") }.map { type -> describe(info, type) } }
            }.getOrElse { listOf("unavailable: $it") }
            logger.info { "[VideoDecoder] device decoders:\n" + lines.joinToString("\n") }
        }

        private fun describe(info: PlatformCodecInfo, type: String): String {
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return "$type ${info.name}: no capabilities"
            val video = caps.videoCapabilities
            val size = video?.let { "${it.supportedWidths.upper}x${it.supportedHeights.upper}" } ?: "?"
            val kind = if (Build.VERSION.SDK_INT >= 29) (if (info.isHardwareAccelerated) " hw" else " sw") else ""
            val profiles = caps.profileLevels.map { it.profile }.distinct().sorted()
            val tenBit = TEN_BIT_PROFILES[type]?.let { bit -> if (profiles.any { it and bit != 0 }) " 10-bit" else " 8-bit only" }.orEmpty()
            return "$type ${info.name}$kind max $size$tenBit profiles=${profiles.joinToString(",") { "0x" + it.toString(16) }}"
        }

        /** 各编码里 10-bit 那一档的 profile 位 (MediaCodecInfo.CodecProfileLevel) */
        private val TEN_BIT_PROFILES = mapOf(
            MimeTypes.VIDEO_H264 to PlatformCodecInfo.CodecProfileLevel.AVCProfileHigh10,
            MimeTypes.VIDEO_H265 to PlatformCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MimeTypes.VIDEO_VP9 to PlatformCodecInfo.CodecProfileLevel.VP9Profile2,
            MimeTypes.VIDEO_AV1 to PlatformCodecInfo.CodecProfileLevel.AV1ProfileMain10,
        )

        /**
         * 把 [out] 里 [DefaultRenderersFactory] 建的系统解码器视频渲染器换成 [UndecodableProfileGuardingVideoRenderer].
         * 配置照 `DefaultRenderersFactory.buildVideoRenderers` (三个实验开关取它的默认值, 应用没改过).
         */
        fun replaceIn(
            out: ArrayList<Renderer>,
            context: Context,
            codecAdapterFactory: MediaCodecAdapter.Factory,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
        ) {
            // 列一遍设备解码器 (MediaCodecList) 要几十毫秒, 而建播放器在主线程上
            thread(name = "ani-decoder-log", isDaemon = true) { logDeviceDecodersOnce() }
            val index = out.indexOfFirst { it.javaClass == MediaCodecVideoRenderer::class.java }
            if (index < 0) return
            out[index] = UndecodableProfileGuardingVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
            )
        }
    }
}
