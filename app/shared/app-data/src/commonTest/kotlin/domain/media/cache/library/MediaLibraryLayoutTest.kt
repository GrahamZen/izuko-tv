/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.library

import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaLibraryLayoutTest {
    private fun metadata(
        name: String = "孤独摇滚！",
        sort: String = "5",
        subjectId: String = "328609",
        episodeId: String = "1145141",
    ) = MediaCacheMetadata(
        subjectId = subjectId,
        episodeId = episodeId,
        subjectNameCN = name,
        subjectNames = listOf(name),
        episodeSort = EpisodeSort(sort),
        episodeName = "",
    )

    @Test
    fun `names carry the subject and episode ids`() {
        assertEquals("孤独摇滚！ [bgm-328609]", MediaLibraryLayout.subjectDirName(metadata()))
        assertEquals("孤独摇滚！ - 05 [bgmep-1145141]", MediaLibraryLayout.episodeBaseName(metadata()))
        assertEquals("孤独摇滚！ - 05 [bgmep-1145141] (2)", MediaLibraryLayout.episodeBaseName(metadata(), copy = 2))
    }

    @Test
    fun `only whole episodes under ten are padded`() {
        assertEquals("番 - 12 [bgmep-1]", MediaLibraryLayout.episodeBaseName(metadata(name = "番", sort = "12", episodeId = "1")))
        assertEquals("番 - 12.5 [bgmep-1]", MediaLibraryLayout.episodeBaseName(metadata(name = "番", sort = "12.5", episodeId = "1")))
        assertEquals("番 - 01 [bgmep-1]", MediaLibraryLayout.episodeBaseName(metadata(name = "番", sort = "1", episodeId = "1")))
    }

    @Test
    fun `characters the file system rejects are replaced and brackets made full width`() {
        val name = MediaLibraryLayout.subjectDirName(metadata(name = "Re:Zero / [第二季]?  "))
        assertEquals("Re Zero ［第二季］ [bgm-328609]", name)
        assertEquals("328609", MediaLibraryLayout.parseSubjectDir(name)?.subjectId)
    }

    @Test
    fun `episode files parse back to their ids and sort`() {
        val parsed = assertNotNull(MediaLibraryLayout.parseEpisodeFile("孤独摇滚！ - 05 [bgmep-1145141] (2).MKV"))
        assertEquals("1145141", parsed.episodeId)
        assertEquals("05", parsed.sort)
        assertEquals("孤独摇滚！", parsed.subjectName)
        assertEquals("mkv", parsed.extension)
    }

    @Test
    fun `subject folders parse back to their id and name`() {
        val parsed = assertNotNull(MediaLibraryLayout.parseSubjectDir("孤独摇滚！ [bgm-328609]"))
        assertEquals("328609", parsed.subjectId)
        assertEquals("孤独摇滚！", parsed.subjectName)
    }

    @Test
    fun `files that are not library videos are ignored`() {
        assertNull(MediaLibraryLayout.parseEpisodeFile("孤独摇滚！ - 05 [bgmep-1].mkv.izuko.json"))
        assertNull(MediaLibraryLayout.parseEpisodeFile("孤独摇滚！ - 05.mkv"))
        assertNull(MediaLibraryLayout.parseEpisodeFile(".下载中 - 05 [bgmep-1].mkv"))
        assertNull(MediaLibraryLayout.parseEpisodeFile("readme [bgmep-1].txt"))
        assertNull(MediaLibraryLayout.parseSubjectDir(".下载中"))
        assertNull(MediaLibraryLayout.parseSubjectDir("孤独摇滚！"))
    }

    @Test
    fun `a file with only the episode id still parses`() {
        val parsed = assertNotNull(MediaLibraryLayout.parseEpisodeFile("[bgmep-42].mp4"))
        assertEquals("42", parsed.episodeId)
        assertNull(parsed.sort)
        assertNull(parsed.subjectName)
    }

    @Test
    fun `caches without ids cannot be placed in the library`() {
        assertTrue(MediaLibraryLayout.canPlace(metadata()))
        assertFalse(MediaLibraryLayout.canPlace(metadata(subjectId = "")))
        assertFalse(MediaLibraryLayout.canPlace(metadata(episodeId = "")))
    }
}
