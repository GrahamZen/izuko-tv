/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaAvFeaturesTest {
    private fun of(title: String) = MediaAvFeatures.fromTitle(title)

    @Test
    fun `release names with hdr and lossless audio`() {
        assertEquals(
            MediaAvFeatures(MediaDynamicRange.DolbyVision, MediaSurroundAudio.DolbyAtmos),
            of("Movie.2160p.UHD.BluRay.REMUX.HDR.DV.HEVC.TrueHD.7.1.Atmos-FGT.mkv"),
        )
        assertEquals(MediaAvFeatures(MediaDynamicRange.Hdr10Plus, MediaSurroundAudio.DtsHd), of("x.HDR10+.DTS-HD.MA"))
        assertEquals(MediaAvFeatures(MediaDynamicRange.Hdr10), of("HDR10"))
        assertEquals(MediaAvFeatures(MediaDynamicRange.Hlg), of("HLG"))
        assertEquals(MediaAvFeatures(MediaDynamicRange.Hdr), of("HDR"))
        assertEquals(MediaAvFeatures(MediaDynamicRange.DolbyVision), of("[Group] Show - 01 [WEB-DL 2160p DoVi]"))
        assertEquals(MediaAvFeatures(MediaDynamicRange.DolbyVision), of("Show.S01E01.Dolby.Vision.mkv"))
    }

    @Test
    fun `dts x spellings`() {
        assertEquals(MediaAvFeatures(audio = MediaSurroundAudio.DtsX), of("DTS-X"))
        assertEquals(MediaAvFeatures(audio = MediaSurroundAudio.DtsX), of("DTS:X"))
        assertEquals(MediaAvFeatures(audio = MediaSurroundAudio.TrueHd), of("Show.TrueHD.5.1"))
    }

    @Test
    fun `chinese names`() {
        assertEquals(MediaAvFeatures(MediaDynamicRange.DolbyVision, MediaSurroundAudio.DolbyAtmos), of("杜比视界 全景声"))
        assertEquals(MediaAvFeatures(MediaDynamicRange.DolbyVision, MediaSurroundAudio.DolbyAtmos), of("杜比視界 全景聲"))
    }

    @Test
    fun `source types and plain titles are not features`() {
        assertEquals(MediaAvFeatures.NONE, of("[VCB-Studio] X [Ma10p_2160p][x265_flac]"))
        // HDRip 是片源类型, 不是 HDR
        assertEquals(MediaAvFeatures.NONE, of("1080p HDRip"))
        assertEquals(MediaAvFeatures.NONE, of("DVDRip"))
        assertEquals(MediaAvFeatures.NONE, of("[Group] Show - 01 [1080p][AAC]"))
        assertEquals(MediaAvFeatures.NONE, of(""))
    }

    @Test
    fun `plus keeps the better of each`() {
        val title = MediaAvFeatures(MediaDynamicRange.Hdr, null)
        val probed = MediaAvFeatures(MediaDynamicRange.DolbyVision, MediaSurroundAudio.TrueHd)
        assertEquals(probed, title + probed)
        assertEquals(probed, probed + MediaAvFeatures.NONE)
    }
}
