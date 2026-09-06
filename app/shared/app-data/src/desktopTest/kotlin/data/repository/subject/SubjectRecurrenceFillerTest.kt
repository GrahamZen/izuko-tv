/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import me.him188.ani.app.data.models.subject.SubjectRecurrence
import me.him188.ani.app.data.network.schedule.AnimeScheduleCache
import me.him188.ani.app.data.network.schedule.BangumiScheduleSource
import me.him188.ani.app.data.network.schedule.BroadcastRule
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.data.repository.realTimeTest
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.ktor.asScopedHttpClient
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class SubjectRecurrenceFillerTest {
    private val database = createTestAniDatabase()
    private val dao = database.subjectCollection()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** bangumi-data 月文件的内容, 按需改. */
    private var monthFile = "[]"
    private val client = HttpClient(
        MockEngine { request ->
            requests += request.url.toString()
            respond(monthFile, HttpStatusCode.OK)
        },
    )
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    @AfterTest
    fun close() {
        scope.cancel()
        client.close()
        database.close()
    }

    private fun filler(cache: AnimeScheduleCache = AnimeScheduleCache()) = SubjectRecurrenceFiller(
        AnimeScheduleRepository(BangumiScheduleSource(client.asScopedHttpClient(), MemoryDataStore(cache))),
        dao,
        scope,
        getCurrentDate = { TODAY },
    )

    /** 等 [SubjectRecurrenceFiller.fillLater] 起的后台任务全部结束. */
    private suspend fun awaitBackground() = job.children.toList().joinAll()

    // region mayStillAir

    @Test
    fun `正片都播完两天以上 - 不在播`() {
        assertFalse(mayStillAir(listOf(episode(1, "2026-07-05"), episode(2, "2026-09-20")), "2026-07-05", TODAY))
    }

    @Test
    fun `最后一集是前天 - 已经不受播出周期影响`() {
        assertFalse(mayStillAir(listOf(episode(1, "2026-09-17"), episode(2, "2026-09-24")), "2026-09-17", TODAY))
    }

    @Test
    fun `最后一集是昨天 - 可能还在播`() {
        assertTrue(mayStillAir(listOf(episode(1, "2026-09-18"), episode(2, "2026-09-25")), "2026-09-18", TODAY))
    }

    @Test
    fun `有将来的集 - 在播`() {
        assertTrue(mayStillAir(listOf(episode(1, "2023-04-02"), episode(2, "2026-10-03")), "2023-04-02", TODAY))
    }

    @Test
    fun `有正片没日期且首播在一年内 - 可能还在播`() {
        assertTrue(mayStillAir(listOf(episode(1, "2026-07-05"), episode(2, null)), "2026-07-05", TODAY))
    }

    @Test
    fun `有正片没日期但首播超过一年 - 不算在播`() {
        assertFalse(mayStillAir(listOf(episode(1, null)), "1991-03-21", TODAY))
        assertFalse(mayStillAir(listOf(episode(1, "2024-01-07"), episode(2, null)), "2024-01-07", TODAY))
    }

    @Test
    fun `有正片没日期也不知道首播 - 按可能在播算`() {
        assertTrue(mayStillAir(listOf(episode(1, null)), null, TODAY))
        assertTrue(mayStillAir(listOf(episode(1, null)), "", TODAY))
    }

    @Test
    fun `只有特别篇没有播出日期 - 不算在播`() {
        assertFalse(mayStillAir(listOf(episode(1, "2026-07-05"), episode(2, null, EpisodeType.SP)), "2026-07-05", TODAY))
    }

    @Test
    fun `还没有正片 - 不算在播`() {
        assertFalse(mayStillAir(emptyList(), "2026-10-03", TODAY))
        assertFalse(mayStillAir(listOf(episode(1, "2026-10-03", EpisodeType.SP)), "2026-10-03", TODAY))
    }

    // endregion

    @Test
    fun `缓存里有规则 - 后台补上且不联网`() = realTimeTest {
        val saved = testSubjectCollectionEntity(SUBJECT)
        dao.upsert(saved)

        filler(AnimeScheduleCache(broadcastRules = mapOf(SUBJECT to BroadcastRule(START_TIME, 7))))
            .fillLater(SUBJECT, "2026-07-05", listOf(episode(1, "2026-09-27")))
        awaitBackground()

        assertEquals(emptyList(), requests)
        assertEquals(saved.copy(recurrence = RECURRENCE), dao.findById(SUBJECT).first())
    }

    @Test
    fun `在播的条目 - 后台下月文件补上且只改这一列`() = realTimeTest {
        val saved = testSubjectCollectionEntity(SUBJECT, UnifiedCollectionType.WISH, lastUpdated = 123)
        dao.upsert(saved)
        monthFile = """[{"broadcast":"R/$START_TIME/P7D","sites":[{"site":"bangumi","id":"$SUBJECT"}]}]"""

        filler().fillLater(SUBJECT, "2026-07-05", listOf(episode(1, "2026-07-05"), episode(13, "2026-09-27")))
        awaitBackground()

        assertEquals(listOf("https://cdn.jsdelivr.net/gh/bangumi-data/bangumi-data@master/data/items/2026/07.json"), requests)
        assertEquals(saved.copy(recurrence = RECURRENCE), dao.findById(SUBJECT).first())
    }

    @Test
    fun `完结的条目 - 不查`() = realTimeTest {
        dao.upsert(testSubjectCollectionEntity(SUBJECT))

        filler().fillLater(SUBJECT, "2015-04-05", listOf(episode(1, "2015-04-05"), episode(12, "2015-06-21")))

        assertTrue(job.children.none())
        assertEquals(emptyList(), requests)
        assertNull(dao.findById(SUBJECT).first()?.recurrence)
    }

    @Test
    fun `月文件里没有这个条目 - 不动`() = realTimeTest {
        dao.upsert(testSubjectCollectionEntity(SUBJECT))
        monthFile = """[{"broadcast":"R/$START_TIME/P7D","sites":[{"site":"bangumi","id":"1"}]}]"""

        filler().fillLater(SUBJECT, "2026-07-05", listOf(episode(1, "2026-09-27")))
        awaitBackground()

        assertEquals(1, requests.size)
        assertNull(dao.findById(SUBJECT).first()?.recurrence)
    }

    @Suppress("DEPRECATION")
    private fun episode(id: Int, airDate: String?, type: EpisodeType = EpisodeType.MainStory) = EpisodeCollectionEntity(
        subjectId = SUBJECT, episodeId = id, episodeType = type, name = "", nameCn = "",
        airDate = airDate?.let { PackedDate.parseFromDate(it) } ?: PackedDate.Invalid, comment = 0, desc = "",
        sort = EpisodeSort(id), sortNumber = id.toFloat(), selfCollectionType = UnifiedCollectionType.NOT_COLLECTED,
        lastFetched = 0,
    )

    private companion object {
        const val SUBJECT = 552533
        const val START_TIME = "2026-07-05T13:00:00.000Z"
        val TODAY = PackedDate.parseFromDate("2026-09-26")
        val RECURRENCE = SubjectRecurrence(startTime = Instant.parse(START_TIME), interval = 7.days)
    }
}
