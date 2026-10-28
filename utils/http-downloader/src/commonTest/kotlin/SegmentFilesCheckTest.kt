/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 继续下载前对分段文件 (见 [checkSegmentFiles]): 早已合并完的认成完成, 文件没了的分段重下, 其余照常.
 */
class SegmentFilesCheckTest {
    private fun state(mediaType: MediaType, vararg sizes: Long, downloaded: Boolean = true) = DownloadState(
        downloadId = DownloadId("d"),
        url = "https://example.com/v",
        relativeOutputPath = "d.mp4",
        segments = sizes.mapIndexed { i, size ->
            SegmentInfo(index = i, url = "https://example.com/$i", isDownloaded = downloaded, byteSize = size, relativeTempFilePath = "segments_d/$i.part")
        },
        totalSegments = sizes.size,
        downloadedBytes = if (downloaded) sizes.sum() else 0,
        timestamp = 0,
        status = DownloadStatus.PAUSED,
        relativeSegmentCacheDir = "segments_d",
        requestHeaders = emptyMap(),
        mediaType = mediaType,
    )

    @Test
    fun `segments that are all there are left alone`() {
        assertEquals(SegmentFilesCheck.Intact, state(MediaType.MP4, 10, 20).checkSegmentFiles({ true }, outputSize = null))
    }

    @Test
    fun `segments not downloaded yet do not count as gone`() {
        val st = state(MediaType.MP4, 10, 20, downloaded = false)
        assertEquals(SegmentFilesCheck.Intact, st.checkSegmentFiles({ false }, outputSize = null))
    }

    @Test
    fun `an mp4 whose segments are gone after a finished merge is completed`() {
        assertEquals(SegmentFilesCheck.AlreadyMerged, state(MediaType.MP4, 10, 20).checkSegmentFiles({ false }, outputSize = 30))
        assertEquals(SegmentFilesCheck.AlreadyMerged, state(MediaType.MKV, 10, 20).checkSegmentFiles({ false }, outputSize = 30))
    }

    @Test
    fun `an mp4 whose output is empty or short is downloaded again`() {
        val st = state(MediaType.MP4, 10, 20)
        assertEquals(SegmentFilesCheck.Redownload(setOf(0, 1)), st.checkSegmentFiles({ false }, outputSize = 0))
        assertEquals(SegmentFilesCheck.Redownload(setOf(0, 1)), st.checkSegmentFiles({ false }, outputSize = 29))
        assertEquals(SegmentFilesCheck.Redownload(setOf(0, 1)), st.checkSegmentFiles({ false }, outputSize = null))
    }

    @Test
    fun `hls segments that are gone are downloaded again even with an output`() {
        val st = state(MediaType.M3U8, 10, 20)
        assertEquals(SegmentFilesCheck.Redownload(setOf(0, 1)), st.checkSegmentFiles({ false }, outputSize = 30))
    }

    @Test
    fun `only the segments whose files are gone are downloaded again`() {
        val st = state(MediaType.MP4, 10, 20, 30)
        assertEquals(SegmentFilesCheck.Redownload(setOf(1)), st.checkSegmentFiles({ it.index != 1 }, outputSize = 60))
    }

    @Test
    fun `segments to download again are marked not downloaded and their bytes are taken off`() {
        val st = state(MediaType.MP4, 10, 20, 30).withSegmentsToRedownload(setOf(1))
        assertEquals(listOf(true, false, true), st.segments.map { it.isDownloaded })
        assertEquals(40, st.downloadedBytes)
    }
}
