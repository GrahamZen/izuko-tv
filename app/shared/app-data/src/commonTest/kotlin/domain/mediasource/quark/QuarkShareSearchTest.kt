/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.quark

import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「夸克分享搜索」: 搜站点、打开分享、按文件名对集.
 */
class QuarkShareSearchTest {
    private val config = QuarkShareSearchConfig(
        searchUrl = "https://site.test/api.php/provide/vod?ac=detail&wd={keyword}",
        itemsPath = "list",
        titlePath = "vod_name",
        linkPaths = listOf("vod_down_url", "vod_content"),
    )

    /** 苹果 CMS 接口的样子: 一部对得上的番 (带夸克与百度两个链接), 一部无关的. */
    private val siteResponse = """
        {"code":1,"list":[
          {"vod_name":"葬送的芙莉莲第二季","vod_down_url":"夸克${'$'}https://pan.quark.cn/s/share1#百度${'$'}https://pan.baidu.com/s/xyz","vod_content":"<p>简介</p>"},
          {"vod_name":"别的番","vod_down_url":"夸克${'$'}https://pan.quark.cn/s/other"}
        ]}
    """.trimIndent()

    private class FakeShares(
        val folders: Map<String, Map<String, List<QuarkShareFile>>>,
        val unavailable: Set<String> = emptySet(),
    ) : QuarkShareBrowser {
        val opened = mutableListOf<String>()

        override suspend fun open(shareId: String, passcode: String): String {
            opened += shareId
            if (shareId in unavailable) throw QuarkShareUnavailableException(41004, "文件不存在")
            return "打乱的标题"
        }

        override suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<QuarkShareFile> =
            folders[shareId]?.get(folderId).orEmpty()
    }

    private fun dir(fid: String, name: String) = QuarkShareFile(fid = fid, fileName = name, dir = true)

    private fun video(fid: String, name: String) =
        QuarkShareFile(fid = fid, fileName = name, size = 500L * 1024 * 1024, category = "video", shareFidToken = "t-$fid")

    /** 分享 share1: 根下一个打乱了标题的文件夹, 里面是 S01 文件夹 (第一季) 和散放的几集 (站点说是第二季). */
    private val share1 = mapOf(
        "0" to listOf(dir("root", "ZSD芙莉莲 1-2季（更1）")),
        "root" to listOf(dir("s1", "S01"), video("e08", "08.mp4"), video("e09", "09.mp4")),
        "s1" to listOf(video("s1e01", "S01E01.mp4"), video("s1e02", "S01E02.mp4")),
    )

    private fun request(vararg names: String) = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNames = names.toList(),
        episodeSort = EpisodeSort(1),
        episodeName = "",
    )

    private fun engine(shares: QuarkShareBrowser, response: String = siteResponse, urls: MutableList<String> = mutableListOf()) =
        QuarkShareSearchEngine(config, shares) { url ->
            urls += url
            response.encodeToByteArray()
        }

    @Test
    fun `first season gets the files in the S01 folder`() = runTest {
        val shares = FakeShares(mapOf("share1" to share1))
        val urls = mutableListOf<String>()
        val matched = engine(shares, urls = urls).search(request("葬送的芙莉莲"))

        assertEquals(listOf("s1e01" to EpisodeSort(1), "s1e02" to EpisodeSort(2)), matched.map { it.file.fid to it.episode })
        assertEquals(listOf("ZSD芙莉莲 1-2季（更1）", "S01"), matched.first().folders)
        // 关键词做了 URL 编码; 剧名对不上的那部的分享不打开
        assertEquals(listOf("https://site.test/api.php/provide/vod?ac=detail&wd=%E8%91%AC%E9%80%81%E7%9A%84%E8%8A%99%E8%8E%89%E8%8E%B2"), urls)
        assertEquals(listOf("share1"), shares.opened)
    }

    @Test
    fun `second season gets the loose files - season taken from the site title`() = runTest {
        val shares = FakeShares(mapOf("share1" to share1))
        val matched = engine(shares).search(request("葬送的芙莉莲 第二季"))
        assertEquals(listOf("e08" to EpisodeSort(8), "e09" to EpisodeSort(9)), matched.map { it.file.fid to it.episode })
    }

    @Test
    fun `tries the next name only when the previous one found nothing`() = runTest {
        val shares = FakeShares(mapOf("share1" to share1))
        val urls = mutableListOf<String>()
        val matched = engine(shares, urls = urls).search(request("没有这部", "葬送的芙莉莲", "Frieren"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
        assertEquals(2, urls.size)
    }

    @Test
    fun `unavailable share is skipped`() = runTest {
        val response = """{"list":[{"vod_name":"葬送的芙莉莲第二季","vod_down_url":"https://pan.quark.cn/s/dead https://pan.quark.cn/s/share1"}]}"""
        val shares = FakeShares(mapOf("share1" to share1), unavailable = setOf("dead"))
        val matched = engine(shares, response).search(request("葬送的芙莉莲"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
        assertEquals(setOf("dead", "share1"), shares.opened.toSet())
    }

    @Test
    fun `web page search opens matching detail pages for share links`() = runTest {
        val pages = mapOf(
            "https://site.test/search?wd=%E8%91%AC%E9%80%81%E7%9A%84%E8%8A%99%E8%8E%89%E8%8E%B2" to """
                <div class="item"><h3><a href="/detail/1.html" title="葬送的芙莉莲第二季">葬送的芙莉莲第二季</a></h3></div>
                <div class="item"><h3><a href="/detail/2.html">别的番</a></h3></div>
            """.trimIndent(),
            "https://site.test/detail/1.html" to """
                <a data-clipboard-text="https://pan.quark.cn/s/share1">第1集</a><p>https://pan.quark.cn/s/share1</p>
            """.trimIndent(),
        )
        val urls = mutableListOf<String>()
        val htmlConfig = QuarkShareSearchConfig(searchUrl = "https://site.test/search?wd={keyword}", detailLinkSelector = ".item h3 a")
        val shares = FakeShares(mapOf("share1" to share1))
        val engine = QuarkShareSearchEngine(htmlConfig, shares) { url -> urls += url; pages[url]?.encodeToByteArray() }

        val matched = engine.search(request("葬送的芙莉莲 第二季"))
        assertEquals(listOf("e08", "e09"), matched.map { it.file.fid })
        // 剧名对不上的详情页不打开
        assertEquals(2, urls.size)
        assertEquals(listOf("share1"), shares.opened)
    }

    @Test
    fun `site failure gives no results`() = runTest {
        val engine = QuarkShareSearchEngine(config, FakeShares(emptyMap())) { null }
        assertTrue(engine.search(request("葬送的芙莉莲")).isEmpty())
    }

    @Test
    fun `extracts share links with passcode`() {
        assertEquals(
            listOf("abc123" to "", "def456" to "x9z1"),
            QuarkShareSearchEngine.extractShareLinks(
                "正片${'$'}https://pan.quark.cn/s/abc123#花絮${'$'}https://pan.quark.cn/s/def456?pwd=x9z1 百度 https://pan.baidu.com/s/1",
            ),
        )
    }

    @Test
    fun `title matching`() {
        val keyword = DriveNameParser.normalize("葬送的芙莉莲")
        assertTrue(QuarkShareSearchEngine.titleMatches("葬送的芙莉莲第二季", keyword))
        assertTrue(QuarkShareSearchEngine.titleMatches("葬送的芙莉莲", keyword))
        assertFalse(QuarkShareSearchEngine.titleMatches("别的番", keyword))
        assertFalse(QuarkShareSearchEngine.titleMatches("", keyword))
    }

    @Test
    fun `share file ref round trip`() {
        val ref = QuarkShareFileRef("share1", "x9z1", "fid#1", "tok/+=", "葬送的芙莉莲 S01E01 [1080p].mkv", 123456)
        val uri = ref.toUri()
        assertTrue(uri.startsWith("https://pan.quark.cn/s/share1#"))
        assertEquals(ref, QuarkShareFileRef.parse(uri))
        // 普通的分享链接与自己网盘的占位地址都不是
        assertNull(QuarkShareFileRef.parse("https://pan.quark.cn/s/share1"))
        assertNull(QuarkShareFileRef.parse(QuarkMediaSource.uriOf("f1")))
        assertNull(QuarkMediaSource.fileIdOf(uri))
        // 记下所在文件夹的; 以前没有这一项的地址照常解析
        val inFolder = ref.copy(folderId = "dir/1")
        assertEquals(inFolder, QuarkShareFileRef.parse(inFolder.toUri()))
        assertEquals("", QuarkShareFileRef.parse(uri)!!.folderId)
    }

    @Test
    fun `prune keeps the newest files`() {
        val files = (1..5).map { QuarkFile(fid = "f$it", fileName = "$it.mp4", updatedAt = it * 1000L) }.shuffled()
        assertEquals(listOf("f1", "f2"), QuarkDriveService.filesToPrune(files, keep = 3, keepSubtitles = 0).map { it.fid })
        assertTrue(QuarkDriveService.filesToPrune(files, keep = 5, keepSubtitles = 0).isEmpty())
    }

    @Test
    fun `prune counts videos and subtitles separately`() {
        val videos = (1..3).map { QuarkFile(fid = "v$it", fileName = "$it.mkv", updatedAt = it * 1000L) }
        val subtitles = (1..3).map { QuarkFile(fid = "s$it", fileName = "$it.sc.ass", updatedAt = it * 1000L + 500) }
        val stale = QuarkDriveService.filesToPrune((videos + subtitles).shuffled(), keep = 2, keepSubtitles = 1)
        assertEquals(setOf("v1", "s1", "s2"), stale.map { it.fid }.toSet())
    }
}
