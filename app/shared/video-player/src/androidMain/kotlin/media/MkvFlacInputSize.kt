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
import androidx.media3.extractor.FlacStreamMetadata

/**
 * mkv 里 FLAC 轨的解码器输入缓冲大小. media3 读 mkv 时不给 FLAC 轨设 [Format.maxInputSize], 系统 FLAC 解码器的输入缓冲按默认的 32 KiB 开,
 * 多声道的一帧 (6 声道常见 33~51 KB) 放不下, 每帧都报 `InsufficientCapacityException`, 一直没声音 (从中间续播时大帧多, 更容易碰到).
 * 照 media3 读 .flac 文件的做法, 按 CodecPrivate 里 STREAMINFO 记的最大帧长补上; 没记最大帧长时按不压缩的一帧算. 由 [SubtitleFixingMkvExtractor] 调.
 */
@AndroidxOptIn(UnstableApi::class)
internal object MkvFlacInputSize {
    /** 补上输入缓冲大小的 [format]; 不是 FLAC、已经有了或认不出 CodecPrivate 时为 null */
    fun fill(format: Format): Format? {
        if (format.sampleMimeType != MimeTypes.AUDIO_FLAC || format.maxInputSize != Format.NO_VALUE) return null
        val size = of(format.initializationData.firstOrNull() ?: return null) ?: return null
        return format.buildUpon().setMaxInputSize(size).build()
    }

    /**
     * CodecPrivate (「fLaC」+ 元数据块, 第一块是 STREAMINFO) 里能装下最大一帧的大小, 不小于解码器默认的 [DEFAULT_INPUT_SIZE]
     * (STREAMINFO 记小了也不比不设时更差); 认不出时为 null.
     */
    fun of(codecPrivate: ByteArray): Int? {
        if (codecPrivate.size < STREAMINFO_OFFSET + STREAMINFO_SIZE) return null
        if (!codecPrivate.copyOf(MARKER.size).contentEquals(MARKER)) return null
        // 块头第一个字节的低 7 位是块类型, STREAMINFO 是 0
        if (codecPrivate[MARKER.size].toInt() and 0x7F != 0) return null
        val info = FlacStreamMetadata(codecPrivate, STREAMINFO_OFFSET)
        val size = if (info.maxFrameSize > 0) {
            info.maxFrameSize
        } else {
            val raw = info.maxBlockSizeSamples * info.channels * ((info.bitsPerSample + 7) / 8)
            if (raw <= 0) return null
            raw + FRAME_OVERHEAD
        }
        return size.coerceAtLeast(DEFAULT_INPUT_SIZE)
    }

    private val MARKER = "fLaC".toByteArray(Charsets.US_ASCII)

    /** 「fLaC」与 STREAMINFO 的 4 字节块头之后 */
    private const val STREAMINFO_OFFSET = 8
    private const val STREAMINFO_SIZE = 34

    /** 不压缩时一帧比样本数据多出的帧头、子帧头与校验, 远小于这个数 */
    private const val FRAME_OVERHEAD = 1024

    /** 不设时系统 FLAC 解码器的输入缓冲大小 */
    const val DEFAULT_INPUT_SIZE = 32 * 1024
}
