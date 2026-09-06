/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import me.him188.ani.app.data.network.schedule.AnimeScheduleCache
import me.him188.ani.app.data.network.schedule.BangumiScheduleSource
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.domain.episode.AiringScheduleForDate
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AnimeScheduleRepositoryTest {
    /** 放行卡住的那一部 ([SLOW]) 的分集请求. */
    private val slowGate = CompletableDeferred<Unit>()
    private val store = MemoryDataStore(AnimeScheduleCache())
    private val client = HttpClient(
        MockEngine { request ->
            val url = request.url
            when {
                url.encodedPath == "/p1/calendar" -> respond(CALENDAR, HttpStatusCode.OK)
                url.encodedPath == "/v0/episodes" -> {
                    val id = url.parameters["subject_id"]!!.toInt()
                    if (id == SLOW) slowGate.await()
                    respond(episodesOf(id), HttpStatusCode.OK)
                }
                // bangumi-data 月文件: 这里不关心播出时刻
                else -> respond("[]", HttpStatusCode.OK)
            }
        },
    )
    private val repository = AnimeScheduleRepository(BangumiScheduleSource(client.asScopedHttpClient(), store))

    @AfterTest
    fun close() {
        slowGate.complete(Unit)
        client.close()
    }

    @Test
    fun `一部卡住时同一天已取到的先显示 - 放行后补完`() = realTimeTest {
        coroutineScope {
            val emissions = Channel<List<AiringScheduleForDate>>(Channel.UNLIMITED)
            val job = launch { repository.recentAiringSchedulesFlow(TODAY, TimeZone.UTC).collect { emissions.send(it) } }

            val partial = emissions.awaitFirst { it.today().list.isNotEmpty() }
            assertEquals(listOf(FAST), partial.today().list.map { it.subject.subjectId })
            assertTrue(partial.today().pending)

            slowGate.complete(Unit)
            val full = emissions.awaitFirst { !it.today().pending }
            assertEquals(setOf(FAST, SLOW), full.today().list.map { it.subject.subjectId }.toSet())
            job.cancelAndJoin()
        }
    }

    @Test
    fun `中途离开时已取到的照样落盘`() = realTimeTest {
        coroutineScope {
            val emissions = Channel<List<AiringScheduleForDate>>(Channel.UNLIMITED)
            val job = launch { repository.recentAiringSchedulesFlow(TODAY, TimeZone.UTC).collect { emissions.send(it) } }

            emissions.awaitFirst { it.today().list.isNotEmpty() }
            job.cancelAndJoin()

            assertEquals(setOf(FAST), store.data.first().episodes.keys)
        }
    }

    private fun List<AiringScheduleForDate>.today() = first { it.date == TODAY }

    private suspend fun Channel<List<AiringScheduleForDate>>.awaitFirst(
        predicate: (List<AiringScheduleForDate>) -> Boolean,
    ): List<AiringScheduleForDate> = withTimeout(10.seconds) {
        var value = receive()
        while (!predicate(value)) value = receive()
        value
    }

    private companion object {
        const val FAST = 1
        const val SLOW = 2

        /** 周二. */
        val TODAY = LocalDate(2026, 9, 29)

        /** 名册: 周二 (键 2) 两部. */
        val CALENDAR = """
            {"2": [
              {"subject": {"id": $FAST, "name": "Fast", "nameCN": "快", "images": {"large": "https://lain.bgm.tv/pic/cover/l/f.jpg"}}},
              {"subject": {"id": $SLOW, "name": "Slow", "nameCN": "慢", "images": {"large": "https://lain.bgm.tv/pic/cover/l/s.jpg"}}}
            ]}
        """.trimIndent()

        fun episodesOf(subjectId: Int) =
            """{"data": [{"id": ${subjectId * 10}, "name": "ep1", "sort": 1, "ep": 1, "airdate": "$TODAY"}]}"""
    }
}
