/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.hls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HlsDisguisedSegmentsTest {
    @Test
    fun `strips the image in front of the ts stream`() {
        val ts = tsPackets(8)
        val unwrapped = unwrapDisguisedTs(PNG_1X1 + ts)
        assertContentEquals(ts, unwrapped)
    }

    @Test
    fun `keeps a plain ts segment as is`() {
        val ts = tsPackets(8)
        assertSame(ts, unwrapDisguisedTs(ts))
    }

    @Test
    fun `keeps data without a ts packet run as is`() {
        // 随机字节里偶尔出现的 0x47 不算包头; 连着几个包都对上才截
        val random = Random(42).nextBytes(64 * 1024)
        assertSame(random, unwrapDisguisedTs(random))
        val single = ByteArray(1024).also { it[100] = 0x47 }
        assertSame(single, unwrapDisguisedTs(single))
    }

    @Test
    fun `image like uris by path extension`() {
        assertTrue("https://cdn.example.com/file/abc/000.png".isImageLikeHlsUri())
        assertTrue("https://cdn.example.com/a/001.JPG?sign=1#x".isImageLikeHlsUri())
        assertFalse("https://cdn.example.com/a/001.ts".isImageLikeHlsUri())
        assertFalse("https://cdn.example.com/a/001.ts?cover=a.png".isImageLikeHlsUri())
    }

    @Test
    fun `playlist proxies segments only when unwrapping and segments look like images`() {
        val disguised = "#EXTM3U\n#EXTINF:10,\nhttps://cdn.example.com/000.png\n#EXT-X-ENDLIST\n"
        val plain = "#EXTM3U\n#EXTINF:10,\nhttps://cdn.example.com/000.ts\n#EXT-X-ENDLIST\n"
        val unwrap = HlsPlaybackOptions(unwrapDisguisedSegments = true)
        assertTrue(unwrap.proxiesSegmentsOf(disguised))
        assertFalse(unwrap.proxiesSegmentsOf(plain))
        assertFalse(HlsPlaybackOptions().proxiesSegmentsOf(disguised))
        assertTrue(HlsPlaybackOptions(proxySegments = true).proxiesSegmentsOf(plain))
        assertEquals(true, unwrap.isEnabled)
    }

    @Test
    fun `forwarding request headers proxies every segment`() {
        val plain = "#EXTM3U\n#EXTINF:10,\nhttps://cdn.example.com/000.ts\n#EXT-X-ENDLIST\n"
        val forward = HlsPlaybackOptions(forwardRequestHeaders = true)
        assertTrue(forward.proxiesSegmentsOf(plain))
        assertTrue(forward.isEnabled)
    }

    companion object {
        /** 站点垫在分片前面的那种 1×1 PNG (照抓到的那张裁掉了几个辅助块). */
        val PNG_1X1: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x18, 0x57, 0x63, 0xf8.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0x7f, 0x00, 0x09, 0xfb.toByte(), 0x03, 0xfd.toByte(), 0x05, 0x43, 0x45,
            0xca.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

        /** [count] 个 188 字节的 TS 包: 包头 0x47, 其余填充. */
        fun tsPackets(count: Int): ByteArray = ByteArray(count * 188) { if (it % 188 == 0) 0x47 else (it % 7).toByte() }
    }
}
