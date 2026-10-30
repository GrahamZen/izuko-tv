/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MkvFourCcVideoTest {
    private val unknown = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_UNKNOWN).setWidth(720).setHeight(480).build()

    @Test
    fun `xvid in vfw mode is read as mpeg4 video`() {
        // 实测黑屏的那个文件: XviD DVDrip, media3 认成 video/x-unknown
        val format = MkvFourCcVideo.fill(unknown, "V_MS/VFW/FOURCC", bitmapInfoHeader("XVID"))!!
        assertEquals(MimeTypes.VIDEO_MP4V, format.sampleMimeType)
        assertEquals(720, format.width)
        assertEquals(480, format.height)
    }

    @Test
    fun `other mpeg4 part 2 fourccs are read as mpeg4 video`() {
        for (fourCc in listOf("xvid", "divx", "DX50", "FMP4", "3VID")) {
            assertEquals(MimeTypes.VIDEO_MP4V, MkvFourCcVideo.fill(unknown, "V_MS/VFW/FOURCC", bitmapInfoHeader(fourCc))?.sampleMimeType, fourCc)
        }
        // media3 认成 video/divx 的, 多数设备没有这个解码器
        val divx = unknown.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DIVX).build()
        assertEquals(MimeTypes.VIDEO_MP4V, MkvFourCcVideo.fill(divx, "V_MS/VFW/FOURCC", bitmapInfoHeader("DIVX"))?.sampleMimeType)
    }

    @Test
    fun `h264 in vfw mode is read as h264`() {
        for (fourCc in listOf("H264", "h264", "avc1", "AVC1", "X264", "x264")) {
            assertEquals(MimeTypes.VIDEO_H264, MkvFourCcVideo.fill(unknown, "V_MS/VFW/FOURCC", bitmapInfoHeader(fourCc))?.sampleMimeType, fourCc)
        }
    }

    @Test
    fun `other fourccs and codec ids are left alone`() {
        assertNull(MkvFourCcVideo.fill(unknown, "V_MS/VFW/FOURCC", bitmapInfoHeader("WVC1")))
        assertNull(MkvFourCcVideo.fill(unknown, "V_MS/VFW/FOURCC", bitmapInfoHeader("MJPG")))
        assertNull(MkvFourCcVideo.fill(unknown, "V_THEORA", bitmapInfoHeader("XVID")))
        assertNull(MkvFourCcVideo.fill(unknown, "V_MS/VFW/FOURCC", null))
        // 不完整的 BITMAPINFOHEADER
        assertNull(MkvFourCcVideo.fill(unknown, "V_MS/VFW/FOURCC", bitmapInfoHeader("XVID").copyOf(18)))
    }

    /** VfW 方式的 CodecPrivate: 40 字节的 BITMAPINFOHEADER, 压缩方式是 [fourCc] */
    private fun bitmapInfoHeader(fourCc: String): ByteArray = ByteArray(40).also { header ->
        header[0] = 40
        fourCc.toByteArray(Charsets.ISO_8859_1).copyInto(header, destinationOffset = 16)
    }
}
