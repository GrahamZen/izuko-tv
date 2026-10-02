/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.media.MediaAvFeatures
import me.him188.ani.app.domain.media.MediaDynamicRange
import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals

class DriveMediaAvFeaturesTest {
    // 整包分享: 分享名把包里几个版本都写上了, 各版本在各自的文件夹里
    private val pack = FoundShare("av-pack", "", "边缘行者 第二季 (2026) 4K SDR HDR DV杜比视界 NF DDP5.1 全10集")

    private fun media(fid: String, folder: String, fileName: String, dolbyVision: Boolean = false) = DriveShareMatch(
        pack,
        DriveFile(
            fid, fileName = fileName, isVideo = true, shareToken = "t",
            avFeatures = if (dolbyVision) MediaAvFeatures(MediaDynamicRange.DolbyVision) else MediaAvFeatures.NONE,
        ),
        listOf(folder),
        EpisodeSort(4),
    ).toShareMedia(DrivePlaceholders(TestDrive.protocol), "share-search", "PanSou", null)

    @Test
    fun `share files are judged by their own file name - not the pack name`() {
        val hdr = media("hdr", "2026.S02.2160p.NF.WEB-DL.HDR.H.265.DDP5.1", "S02E04.2160p.NF.WEB-DL.DDP5.1.HDR.H.265.mkv")
        assertEquals(MediaAvFeatures(MediaDynamicRange.Hdr), MediaAvFeatures.of(hdr))
        val sdr = media("sdr", "2026.S02.1080p.NF.WEB-DL.H264.DDP5.1", "2026.S02E04.1080p.NF.WEB-DL.H264.DDP5.1.2Audios.mkv")
        assertEquals(MediaAvFeatures.NONE, MediaAvFeatures.of(sdr))
    }

    @Test
    fun `dolby vision flag from the share listing is used`() {
        val listed = media("dv", "2026.S02.1080p", "S02E04.mkv", dolbyVision = true)
        assertEquals(MediaAvFeatures(MediaDynamicRange.DolbyVision), MediaAvFeatures.of(listed))
    }
}
