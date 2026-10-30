/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MkvUnknownVideoTest {
    private val unknown = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_UNKNOWN).setWidth(720).setHeight(480).build()

    @Test
    fun `video codecs media3 drops are kept as unknown video`() {
        assertTrue(MkvUnknownVideo.isDropped(C.TRACK_TYPE_VIDEO, "V_REAL/RV40"))
        assertTrue(MkvUnknownVideo.isDropped(C.TRACK_TYPE_VIDEO, "V_MPEG1"))
        assertFalse(MkvUnknownVideo.isDropped(C.TRACK_TYPE_VIDEO, "V_MPEG4/ISO/AVC"))
        assertFalse(MkvUnknownVideo.isDropped(C.TRACK_TYPE_VIDEO, "V_MS/VFW/FOURCC"))
        // 别的轨照 media3 原样
        assertFalse(MkvUnknownVideo.isDropped(C.TRACK_TYPE_AUDIO, "A_REAL/COOK"))
        assertFalse(MkvUnknownVideo.isDropped(C.TRACK_TYPE_VIDEO, null))
    }

    @Test
    fun `unknown video remembers the original codec`() {
        assertEquals("V_REAL/RV40", MkvUnknownVideo.label(unknown, "V_REAL/RV40", null)?.codecs)
        assertEquals("V_THEORA", MkvUnknownVideo.label(unknown, "V_THEORA", null)?.codecs)
        // VfW 方式的记 FourCC
        val divx3 = ByteArray(40).also { "DIV3".toByteArray(Charsets.ISO_8859_1).copyInto(it, destinationOffset = 16) }
        assertEquals("DIV3", MkvUnknownVideo.label(unknown, "V_MS/VFW/FOURCC", divx3)?.codecs)
    }

    @Test
    fun `known formats and formats with codecs are left alone`() {
        val mpeg4 = unknown.buildUpon().setSampleMimeType(MimeTypes.VIDEO_MP4V).build()
        assertNull(MkvUnknownVideo.label(mpeg4, "V_MS/VFW/FOURCC", ByteArray(40)))
        assertNull(MkvUnknownVideo.label(unknown.buildUpon().setCodecs("V_MPEG1").build(), "V_THEORA", null))
        assertNull(MkvUnknownVideo.label(unknown, "V_MS/VFW/FOURCC", null))
    }
}
