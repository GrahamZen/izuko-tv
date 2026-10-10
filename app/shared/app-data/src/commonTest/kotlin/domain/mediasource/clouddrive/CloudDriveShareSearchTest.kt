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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「分享搜索」: 查固定的分享、搜站点、打开分享、按文件名对集.
 */
class CloudDriveShareSearchTest {
    private val links = DriveShareLinks(TestDrive.protocol)

    private val config = DriveShareSearchConfig(
        searchUrl = "https://site.test/api.php/provide/vod?ac=detail&wd={keyword}",
        itemsPath = "list",
        titlePath = "vod_name",
        linkPaths = listOf("vod_down_url", "vod_content"),
    )

    /** 资源站接口的样子: 一部对得上的番 (带测试网盘与别的网盘两个链接), 一部无关的. */
    private val siteResponse = """
        {"code":1,"list":[
          {"vod_name":"葬送的芙莉莲第二季","vod_down_url":"测试网盘${'$'}https://share.drive.test/s/share1#别家${'$'}https://other.example/s/xyz","vod_content":"<p>简介</p>"},
          {"vod_name":"别的番","vod_down_url":"测试网盘${'$'}https://share.drive.test/s/other"}
        ]}
    """.trimIndent()

    /** 分享 share1: 根下一个打乱了标题的文件夹, 里面是 S01 文件夹 (第一季) 和散放的几集 (站点说是第二季). */
    private val share1 = mapOf(
        TestDrive.SHARE_ROOT to listOf(dir("root", "ZSD芙莉莲 1-2季（更1）")),
        "root" to listOf(dir("s1", "S01"), video("e08", "08.mp4"), video("e09", "09.mp4")),
        "s1" to listOf(video("s1e01", "S01E01.mp4"), video("s1e02", "S01E02.mp4")),
    )

    private fun shares(
        folders: Map<String, Map<String, List<DriveFile>>>,
        unavailable: Set<String> = emptySet(),
    ) = FakeShares(folders.toMutableMap(), unavailable = unavailable.toMutableSet(), defaultTitle = { "打乱的标题" })

    private val FakeShares.openedIds get() = opened.map { it.first }

    private fun request(vararg names: String, episode: Int = 1) = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNames = names.toList(),
        episodeSort = EpisodeSort(episode),
        episodeName = "",
    )

    private fun engine(
        shares: DriveShareBrowser,
        config: DriveShareSearchConfig = this.config,
        response: String = siteResponse,
        urls: MutableList<String> = mutableListOf(),
    ) = DriveShareSearchEngine(config, shares, links) { url ->
        urls += url
        response.encodeToByteArray()
    }

    // region 站点搜索

    @Test
    fun `first season gets the files in the S01 folder`() = runTest {
        val shares = shares(mapOf("share1" to share1))
        val urls = mutableListOf<String>()
        val matched = engine(shares, urls = urls).search(request("葬送的芙莉莲"))

        assertEquals(listOf("s1e01" to EpisodeSort(1), "s1e02" to EpisodeSort(2)), matched.map { it.file.fid to it.episode })
        assertEquals(listOf("ZSD芙莉莲 1-2季（更1）", "S01"), matched.first().folders)
        // 关键词做了 URL 编码; 剧名对不上的那部的分享不打开
        assertEquals(listOf("https://site.test/api.php/provide/vod?ac=detail&wd=%E8%91%AC%E9%80%81%E7%9A%84%E8%8A%99%E8%8E%89%E8%8E%B2"), urls)
        assertEquals(listOf("share1"), shares.openedIds)
    }

    @Test
    fun `second season gets the loose files - season taken from the site title`() = runTest {
        val shares = shares(mapOf("share1" to share1))
        val matched = engine(shares).search(request("葬送的芙莉莲 第二季"))
        assertEquals(listOf("e08" to EpisodeSort(8), "e09" to EpisodeSort(9)), matched.map { it.file.fid to it.episode })
    }

    @Test
    fun `tries the next name only when the previous one found nothing`() = runTest {
        val shares = shares(mapOf("share1" to share1))
        val urls = mutableListOf<String>()
        val matched = engine(shares, urls = urls).search(request("没有这部", "葬送的芙莉莲", "Frieren"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
        assertEquals(2, urls.size)
    }

    @Test
    fun `unavailable share is skipped`() = runTest {
        val response = """{"list":[{"vod_name":"葬送的芙莉莲第二季","vod_down_url":"https://share.drive.test/s/dead https://share.drive.test/s/share1"}]}"""
        val shares = shares(mapOf("share1" to share1), unavailable = setOf("dead"))
        val matched = engine(shares, response = response).search(request("葬送的芙莉莲"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
        assertEquals(setOf("dead", "share1"), shares.openedIds.toSet())
    }

    @Test
    fun `web page search opens matching detail pages for share links`() = runTest {
        val pages = mapOf(
            "https://site.test/search?wd=%E8%91%AC%E9%80%81%E7%9A%84%E8%8A%99%E8%8E%89%E8%8E%B2" to """
                <div class="item"><h3><a href="/detail/1.html" title="葬送的芙莉莲第二季">葬送的芙莉莲第二季</a></h3></div>
                <div class="item"><h3><a href="/detail/2.html">别的番</a></h3></div>
            """.trimIndent(),
            "https://site.test/detail/1.html" to """
                <a data-clipboard-text="https://share.drive.test/s/share1">第1集</a><p>https://share.drive.test/s/share1</p>
            """.trimIndent(),
        )
        val urls = mutableListOf<String>()
        val htmlConfig = DriveShareSearchConfig(searchUrl = "https://site.test/search?wd={keyword}", detailLinkSelector = ".item h3 a")
        val shares = shares(mapOf("share1" to share1))
        val engine = DriveShareSearchEngine(htmlConfig, shares, links) { url -> urls += url; pages[url]?.encodeToByteArray() }

        val matched = engine.search(request("葬送的芙莉莲 第二季"))
        assertEquals(listOf("e08", "e09"), matched.map { it.file.fid })
        // 剧名对不上的详情页不打开
        assertEquals(2, urls.size)
        assertEquals(listOf("share1"), shares.openedIds)
    }

    @Test
    fun `share folders of other seasons are not listed`() = runTest {
        val shares = shares(
            mapOf(
                "share1" to mapOf(
                    TestDrive.SHARE_ROOT to listOf(dir("all", "葬送的芙莉莲 合集")),
                    "all" to listOf(dir("s1", "S1"), dir("s2", "S2"), dir("s3", "S3")),
                    "s1" to listOf(video("a1", "01.mp4")),
                    "s2" to listOf(video("b1", "01.mp4")),
                    "s3" to listOf(video("c1", "01.mp4")),
                ),
            ),
        )
        val matched = engine(shares).search(request("葬送的芙莉莲 第二季"))
        assertEquals(listOf("b1"), matched.map { it.file.fid })
        assertEquals(listOf(TestDrive.SHARE_ROOT, "all", "s2"), shares.listed.map { it.second })
    }

    @Test
    fun `shares naming the wanted season are opened first`() = runTest {
        // 站点按系列名搜到的顺序: 第二季、没写季的合集、第三季; 只能打开两个时先开第三季, 再开没写季的
        val response = """
            {"list":[
              {"vod_name":"无职转生Ⅱ 到了异世界就拿出真本事","vod_down_url":"https://share.drive.test/s/s2"},
              {"vod_name":"无职转生 合集","vod_down_url":"https://share.drive.test/s/all"},
              {"vod_name":"无职转生 第三季","vod_down_url":"https://share.drive.test/s/s3"}
            ]}
        """.trimIndent()
        val shares = shares(
            mapOf(
                "s3" to mapOf(TestDrive.SHARE_ROOT to listOf(video("s3e1", "01.mp4"))),
                "all" to mapOf(TestDrive.SHARE_ROOT to listOf(video("all-s3e2", "S03E02.mp4"))),
            ),
        )
        val matched = engine(shares, config.copy(maxShares = 2), response).search(request("无职转生 第三季 ～到了异世界就拿出真本事～"))
        assertEquals(setOf("s3", "all"), shares.openedIds.toSet())
        assertEquals(setOf("s3e1", "all-s3e2"), matched.map { it.file.fid }.toSet())
    }

    @Test
    fun `site failure gives no results`() = runTest {
        val engine = DriveShareSearchEngine(config, shares(emptyMap()), links) { null }
        assertTrue(engine.search(request("葬送的芙莉莲")).isEmpty())
    }

    @Test
    fun `title matching`() {
        val keyword = DriveNameParser.normalize("葬送的芙莉莲")
        assertTrue(DriveShareSearchEngine.titleMatches("葬送的芙莉莲第二季", keyword))
        assertTrue(DriveShareSearchEngine.titleMatches("葬送的芙莉莲", keyword))
        assertFalse(DriveShareSearchEngine.titleMatches("别的番", keyword))
        assertFalse(DriveShareSearchEngine.titleMatches("", keyword))
    }

    // endregion

    // region 固定的分享

    /**
     * 按番分好文件夹的合集 `coll`: 第一层是分类, 第二层是各部番; 文件夹名前面常带序号与画质标记.
     */
    private val collection = mapOf(
        TestDrive.SHARE_ROOT to listOf(
            dir("autumn", "2023 秋"),
            dir("slice", "日常系"),
            dir("idol", "偶像大师"),
            dir("idol-cg", "偶像大师 灰姑娘女孩"),
            dir("fantasy", "Fairy Tail"),
            dir("air", "[2005] AIR 1080p"),
        ),
        "autumn" to listOf(dir("frieren", "A 4k 葬送的芙莉莲"), dir("other", "别的番")),
        "frieren" to listOf(video("f01", "01.mp4"), video("f02", "02.mp4"), video("f03", "03.mp4")),
        "other" to listOf(video("o01", "01.mp4")),
        "slice" to listOf(dir("danshi", "男子高中生的日常"), dir("nichijou", "[TV] 日常 BD")),
        "danshi" to listOf(video("d01", "01.mp4")),
        "nichijou" to listOf(video("n01", "01.mp4")),
        "idol" to listOf(video("i01", "01.mp4")),
        "idol-cg" to listOf(video("c01", "01.mp4")),
        "fantasy" to listOf(video("t01", "01.mp4")),
        "air" to listOf(video("a01", "01.mp4")),
    )

    private val fixedConfig = DriveShareSearchConfig(shares = listOf("番剧合集 https://share.drive.test/s/coll?pwd=ab12"))

    @Test
    fun `fixed shares are matched by folder name and the passcode in the text is used`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val engine = engine(shares, fixedConfig)
        assertTrue(engine.isConfigured)

        val matched = engine.search(request("葬送的芙莉莲"))
        assertEquals(listOf("f01" to EpisodeSort(1), "f02" to EpisodeSort(2), "f03" to EpisodeSort(3)), matched.map { it.file.fid to it.episode })
        // 分享里从根起的路径; 文件夹名当作站点剧名
        assertEquals(listOf("2023 秋", "A 4k 葬送的芙莉莲"), matched.first().folders)
        assertEquals("A 4k 葬送的芙莉莲", matched.first().share.siteTitle)
        assertEquals("ab12", matched.first().share.passcode)
        assertTrue(shares.opened.all { it == "coll" to "ab12" })
    }

    @Test
    fun `fixed share index is listed once and cached`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val engine = engine(shares, fixedConfig)
        engine.search(request("葬送的芙莉莲"))
        engine.search(request("偶像大师"))
        // 往下列两层 (默认): 第二层的文件夹只为对番名列一次, 不再往下
        assertEquals(1, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
        assertEquals(1, shares.listed.count { it.second == "autumn" })
        assertEquals(0, shares.listed.count { it.second == "other" })
    }

    @Test
    fun `fixed share folder with exactly the subject name wins over the rest of the series`() = runTest {
        val matched = engine(shares(mapOf("coll" to collection)), fixedConfig).search(request("偶像大师"))
        assertEquals(listOf("i01"), matched.map { it.file.fid })
    }

    @Test
    fun `short subject names match fixed share folders by whole words only`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        assertEquals(listOf("n01"), engine(shares, fixedConfig).search(request("日常")).map { it.file.fid })
        assertEquals(listOf("a01"), engine(shares, fixedConfig).search(request("Air")).map { it.file.fid })
    }

    @Test
    fun `folder title drops leading index and quality tags`() {
        val frieren = DriveNameParser.normalize("葬送的芙莉莲")
        assertEquals(frieren, DriveShareSearchEngine.folderTitle("A 4k 葬送的芙莉莲"))
        assertEquals(frieren, DriveShareSearchEngine.folderTitle("1080p 葬送的芙莉莲"))
        assertTrue(DriveShareSearchEngine.folderMatches("A 4k 葬送的芙莉莲", frieren))
        // 番名不短时包含即可
        assertTrue(DriveShareSearchEngine.folderMatches("葬送的芙莉莲 第二季", frieren))
        assertFalse(DriveShareSearchEngine.folderMatches("别的番", frieren))
        // 短番名只认整个词
        val nichijou = DriveNameParser.normalize("日常")
        assertTrue(DriveShareSearchEngine.folderMatches("日常", nichijou))
        assertTrue(DriveShareSearchEngine.folderMatches("[TV] 日常 BD", nichijou))
        assertFalse(DriveShareSearchEngine.folderMatches("男子高中生的日常", nichijou))
        val air = DriveNameParser.normalize("Air")
        assertTrue(DriveShareSearchEngine.folderMatches("[2005] AIR 1080p", air))
        assertFalse(DriveShareSearchEngine.folderMatches("Fairy Tail", air))
    }

    @Test
    fun `an outer folder that matches is read instead of the inner ones`() = runTest {
        val series = mapOf(
            TestDrive.SHARE_ROOT to listOf(dir("all", "葬送的芙莉莲 全集")),
            "all" to listOf(dir("all-s1", "葬送的芙莉莲 S1"), video("x", "说明.mp4")),
            "all-s1" to listOf(video("s1e01", "S01E01.mp4"), video("s1e02", "S01E02.mp4")),
        )
        val shares = shares(mapOf("coll" to series))
        val matched = engine(shares, fixedConfig).search(request("葬送的芙莉莲"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
        // 里层只在读外层时列一次
        assertEquals(1, shares.listed.count { it.second == "all-s1" })
    }

    @Test
    fun `site search is skipped when the fixed shares have the episode`() = runTest {
        val shares = shares(mapOf("coll" to collection, "share1" to share1))
        val urls = mutableListOf<String>()
        val both = config.copy(shares = fixedConfig.shares)

        val found = engine(shares, both, urls = urls).search(request("葬送的芙莉莲", episode = 2))
        assertEquals(listOf("f01", "f02", "f03"), found.map { it.file.fid })
        assertEquals(emptyList(), urls)

        // 这一集固定的分享里没有: 再搜站点, 两边的结果都给
        val more = engine(shares, both, urls = urls).search(request("葬送的芙莉莲", episode = 5))
        assertEquals(listOf("f01", "f02", "f03", "s1e01", "s1e02"), more.map { it.file.fid })
        assertEquals(1, urls.size)
    }

    @Test
    fun `fixed shares that cannot be opened give nothing`() = runTest {
        val shares = shares(emptyMap(), unavailable = setOf("coll"))
        assertEquals(emptyList(), engine(shares, fixedConfig).search(request("葬送的芙莉莲")))
        assertFalse(engine(shares, DriveShareSearchConfig()).isConfigured)
        // 文字里没有这个网盘的分享链接也算没配置
        assertFalse(engine(shares, DriveShareSearchConfig(shares = listOf("https://other.example/s/xyz"))).isConfigured)
    }

    // endregion
}
