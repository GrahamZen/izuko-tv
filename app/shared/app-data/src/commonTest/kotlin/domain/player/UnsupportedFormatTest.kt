/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnsupportedFormatTest {
    private fun error(message: String, cause: Throwable? = null) = RuntimeException(message, cause)

    @Test
    fun `audio the device has no decoder for`() {
        // 用户日志原文: Amlogic 盒子放蓝光原盘的 TrueHD
        val message = "ExoPlayer playback failed: ERROR_CODE_DECODER_INIT_FAILED (4001): MediaCodecAudioRenderer error, index=1, " +
            "format=Format(2, 日语, video/x-matroska, audio/true-hd, null, -1, ja, [-1, -1, -1.0, null], [8, 48000]), " +
            "format_supported=NO_UNSUPPORTED_TYPE"
        assertEquals(UnsupportedFormat(isAudio = true, name = "TrueHD"), UnsupportedFormat.of(error(message)))
    }

    @Test
    fun `video beyond the decoder carries the resolution`() {
        val message = "MediaCodecVideoRenderer error, index=0, format=Format(1, null, video/x-matroska, video/av01, av01.0.08M.10, -1, null, " +
            "[3840, 2160, 23.976, ColorInfo(BT2020, Limited range, SMPTE ST 2084 (PQ), false, 10bit Luma, 10bit Chroma)], [-1, -1]), " +
            "format_supported=NO_EXCEEDS_CAPABILITIES"
        assertEquals(UnsupportedFormat(isAudio = false, name = "AV1 2160p"), UnsupportedFormat.of(error(message)))
    }

    @Test
    fun `10-bit H264 is named so it does not read as plain H264`() {
        // Shield 原文: 网盘 BD 压制的 10-bit H.264 (avc1.6E = High 10)
        val message = "MediaCodecVideoRenderer error, index=0, format=Format(1, null, video/x-matroska, video/avc, avc1.6E0029, -1, und, " +
            "[1920, 1080, -1.0, ColorInfo(BT709, Limited range, SDR SMPTE 170M, false, NA, NA)], [-1, -1]), format_supported=NO_EXCEEDS_CAPABILITIES"
        assertEquals(UnsupportedFormat(isAudio = false, name = "H.264 10-bit 1080p"), UnsupportedFormat.of(error(message)))
        val high = message.replace("avc1.6E0029", "avc1.640029").replace("[1920, 1080", "[3840, 2160")
        assertEquals(UnsupportedFormat(isAudio = false, name = "H.264 2160p"), UnsupportedFormat.of(error(high)))
    }

    @Test
    fun `found down the cause chain and without a container`() {
        val inner = error(
            "MediaCodecAudioRenderer error, index=1, format=Format(3, null, null, audio/vnd.dts.hd;profile=lbr, null, -1, en, " +
                "[-1, -1, -1.0, null], [6, 48000]), format_supported=NO",
        )
        assertEquals(UnsupportedFormat(isAudio = true, name = "DTS Express"), UnsupportedFormat.of(error("Source error", inner)))
    }

    @Test
    fun `decoder failures of supported formats and other errors are not it`() {
        // 用户日志原文: 在线源的 AAC 解码器自己出错, 格式是支持的
        val decoderFailed = "ExoPlayer playback failed: ERROR_CODE_DECODING_FAILED (4003): MediaCodecAudioRenderer error, index=1, " +
            "format=Format(1/15, null, video/mp2t, audio/mp4a-latm, mp4a.40.2, -1, und, [-1, -1, -1.0, null], [0, 24000]), format_supported=YES"
        assertNull(UnsupportedFormat.of(error(decoderFailed)))
        assertNull(UnsupportedFormat.of(error("ExoPlayer playback failed: ERROR_CODE_IO_BAD_HTTP_STATUS (2004): Source error")))
        assertNull(UnsupportedFormat.of(null))
    }

    @Test
    fun `format names`() {
        assertEquals("E-AC3 Atmos", MediaFormatNames.of("audio/eac3-joc"))
        assertEquals("X-FOO", MediaFormatNames.of("audio/x-foo"))
        assertNull(MediaFormatNames.of(null))
    }
}
