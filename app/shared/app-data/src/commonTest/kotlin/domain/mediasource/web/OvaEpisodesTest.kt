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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormatNoChannel
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatA
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/** OVA 条目的条目页上集名写成「OVA01」时按正片第几集认 ([asEpisodesOf]). */
class OvaEpisodesTest {
    private fun episode(name: String) = WebSearchEpisodeInfo(channel = null, name = name, episodeSortOrEp = EpisodeSort(name), playUrl = "https://example.com/$name")

    private fun query(ova: Boolean) = SelectorSearchQuery(
        subjectName = "x", allSubjectNames = setOf("x"), episodeSort = EpisodeSort(1), episodeEp = EpisodeSort(1), episodeName = null,
        ovaSubject = ova,
    )

    @Test
    fun `an OVA subject whose page has only OVA numbers takes them as its episodes`() {
        val page = listOf(episode("OVA01"), episode("OVA02"))
        assertEquals(listOf(EpisodeSort(1), EpisodeSort(2)), page.asEpisodesOf(query(ova = true)).map { it.episodeSortOrEp })
    }

    @Test
    fun `OVA numbers stay specials for other subjects and on pages with numbered episodes`() {
        val ovaOnly = listOf(episode("OVA01"))
        assertEquals(ovaOnly, ovaOnly.asEpisodesOf(query(ova = false)))
        // TV 动画页带几集 OVA: 那些 OVA 仍是特别篇
        val mixed = listOf(episode("01"), episode("02"), episode("OVA01"))
        assertEquals(mixed, mixed.asEpisodesOf(query(ova = true)))
    }

    @Test
    fun `a subject named as an OVA is an OVA subject`() {
        assertTrue(request(listOf("魔法少女 OVA")).isOvaSubject())
        assertFalse(request(listOf("魔法少女")).isOvaSubject())
    }

    @Test
    fun `the first OVA episode on the page is found for episode one of an OVA subject`() = runTest {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            val html = when {
                url.startsWith("https://example.com/search") ->
                    """<html><body><div class="result"><a href="/subject/7.html" title="魔法少女 OVA">魔法少女 OVA</a></div></body></html>"""

                url == "https://example.com/subject/7.html" ->
                    """<html><body><div class="list"><a href="/play/1.html">OVA01</a><a href="/play/2.html">OVA02</a></div></body></html>"""

                else -> return@MockEngine respondError(HttpStatusCode.NotFound)
            }
            respond(html, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
        }
        val config = SelectorSearchConfig(
            searchUrl = "https://example.com/search?wd={keyword}",
            requestInterval = Duration.ZERO,
            subjectFormatId = SelectorSubjectFormatA.id,
            selectorSubjectFormatA = SelectorSubjectFormatA.Config(selectLists = "div.result > a"),
            channelFormatId = SelectorChannelFormatNoChannel.id,
            selectorChannelFormatNoChannel = SelectorChannelFormatNoChannel.Config(selectEpisodes = ".list > a"),
        )
        val media = createTestSelectorMediaSource(config, engine).fetch(request(listOf("魔法少女 OVA"))).results.toList().map { it.media }
        assertEquals(listOf("https://example.com/play/1.html"), media.map { it.originalUrl })
    }

    private fun request(names: List<String>) = MediaFetchRequest(
        subjectId = "0",
        episodeId = "1",
        subjectNames = names,
        episodeSort = EpisodeSort(1),
        episodeName = "",
    )
}
