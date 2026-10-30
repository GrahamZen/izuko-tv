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
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * mkv 里按 VfW 兼容方式封的画面 (CodecID `V_MS/VFW/FOURCC`, CodecPrivate 是 BITMAPINFOHEADER), 老番的 DVDrip 常见 (从 AVI 转成 mkv 时默认这样封).
 * media3 读 mkv 时这种方式的 FourCC 只认 `DIVX` (认成 `video/divx`, 多数设备没有这个解码器)、`H263` 与 `WVC1`, XviD、H.264 等认成 `video/x-unknown`;
 * 没有解码器接, 画面轨选不上, 只放声音和字幕.
 * 照 media3 读 AVI 的认法 (`StreamFormatChunk`): MPEG-4 Part 2 (XviD、DivX 5 等) 当 `video/mp4v-es`, H.264 当 `video/avc`,
 * 都不带初始化数据 (MPEG-4 的 VOL 头、H.264 的 SPS / PPS 在码流里). 由 [SubtitleFixingMkvExtractor] 调.
 */
@AndroidxOptIn(UnstableApi::class)
internal object MkvFourCcVideo {
    /** 改认成设备能解的画面的 [format]; 不是 VfW 方式封的这几种画面时为 null */
    fun fill(format: Format, codecId: String?, codecPrivate: ByteArray?): Format? {
        if (codecId != CODEC_ID_FOURCC) return null
        val mimeType = fourCcOf(codecPrivate ?: return null)?.let(::mimeTypeOf) ?: return null
        if (format.sampleMimeType == mimeType) return null
        return format.buildUpon().setSampleMimeType(mimeType).build()
    }

    /** [fourCc] 对应的画面格式; 不是这里认的为 null */
    fun mimeTypeOf(fourCc: String): String? = when (fourCc) {
        in MPEG4_PART2 -> MimeTypes.VIDEO_MP4V
        in H264 -> MimeTypes.VIDEO_H264
        else -> null
    }

    /** BITMAPINFOHEADER 里的压缩方式 (biCompression, 第 16 字节起的 4 个字符); 不够长时为 null */
    fun fourCcOf(codecPrivate: ByteArray): String? =
        if (codecPrivate.size < FOURCC_OFFSET + 4) null else String(codecPrivate, FOURCC_OFFSET, 4, Charsets.ISO_8859_1)

    const val CODEC_ID_FOURCC = "V_MS/VFW/FOURCC"

    /** biSize、biWidth、biHeight (各 4 字节)、biPlanes、biBitCount (各 2 字节) 之后 */
    private const val FOURCC_OFFSET = 16

    /** 同 media3 读 AVI 时认成 `video/mp4v-es` 的那些 */
    private val MPEG4_PART2 = setOf("XVID", "xvid", "DIVX", "divx", "DX50", "FMP4", "3VID")

    /** media3 读 AVI 时认成 `video/avc` 的那些, 加上 x264 的 VfW 编码器写的 */
    private val H264 = setOf("H264", "h264", "avc1", "AVC1", "X264", "x264")
}
