/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

/**
 * 播放器因为设备放不了这个视频的某种编码格式而报错: 没有解码器、也不能直通 (如多数盒子上的 TrueHD / DTS 音频、老芯片上的 AV1),
 * 或者超出了解码器的能力 (如只能解 1080p 的芯片放 4K HEVC). 界面据此说清是哪种格式, 而不是笼统的「播放失败」.
 *
 * 从 ExoPlayer 渲染器异常的消息里认 (mediamp 的 PlaybackException 消息里带着它):
 * `MediaCodecAudioRenderer error, index=1, format=Format(2, 日语, video/x-matroska, audio/true-hd, …), format_supported=NO_UNSUPPORTED_TYPE`.
 * `format_supported=YES` 的不算: 那是格式支持、解码器自己出了错 (换个时机可能就好了).
 *
 * @param isAudio 是音频的格式; 否则是画面的
 * @param name 格式名 (见 [MediaFormatNames]); 画面的带上分辨率, 如 `HEVC 2160p`; H.264 的 10-bit、4:2:2、4:4:4 规格也写上 (`H.264 10-bit 1080p`),
 *   否则看着像连普通 H.264 都放不了
 */
data class UnsupportedFormat(
    val isAudio: Boolean,
    val name: String,
) {
    companion object {
        private val RENDERER_ERROR = Regex("""format=Format\((.*)\), format_supported=(\w+)""")
        private val MIME = Regex("""\b(?:audio|video)/[\w.+\-]+(?:;[\w=.\-]+)?""")

        /** Format 里编码串 `avc1.6E0029` 的 profile_idc (`6E`) */
        private val AVC_PROFILE = Regex("""\bavc[13]\.([0-9A-Fa-f]{2})""")

        /** 硬解多半不支持、值得写出来的 H.264 规格 */
        private val AVC_PROFILE_NAMES = mapOf(0x6E to "10-bit", 0x7A to "4:2:2", 0xF4 to "4:4:4", 0x2C to "4:4:4")

        /** Format 里 `[宽, 高, 帧率, 色彩]` 那一组 */
        private val VIDEO_SIZE = Regex("""\[(-?\d+), (-?\d+), """)

        /** cause 链的深度上限: 链可能自引用. */
        private const val MAX_DEPTH = 8

        /** 解析时认不出的画面格式 */
        private const val UNKNOWN_VIDEO = "video/x-unknown"

        /** Format 里紧跟编码 MIME [mime] 的编码串; 没写为 null */
        private fun codecsAfter(format: String, mime: String): String? =
            format.substringAfter("$mime, ", "").substringBefore(", ").takeIf { it.isNotEmpty() && it != "null" }

        /** [error] 或它的 cause 链上有设备放不了的格式时返回它, 否则 null. */
        fun of(error: Throwable?): UnsupportedFormat? {
            var current = error
            repeat(MAX_DEPTH) {
                val e = current ?: return null
                e.message?.let(::parse)?.let { return it }
                current = e.cause?.takeIf { it !== e }
            }
            return null
        }

        internal fun parse(message: String): UnsupportedFormat? {
            val match = RENDERER_ERROR.find(message) ?: return null
            val (format, support) = match.destructured
            if (support == "YES") return null
            // Format 依次写 id、名字、容器 MIME、编码 MIME: 有两个时第二个是编码的; 只有一个时是容器没写
            val mimes = MIME.findAll(format).map { it.value }.toList()
            val mime = (if (mimes.size >= 2) mimes[1] else mimes.firstOrNull()) ?: return null
            // 解析时就认不出的画面: 播放器在编码串里记了原来的编码 (FourCC 或 mkv 的 CodecID)
            val name = (if (mime == UNKNOWN_VIDEO) codecsAfter(format, mime)?.let(MediaFormatNames::ofCodec) else null)
                ?: MediaFormatNames.of(mime) ?: return null
            if (mime.startsWith("audio/")) return UnsupportedFormat(isAudio = true, name = name)
            val height = VIDEO_SIZE.find(format)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }
            val profile = if (mime == "video/avc") AVC_PROFILE.find(format)?.groupValues?.get(1)?.toIntOrNull(16)?.let(AVC_PROFILE_NAMES::get) else null
            val fullName = listOfNotNull(name, profile, height?.let { "${it}p" }).joinToString(" ")
            return UnsupportedFormat(isAudio = false, name = fullName)
        }
    }
}

/** 播放器里的编码格式 (MIME, 如 `audio/true-hd`) 在界面上的叫法. */
object MediaFormatNames {
    private val names = mapOf(
        "audio/true-hd" to "TrueHD",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD",
        "audio/vnd.dts.hd;profile=lbr" to "DTS Express",
        "audio/vnd.dts.uhd;profile=p2" to "DTS:X",
        "audio/ac3" to "AC3",
        "audio/eac3" to "E-AC3",
        "audio/eac3-joc" to "E-AC3 Atmos",
        "audio/ac4" to "AC4",
        "audio/mp4a-latm" to "AAC",
        "audio/flac" to "FLAC",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/mpeg" to "MP3",
        "audio/mpeg-L2" to "MP2",
        "audio/raw" to "PCM",
        "audio/alac" to "ALAC",
        "video/avc" to "H.264",
        "video/hevc" to "HEVC",
        "video/av01" to "AV1",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/dolby-vision" to "Dolby Vision",
        "video/mpeg2" to "MPEG-2",
        "video/wvc1" to "VC-1",
        "video/mp4v-es" to "MPEG-4",
        "video/divx" to "DivX",
        "video/mp43" to "DivX 3",
        "video/mp42" to "MS-MPEG4 v2",
        "video/mjpeg" to "MJPEG",
        "video/3gpp" to "H.263",
    )

    /** 解析时认不出的画面原来的编码: VfW 的 FourCC (大小写不论) 与 mkv 的 CodecID */
    private val codecNames = mapOf(
        "DIV3" to "DivX 3", "DIV4" to "DivX 3", "MP43" to "DivX 3", "MPG3" to "DivX 3",
        "MP42" to "MS-MPEG4 v2", "DIV2" to "MS-MPEG4 v2",
        "MP41" to "MS-MPEG4 v1", "MPG4" to "MS-MPEG4 v1",
        "WMV1" to "WMV7", "WMV2" to "WMV8", "WMV3" to "WMV9",
        "MJPG" to "MJPEG",
        "VP60" to "VP6", "VP61" to "VP6", "VP62" to "VP6", "VP6F" to "VP6",
        "FLV1" to "Sorenson Spark",
        "CVID" to "Cinepak",
        "V_MPEG1" to "MPEG-1",
        "V_THEORA" to "Theora",
        "V_MPEGI/ISO/VVC" to "H.266",
        "V_PRORES" to "ProRes",
        "V_FFV1" to "FFV1",
        "V_DIRAC" to "Dirac",
        "V_QUICKTIME" to "QuickTime",
        "V_UNCOMPRESSED" to "Uncompressed",
    )

    /** 表里没有的写 MIME 的子类型 (`audio/x-foo` → `X-FOO`); [mimeType] 为空时返回 null. */
    fun of(mimeType: String?): String? {
        if (mimeType.isNullOrBlank()) return null
        return names[mimeType] ?: mimeType.substringAfter('/').substringBefore(';').uppercase()
    }

    /**
     * 解析时认不出的画面原来的编码 ([codec] 是 VfW 的 FourCC 如 `DIV3`, 或 mkv 的 CodecID 如 `V_REAL/RV40`) 的叫法;
     * 表里没有的 CodecID 去掉 `V_` 前缀, FourCC 原样.
     */
    fun ofCodec(codec: String): String = when {
        codec.startsWith("V_REAL/") -> "RealVideo"
        else -> codecNames[codec] ?: codecNames[codec.uppercase()] ?: codec.removePrefix("V_")
    }
}
