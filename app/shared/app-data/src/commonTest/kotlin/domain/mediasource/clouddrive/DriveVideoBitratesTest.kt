/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DriveVideoBitratesTest {
    @Test
    fun `bitrate labels`() {
        assertEquals("8.1 Mbps", DriveVideoBitrates.format(8_134_000))
        assertEquals("12.0 Mbps", DriveVideoBitrates.format(11_960_000))
        assertEquals("850 kbps", DriveVideoBitrates.format(850_000))
    }

    @Test
    fun `share files with a duration get their average bitrate`() {
        // 无职转生第三季第 1 集: 1.44 GB, 1420 秒
        val media = DriveShareMatch(
            FoundShare("bitrate-share", "", "无职转生Ⅲ"),
            DriveFile("e1", fileName = "S03E01.mkv", size = 1_443_789_299, isVideo = true, shareToken = "t", durationSeconds = 1420),
            emptyList(),
            EpisodeSort(1),
        ).toShareMedia(DrivePlaceholders(TestDrive.protocol), "share-search", "PanHunt", null)
        assertEquals("8.1 Mbps", DriveVideoBitrates.label(media.mediaId))
    }

    @Test
    fun `files without a duration are not recorded`() {
        DriveVideoBitrates.record("no-duration", DriveFile("e2", size = 1_000_000_000, isVideo = true))
        assertNull(DriveVideoBitrates.label("no-duration"))
    }
}
