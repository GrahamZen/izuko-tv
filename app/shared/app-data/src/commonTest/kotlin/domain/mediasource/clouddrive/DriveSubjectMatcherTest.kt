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
import me.him188.ani.app.domain.mediasource.search.TitleForms
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DriveSubjectMatcherTest {
    private class FakeBrowser(
        val searchResults: Map<String, List<DriveFile>>,
        val folders: Map<String, List<DriveFile>>,
        val searchError: Throwable? = null,
    ) : DriveBrowser {
        val searched = mutableListOf<String>()
        val listed = mutableListOf<String>()

        override suspend fun search(keyword: String): List<DriveFile> {
            searched += keyword
            searchError?.let { throw it }
            return searchResults[keyword].orEmpty()
        }

        override suspend fun listFolder(folderId: String): List<DriveFile> {
            listed += folderId
            return folders[folderId].orEmpty()
        }
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

    @Test
    fun `files far shorter than the episode are not this episode`() {
        val episodes = (1..12).map { MediaFetchRequest.Episode(episodeId = "e$it", sort = EpisodeSort(it), durationSeconds = 1450) }
        val request = request("间谍过家家", episodes = episodes)
        fun candidate(id: String, name: String, seconds: Int) = DriveSubjectMatcher.Candidate(
            DriveFile(id, fileName = name, size = 300L * 1024 * 1024, isVideo = true, durationSeconds = seconds),
            listOf("间谍过家家合集"),
        )
        // 短剧汇总里的「第06集」2 分 52 秒; 时长不知道的照旧按集号认
        val candidates = listOf(candidate("drama", "第06集.mp4", 172), candidate("ep6", "06.mp4", 1416), candidate("ep7", "07.mp4", 0))
        assertEquals(listOf("06.mp4@06", "07.mp4@07"), DriveSubjectMatcher.matchEpisodes(request, candidates).map { it.summary() })
        // 用户手挑的文件夹不查
        assertEquals(3, DriveSubjectMatcher.matchEpisodes(request, candidates, trustFolders = true).size)
    }
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
    fun `season numbered files of a subject the map numbers as one long season`() = runTest {
        // TMDB 把整部算一季接着排 (第四季第 1 集 = S1E78), 网盘按本季写成 S04E01; 另一套编号的 S05E01 与别的季的照旧不要
        val browser = FakeBrowser(
            searchResults = mapOf("Re：从零开始的异世界生活" to listOf(dir("root", "Re：从零开始的异世界生活 第四季"))),
            folders = mapOf(
                "root" to listOf(
                    video("s4e1", "Re.ZERO.S04E01.mkv"),
                    video("tmdb", "Re.ZERO.S01E79.mkv"),
                    video("s5e1", "Re.ZERO.S05E01.mkv"),
                    video("s3e1", "Re.ZERO.S03E01.mkv"),
                ),
            ),
        )
        val episodes = (1..2).map { MediaFetchRequest.Episode(episodeId = "$it", sort = EpisodeSort(77 + it), ep = EpisodeSort(it)) }
        val numbering = TmdbEpisodeNumbering { request -> request.episodes.associate { (1 to it.sort.number!!.toInt()) to it.sort } }

        val matched = DriveSubjectMatcher(browser, numbering).match(request("Re：从零开始的异世界生活 第四季", episodes = episodes))
        assertEquals(listOf("Re.ZERO.S01E79.mkv@79", "Re.ZERO.S04E01.mkv@01"), matched.map { it.summary() }.sorted())
    }

    @Test
    fun `folders of other seasons in a season split layer are not listed`() = runTest {
        val browser = FakeBrowser(
            searchResults = mapOf("葬送的芙莉莲" to listOf(dir("root", "葬送的芙莉莲"))),
            folders = mapOf(
                "root" to listOf(dir("s1", "Season 1"), dir("s2", "Season 2"), dir("s3", "Season 3"), dir("sp", "Specials")),
                "s1" to listOf(video("a1", "01.mp4")),
                "s2" to listOf(video("b1", "01.mp4")),
                "s3" to listOf(video("c1", "01.mp4")),
            ),
        )
        val matched = DriveSubjectMatcher(browser).match(request("葬送的芙莉莲 第二季"))
        assertEquals(listOf("b1"), matched.map { it.file.fid })
        assertEquals(listOf("root", "s2", "sp"), browser.listed)
    }

    @Test
    fun `a lone folder that names a season is still listed`() = runTest {
        // 总文件夹叫「第二季」, 里面连第一季一起放: 这一层没按季分, 照样列
        val browser = FakeBrowser(
            searchResults = mapOf("葬送的芙莉莲" to listOf(dir("root", "葬送的芙莉莲"))),
            folders = mapOf(
                "root" to listOf(dir("all", "葬送的芙莉莲 第二季")),
                "all" to listOf(dir("s1", "S01"), video("b1", "S02E01.mp4")),
                "s1" to listOf(video("a1", "S01E01.mp4")),
            ),
        )
        val matched = DriveSubjectMatcher(browser).match(request("葬送的芙莉莲"))
        assertEquals(listOf("a1"), matched.map { it.file.fid })
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

    /** 整个系列的合集: 按季分的文件夹用罗马数字写季, 另有外传与剧场版的文件夹, 视频都从 01 编起. */
    private fun franchiseCollection() = FakeBrowser(
        searchResults = mapOf("在地下城寻求邂逅是否搞错了什么" to listOf(dir("root", "Z 4k 在地下城寻求邂逅是否搞错了什么"))),
        folders = mapOf(
            "root" to listOf(
                dir("s1", "在地下城寻求邂逅是否搞错了什么 I"),
                dir("s2", "在地下城寻求邂逅是否搞错了什么 II"),
                dir("s4", "在地下城寻求邂逅是否搞错了什么 IV"),
                dir("s5", "在地下城寻求邂逅是否搞错了什么 V"),
                dir("gaiden", "在地下城寻求邂逅是否搞错了什么 外传 剑姬神圣谭"),
                dir("movie", "剧场版 -俄里翁之箭-"),
            ),
            "s1" to listOf(1, 13).map { video("s1e$it", "[TUDO&Ygm] DanMachi [${two(it)}][Ma10p_2160p].mkv") },
            "s2" to listOf(video("s2e1", "[TUDO&Ygm] DanMachi II [01][Ma10p_2160p].mkv")),
            "s4" to listOf(2, 13).map { video("s4e$it", "[TUDO&Ygm] Danmachi IV [${two(it)}][Ma10p_2160p].mkv") },
            "s5" to listOf(1, 13).map { video("s5e$it", "[Zdm] DanMachi V [${two(it)}][Ma10p_2160p].mkv") },
            "gaiden" to listOf(video("g1", "[TUDO&Ygm] DanMachi ~Sword Oratoria [01][Ma10p_2160p].mkv")),
            "movie" to listOf(video("m1", "[JYFanSub][Gekijouban Danmachi - Orion no Ya][01][GB][1080P][MP4].mp4")),
        ),
    )

    @Test
    fun `a franchise collection gives the first season only its own season folder`() = runTest {
        val browser = franchiseCollection()
        val matched = DriveSubjectMatcher(browser).match(request("在地下城寻求邂逅是否搞错了什么"))
        assertEquals(listOf("s1e1", "s1e13"), matched.map { it.file.fid })
        // 罗马数字写的别的季不往下列; 外传与剧场版的文件夹没写季, 列出来再按类别去掉
        assertEquals(listOf("root", "s1", "gaiden", "movie"), browser.listed)
    }

    @Test
    fun `a franchise collection gives a later season its roman numbered folder`() = runTest {
        // 第四季的第二段: sort 从 12 起; 第四季的文件夹按整季编号, 由选源按页面的编号方式挑那一集
        val episodes = (1..11).map { MediaFetchRequest.Episode(episodeId = "$it", sort = EpisodeSort(11 + it), ep = EpisodeSort(it)) }
        val matched = DriveSubjectMatcher(franchiseCollection()).match(request("在地下城寻求邂逅是否搞错了什么 第四季 深章 灾厄篇", episodes = episodes))
        assertEquals(listOf("s4e2", "s4e13"), matched.map { it.file.fid })
    }

    @Test
    fun `a movie subject keeps the movie folder`() = runTest {
        val matched = DriveSubjectMatcher(franchiseCollection()).match(request("剧场版 在地下城寻求邂逅是否搞错了什么 -俄里翁之箭-", "在地下城寻求邂逅是否搞错了什么"))
        assertTrue(matched.any { it.file.fid == "m1" })
        assertTrue(matched.none { it.file.fid == "g1" })
    }

    /** 条目名搜索用的关键词 (第一个名字的主标题). */
    private fun keywordOf(name: String) = TitleForms.mainTitle(name)

    /** 「赛博朋克：边缘行者 2」的分享: 第一季原盘与第二季并排, 原盘文件名不写 S (`01E06-E07`); 另有不写季的 4K 版本文件夹. */
    @Test
    fun `a subject TMDB puts in season 2 drops season 1 folders and keeps season 2 ones`() = runTest {
        val name = "赛博朋克：边缘行者 2"
        val browser = FakeBrowser(
            searchResults = mapOf(keywordOf(name) to listOf(dir("root", "赛博朋克：边缘行者 2"))),
            folders = mapOf(
                "root" to listOf(dir("s1", "S01"), dir("s2", "S02"), dir("s24k", "S02.4K")),
                "s1" to listOf(dir("remux", "2022.S01.1080p.Bluray.REMUX.AVC.DTS-HDMA5.1")),
                "remux" to listOf(video("old", "01E06-E07.2022.1080p.Bluray.REMUX.AVC.DTS-HDMA5.1.mkv")),
                "s2" to listOf(video("new", "S02E06.2026.1080p.NF.WEB-DL.DV.H265.DDP5.1.mkv")),
                "s24k" to listOf(video("uhd", "06.mkv")),
            ),
        )
        val episodes = (1..10).map { MediaFetchRequest.Episode("e$it", EpisodeSort(it)) }
        val numbering = TmdbEpisodeNumbering { request -> request.episodes.associate { (2 to it.sort.number!!.toInt()) to it.sort } }

        val matched = DriveSubjectMatcher(browser, numbering).match(request(name, episodes = episodes))
        assertEquals(listOf("new", "uhd"), matched.map { it.file.fid })
        assertEquals(listOf(EpisodeSort(6), EpisodeSort(6)), matched.map { it.episode })
        assertTrue("s1" !in browser.listed, "按季分好的一层不列第一季")
    }

    @Test
    fun `a folder season is looked up in the TMDB map like a file season`() = runTest {
        // 【我推的孩子】第二季: TMDB 记成 S1E12 起; 按 TMDB 整理的「Season 1」文件夹里只写集号的 12 是第二季第 1 集, 03 是第一季
        val name = "【我推的孩子】 第二季"
        val browser = FakeBrowser(
            searchResults = mapOf(keywordOf(name) to listOf(dir("root", "【我推的孩子】 (2023)"))),
            folders = mapOf("root" to listOf(dir("s1", "Season 1")), "s1" to listOf(video("e3", "03.mkv"), video("e12", "12.mkv"))),
        )
        val episodes = (1..13).map { MediaFetchRequest.Episode("e$it", EpisodeSort(11 + it), EpisodeSort(it)) }
        val numbering = TmdbEpisodeNumbering { request -> request.episodes.associate { (1 to it.sort.number!!.toInt()) to it.sort } }

        val matched = DriveSubjectMatcher(browser, numbering).match(request(name, episodes = episodes))
        assertEquals(listOf("12.mkv@12"), matched.map { it.summary() })
    }

    @Test
    fun `a later season from the series graph keeps folders that name it`() = runTest {
        // 京都动乱: TMDB 记成 S1E25 起, 系列关系图里是浪客剑心 (2023) 的下一季. 写「第二季」、从 01 编的文件夹是这一部,
        // 「第一季」文件夹里的 [01] 不是; 「附第1季」那个文件夹里接着编号的 [25] 按表认
        val name = "浪客剑心 明治剑客浪漫谭 京都动乱"
        val browser = FakeBrowser(
            searchResults = mapOf(keywordOf(name) to listOf(dir("root", "浪客剑心 明治剑客浪漫谭 京都动乱第二季 更46"))),
            folders = mapOf(
                "root" to listOf(
                    dir("s1", "[内嵌简日]第一季[24集全]"),
                    dir("s2", "L浪客剑心 明治剑客浪漫谭 第二季"),
                    dir("kd", "[附第1季]（浪客剑心 明治剑客浪漫谭 京都动乱；第二季）"),
                ),
                "s1" to listOf(video("old", "[orion origin] Rurouni Kenshin [01] [1080p].mp4")),
                "s2" to listOf(video("new", "01.mp4")),
                "kd" to listOf(video("abs", "[orion origin] Rurouni Kenshin - Kyoto Douran [25] [1080p].mp4")),
            ),
        )
        val episodes = (1..23).map { MediaFetchRequest.Episode("e$it", EpisodeSort(24 + it), EpisodeSort(it)) }
        val numbering = TmdbEpisodeNumbering { request -> request.episodes.associate { (1 to it.sort.number!!.toInt()) to it.sort } }
        val identity = DriveSubjectIdentity(ownNames = emptyList(), otherWorkNames = emptyList(), seriesSeason = 2, laterWork = true)

        val matched = DriveSubjectMatcher(browser, numbering, DriveSubjectIdentities { identity }).match(request(name, episodes = episodes))
        assertEquals(listOf("new@01", "abs@25"), matched.map { "${it.file.fid}@${it.episode}" })
        // 没有系列关系图时推不出是第二季: 照旧当第一季
        val fallback = DriveSubjectMatcher(browser, numbering).match(request(name, episodes = episodes))
        assertEquals(listOf("old", "abs"), fallback.map { it.file.fid })
    }

    @Test
    fun `a sequel TMDB lists as its own show reads S1 folders by what the path names`() = runTest {
        // 堀与宫村 -piece-: TMDB 单列成一部 (S1E1 起), 系列关系图里是第二季. 按系列整理的「堀与宫村 S1」是前传,
        // 「S2」与根下点名了 piece 的才是这一部; 文件名点名了 piece 的 `S01` 分享是这一部自己的第一季
        val name = "堀与宫村 -piece-"
        val browser = FakeBrowser(
            searchResults = mapOf(keywordOf(name) to listOf(dir("k", "K 堀与宫村 piece（附第1季）"), dir("p", "堀与宫村 -piece- (2023) S01"))),
            folders = mapOf(
                "k" to listOf(dir("s1", "堀与宫村 S1"), dir("s2", "堀与宫村 S2 更新中"), video("k9", "09.mp4")),
                "s1" to listOf(video("old", "[SweetSub] Horimiya - 01 [BDRip][1080P].mp4")),
                "s2" to listOf(video("new", "01.mp4")),
                "p" to listOf(video("tmdb", "[SweetSub&LoliHouse] Horimiya Piece - 01 [WebRip 1080p].mkv")),
            ),
        )
        val episodes = (1..13).map { MediaFetchRequest.Episode("e$it", EpisodeSort(it)) }
        val numbering = TmdbEpisodeNumbering { request -> request.episodes.associate { (1 to it.sort.number!!.toInt()) to it.sort } }
        // 「piece」太泛, 不当副标题 (没有 parts); 认路径用条目全名
        val identity = DriveSubjectIdentity(
            ownNames = listOf(TitleForms.compact(name)), otherWorkNames = emptyList(), seriesSeason = 2, laterWork = true,
        )

        val matched = DriveSubjectMatcher(browser, numbering, DriveSubjectIdentities { identity }).match(request(name, episodes = episodes))
        assertEquals(listOf("k9@09", "new@01", "tmdb@01"), matched.map { "${it.file.fid}@${it.episode}" }.sorted())
    }

    @Test
    fun `unmarked files stay when the season is only inferred`() = runTest {
        // 进击的巨人 最终季 Part.2: 名字里的「最终季」不是季号, TMDB 是 S4E17 起; 网盘按本段从 01 编、不写季
        val name = "进击的巨人 最终季 Part.2"
        val browser = FakeBrowser(
            searchResults = mapOf(keywordOf(name) to listOf(dir("root", "进击的巨人 最终季 Part.2 (2022)"))),
            folders = mapOf(
                "root" to listOf(dir("p2", "2022进击的巨人最终季Part.2"), dir("s3", "进击的巨人 第三季")),
                "p2" to listOf(video("new", "01.mp4")),
                "s3" to listOf(video("old", "01.mp4")),
            ),
        )
        val episodes = (1..12).map { MediaFetchRequest.Episode("e$it", EpisodeSort(75 + it), EpisodeSort(it)) }
        val numbering = TmdbEpisodeNumbering { request -> request.episodes.associate { (4 to 16 + it.ep!!.number!!.toInt()) to it.sort } }

        val matched = DriveSubjectMatcher(browser, numbering).match(request(name, episodes = episodes))
        assertEquals(listOf("new"), matched.map { it.file.fid })
    }

    @Test
    fun `a part numbered as its own show continues by the series sort`() = runTest {
        // 死神 千年血战篇-祸进谭-: TMDB 记成 S2E41 起, 这个分享把千年血战篇当单独一部写成 S01E41; 路径点名了祸进谭、集号又正是接续集号
        val name = "死神 千年血战篇-祸进谭-"
        val browser = FakeBrowser(
            searchResults = mapOf(keywordOf(name) to listOf(dir("root", "死神 千年血战篇 -祸进谭- 更48集"))),
            folders = mapOf(
                "root" to listOf(dir("s", "S死神")),
                "s" to listOf(video("e41", "S01E41.mkv"), video("e5", "S01E05.mkv")),
            ),
        )
        val episodes = (1..8).map { MediaFetchRequest.Episode("e$it", EpisodeSort(40 + it), EpisodeSort(it)) }
        val numbering = TmdbEpisodeNumbering { request -> request.episodes.associate { (2 to it.sort.number!!.toInt()) to it.sort } }
        val parts = DriveSeriesParts(
            keywords = listOf("祸进谭"),
            seriesNames = listOf(TitleForms.compact("死神 千年血战篇")),
            ownParts = listOf(TitleForms.compact("祸进谭")),
            otherParts = listOf("诀别谭", "相克谭").map(TitleForms::compact),
        )
        val identity = DriveSubjectIdentity(ownNames = emptyList(), otherWorkNames = emptyList(), parts = parts)

        val matched = DriveSubjectMatcher(browser, numbering, DriveSubjectIdentities { identity }).match(request(name, episodes = episodes))
        assertEquals(listOf("S01E41.mkv@41"), matched.map { it.summary() })
        // 路径没点名这一部时照旧按表: 不是这个条目
        val unnamed = DriveSubjectMatcher(browser, numbering).match(request(name, episodes = episodes))
        assertEquals(emptyList(), unnamed.map { it.summary() })
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
    fun `a single episode subject takes a lone video whatever number is left in its name`() {
        val request = request("言叶之庭", episodes = listOf(MediaFetchRequest.Episode("e1", EpisodeSort(1))))
        val lone = DriveSubjectMatcher.Candidate(video("movie", "【电影】言叶之庭.简日双语特效.酷漫404&拨雪寻春.2013.1080P.mp4"), listOf("言叶之庭"))
        assertEquals(listOf("【电影】言叶之庭.简日双语特效.酷漫404&拨雪寻春.2013.1080P.mp4@01"), DriveSubjectMatcher.matchEpisodes(request, listOf(lone)).map { it.summary() })
        // 一个文件夹里有好几个视频的照旧按集号认
        val series = (1..3).map { DriveSubjectMatcher.Candidate(video("tv$it", "0$it.mp4"), listOf("言叶之庭 TV")) }
        assertEquals(listOf("01.mp4@01", "02.mp4@02", "03.mp4@03"), DriveSubjectMatcher.matchEpisodes(request, series).map { it.summary() })
    }

    @Test
    fun `numbers in the subject name are not episode numbers`() {
        val episodes = (1..3).map { MediaFetchRequest.Episode("e$it", EpisodeSort(it)) }
        val request = request("秒速5厘米", episodes = episodes)
        val candidates = listOf(
            DriveSubjectMatcher.Candidate(video("a", "秒速5厘米 02.mp4"), listOf("秒速5厘米")),
            DriveSubjectMatcher.Candidate(video("b", "秒速5厘米.2007.BD1080P.mp4"), listOf("秒速5厘米 全片")),
        )
        assertEquals(listOf("秒速5厘米 02.mp4@02"), DriveSubjectMatcher.matchEpisodes(request, candidates).map { it.summary() })
    }
}
