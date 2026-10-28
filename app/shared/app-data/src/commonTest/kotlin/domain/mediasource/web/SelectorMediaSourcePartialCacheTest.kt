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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormatNoChannel
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatA
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * 一个词搜到几个条目页, 只开完一部分就被停下 (先交出的被选中后搜索被暂停) 时缓存里只有那几页:
 * 下次读到的先交出, 同时照常重搜补齐; 这一轮的页都开完了的缓存照旧直接用, 不发请求.
 */
class SelectorMediaSourcePartialCacheTest {
    /** 第二页一直取不完 (还在取时搜索就被停下) */
    private var secondPageHangs = true
    private val requests = mutableListOf<String>()

    private val engine = MockEngine { request ->
        val url = request.url.toString()
        requests += url
        val html = when {
            url.startsWith("https://example.com/search") -> """
                <html><body>
                <div class="result"><a href="/subject/1.html" title="铃芽之旅">铃芽之旅</a></div>
                <div class="result"><a href="/subject/2.html" title="铃芽之旅">铃芽之旅</a></div>
                </body></html>
            """.trimIndent()

            url == "https://example.com/subject/1.html" ->
                """<html><body><div class="list"><a href="/play/1-1.html">第1集</a></div></body></html>"""

            url == "https://example.com/subject/2.html" -> {
                if (secondPageHangs) awaitCancellation()
                """<html><body><div class="list"><a href="/play/2-1.html">01</a></div></body></html>"""
            }

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

    private val request = MediaFetchRequest(
        subjectId = "8546",
        episodeId = "1",
        subjectNames = listOf("铃芽之旅"),
        episodeSort = EpisodeSort(1),
        episodeName = "",
    )

    @Test
    fun `a round stopped before all its pages opened is searched again and the rest is found`() = runTest {
        val source = createTestSelectorMediaSource(config, engine, cacheDao = InMemoryWebSearchSessionCacheDao(), cacheTtl = 1.hours)
        // 拿到第一条就不再收: 同自动选源选中后暂停其余的搜索
        source.fetch(request).results.first()

        secondPageHangs = false
        val media = source.fetch(request).results.toList().map { it.media.originalUrl }.toSet()
        assertEquals(setOf("https://example.com/play/1-1.html", "https://example.com/play/2-1.html"), media)
    }

    @Test
    fun `a round with all its pages opened is read from the cache without requests`() = runTest {
        secondPageHangs = false
        val source = createTestSelectorMediaSource(config, engine, cacheDao = InMemoryWebSearchSessionCacheDao(), cacheTtl = 1.hours)
        source.fetch(request).results.toList()

        requests.clear()
        val media = source.fetch(request).results.toList().map { it.media.originalUrl }.toSet()
        assertEquals(setOf("https://example.com/play/1-1.html", "https://example.com/play/2-1.html"), media)
        assertEquals(emptyList(), requests)
    }
}
