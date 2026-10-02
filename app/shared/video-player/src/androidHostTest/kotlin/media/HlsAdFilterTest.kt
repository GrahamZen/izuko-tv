/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 去掉资源站插在正片里的广告段, 正常的断点不动.
 */
class HlsAdFilterTest {
    private val playlistUri = "https://cdn.test/20260116/abc/5198kb/hls/index.m3u8"

    private fun segments(count: Int, prefix: String, duration: Double = 4.0) =
        (1..count).joinToString("\n") { "#EXTINF:$duration,\n$prefix$it.ts" }

    private fun playlist(vararg blocks: String, endList: Boolean = true) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:4")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        append(blocks.joinToString("\n#EXT-X-DISCONTINUITY\n"))
        if (endList) append("\n#EXT-X-ENDLIST")
    }

    @Test
    fun `ad block from another directory is removed with its opener`() {
        val content = "/20260116/abc/5198kb/hls/c"
        val ad = "https://ads.test/20260917/xyz/hls/ad"
        val original = playlist(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"/key.key\"\n" + segments(100, content),
            "#EXT-X-KEY:METHOD=NONE\n" + segments(4, ad, duration = 5.0),
            "#EXT-X-KEY:METHOD=AES-128,URI=\"/key.key\"\n" + segments(100, "$content-b"),
        )
        val result = HlsAdFilter.strip(original, playlistUri)
        assertEquals(4, result.removedSegments)
        assertEquals(20.0, result.removedSeconds)
        assertFalse("ads.test" in result.playlist)
        // 广告块里的 KEY 保留, 后面正片的解密方式不变; 两段正片之间只剩一个断点
        assertTrue("#EXT-X-KEY:METHOD=NONE\n#EXT-X-DISCONTINUITY\n#EXT-X-KEY:METHOD=AES-128" in result.playlist)
        assertEquals(1, Regex("#EXT-X-DISCONTINUITY").findAll(result.playlist).count())
        assertTrue(result.playlist.endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun `relative segments resolve against the playlist`() {
        val original = playlist(
            segments(100, "c"),
            segments(3, "/20260917/xyz/hls/ad"),
            segments(100, "d"),
        )
        val result = HlsAdFilter.strip(original, playlistUri)
        assertEquals(3, result.removedSegments)
    }

    @Test
    fun `discontinuities between segments of the same directory are kept`() {
        val original = playlist(segments(6, "a"), segments(6, "b"), segments(6, "c"))
        val result = HlsAdFilter.strip(original, playlistUri)
        assertEquals(0, result.removedSegments)
        assertEquals(original, result.playlist)
    }

    @Test
    fun `content split across directories is not touched`() {
        // 后半段在另一个目录、被切成很多小块: 加起来超过两成, 不当广告
        val second = (1..10).map { segments(6, "/other/part2/s$it-") }.toTypedArray()
        val original = playlist(segments(150, "c"), *second)
        assertEquals(0, HlsAdFilter.strip(original, playlistUri).removedSegments)
    }

    @Test
    fun `live playlists are not touched`() {
        val original = playlist(segments(100, "c"), segments(3, "/ads/ad"), endList = false)
        assertEquals(0, HlsAdFilter.strip(original, playlistUri).removedSegments)
    }

    @Test
    fun `directory ignores host and query`() {
        assertEquals("/a/b", HlsAdFilter.directoryOf("https://x.test:65/a/b/c.ts?t=1", playlistUri))
        assertEquals("/a/b", HlsAdFilter.directoryOf("/a/b/c.ts", playlistUri))
        assertEquals("/20260116/abc/5198kb/hls", HlsAdFilter.directoryOf("c.ts", playlistUri))
    }
}
