/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media

import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformContainerSupportTest {
    @Test
    fun `avplayer cannot open mkv and similar containers`() {
        assertTrue(avPlayerCannotOpen("孤独摇滚 / Bocchi.the.Rock.S01E01.2022.1080p.mkv"))
        assertTrue(avPlayerCannotOpen("[Group] Title - 01 [1080p].MKV"))
        assertTrue(avPlayerCannotOpen("Title 01.flv"))
    }

    @Test
    fun `mp4 and names without an extension count as playable`() {
        assertFalse(avPlayerCannotOpen("孤独摇滚 / Bocchi.the.Rock.S01E01.2022.mp4"))
        assertFalse(avPlayerCannotOpen("Title.S01E01.2022"))
        assertFalse(avPlayerCannotOpen("孤独摇滚！ 第01集"))
    }

    @Test
    fun `a drive file in a container avplayer cannot open is unplayable`() {
        assertTrue(fileMedia("搜索 / 第01话 / Title.S01E01.2160p.DV.mkv").avPlayerCannotOpenFile())
        assertFalse(fileMedia("搜索 / 第01话 / Title.S01E01.1080p.mp4").avPlayerCannotOpenFile())
    }

    @Test
    fun `only file resources are judged by the title extension`() {
        val webPage = fileMedia("Title 01.mkv", ResourceLocation.WebVideo("https://example.com/play/1"))
        assertFalse(webPage.avPlayerCannotOpenFile())
    }

    private fun fileMedia(
        title: String,
        download: ResourceLocation = ResourceLocation.HttpStreamingFile("https://drive.invalid/file/1"),
    ): Media = createTestDefaultMedia(
        mediaId = "drive.1",
        mediaSourceId = "drive",
        originalUrl = "https://drive.invalid/folder/1",
        download = download,
        originalTitle = title,
        publishedTime = 0L,
        properties = createTestMediaProperties(),
        episodeRange = EpisodeRange.single(EpisodeSort(1)),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.WEB,
    )
}
