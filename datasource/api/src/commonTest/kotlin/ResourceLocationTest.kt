/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api

import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.guessFromUrl
import me.him188.ani.datasources.api.topic.isVideoFileName
import me.him188.ani.datasources.api.topic.isVideoMimeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceLocationTest {
    @Test
    fun `guessFromUrl recognizes magnet and torrent`() {
        assertIs<ResourceLocation.MagnetLink>(ResourceLocation.guessFromUrl("magnet:?xt=urn:btih:abc"))
        assertIs<ResourceLocation.HttpTorrentFile>(ResourceLocation.guessFromUrl("https://example.com/a.torrent"))
    }

    @Test
    fun `guessFromUrl recognizes video files`() {
        for (url in listOf(
            "https://example.com/a.mp4",
            "https://example.com/a.mkv",
            "https://example.com/dir/index.m3u8",
            "http://example.com/a.flv",
            "https://example.com/a.mp4?token=1",
            "https://example.com/a.MP4",
        )) {
            assertIs<ResourceLocation.HttpStreamingFile>(ResourceLocation.guessFromUrl(url), url)
        }
    }

    @Test
    fun `guessFromUrl keeps the original url`() {
        val url = "https://example.com/a.mp4?token=1"
        assertEquals(url, ResourceLocation.guessFromUrl(url)?.uri)
    }

    @Test
    fun `guessFromUrl rejects pages and unknown schemes`() {
        assertNull(ResourceLocation.guessFromUrl("https://example.com/watch/1"))
        assertNull(ResourceLocation.guessFromUrl("https://example.com/a.html"))
        assertNull(ResourceLocation.guessFromUrl("ftp://example.com/a.mp4"))
    }

    @Test
    fun `isVideoFileName checks the extension`() {
        assertTrue(isVideoFileName("[Group] Title - 01 [1080P][CHT].mp4"))
        assertFalse(isVideoFileName("[Group] Title - 01 [1080P][CHT].torrent"))
        assertFalse(isVideoFileName("no extension"))
    }

    @Test
    fun `isVideoMimeType accepts video and hls`() {
        assertTrue(isVideoMimeType("video/mp4"))
        assertTrue(isVideoMimeType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertFalse(isVideoMimeType("application/x-bittorrent"))
    }
}
