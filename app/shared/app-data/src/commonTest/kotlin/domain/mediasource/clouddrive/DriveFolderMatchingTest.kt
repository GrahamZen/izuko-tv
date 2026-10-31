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
import me.him188.ani.app.domain.mediasource.search.TitleIdentities
import me.him188.ani.app.domain.mediasource.search.TitleIdentity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 指定的文件夹里什么番都有时不把别的番认进来; 在索引里按名字找时不受搜索请求那些个数限制. */
class DriveFolderMatchingTest {
    private class IndexBrowser(val folders: Map<String, List<DriveFile>>, override val searchesIndex: Boolean = true) : DriveBrowser {
        private val index = DriveLibraryIndex({ folders[it].orEmpty() }, { listOf(ROOT) }, { DriveLibraryConfig(depth = 3) })
        val searched = mutableListOf<String>()

        override suspend fun search(keyword: String): List<DriveFile> {
            searched += keyword
            return index.search(keyword)
        }

        override suspend fun listFolder(folderId: String): List<DriveFile> = folders[folderId].orEmpty()
    }

    private fun video(name: String) = DriveFile(name, fileName = name, size = 300L * 1024 * 1024, isVideo = true)
    private fun folder(id: String, name: String = id) = DriveFile(id, fileName = name, dir = true)

    private fun request(names: List<String>, fallback: List<String> = emptyList(), sort: Int = 4, episodes: Int = 12) = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNames = names,
        episodeSort = EpisodeSort(sort),
        episodeName = "",
        episodes = (1..episodes).map { MediaFetchRequest.Episode(episodeId = "e$it", sort = EpisodeSort(it)) },
        fallbackSearchKeywords = fallback,
    )

    private fun List<DriveSubjectMatcher.MatchedFile>.summary() = map { "${it.file.fileName}@${it.episode}" }

    private val toaru = "[KTXP][Toaru_ITEM][04][GB_CN][AV1_opus][1080p].mkv"
    private val mixed = listOf(
        video(toaru),
        video("[MingY] Romelia Senki [01][WebRip][JPSC].mp4"),
        video("[MingY] Romelia Senki [04][WebRip][JPSC].mp4"),
        video("BeanSubKusuriya_no_Hitorigoto_04CHS1080Px264_AAC.mp4"),
    )
    private val names = listOf("某暗部的少女共栖", "A Certain Dark Item", "Toaru Item")

    @Test
    fun `a picked folder holding several shows keeps only the videos naming this subject`() = runTest {
        val browser = IndexBrowser(mapOf("nagram" to mixed))
        val matched = DriveSubjectMatcher(browser).matchPickedFolder(request(names), "nagram", "Nagram")
        assertEquals(listOf("$toaru@04"), matched.summary())
    }

    @Test
    fun `videos without a title stay in a picked folder holding several shows`() = runTest {
        val browser = IndexBrowser(mapOf("nagram" to mixed + video("05.mkv") + video("第6集.mp4") + video("S01E07.mkv")))
        val matched = DriveSubjectMatcher(browser).matchPickedFolder(request(names), "nagram", "Nagram")
        assertEquals(listOf("$toaru@04", "05.mkv@05", "第6集.mp4@06", "S01E07.mkv@07"), matched.summary())
    }

    @Test
    fun `a picked folder where no video names the subject is kept whole`() = runTest {
        val browser = IndexBrowser(mapOf("mine" to listOf(video("[Sub] Some Other Title [01].mkv"), video("[Sub] Some Other Title [02].mkv"))))
        val matched = DriveSubjectMatcher(browser).matchPickedFolder(request(names), "mine", "mine")
        assertEquals(listOf("01", "02"), matched.map { it.episode.toString() })
    }

    @Test
    fun `a picked folder named after the subject is kept whole`() = runTest {
        val browser = IndexBrowser(mapOf("own" to listOf(video(toaru), video("[Sub] Another Title Of It [05].mkv"))))
        val matched = DriveSubjectMatcher(browser).matchPickedFolder(request(names), "own", "某暗部的少女共栖 合集")
        assertEquals(listOf("04", "05"), matched.map { it.episode.toString() })
    }

    @Test
    fun `folders below the picked folder can name the subject`() = runTest {
        val browser = IndexBrowser(
            mapOf(
                "all" to listOf(folder("dark", "A Certain Dark Item"), folder("romelia", "Romelia Senki")),
                "dark" to listOf(video("03.mkv")),
                "romelia" to listOf(video("04.mkv")),
            ),
        )
        val matched = DriveSubjectMatcher(browser).matchPickedFolder(request(names), "all", "全部")
        assertEquals(listOf("03.mkv@03"), matched.summary())
    }

    @Test
    fun `short names do not decide what stays in a picked folder`() = runTest {
        // 「Air」含在 Fairy Tail 里, 不能因此把写着别的标题的都去掉
        val browser = IndexBrowser(mapOf("mine" to listOf(video("Fairy Tail 01.mkv"), video("Kuuki 02.mkv"))))
        val matched = DriveSubjectMatcher(browser).matchPickedFolder(request(listOf("Air")), "mine", "mine")
        assertEquals(listOf("01", "02"), matched.map { it.episode.toString() })
    }

    @Test
    fun `an index compares every name of the subject`() = runTest {
        val others = (1..12).map { "别的名字第${it}个写法" }
        val browser = IndexBrowser(mapOf(ROOT to listOf(video(toaru))))
        val matched = DriveSubjectMatcher(browser).match(request(others + "Toaru Item"))
        assertEquals(listOf("$toaru@04"), matched.summary())
    }

    @Test
    fun `a search that sends requests still stops at the name limit`() = runTest {
        val others = (1..12).map { "别的名字第${it}个写法" }
        val browser = IndexBrowser(mapOf(ROOT to listOf(video(toaru))), searchesIndex = false)
        val matched = DriveSubjectMatcher(browser).match(request(others + "Toaru Item"))
        assertTrue(matched.isEmpty())
        assertFalse("Toaru Item" in browser.searched)
        assertEquals(DriveSubjectMatcher.NAME_KEYWORDS, browser.searched.count { it in others })
    }

    @Test
    fun `writing variants do not use up the fallback keywords of an index`() = runTest {
        val browser = IndexBrowser(mapOf(ROOT to listOf(video(toaru))))
        val matched = DriveSubjectMatcher(browser).match(request(listOf("某 暗部", "另 一个", "第 三个"), fallback = listOf("Toaru Item")))
        assertEquals(listOf("$toaru@04"), matched.summary())
        assertFalse("某暗部" in browser.searched)
    }

    @Test
    fun `fallback keywords fill in only the episodes the names did not find`() = runTest {
        val browser = IndexBrowser(
            mapOf(
                ROOT to listOf(folder("own", "某暗部的少女共栖"), folder("series", "魔法禁书目录")),
                "own" to listOf(video("某暗部的少女共栖 01.mkv")),
                "series" to listOf(video("魔法禁书目录 01.mkv"), video("魔法禁书目录 02.mkv")),
            ),
        )
        val matched = DriveSubjectMatcher(browser).match(request(listOf("某暗部的少女共栖"), fallback = listOf("魔法禁书目录"), sort = 1, episodes = 2))
        assertEquals(listOf("某暗部的少女共栖 01.mkv@01", "魔法禁书目录 02.mkv@02"), matched.summary())
    }

    @Test
    fun `fallback keywords are not compared once the names cover every episode`() = runTest {
        val browser = IndexBrowser(
            mapOf(
                ROOT to listOf(folder("own", "某暗部的少女共栖")),
                "own" to listOf(video("某暗部的少女共栖 01.mkv"), video("某暗部的少女共栖 02.mkv")),
            ),
        )
        DriveSubjectMatcher(browser).match(request(listOf("某暗部的少女共栖"), fallback = listOf("魔法禁书目录"), sort = 1, episodes = 2))
        assertEquals(listOf("某暗部的少女共栖"), browser.searched)
    }

    @Test
    fun `a folder of another season does not stop the fallback keywords of an index`() = runTest {
        val browser = IndexBrowser(
            mapOf(
                ROOT to listOf(folder("first", "某暗部的少女共栖 第一季"), folder("second", "Toaru Item 第二季")),
                "first" to listOf(video("某暗部的少女共栖 第一季 04.mkv")),
                "second" to listOf(video("04.mkv")),
            ),
        )
        val matched = DriveSubjectMatcher(browser).match(request(listOf("某暗部的少女共栖 第二季"), fallback = listOf("Toaru Item")))
        assertEquals(listOf("04.mkv@04"), matched.summary())
    }

    @Test
    fun `folders naming this subject are expanded before folders a short name happens to hit`() = runTest {
        val boxes = (1..7).map { folder("box$it", "Item Box $it") }
        val browser = IndexBrowser(
            mapOf(ROOT to boxes + folder("own", "Dark Item"), "own" to listOf(video("own 01.mkv"))) +
                    boxes.associate { it.fid to listOf(video("${it.fid} 01.mkv")) },
        )
        val identity = TitleIdentity(ownNames = listOf("darkitem"), otherWorkNames = emptyList())
        val matched = DriveSubjectMatcher(browser, identities = TitleIdentities { identity }).match(request(listOf("Item", "Dark Item"), sort = 1))
        assertTrue("own 01.mkv" in matched.map { it.file.fileName }, matched.summary().toString())
    }

    private companion object {
        const val ROOT = "root"
    }
}
