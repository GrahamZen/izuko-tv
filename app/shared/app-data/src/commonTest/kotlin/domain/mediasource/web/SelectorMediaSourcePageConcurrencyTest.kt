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
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.mediasource.web.format.SelectorChannelFormatNoChannel
import me.him188.ani.app.domain.mediasource.web.format.SelectorSubjectFormatA
import me.him188.ani.app.platform.DevSwitches
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** 网页抓取源一个关键词搜到几个条目页时同时开两页 ([DevSwitches.selectorPagesConcurrent]), 关时一页页开. */
class SelectorMediaSourcePageConcurrencyTest {
    private val inFlight = atomic(0)
    private val maxInFlight = atomic(0)
    private val pagesOpened = atomic(0)

    private val engine = MockEngine { request ->
        val url = request.url.toString()
        val html = when {
            url.startsWith("https://example.com/search") -> """
                <html><body>
                <div class="result"><a href="/subject/1.html" title="铃芽之旅">铃芽之旅</a></div>
                <div class="result"><a href="/subject/2.html" title="铃芽之旅">铃芽之旅</a></div>
                <div class="result"><a href="/subject/3.html" title="铃芽之旅">铃芽之旅</a></div>
                </body></html>
            """.trimIndent()

            url.startsWith("https://example.com/subject/") -> {
                pagesOpened.incrementAndGet()
                val now = inFlight.incrementAndGet()
                while (true) {
                    val max = maxInFlight.value
                    if (now <= max || maxInFlight.compareAndSet(max, now)) break
                }
                delay(200)
                inFlight.decrementAndGet()
                val id = url.substringAfterLast('/').substringBefore('.')
                """<html><body><div class="list"><a href="/play/$id-1.html">第1集</a></div></body></html>"""
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

    @AfterTest
    fun resetSwitch() {
        DevSwitches.selectorPagesConcurrent.set("1")
    }

    @Test
    fun `two subject pages are opened at a time`() = runTest {
        DevSwitches.selectorPagesConcurrent.set("1")
        createTestSelectorMediaSource(config, engine).fetch(request).results.toList()
        assertEquals(3, pagesOpened.value)
        assertEquals(2, maxInFlight.value)
    }

    @Test
    fun `pages are opened one by one when the switch is off`() = runTest {
        DevSwitches.selectorPagesConcurrent.set("0")
        createTestSelectorMediaSource(config, engine).fetch(request).results.toList()
        assertEquals(3, pagesOpened.value)
        assertEquals(1, maxInFlight.value)
    }
}
