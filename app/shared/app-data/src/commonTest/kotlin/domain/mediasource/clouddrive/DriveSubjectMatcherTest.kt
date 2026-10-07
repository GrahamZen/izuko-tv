/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DriveSubjectMatcherTest {
    private class FakeBrowser(
        val searchResults: Map<String, List<DriveFile>>,
        val folders: Map<String, List<DriveFile>>,
        val searchError: Throwable? = null,
    ) : DriveBrowser {
        val searched = mutableListOf<String>()

        override suspend fun search(keyword: String): List<DriveFile> {
            searched += keyword
            searchError?.let { throw it }
            return searchResults[keyword].orEmpty()
        }

        override suspend fun listFolder(folderId: String): List<DriveFile> = folders[folderId].orEmpty()
    }

    private fun video(id: String, name: String, size: Long = 300L * 1024 * 1024) =
        DriveFile(id, fileName = name, size = size, isVideo = true)

    private fun request(
        vararg names: String,
        episodes: List<MediaFetchRequest.Episode> = emptyList(),
    ) = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNames = names.toList(),
        episodeSort = EpisodeSort(1),
        episodeName = "",
        episodes = episodes,
    )

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun DriveSubjectMatcher.MatchedFile.summary() = "${file.fileName}@${episode}"

    @Test
    fun `finds folder by english alias and expands it`() = runTest {
        val folderName = "Smoking.Behind.the.Supermarket.With.You.S01"
        val files = listOf(1, 3, 4, 5).map { video("f$it", "Smoking.Behind.the.Supermarket.With.You.S01E${two(it)}.mp4") } +
                video("sample", "Smoking.Behind.the.Supermarket.With.You.S01E06.mp4", size = 1024)
        val browser = FakeBrowser(
            searchResults = mapOf("Smoking Behind the Supermarket with You" to listOf(dir("d1", folderName))),
            folders = mapOf("d1" to files),
        )
        val matched = DriveSubjectMatcher(browser).match(
            request(
                "在超市后门吸烟的二人",
                "スーパーの裏でヤニ吸うふたり",
                "躲在超市后门抽烟的两人",
                "Super no Ura de Yani Suu Futari",
                "Smoking Behind the Supermarket with You",
            ),
        )
        assertEquals(listOf(1, 3, 4, 5).map { EpisodeSort(it) }, matched.map { it.episode })
        assertEquals(listOf(folderName), matched.first().folders)
        assertEquals(5, browser.searched.size)
    }

    @Test
    fun `picks the requested season from season folders`() = runTest {
        val browser = FakeBrowser(
            searchResults = mapOf("葬送的芙莉莲" to listOf(dir("root", "葬送的芙莉莲"))),
            folders = mapOf(
                "root" to listOf(dir("s1", "Season 1"), dir("s2", "Season 2")),
                "s1" to (1..3).map { video("a$it", "${two(it)}.mp4") },
                "s2" to (1..2).map { video("b$it", "${two(it)}.mp4") },
            ),
        )
        val matched = DriveSubjectMatcher(browser).match(request("葬送的芙莉莲 第二季"))
        assertEquals(listOf("b1", "b2"), matched.map { it.file.fid })
        assertEquals(listOf("葬送的芙莉莲", "Season 2"), matched.first().folders)
    }

    @Test
    fun `first season subject drops later seasons`() = runTest {
        val browser = FakeBrowser(
            searchResults = mapOf("葬送的芙莉莲" to listOf(dir("root", "葬送的芙莉莲"))),
            folders = mapOf(
                "root" to listOf(
                    video("a1", "Frieren.S01E01.mkv"),
                    video("b1", "Frieren.S02E01.mkv"),
                    dir("s2", "第二季"),
                ),
                "s2" to listOf(video("b2", "02.mkv")),
            ),
        )
        val matched = DriveSubjectMatcher(browser).match(request("葬送的芙莉莲"))
        assertEquals(listOf("a1"), matched.map { it.file.fid })
    }

    @Test
    fun `TMDB season-episode file names follow the episode map of the subject`() = runTest {
        // 天降之物f 的条目名不写季, 网盘按 TMDB 整理成 S02; 同一个文件夹里还混着第一季的一集
        val forte = (1..3).map { video("f$it", "Heaven's Lost Property.2009.S02E${two(it)}.mkv") }
        val browser = FakeBrowser(
            searchResults = mapOf("天降之物f" to listOf(dir("root", "天降之物 f"))),
            folders = mapOf("root" to forte + video("s1", "Heaven's Lost Property.2009.S01E01.mkv")),
        )
        val episodes = (1..3).map { MediaFetchRequest.Episode("e$it", EpisodeSort(it)) }
        // 对应表: 天降之物f 第 k 集 = TMDB S2Ek
        val numbering = TmdbEpisodeNumbering { request -> request.episodes.associate { (2 to it.sort.number!!.toInt()) to it.sort } }

        val matched = DriveSubjectMatcher(browser, numbering).match(request("天降之物f", episodes = episodes))
        assertEquals(listOf("f1@01", "f2@02", "f3@03"), matched.map { "${it.file.fid}@${it.episode}" }.sorted())

        // 表里没有这个条目时照旧按条目名认季: 名字不写季 = 第一季
        val fallback = DriveSubjectMatcher(browser).match(request("天降之物f", episodes = episodes))
        assertEquals(listOf("s1"), fallback.map { it.file.fid })
    }

    @Test
    fun `unmarked files of a later season need absolute episode numbers`() = runTest {
        val browser = FakeBrowser(
            searchResults = mapOf("葬送的芙莉莲" to listOf(dir("root", "葬送的芙莉莲"))),
            folders = mapOf("root" to listOf(3, 29, 30).map { video("e$it", "${two(it)}.mp4") }),
        )
        val episodes = (1..10).map {
            MediaFetchRequest.Episode(episodeId = "$it", sort = EpisodeSort(28 + it), ep = EpisodeSort(it))
        }
        val matched = DriveSubjectMatcher(browser).match(request("葬送的芙莉莲 第二季", episodes = episodes))
        assertEquals(listOf("29.mp4@29", "30.mp4@30"), matched.map { it.summary() })
    }

    @Test
    fun `drops loosely matched search results`() = runTest {
        val browser = FakeBrowser(
            searchResults = mapOf(
                "Smoking Behind the Supermarket with You" to listOf(
                    video("x", "Supermarket Sweep 01.mp4"),
                    video("y", "Smoking Behind the Supermarket with You - 02 [1080p].mkv"),
                ),
            ),
            folders = emptyMap(),
        )
        val matched = DriveSubjectMatcher(browser).match(request("Smoking Behind the Supermarket with You"))
        assertEquals(listOf("y"), matched.map { it.file.fid })
        assertEquals(EpisodeSort(2), matched.single().episode)
        assertEquals(emptyList(), matched.single().folders)
    }

    @Test
    fun `reports login errors instead of returning nothing`() = runTest {
        val browser = FakeBrowser(emptyMap(), emptyMap(), searchError = CloudDriveAuthException())
        assertFailsWith<CloudDriveAuthException> {
            DriveSubjectMatcher(browser).match(request("葬送的芙莉莲"))
        }
    }

    @Test
    fun `keywords are base titles without duplicates`() {
        assertEquals(
            listOf("葬送的芙莉莲", "葬送のフリーレン"),
            DriveSubjectMatcher.keywordsOf(listOf("葬送的芙莉莲 第二季", "葬送のフリーレン 第2期", "葬送的芙莉莲", "A")),
        )
    }
}
