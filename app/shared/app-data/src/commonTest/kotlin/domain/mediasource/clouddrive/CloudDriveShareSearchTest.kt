/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.domain.mediasource.search.TitleForms
import me.him188.ani.app.domain.mediasource.search.TitleIdentities
import me.him188.ani.app.domain.mediasource.search.TitleIdentity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
    fun `tries the next name when the shares found by the previous one have no episode of this work`() = runTest {
        // 第一个名字搜到的分享里没有视频, 第二个名字搜到的有
        val empty = mapOf(TestDrive.SHARE_ROOT to listOf(dir("root", "名字一 全集")), "root" to emptyList<DriveFile>())
        val shares = shares(mapOf("share1" to share1, "empty" to empty))
        val emptyResponse = """{"code":1,"list":[{"vod_name":"名字一 全集","vod_down_url":"测试网盘${'$'}https://share.drive.test/s/empty"}]}"""
        val engine = DriveShareSearchEngine(config, shares, links) { url ->
            (if ("wd=%E8%91%AC%E9%80%81" in url) siteResponse else emptyResponse).encodeToByteArray()
        }
        val matched = engine.search(request("名字一", "葬送的芙莉莲"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
        assertEquals(listOf("empty", "share1"), shares.openedIds)
    }

    @Test
    fun `tries the next name when the episodes found by the previous one do not cover the work`() = runTest {
        // 第一个名字搜到的分享只收了前两集, 第二个名字搜到的分享有第三集
        val part = mapOf(TestDrive.SHARE_ROOT to listOf(dir("p", "名字一")), "p" to listOf(video("p01", "01.mp4"), video("p02", "02.mp4")))
        val rest = mapOf(TestDrive.SHARE_ROOT to listOf(dir("r", "名字二")), "r" to listOf(video("r03", "03.mp4")))
        val shares = shares(mapOf("part" to part, "rest" to rest))
        val urls = mutableListOf<String>()
        val engine = DriveShareSearchEngine(config, shares, links) { url ->
            urls += url
            val keyword = url.substringAfter("wd=").decodeURLQueryComponent()
            val (title, share) = if (keyword == "名字一") "名字一" to "part" else "名字二" to "rest"
            """{"code":1,"list":[{"vod_name":"$title","vod_down_url":"测试网盘${'$'}https://share.drive.test/s/$share"}]}""".encodeToByteArray()
        }
        val episodes = (1..3).map { MediaFetchRequest.Episode("e$it", EpisodeSort(it)) }
        val matched = engine.search(request("名字一", "名字二").copy(episodes = episodes))
        assertEquals(listOf("p01", "p02", "r03"), matched.map { it.file.fid }.sorted())
        assertEquals(listOf("名字一", "名字二"), urls.map { it.substringAfter("wd=").decodeURLQueryComponent() })

        // 第一个名字搜到的已经覆盖了每一集: 不再换名字搜
        urls.clear()
        engine.search(request("名字一", "名字二").copy(episodes = episodes.take(2)))
        assertEquals(listOf("名字一"), urls.map { it.substringAfter("wd=").decodeURLQueryComponent() })
    }

    @Test
    fun `a site that keeps failing is not searched with every keyword`() = runTest {
        // 站点一直重置连接: 换了词也一样, 连着两次就不再搜
        val urls = mutableListOf<String>()
        val engine = DriveShareSearchEngine(config, shares(mapOf("share1" to share1)), links) { url ->
            urls += url
            throw ShareSiteUnavailableException(url, Exception("Connection reset"))
        }
        assertEquals(emptyList(), engine.search(request("名字一", "名字二", "葬送的芙莉莲")))
        assertEquals(2, urls.size)
    }

    @Test
    fun `one failed search does not stop the next keyword`() = runTest {
        val urls = mutableListOf<String>()
        val engine = DriveShareSearchEngine(config, shares(mapOf("share1" to share1)), links) { url ->
            urls += url
            if (urls.size == 1) throw ShareSiteUnavailableException(url, Exception("Connection reset"))
            if ("wd=%E8%91%AC%E9%80%81" in url) siteResponse.encodeToByteArray() else """{"list":[]}""".encodeToByteArray()
        }
        val matched = engine.search(request("名字一", "葬送的芙莉莲"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
    }

    @Test
    fun `searches the site answered without results do not count as failures`() = runTest {
        // 回了 (哪怕是空的、4xx 当作没有) 就不算站点出错: 两个名字都没搜到, 第三个照样搜
        val urls = mutableListOf<String>()
        val engine = DriveShareSearchEngine(config.copy(maxKeywords = 3), shares(mapOf("share1" to share1)), links) { url ->
            urls += url
            when {
                "wd=%E8%91%AC%E9%80%81" in url -> siteResponse.encodeToByteArray()
                urls.size == 1 -> throw ShareSiteUnavailableException(url, Exception("HTTP 520"))
                else -> null
            }
        }
        val matched = engine.search(request("名字一", "名字二", "葬送的芙莉莲"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
    }

    @Test
    fun `a name with decorations is searched again without them before the next name`() = runTest {
        // 站点按标题原样找: 带全角破折号的名字搜不到, 去掉破折号的同一个名字搜得到; 搜到了就不再换下一个名字
        val shares = shares(mapOf("share1" to share1))
        val urls = mutableListOf<String>()
        val engine = DriveShareSearchEngine(config.copy(maxKeywords = 2), shares, links) { url ->
            urls += url
            // 只有「葬送」开头、不带全角破折号的关键词搜得到
            (if ("wd=%E8%91%AC%E9%80%81" in url && "%EF%BC%8D" !in url) siteResponse else """{"list":[]}""").encodeToByteArray()
        }
        val matched = engine.search(request("葬送的芙莉莲 －勇者一行－", "Frieren"))
        assertEquals(listOf("s1e01", "s1e02"), matched.map { it.file.fid })
        assertEquals(2, urls.size)
        assertTrue("%EF%BC%8D" !in urls[1] && "Frieren" !in urls[1])

        // 去掉符号、再去掉中日文之间的空格也搜不到时照常轮到下一个名字: 重搜的那几次不占名字的名额
        urls.clear()
        engine.search(request("别的番 －副标题－", "Frieren"))
        assertEquals(
            listOf("别的番 －副标题－", "别的番 副标题", "别的番副标题", "Frieren"),
            urls.map { it.substringAfter("wd=").decodeURLQueryComponent() },
        )
    }

    @Test
    fun `each share hands over its episodes as soon as it is read`() = runTest {
        val response = """{"list":[{"vod_name":"葬送的芙莉莲第二季","vod_down_url":"https://share.drive.test/s/share1 https://share.drive.test/s/share2"}]}"""
        val shares = shares(mapOf("share1" to share1, "share2" to share1))
        val batches = engine(shares, response = response).searchInBatches(request("葬送的芙莉莲")).toList()
        assertEquals(listOf(listOf("s1e01", "s1e02"), listOf("s1e01", "s1e02")), batches.filter { it.isNotEmpty() }.map { batch -> batch.map { it.file.fid } })
        assertEquals(setOf("share1", "share2"), batches.flatten().map { it.share.shareId }.toSet())
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
    fun `shares of other works in the same franchise are not opened`() = runTest {
        // 搜「机动战士高达」, 站点把系列里别的作品也一起给出 (名字以它开头)
        val response = """
            {"code":1,"list":[
              {"vod_name":"机动战士高达SEED FREEDOM","vod_down_url":"测试网盘${'$'}https://share.drive.test/s/seed"},
              {"vod_name":"机动战士高达 TV版","vod_down_url":"测试网盘${'$'}https://share.drive.test/s/tv"}
            ]}
        """.trimIndent()
        val shares = shares(
            mapOf(
                "seed" to mapOf(TestDrive.SHARE_ROOT to listOf(video("seed01", "01.mp4"))),
                "tv" to mapOf(TestDrive.SHARE_ROOT to listOf(video("tv01", "01.mp4"))),
            ),
        )
        val identity = TitleIdentity(ownNames = listOf("机动战士高达"), otherWorkNames = listOf("机动战士高达seed"), airYear = 1979)
        val engine = DriveShareSearchEngine(config, shares, links, identities = TitleIdentities { identity }) { response.encodeToByteArray() }

        val matched = engine.search(request("机动战士高达"))

        assertEquals(listOf("tv01"), matched.map { it.file.fid })
        assertEquals(listOf("tv"), shares.openedIds)
    }

    @Test
    fun `a lineage share folder of another work is dropped`() = runTest {
        val shares = shares(mapOf("seed" to mapOf("f" to listOf(video("seed01", "01.mp4")))))
        val identity = TitleIdentity(ownNames = listOf("机动战士高达"), otherWorkNames = listOf("机动战士高达seed"), airYear = 1979)
        val engine = DriveShareSearchEngine(config, shares, links, identities = TitleIdentities { identity }) { null }
        val seed = DriveShareLocation("seed", "", "机动战士高达SEED DESTINY HD重制 (2013)", "f", listOf("【动漫】机动战士高达SEED DESTINY HD重制 (2013)"))

        // null = 线索作废: 不再顺着它找; 也不去打开它
        assertEquals(null, engine.matchFolder(request("机动战士高达"), seed))
        assertEquals(emptyList(), shares.openedIds)
    }

    @Test
    fun `title matching`() {
        val keyword = TitleForms.compact("葬送的芙莉莲")
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

    /** 存在内存里的 [DriveShareIndexStore]: 当作应用重启前后都在的那份. */
    private class MemoryIndexStore : DriveShareIndexStore {
        val saved = HashMap<String, DriveShareIndex>()
        override suspend fun get(shareId: String): DriveShareIndex? = saved[shareId]
        override suspend fun put(shareId: String, index: DriveShareIndex) {
            saved[shareId] = index
        }
    }

    /** 每个都是新的搜索 (内存里什么也没有), 等于应用重启过. 后台重列跑在测试的虚拟时钟上. */
    private fun TestScope.fixedEngine(shares: DriveShareBrowser, store: DriveShareIndexStore, now: Long, depth: Int = 2) =
        DriveShareSearchEngine(
            fixedConfig.copy(shareIndexDepth = depth), shares, links,
            indexStore = store, now = { now }, backgroundScope = backgroundScope,
        ) { null }

    @Test
    fun `fixed share index is stored and reused after a restart`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val store = MemoryIndexStore()
        fixedEngine(shares, store, now = 1_000).search(request("葬送的芙莉莲"))
        assertEquals(setOf("coll"), store.saved.keys)

        val matched = fixedEngine(shares, store, now = 1_000 + 5.hours.inWholeMilliseconds).search(request("偶像大师"))
        assertEquals(listOf("i01"), matched.map { it.file.fid })
        assertEquals(1, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
    }

    @Test
    fun `stored fixed share index older than six hours is listed again for a subject it does not have`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val store = MemoryIndexStore()
        fixedEngine(shares, store, now = 1_000).search(request("葬送的芙莉莲"))
        // 合集后来加了一部
        shares.folders["coll"] = collection + mapOf(
            "autumn" to collection.getValue("autumn") + dir("new", "新的番"),
            "new" to listOf(video("x01", "01.mp4")),
        )
        val later = 1_000 + 7.hours.inWholeMilliseconds
        val matched = fixedEngine(shares, store, now = later).search(request("新的番"))
        assertEquals(listOf("x01"), matched.map { it.file.fid })
        assertEquals(2, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
        assertEquals(later, store.saved.getValue("coll").indexedAtMillis)
    }

    @Test
    fun `stored fixed share index older than six hours is used while its folder still holds the subject`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val store = MemoryIndexStore()
        fixedEngine(shares, store, now = 1_000).search(request("葬送的芙莉莲"))
        val matched = fixedEngine(shares, store, now = 1_000 + 7.hours.inWholeMilliseconds).search(request("偶像大师"))
        assertEquals(listOf("i01"), matched.map { it.file.fid })
        assertEquals(1, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
        assertEquals(1_000, store.saved.getValue("coll").indexedAtMillis)
    }

    @Test
    fun `stored fixed share index whose folder no longer holds the subject is listed again`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val store = MemoryIndexStore()
        fixedEngine(shares, store, now = 1_000).search(request("葬送的芙莉莲"))
        // 分享者把这部挪到了新文件夹, 旧文件夹不再共享 (列出来是空的)
        shares.folders["coll"] = collection - "frieren" + mapOf(
            "autumn" to listOf(dir("frieren-new", "A 4k 葬送的芙莉莲"), dir("other", "别的番")),
            "frieren-new" to listOf(video("n01", "01.mp4"), video("n02", "02.mp4")),
        )
        val matched = fixedEngine(shares, store, now = 1_000 + 7.hours.inWholeMilliseconds).search(request("葬送的芙莉莲"))
        assertEquals(listOf("n01", "n02"), matched.map { it.file.fid })
        assertEquals(2, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
    }

    /** 列分享 [slowShare] 的每一层都要等 [delayMillis] (虚拟时钟) 的 [DriveShareBrowser]. */
    private class SlowShares(
        private val inner: FakeShares,
        private val slowShare: String,
        private val delayMillis: Long,
    ) : DriveShareBrowser by inner {
        override suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<DriveFile> {
            if (shareId == slowShare) delay(delayMillis)
            return inner.listFolder(shareId, passcode, folderId)
        }
    }

    @Test
    fun `a fixed share hands over its folders without waiting for the other shares to be listed`() = runTest {
        val shares = shares(
            mapOf(
                "coll" to collection,
                "big" to mapOf(TestDrive.SHARE_ROOT to listOf(dir("big-other", "别的番"))),
            ),
        )
        val config = DriveShareSearchConfig(shares = listOf("番剧合集 https://share.drive.test/s/coll?pwd=ab12", "大合集 https://share.drive.test/s/big"))
        val engine = DriveShareSearchEngine(config, SlowShares(shares, slowShare = "big", delayMillis = 60_000), links) { null }

        val first = engine.searchInBatches(request("葬送的芙莉莲")).first { it.isNotEmpty() }
        assertEquals(listOf("f01", "f02", "f03"), first.map { it.file.fid })
        // 大合集还在列 (要 60 秒), 芙莉莲所在的合集列好就交出了
        assertTrue(currentTime < 60_000, "handed over at $currentTime ms")
    }

    @Test
    fun `an expired fixed share index is searched by name instead of listed again and is listed later in the background`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val store = MemoryIndexStore()
        fixedEngine(shares, store, now = 1_000).search(request("葬送的芙莉莲"))
        // 合集后来加了一部, 旧索引里没有
        shares.folders["coll"] = collection + mapOf(
            "autumn" to collection.getValue("autumn") + dir("new", "新的番"),
            "new" to listOf(video("x01", "01.mp4")),
        )
        shares.searchable = true
        val later = 1_000 + 7.hours.inWholeMilliseconds
        val matched = fixedEngine(shares, store, now = later).search(request("新的番"))

        assertEquals(listOf("x01"), matched.map { it.file.fid })
        assertEquals(listOf("coll" to "新的番"), shares.searched)
        // 搜的时候不重列, 过一会儿在后台重列存起来
        assertEquals(1, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
        advanceTimeBy(2.minutes)
        assertEquals(2, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
        assertEquals(later, store.saved.getValue("coll").indexedAtMillis)
    }

    @Test
    fun `a fixed share without an index is searched by name and only the found folder is read`() = runTest {
        val shares = shares(mapOf("coll" to collection)).apply { searchable = true }
        val matched = fixedEngine(shares, MemoryIndexStore(), now = 1_000).search(request("葬送的芙莉莲"))
        assertEquals(listOf("f01", "f02", "f03"), matched.map { it.file.fid })
        assertEquals(listOf("coll" to "frieren"), shares.listed)
    }

    @Test
    fun `a subject missing from the fixed shares is ruled out by name search without listing them`() = runTest {
        val shares = shares(mapOf("coll" to collection)).apply { searchable = true }
        val matched = fixedEngine(shares, MemoryIndexStore(), now = 1_000).search(request("没有这部番"))
        assertEquals(emptyList(), matched)
        assertEquals(emptyList(), shares.listed)
        assertTrue(shares.searched.isNotEmpty())
    }

    @Test
    fun `name search hits the collection folder only - the folder inside it is read with it`() = runTest {
        // 两个都不是完全同名, 同名优先管不着
        val nested = collection + mapOf(
            "autumn" to listOf(dir("frieren", "A 4k 葬送的芙莉莲 全集"), dir("other", "别的番")),
            "frieren" to listOf(dir("frieren-sp", "葬送的芙莉莲 特典"), video("f01", "01.mp4"), video("f02", "02.mp4")),
            "frieren-sp" to listOf(video("sp1", "SP01.mp4")),
        )
        val shares = shares(mapOf("coll" to nested)).apply { searchable = true }
        fixedEngine(shares, MemoryIndexStore(), now = 1_000).search(request("葬送的芙莉莲"))
        // 里层也搜得到、也认得出, 但已经套在读的那个文件夹里: 不单独再读一遍
        assertEquals(1, shares.listed.count { it.second == "frieren-sp" })
        assertEquals(1, shares.listed.count { it.second == "frieren" })
    }

    /** 按名字搜一律出错的网盘. */
    private class FailingSearch(private val inner: FakeShares) : DriveShareBrowser by inner {
        override suspend fun searchFolders(shareId: String, passcode: String, keyword: String): List<DriveFile>? =
            throw IOException("连不上")
    }

    @Test
    fun `a fixed share whose name search fails is listed instead`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val matched = fixedEngine(FailingSearch(shares), MemoryIndexStore(), now = 1_000).search(request("葬送的芙莉莲"))
        assertEquals(listOf("f01", "f02", "f03"), matched.map { it.file.fid })
        assertEquals(1, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
    }

    @Test
    fun `stored fixed share index listed to another depth is listed again`() = runTest {
        val shares = shares(mapOf("coll" to collection))
        val store = MemoryIndexStore()
        fixedEngine(shares, store, now = 1_000).search(request("葬送的芙莉莲"))
        fixedEngine(shares, store, now = 2_000, depth = 1).search(request("偶像大师"))
        assertEquals(2, shares.listed.count { it.second == TestDrive.SHARE_ROOT })
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
        val frieren = TitleForms.compact("葬送的芙莉莲")
        assertEquals(frieren, DriveShareSearchEngine.folderTitle("A 4k 葬送的芙莉莲"))
        assertEquals(frieren, DriveShareSearchEngine.folderTitle("1080p 葬送的芙莉莲"))
        assertTrue(DriveShareSearchEngine.folderMatches("A 4k 葬送的芙莉莲", frieren))
        // 番名不短时包含即可
        assertTrue(DriveShareSearchEngine.folderMatches("葬送的芙莉莲 第二季", frieren))
        assertFalse(DriveShareSearchEngine.folderMatches("别的番", frieren))
        // 短番名只认整个词
        val nichijou = TitleForms.compact("日常")
        assertTrue(DriveShareSearchEngine.folderMatches("日常", nichijou))
        assertTrue(DriveShareSearchEngine.folderMatches("[TV] 日常 BD", nichijou))
        assertFalse(DriveShareSearchEngine.folderMatches("男子高中生的日常", nichijou))
        val air = TitleForms.compact("Air")
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
