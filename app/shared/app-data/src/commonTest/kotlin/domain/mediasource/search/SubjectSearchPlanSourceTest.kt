/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.search

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.fetch.computeFallbackSearchKeywords
import me.him188.ani.app.domain.mediasource.web.InMemoryWebSearchSessionCacheDao
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSource
import me.him188.ani.app.domain.mediasource.web.SelectorSearchConfig
import me.him188.ani.app.domain.mediasource.web.createTestSelectorMediaSource
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormatNoChannel
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatA
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchNameHints
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * 网页数据源按搜索计划搜: 「继 怪怪守护神」取首词的站用系列名搜, 只打开第二季的页; 同系列两季共用一次搜索 ([SiteSearchMemory]).
 */
class SubjectSearchPlanSourceTest {
    /** 站点按子串搜: 「怪怪守护神」两季都搜得到, 「继」什么都搜不到 (真站点会搜出一堆不相干的) */
    private class Site {
        val searchKeywords = mutableListOf<String>()
        val openedPages = mutableListOf<String>()
        val engine = MockEngine { request ->
            val url = request.url
            val html = when {
                url.encodedPath == "/search" -> {
                    val keyword = url.parameters["wd"].orEmpty()
                    searchKeywords += keyword
                    if ("怪怪守护神" in keyword) SEARCH_PAGE else EMPTY_SEARCH_PAGE
                }

                url.encodedPath.startsWith("/subject/") -> {
                    openedPages += url.encodedPath
                    SUBJECT_PAGE
                }

                else -> return@MockEngine respondError(HttpStatusCode.NotFound)
            }
            respond(html, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
        }
    }

    private val config = SelectorSearchConfig(
        searchUrl = "https://example.com/search?wd={keyword}",
        requestInterval = Duration.ZERO,
        subjectFormatId = SelectorSubjectFormatA.id,
        selectorSubjectFormatA = SelectorSubjectFormatA.Config(selectLists = "div.result > a"),
        channelFormatId = SelectorChannelFormatNoChannel.id,
        selectorChannelFormatNoChannel = SelectorChannelFormatNoChannel.Config(selectEpisodes = ".list > a"),
    )

    private fun request(id: String, names: List<String>, ownNames: List<String>, otherNames: List<String>, seriesNames: List<String>) =
        MediaFetchRequest(
            subjectId = id,
            episodeId = "1",
            subjectNameCN = names.first(),
            subjectNames = names,
            episodeSort = EpisodeSort(1),
            episodeName = "",
            episodeEp = EpisodeSort(1),
            episodes = (1..2).map { MediaFetchRequest.Episode(it.toString(), EpisodeSort(it), EpisodeSort(it)) },
            fallbackSearchKeywords = computeFallbackSearchKeywords(names, otherNames),
            nameHints = MediaFetchNameHints(ownNames = ownNames, seriesNames = seriesNames, otherWorkNames = otherNames),
        )

    private val secondSeason = request(
        id = "276792",
        names = listOf("继 怪怪守护神", "継つぐもも"),
        ownNames = listOf("继 怪怪守护神", "継つぐもも", "怪怪守护神 第2季"),
        otherNames = listOf("怪怪守护神", "つぐもも", "怪怪守护神 第1季"),
        seriesNames = listOf("怪怪守护神", "つぐもも"),
    )

    private val firstSeason = request(
        id = "192428",
        names = listOf("怪怪守护神", "つぐもも"),
        ownNames = listOf("怪怪守护神", "つぐもも", "怪怪守护神 第1季"),
        otherNames = listOf("继 怪怪守护神", "継つぐもも", "怪怪守护神 第2季"),
        seriesNames = emptyList(),
    )

    private suspend fun SelectorMediaSource.pagesOf(request: MediaFetchRequest): List<String> =
        fetch(request).results.toList().map { it.media.properties.subjectName.orEmpty() }.distinct()

    @Test
    fun `the second season is searched by the series name and only its page is opened`() = runTest {
        val site = Site()
        val source = createTestSelectorMediaSource(config, site.engine)

        assertEquals(listOf("怪怪守护神第二季"), source.pagesOf(secondSeason))
        assertEquals(listOf("怪怪守护神"), site.searchKeywords)
        assertEquals(listOf("/subject/2.html"), site.openedPages)
    }

    @Test
    fun `seasons of the same series share one search of the site`() = runTest {
        val site = Site()
        val source = createTestSelectorMediaSource(config, site.engine, cacheDao = InMemoryWebSearchSessionCacheDao(), cacheTtl = 1.hours)

        assertEquals(listOf("怪怪守护神第一季"), source.pagesOf(firstSeason))
        assertEquals(listOf("怪怪守护神第二季"), source.pagesOf(secondSeason))
        assertEquals(listOf("怪怪守护神"), site.searchKeywords)
        assertEquals(listOf("/subject/1.html", "/subject/2.html"), site.openedPages)
    }

    private companion object {
        val SEARCH_PAGE = """
            <html><body>
            <div class="result"><a href="/subject/2.html" title="怪怪守护神第二季">怪怪守护神第二季</a></div>
            <div class="result"><a href="/subject/1.html" title="怪怪守护神第一季">怪怪守护神第一季</a></div>
            </body></html>
        """.trimIndent()

        val EMPTY_SEARCH_PAGE = """
            <html><body><p>没有找到相关内容</p></body></html>
        """.trimIndent()

        val SUBJECT_PAGE = """
            <html><body>
            <div class="list"><a href="/play/1.html">第1集</a><a href="/play/2.html">第2集</a></div>
            </body></html>
        """.trimIndent()
    }
}
