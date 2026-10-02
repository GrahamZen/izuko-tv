/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.maccms

import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 苹果 CMS 资源站: 搜索地址、片名对条目、线路与剧集.
 */
class MacCmsEngineTest {
    private fun vod(name: String, vararg lines: Pair<String, String>) = buildString {
        append("""{"vod_id":"${name.hashCode()}","vod_name":"$name",""")
        append(""""vod_play_from":"${lines.joinToString("$$$") { it.first }}",""")
        append(""""vod_play_url":"${lines.joinToString("$$$") { it.second }}"}""")
    }

    private fun episodes(vararg labels: String, host: String = "cdn.test", ext: String = "index.m3u8") =
        labels.joinToString("#") { "$it\$https://$host/${it.filter(Char::isDigit)}/$ext" }

    private fun response(vararg vods: String) = """{"code":1,"list":[${vods.joinToString(",")}]}"""

    private fun names(vararg vods: String) = MacCmsEngine.parseVodList(response(*vods).encodeToByteArray())

    private fun matched(subject: String, vararg vods: String, year: Int? = null) =
        MacCmsEngine.matchVods(listOf(subject), names(*vods), year).map { it.vod.name }

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

    private fun episode(sort: Int, ep: Int = sort) = MediaFetchRequest.Episode("e$sort", EpisodeSort(sort), EpisodeSort(ep))

    @Test
    fun `search url replaces ac and keeps other parameters`() {
        assertEquals(
            "https://a.test/api.php/provide/vod/?ac=detail&wd=%E8%8A%99",
            MacCmsEngine.searchUrlOf("https://a.test/api.php/provide/vod/", "芙"),
        )
        assertEquals(
            "https://a.test/api.php/provide/vod/?ac=detail&wd=x",
            MacCmsEngine.searchUrlOf("https://a.test/api.php/provide/vod/?ac=list", "x"),
        )
        assertEquals(
            "https://a.test/inc/api.php?type=1&ac=detail&wd=x",
            MacCmsEngine.searchUrlOf("https://a.test/inc/api.php?type=1&ac=list", "x"),
        )
    }

    @Test
    fun `season and variants decide which vod is the subject`() {
        val all = arrayOf(
            vod("葬送的芙莉莲第二季"),
            vod("葬送的芙莉莲"),
            vod("葬送的芙莉莲[电影解说]"),
            vod("葬送的芙莉莲中配版"),
        )
        assertEquals(listOf("葬送的芙莉莲第二季"), matched("葬送的芙莉莲 第二季", *all))
        assertEquals(listOf("葬送的芙莉莲"), matched("葬送的芙莉莲", *all))
    }

    @Test
    fun `trailing digit in subject name is a season`() {
        val all = arrayOf(vod("为美好的世界献上祝福！"), vod("为美好的世界献上祝福！第三季"), vod("为美好的世界献上祝福！ 第二季"))
        assertEquals(listOf("为美好的世界献上祝福！第三季"), matched("为美好的世界献上祝福！3", *all))
        assertEquals(listOf("为美好的世界献上祝福！"), matched("为美好的世界献上祝福！", *all))
    }

    @Test
    fun `a letter suffix is part of the title`() {
        assertEquals(listOf("天降之物F"), matched("天降之物f", vod("天降之物"), vod("天降之物F")))
    }

    @Test
    fun `year suffix must be the year the subject aired`() {
        val remakes = arrayOf(vod("凉宫春日的忧郁2009"), vod("凉宫春日的忧郁2006版"))
        assertEquals(listOf("凉宫春日的忧郁2006版"), matched("凉宫春日的忧郁", *remakes, year = 2006))
        assertEquals(emptyList(), matched("凉宫春日的忧郁", vod("凉宫春日的忧郁2009"), year = 2006))
        assertEquals(emptyList(), matched("凉宫春日的忧郁", *remakes))
        // 名字完全相同的优先, 不看年份
        assertEquals(listOf("凉宫春日的忧郁"), matched("凉宫春日的忧郁", vod("凉宫春日的忧郁"), vod("凉宫春日的忧郁2009"), year = 2006))
    }

    @Test
    fun `part two of a season is not a second season`() {
        assertEquals(emptyList(), matched("间谍过家家 第2部分", vod("间谍过家家 第二季")))
    }

    @Test
    fun `episode labels`() {
        assertEquals(EpisodeSort(1), MacCmsEngine.episodeNumberOf("第01集"))
        assertEquals(EpisodeSort(12), MacCmsEngine.episodeNumberOf("12"))
        assertEquals(EpisodeSort(3), MacCmsEngine.episodeNumberOf("EP03"))
        assertEquals(EpisodeSort("12.5"), MacCmsEngine.episodeNumberOf("第12.5集"))
        assertNull(MacCmsEngine.episodeNumberOf("第01-14集"))
        assertNull(MacCmsEngine.episodeNumberOf("HD中字"))
    }

    @Test
    fun `only playable lines - episodes mapped to the subject`() = runTest {
        val body = response(
            vod(
                "葬送的芙莉莲第二季",
                "xxyun" to episodes("第01集", "第02集", ext = "share"),
                "xxm3u8" to episodes("第01集", "第02集", "第03集"),
            ),
            vod("葬送的芙莉莲", "xxm3u8" to episodes("第01集")),
        )
        val urls = mutableListOf<String>()
        val engine = MacCmsEngine(MacCmsConfig(apiUrl = "https://a.test/api.php/provide/vod/"), "资源站") { url ->
            urls += url
            body.encodeToByteArray()
        }
        // 第二季在 bangumi 上接着第一季排: sort 29.., 本季第几集是 ep
        val links = engine.queryLinks(
            request("葬送的芙莉莲 第二季", episodes = listOf(episode(29, 1), episode(30, 2))),
        )
        assertEquals(1, urls.size)
        assertEquals(listOf(EpisodeRange.single(EpisodeSort(29)), EpisodeRange.single(EpisodeSort(30))), links.map { it.episodeRange })
        assertTrue(links.all { it.url.endsWith(".m3u8") && it.channel == "资源站" })
        assertEquals("葬送的芙莉莲 第二季", links.first().subjectName)
    }

    @Test
    fun `searches the main title when the season is a trailing digit`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MacCmsEngine(MacCmsConfig(apiUrl = "https://a.test/api.php/provide/vod/"), "资源站") { url ->
            urls += url
            response().encodeToByteArray()
        }
        engine.queryLinks(request("为美好的世界献上祝福！3"))
        assertEquals(MacCmsEngine.searchUrlOf("https://a.test/api.php/provide/vod/", "为美好的世界献上祝福"), urls.first())
    }

    @Test
    fun `a movie takes its only episode`() = runTest {
        val body = response(vod("你的名字。", "m3u8" to "HD中字\$https://cdn.test/movie/index.m3u8"))
        val engine = MacCmsEngine(MacCmsConfig(apiUrl = "https://a.test/api.php/provide/vod/"), "资源站") { body.encodeToByteArray() }
        val links = engine.queryLinks(request("你的名字。", episodes = listOf(episode(1))))
        assertEquals(listOf(EpisodeRange.single(EpisodeSort(1))), links.map { it.episodeRange })
    }

    @Test
    fun `unreachable api is an error - not an empty result`() = runTest {
        val engine = MacCmsEngine(MacCmsConfig(apiUrl = "https://a.test/api.php/provide/vod/"), "资源站") {
            throw IllegalStateException("HTTP 403")
        }
        assertFailsWith<IllegalStateException> { engine.queryLinks(request("葬送的芙莉莲")) }
    }
}
