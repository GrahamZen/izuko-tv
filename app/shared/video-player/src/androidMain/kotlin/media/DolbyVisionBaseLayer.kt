/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * 杜比视界 profile 7 (蓝光原盘 remux 常见的双层格式) 在没有杜比视界解码器声明支持它时, 当 HDR10 放基础层.
 *
 * profile 7 的基础层就是 HDR10 的 HEVC Main 10; 增强层与动态元数据是 HEVC 里 62 / 63 号 NAL, 普通 HEVC 解码器按规范跳过.
 * media3 自带的回退 (`MediaCodecUtil.getAlternativeCodecMimeType`) 只管 profile 4 / 8 / 9 / 10, 不管 7, 于是这类片源在
 * 没有杜比视界解码器的电视上选不上画面轨, Shield 的杜比视界解码器也只声明 4 / 5 / 8 / 9. Kodi、Plex 等都是这样放 profile 7 的.
 * profile 5 的基础层不是 HDR10 (IPTPQc2), 当 HEVC 放颜色全错, 不走这里 (选源时标成「不支持」).
 *
 * 接在 [UndecodableProfileGuardingVideoRenderer] 上: 判断能不能选这条轨、找解码器时用 [decodableFormat], 配置解码器时按 [configureBaseLayer] 改.
 */
@OptIn(UnstableApi::class)
internal object DolbyVisionBaseLayer {
    /** [format] 要当 HDR10 基础层放时的样子 (见 [baseLayerOf]), 否则原样. */
    fun decodableFormat(format: Format): Format = if (needsFallback(format)) baseLayerOf(format) else format

    /** 是杜比视界 profile 7, 且没有杜比视界解码器声明支持它. */
    fun needsFallback(format: Format): Boolean {
        if (!isProfile7(format)) return false
        val decoders = runCatching { MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false) }.getOrDefault(emptyList())
        return decoders.none { runCatching { it.isFormatSupported(format) }.getOrDefault(false) }
    }

    fun isProfile7(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION &&
                CodecSpecificDataUtil.getCodecProfileAndLevel(format)?.first == CodecProfileLevel.DolbyVisionProfileDvheDtb

    /** 基础层: HEVC, 不带杜比视界的 codecs 串; 容器没写全的色彩按 HDR10 (BT.2020、PQ、有限范围) 补. */
    fun baseLayerOf(format: Format): Format = format.buildUpon()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs(null)
        .setColorInfo(hdr10ColorInfo(format.colorInfo))
        .build()

    /**
     * 拿 HEVC 解码器解 profile 7 时改配置, 与普通 HDR10 文件一样: media3 给杜比视界格式写的 profile 是杜比视界的编号, HEVC 解码器不认,
     * 去掉 (普通 HEVC 本来就不写; 去不掉的老系统写 Main 10 HDR10); 色彩按 [baseLayerOf] 补.
     */
    fun configureBaseLayer(mediaFormat: MediaFormat, format: Format) {
        if (Build.VERSION.SDK_INT >= 29) {
            mediaFormat.removeKey(MediaFormat.KEY_PROFILE)
        } else {
            mediaFormat.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10HDR10)
        }
        MediaFormatUtil.maybeSetColorInfo(mediaFormat, hdr10ColorInfo(format.colorInfo))
    }

    private fun hdr10ColorInfo(existing: ColorInfo?): ColorInfo = (existing?.buildUpon() ?: ColorInfo.Builder())
        .setColorSpace(existing?.colorSpace?.takeIf { it != Format.NO_VALUE } ?: C.COLOR_SPACE_BT2020)
        .setColorRange(existing?.colorRange?.takeIf { it != Format.NO_VALUE } ?: C.COLOR_RANGE_LIMITED)
        .setColorTransfer(existing?.colorTransfer?.takeIf { it != Format.NO_VALUE } ?: C.COLOR_TRANSFER_ST2084)
        .build()
}
