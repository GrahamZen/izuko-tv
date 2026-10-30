/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * mkv 里 media3 解不出的画面轨. media3 读 mkv 只认几种画面编码 ([MEDIA3_VIDEO_CODEC_IDS]), 别的 (RealVideo、MPEG-1、H.266 等) 整条轨丢掉;
 * Theora 与 VfW 方式里认不出的 FourCC (DivX 3、WMV 等, 见 [MkvFourCcVideo]) 记成 `video/x-unknown`. 两种都只剩声音和字幕, 不报错.
 *
 * 丢掉的那种也留成 `video/x-unknown`: 读完 TrackEntry 前把 CodecID 换成 media3 认、但同样记成未知格式的 [PLACEHOLDER_CODEC_ID].
 * 未知格式在 [Format.codecs] 里记下原来的编码 (FourCC 或 CodecID), 视频渲染器照样选上这条轨、开解码器时报错 (见 [UnplayableVideoTrack]),
 * 提示放不了时说得出是哪种格式. 由 [SubtitleFixingMkvExtractor] 调.
 */
@AndroidxOptIn(UnstableApi::class)
internal object MkvUnknownVideo {
    /** [codecId] 的画面轨 media3 会整条丢掉 */
    fun isDropped(trackType: Int, codecId: String?): Boolean =
        trackType == C.TRACK_TYPE_VIDEO && codecId != null && codecId !in MEDIA3_VIDEO_CODEC_IDS

    /** 在 [Format.codecs] 里记下原来编码的 [format]; 不是未知格式或已经有 codecs 时为 null */
    fun label(format: Format, codecId: String?, codecPrivate: ByteArray?): Format? {
        if (format.sampleMimeType != MimeTypes.VIDEO_UNKNOWN || format.codecs != null) return null
        val codec = if (codecId == MkvFourCcVideo.CODEC_ID_FOURCC) codecPrivate?.let(MkvFourCcVideo::fourCcOf) else codecId
        return format.buildUpon().setCodecs(codec ?: return null).build()
    }

    /** media3 认、记成 `video/x-unknown`、读 Block 时也没有特别处理的 CodecID */
    const val PLACEHOLDER_CODEC_ID = "V_THEORA"

    /** 同 media3 `MatroskaExtractor.isCodecSupported` 里的画面编码 */
    private val MEDIA3_VIDEO_CODEC_IDS = setOf(
        "V_VP8", "V_VP9", "V_AV1", "V_MPEG2", "V_MPEG4/ISO/SP", "V_MPEG4/ISO/ASP", "V_MPEG4/ISO/AP",
        "V_MPEG4/ISO/AVC", "V_MPEGH/ISO/HEVC", MkvFourCcVideo.CODEC_ID_FOURCC, PLACEHOLDER_CODEC_ID,
    )
}
