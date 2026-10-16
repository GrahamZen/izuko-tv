/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndecodableVideoProfilesTest {
    @Test
    fun `profile from the codecs string`() {
        assertEquals(110, UndecodableVideoProfiles.avcProfileOf("avc1.6E0029"))
        assertEquals(100, UndecodableVideoProfiles.avcProfileOf("avc1.640028"))
        assertEquals(122, UndecodableVideoProfiles.avcProfileOf("avc3.7A0032"))
        // 音视频写在一起时取视频的
        assertEquals(244, UndecodableVideoProfiles.avcProfileOf("mp4a.40.2, avc1.F4001F"))
        assertNull(UndecodableVideoProfiles.avcProfileOf("hvc1.2.4.L120.B0"))
        assertNull(UndecodableVideoProfiles.avcProfileOf("avc1"))
        assertNull(UndecodableVideoProfiles.avcProfileOf(null))
    }

    @Test
    fun `only 10-bit and chroma upgraded H264 profiles are guarded`() {
        // 网盘 BD 压制的 10-bit H.264: 晶晨盒子不报错、出花屏
        assertTrue(UndecodableVideoProfiles.isGuarded(MimeTypes.VIDEO_H264, "avc1.6E0029"))
        assertTrue(UndecodableVideoProfiles.isGuarded(MimeTypes.VIDEO_H264, "avc1.7A0028"))
        assertTrue(UndecodableVideoProfiles.isGuarded(MimeTypes.VIDEO_H264, "avc1.F40028"))
        // 普通 High / Main 与别的编码不拦 (超出分辨率等能力的照常交给 ExoPlayer: 很多设备少报能力)
        assertFalse(UndecodableVideoProfiles.isGuarded(MimeTypes.VIDEO_H264, "avc1.640033"))
        assertFalse(UndecodableVideoProfiles.isGuarded(MimeTypes.VIDEO_H264, "avc1.4D401F"))
        assertFalse(UndecodableVideoProfiles.isGuarded(MimeTypes.VIDEO_H265, "hvc1.2.4.L150.B0"))
        assertFalse(UndecodableVideoProfiles.isGuarded(MimeTypes.VIDEO_H264, null))
    }
}
