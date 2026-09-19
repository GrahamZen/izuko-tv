/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.directapi

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 数据源必须返回条目的**全部剧集**, 每条带自己的剧集号.
 *
 * 只返回当前集会在切集后整批消失: 同一条目各集共用一个 MediaFetchSession, 切集不重新 fetch,
 * 而选择器会把剧集号不是当前集的资源排除掉.
 */
@OptIn(ExperimentalEncodingApi::class)
class DirectApiAllEpisodesTest {
    private val requestedPaths = mutableListOf<String>()

    private fun engine() = MockEngine { request ->
        val path = request.url.encodedPath
        requestedPaths += path
        val body: ByteArray = when {
            path.startsWith("/search") -> Base64.Default.decode(SEARCH_RESPONSE)
            // 详情用来校验候选是不是要找的条目
            path == "/detail/901" -> BANGUMI_MATCH.encodeToByteArray()
            path.startsWith("/detail/") -> BANGUMI_MISMATCH.encodeToByteArray()
            path.startsWith("/episodes/") -> Base64.Default.decode(EPISODES_RESPONSE)
            path == "/vod/901/1" -> Base64.Default.decode(LINES_EP1)
            path == "/vod/901/2" -> Base64.Default.decode(LINES_EP2)
            path == "/vod/901/78" -> Base64.Default.decode(LINES_EP78)
            else -> ByteArray(0)
        }
        respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf())
    }

    /**
     * 两个候选**都**通过条目校验的站点: 排在前面的 900 是特别篇 (剧集里没有 bangumi 分集 id,
     * 只有一集), 901 才是正片.
     */
    private fun ambiguousEngine() = MockEngine { request ->
        val path = request.url.encodedPath
        requestedPaths += path
        val body: ByteArray = when {
            path.startsWith("/search") -> Base64.Default.decode(SEARCH_RESPONSE)
            path == "/detail/900" -> BANGUMI_MATCH_900.encodeToByteArray()
            path == "/detail/901" -> BANGUMI_MATCH.encodeToByteArray()
            path == "/episodes/900" -> Base64.Default.decode(EPISODES_NO_BANGUMI_ID)
            path.startsWith("/episodes/") -> Base64.Default.decode(EPISODES_RESPONSE)
            path == "/vod/901/1" -> Base64.Default.decode(LINES_EP1)
            path == "/vod/901/2" -> Base64.Default.decode(LINES_EP2)
            path == "/vod/901/78" -> Base64.Default.decode(LINES_EP78)
            else -> ByteArray(0)
        }
        respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf())
    }

    private fun config() = DirectApiMediaSourceArguments.Example.config.copy(baseUrl = "https://api.example.com")

    /** 正在看第 1 集, 但条目有三集. */
    private fun request() = MediaFetchRequest(
        subjectId = "633836",
        episodeId = "111",
        subjectNames = listOf("示例动画"),
        episodeSort = EpisodeSort(1),
        episodeName = "",
        episodes = listOf(
            MediaFetchRequest.Episode(episodeId = "111", sort = EpisodeSort(1)),
            MediaFetchRequest.Episode(episodeId = "222", sort = EpisodeSort(2)),
            MediaFetchRequest.Episode(episodeId = "333", sort = EpisodeSort(78)),
        ),
    )

    @Test
    fun `returns links of every episode with its own episode range`() = runTest {
        val links = DirectApiEngine(config(), HttpClient(engine()).asScopedHttpClient()).queryLinks(request())

        // 三集 x 每集两条线路
        assertEquals(6, links.size)
        assertEquals(
            listOf(EpisodeRange.single(EpisodeSort(1)), EpisodeRange.single(EpisodeSort(2)), EpisodeRange.single(EpisodeSort(78))),
            links.map { it.episodeRange }.distinct(),
        )
        // 每条地址都属于自己那一集
        for (link in links) {
            val sort = link.episodeRange?.knownSorts?.single().toString()
            assertTrue(link.url.contains("/ep$sort-"), "${link.url} 不属于第 $sort 集")
        }
    }

    @Test
    fun `does not label everything as the currently watched episode`() = runTest {
        val links = DirectApiEngine(config(), HttpClient(engine()).asScopedHttpClient()).queryLinks(request())

        // 这正是回归的形态: 全部标成当前集, 切到第二集就一条都不剩
        assertTrue(links.any { it.episodeRange != EpisodeRange.single(EpisodeSort(1)) }, "全被标成了当前集")
    }

    @Test
    fun `verifies the candidate subject instead of trusting the first search result`() = runTest {
        DirectApiEngine(config(), HttpClient(engine()).asScopedHttpClient()).queryLinks(request())

        // 搜索给了两个候选, 第一个的 bangumi id 对不上, 不能用它去取剧集
        assertTrue(requestedPaths.contains("/detail/900"), requestedPaths.toString())
        assertTrue(requestedPaths.contains("/episodes/901"), requestedPaths.toString())
        assertTrue(requestedPaths.none { it.startsWith("/episodes/900") }, requestedPaths.toString())
    }

    /**
     * 站点可能把特别篇与正片**都**标上同一个 bangumi 条目 id (实测「孤独摇滚！」就是这样, 而那个
     * 特别篇一条线路都没有, 选中它的结果就是本源 0 条)。两个都通过条目校验时, 要挑剧集能按 bangumi
     * 分集 id 对上的那个, 而不是排在前面的那个.
     */
    @Test
    fun `picks the candidate whose episodes match by bangumi episode id`() = runTest {
        val links = DirectApiEngine(config(), HttpClient(ambiguousEngine()).asScopedHttpClient()).queryLinks(request())

        // 两个候选的剧集都看过了, 才谈得上挑
        assertTrue(requestedPaths.contains("/episodes/900"), requestedPaths.toString())
        assertTrue(requestedPaths.contains("/episodes/901"), requestedPaths.toString())
        // 取地址只走对得上的那个条目: 特别篇能靠集号撞上第 1 集, 但它不是要找的条目
        assertTrue(requestedPaths.none { it.startsWith("/vod/900/") }, requestedPaths.toString())
        assertEquals(6, links.size)
    }
}

private const val BANGUMI_MATCH = """{"id":901,"sites":[{"site":"bangumi","id":"633836"}]}"""
private const val BANGUMI_MISMATCH = """{"id":900,"sites":[{"site":"bangumi","id":"1"}]}"""

/** 与 [BANGUMI_MATCH] 标着同一个 bangumi 条目 id 的另一个站内条目 (特别篇那种). */
private const val BANGUMI_MATCH_900 = """{"id":900,"sites":[{"site":"bangumi","id":"633836"}]}"""

private const val SEARCH_RESPONSE = "Cg4IhAcSCeWIq+eahOeVqgoRCIUHEgznpLrkvovliqjnlLs="
/** 一集, 有集号但 sites 里没有 bangumi 分集 id —— 特别篇条目的样子. */
private const val EPISODES_NO_BANGUMI_ID = "ChsQASoMCgR0bWRiEgR0dl85QgnnibnliKvnr4c="

private const val EPISODES_RESPONSE = "CikQASoMCgR0bWRiEgR0dl8xKg4KB2Jhbmd1bWkSAzExMUIH56ysMembhgopEAIqDAoEdG1kYhIEdHZfMSoOCgdiYW5ndW1pEgMyMjJCB+esrDLpm4YKKhBOKgwKBHRtZGISBHR2XzEqDgoHYmFuZ3VtaRIDMzMzQgjnrKw3OOmbhg=="
private const val LINES_EP1 = "ClcKM2FIUlgwY0hNNkx5OWpaRzR1WlhoaGJYQnNaUzVqYjIwdlpYQXhMVEV3T0RCUUxtMXdOQSII56ysMDHpm4YqCGFscGhhLTEwMgznpLrkvovliqjnlLsKUwoxYUhSWDBjSE02THk5alpHNHVaWGhoYlhCc1pTNWpiMjB2WlhBeExUY3lNRkF1YlhBMCII56ysMDHpm4YqBmJldGEtOTIM56S65L6L5Yqo55S7"
private const val LINES_EP2 = "ClcKM2FIUlgwY0hNNkx5OWpaRzR1WlhoaGJYQnNaUzVqYjIwdlpYQXlMVEV3T0RCUUxtMXdOQSII56ysMDLpm4YqCGFscGhhLTEwMgznpLrkvovliqjnlLsKUwoxYUhSWDBjSE02THk5alpHNHVaWGhoYlhCc1pTNWpiMjB2WlhBeUxUY3lNRkF1YlhBMCII56ysMDLpm4YqBmJldGEtOTIM56S65L6L5Yqo55S7"
private const val LINES_EP78 = "ClgKNGFIUlgwY0hNNkx5OWpaRzR1WlhoaGJYQnNaUzVqYjIwdlpYQTNPQzB4TURnd1VDNXRjRFEiCOesrDc46ZuGKghhbHBoYS0xMDIM56S65L6L5Yqo55S7ClUKM2FIUlgwY0hNNkx5OWpaRzR1WlhoaGJYQnNaUzVqYjIwdlpYQTNPQzAzTWpCUUxtMXdOQSII56ysNzjpm4YqBmJldGEtOTIM56S65L6L5Yqo55S7"
