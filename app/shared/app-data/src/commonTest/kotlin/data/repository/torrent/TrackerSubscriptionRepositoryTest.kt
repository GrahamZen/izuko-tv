/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.torrent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import me.him188.ani.app.data.models.preference.AnitorrentConfig
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.hours

class TrackerSubscriptionRepositoryTest {
    private val a = "https://lists.example/a.txt"
    private val b = "https://lists.example/b.txt"

    private val listA = """
        udp://one.example:1337/announce

        http://two.example:80/announce

        udp://one.example:1337/announce
    """.trimIndent()
    private val listB = "udp://one.example:1337/announce,wss://three.example,https://four.example/announce"

    private var now = 1_000L
    private val requested = mutableListOf<String>()

    /** @param responses 按地址给 (状态, 响应体); 返回 `null` = 连不上 */
    private fun repository(
        urls: MutableStateFlow<List<String>>,
        responses: (String) -> Pair<HttpStatusCode, String>?,
    ) = TrackerSubscriptionRepository(
        urls = urls,
        dataStore = MemoryDataStore(TrackerSubscriptionSaveData.Default),
        httpClient = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                requested += url
                val (status, body) = responses(url) ?: throw IOException("blocked")
                respond(body, status)
            },
        ).asScopedHttpClient(),
        clock = { now },
    )

    @Test
    fun `trackers of all subscriptions are merged without duplicates`() = runTest {
        val repo = repository(MutableStateFlow(listOf(a, b))) { url ->
            HttpStatusCode.OK to (if (url == a) listA else listB)
        }

        repo.refresh()

        assertEquals(
            listOf(
                "udp://one.example:1337/announce", "http://two.example:80/announce",
                "wss://three.example", "https://four.example/announce",
            ),
            repo.trackers.first(),
        )
        assertEquals(listOf(2, 3), repo.entries.first().map { it.trackers.size })
        assertEquals(listOf(1_000L, 1_000L), repo.entries.first().map { it.fetchedAtMillis })
    }

    @Test
    fun `a failed fetch keeps the trackers from last time and records why`() = runTest {
        var reachable = true
        val repo = repository(MutableStateFlow(listOf(a))) { if (reachable) HttpStatusCode.OK to listA else null }
        repo.refresh()
        reachable = false
        now += 30.hours.inWholeMilliseconds

        repo.refresh()

        val entry = repo.entries.first().single()
        assertEquals(2, entry.trackers.size)
        assertEquals(1_000L, entry.fetchedAtMillis)
        assertNotNull(entry.error)
        assertEquals(2, repo.trackers.first().size)
    }

    @Test
    fun `an error page is not taken as a tracker list`() = runTest {
        val page = """<html><a href="https://lists.example/login">登录</a> https://lists.example/help</html>"""
        val repo = repository(MutableStateFlow(listOf(a, b))) { url ->
            if (url == a) HttpStatusCode.NotFound to page else HttpStatusCode.OK to page
        }

        repo.refresh()

        val (notFound, notAList) = repo.entries.first()
        assertEquals("HTTP 404", notFound.error)
        assertEquals(TrackerSubscriptionEntry.EMPTY_LIST, notAList.error)
        assertEquals(emptyList(), repo.trackers.first())
    }

    @Test
    fun `recently fetched subscriptions are fetched again only when asked or a day later`() = runTest {
        val repo = repository(MutableStateFlow(listOf(a))) { HttpStatusCode.OK to listA }
        repo.refresh(reuseRecent = true)
        repo.refresh(reuseRecent = true)
        assertEquals(1, requested.size)

        repo.refresh(reuseRecent = false)
        assertEquals(2, requested.size)

        now += 25.hours.inWholeMilliseconds
        repo.refresh(reuseRecent = true)
        assertEquals(3, requested.size)
    }

    @Test
    fun `a removed address drops its trackers`() = runTest {
        val urls = MutableStateFlow(listOf(a, b))
        val repo = repository(urls) { url -> HttpStatusCode.OK to (if (url == a) listA else listB) }
        repo.refresh()

        urls.value = listOf(b)
        assertEquals(3, repo.trackers.first().size)
        repo.refresh(reuseRecent = true)
        assertEquals(listOf(b), repo.entries.first().map { it.url })
        assertEquals(1, requested.count { it == b })
    }

    @Test
    fun `only lines that look like trackers are taken`() {
        val text = """
            # comment udp://commented.example:1/announce
            udp://a.example:6969/announce , udp://b.example:6969/announce
            http://c.example:80/announce.php?passkey=1
            https://not-a-tracker.example/about
            ftp://d.example/announce
            "udp://quoted.example:1/announce"
            wss://e.example
            udp://
        """.trimIndent()

        assertEquals(
            listOf(
                "udp://a.example:6969/announce", "udp://b.example:6969/announce",
                "http://c.example:80/announce.php?passkey=1", "wss://e.example",
            ),
            parseTrackerList(text),
        )
    }

    @Test
    fun `subscribed trackers go after the extra trackers`() {
        val config = AnitorrentConfig(extraTrackers = "udp://mine.example:1/announce")
        assertSame(config, config.withSubscribedTrackers(emptyList()))
        assertEquals(
            "udp://mine.example:1/announce\nudp://sub.example:2/announce",
            config.withSubscribedTrackers(listOf("udp://sub.example:2/announce")).extraTrackers,
        )
        assertNull(AnitorrentConfig().trackerSubscriptionUrlList().firstOrNull())
        assertEquals(listOf(a, b), AnitorrentConfig(trackerSubscriptionUrls = " $a \n\n$b\n$a").trackerSubscriptionUrlList())
    }
}
