/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.media.SelectorMediaSourceEpisodeCacheRepository
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormatNoChannel
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatA
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.contains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** 播放线索记着条目页时, 下一集直接打开这一页挑 ([SubjectPageMediaSource]), 不再搜索. */
class SelectorMediaSourceSubjectPageTest {
    private val requests = mutableListOf<String>()

    private val engine = MockEngine { request ->
        val url = request.url.toString()
        requests += url
        val html = when {
            url.startsWith("https://example.com/search") -> """
                <html><body><div class="result"><a href="/subject/5.html" title="铃芽之旅">铃芽之旅</a></div></body></html>
            """.trimIndent()

            url == "https://example.com/subject/5.html" -> """
                <html><body><div class="list"><a href="/play/1.html">第1集</a><a href="/play/2.html">第2集</a></div></body></html>
            """.trimIndent()

            else -> return@MockEngine respondError(HttpStatusCode.NotFound)
        }
        respond(html, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
    }

    private val config = SelectorSearchConfig(
        searchUrl = "https://example.com/search?wd={keyword}",
        requestInterval = Duration.ZERO,
        subjectFormatId = SelectorSubjectFormatA.id,
        selectorSubjectFormatA = SelectorSubjectFormatA.Config(selectLists = "div.result > a"),
        channelFormatId = SelectorChannelFormatNoChannel.id,
        selectorChannelFormatNoChannel = SelectorChannelFormatNoChannel.Config(selectEpisodes = ".list > a"),
    )

    private fun request(episode: Int) = MediaFetchRequest(
        subjectId = "8546",
        episodeId = episode.toString(),
        subjectNames = listOf("铃芽之旅"),
        episodeSort = EpisodeSort(episode),
        episodeName = "",
    )

    @Test
    fun `the page an episode came from is found by its play url and opening it gives the next episode without searching`() = runTest {
        val dao = InMemoryWebSearchSessionCacheDao()
        val source = createTestSelectorMediaSource(config, engine, cacheDao = dao, cacheTtl = 1.hours)
        val first = source.fetch(request(1)).results.toList().map { it.media }
            .single { it.episodeRange?.let { range -> EpisodeSort(1) in range } == true }
        val subjectUrl = assertNotNull(
            SelectorMediaSourceEpisodeCacheRepository(dao, flowOf(1.hours)).subjectUrlOf(source.mediaSourceId, first.originalUrl),
        )
        assertEquals("https://example.com/subject/5.html", subjectUrl)

        requests.clear()
        val next = source.mediaOnSubjectPage(subjectUrl, "铃芽之旅", request(2))
        assertEquals(listOf("https://example.com/subject/5.html"), requests)
        assertTrue(next.isNotEmpty())
        assertTrue(next.all { it.episodeRange?.let { range -> EpisodeSort(2) in range } == true })
        assertTrue(next.all { it.properties.subjectName == "铃芽之旅" })
    }
}
