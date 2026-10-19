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
import io.ktor.http.headersOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.network.EpisodeServiceImpl
import me.him188.ani.app.data.network.schedule.AnimeScheduleCache
import me.him188.ani.app.data.network.schedule.BangumiScheduleSource
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.app.data.repository.subject.GetEpisodeTypeFiltersUseCase
import me.him188.ani.app.domain.session.InvalidSessionReason
import me.him188.ani.app.domain.session.SessionEvent
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.ktor.asScopedHttpClient
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EpisodeCollectionRepository.episodeCollectionInfoFlow] 取分集: 一集过期就把整部取回来全写库; bangumi 取不到时本地有过期的那份就照给,
 * 没有才报错, 而且别的条目写分集不会让它再去取. 另有 [EpisodeCollectionRepository.getEpisodeCollectionType] 不许联网时不联网.
 */
class EpisodeCollectionInfoFlowFallbackTest {
    /** bangumi 那头一直超时 (如 Cloudflare 522); [calls] 是发了几次. */
    private class FailingBangumiApi : ApiInvoker<DefaultApi> {
        val calls = AtomicInteger()
        override suspend fun <R> invoke(action: suspend DefaultApi.() -> R): R {
            calls.incrementAndGet()
            throw IOException("Connection timed out")
        }
    }

    /** 公开分集接口 (没登录走这个) 返回 [episodeIds] 这几集; [calls] 是发了几次. */
    private class EpisodesApi(private vararg val episodeIds: Int) : ApiInvoker<DefaultApi> {
        val calls = AtomicInteger()
        private val api = DefaultApi(
            baseUrl = "http://test",
            httpClientEngine = MockEngine { request ->
                calls.incrementAndGet()
                assertEquals("/v0/episodes", request.url.encodedPath)
                val data = episodeIds.joinToString(",") { id ->
                    """{"id":$id,"type":0,"name":"ep $id","name_cn":"新的第 $id 集","sort":$id,"ep":$id,"airdate":"","comment":0,"duration":"","desc":"","disc":0,"subject_id":1}"""
                }
                respond(
                    """{"data":[$data],"total":${episodeIds.size},"limit":100,"offset":0}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
                )
            },
            httpClientConfig = { it.expectSuccess = true },
        )

        override suspend fun <R> invoke(action: suspend DefaultApi.() -> R): R = action(api)
    }

    private object LoggedOutSession : SessionStateProvider {
        override val stateFlow: Flow<SessionState> = MutableStateFlow(SessionState.Invalid(InvalidSessionReason.NO_TOKEN))
        override val eventFlow: Flow<SessionEvent> = emptyFlow()
    }

    private fun runRepositoryTest(
        api: ApiInvoker<DefaultApi> = FailingBangumiApi(),
        block: suspend (AniDatabase, EpisodeCollectionRepository) -> Unit,
    ) = runBlocking {
        startKoin { modules(module { single<SessionStateProvider> { LoggedOutSession } }) }
        val database = createTestAniDatabase()
        try {
            val repository = EpisodeCollectionRepository(
                subjectDao = database.subjectCollection(),
                episodeCollectionDao = database.episodeCollection(),
                pendingOpDao = database.episodeCollectionPendingOpDao(),
                episodeService = EpisodeServiceImpl(api),
                animeScheduleRepository = AnimeScheduleRepository(
                    BangumiScheduleSource(
                        HttpClient(MockEngine { error("HTTP not expected") }).asScopedHttpClient(),
                        MemoryDataStore(AnimeScheduleCache()),
                    ),
                ),
                subjectCollectionRepository = lazy { error("SubjectCollectionRepository not expected") },
                getEpisodeTypeFiltersUseCase = GetEpisodeTypeFiltersUseCase { flowOf(EpisodeType.entries) },
            )
            block(database, repository)
        } finally {
            database.close()
            stopKoin()
        }
    }

    @Test
    fun `expired local episode is used when refresh fails`() = runRepositoryTest { database, repository ->
        database.subjectCollection().upsert(listOf(subject(1)))
        database.episodeCollection().upsert(listOf(episode(1, 11)))

        val info = repository.episodeCollectionInfoFlow(1, 11).first()

        assertEquals(11, info.episodeId)
        assertEquals("第 11 集", info.episodeInfo.nameCn)
    }

    @Test
    fun `refreshing one expired episode saves every episode of the subject`() {
        val api = EpisodesApi(11, 12, 13)
        runRepositoryTest(api) { database, repository ->
            database.subjectCollection().upsert(listOf(subject(1)))
            database.episodeCollection().upsert(listOf(episode(1, 11), episode(1, 12)))

            assertEquals("新的第 11 集", repository.episodeCollectionInfoFlow(1, 11).first().episodeInfo.nameCn)

            val saved = database.episodeCollection().filterBySubjectId(1).first()
            assertEquals(listOf(11, 12, 13), saved.map { it.episodeId })
            assertTrue(saved.all { it.lastFetched > 0 })
            // 第 12 集已经跟着刷新过, 再要它不必再取
            assertEquals("新的第 12 集", repository.episodeCollectionInfoFlow(1, 12).first().episodeInfo.nameCn)
            assertEquals(1, api.calls.get())
        }
    }

    @Test
    fun `failed refresh is not retried when other subjects write episodes`() {
        val api = FailingBangumiApi()
        runRepositoryTest(api) { database, repository ->
            database.subjectCollection().upsert(listOf(subject(1), subject(2)))
            database.episodeCollection().upsert(listOf(episode(1, 11)))
            coroutineScope {
                val emissions = MutableStateFlow(0)
                val job = launch { repository.episodeCollectionInfoFlow(1, 11).collect { emissions.value++ } }
                emissions.first { it == 1 }

                repeat(3) { i ->
                    database.episodeCollection().upsert(listOf(episode(2, 21 + i)))
                    delay(300)
                }

                assertEquals(1, api.calls.get())
                job.cancel()
            }
        }
    }

    @Test
    fun `collection type without network is unknown when not cached`() {
        val api = FailingBangumiApi()
        runRepositoryTest(api) { database, repository ->
            database.subjectCollection().upsert(listOf(subject(1)))

            assertNull(repository.getEpisodeCollectionType(1, 11, allowNetwork = false))
            assertEquals(0, api.calls.get())
        }
    }

    @Test
    fun `refresh failure is reported when there is no local episode`() = runRepositoryTest { database, repository ->
        database.subjectCollection().upsert(listOf(subject(1)))

        assertFailsWith<IOException> { repository.episodeCollectionInfoFlow(1, 11).first() }
    }

    private fun subject(subjectId: Int) = SubjectCollectionEntity(
        subjectId = subjectId,
        name = "subject-$subjectId",
        nameCn = "条目 $subjectId",
        summary = "",
        nsfw = false,
        imageLarge = "",
        totalEpisodes = 12,
        airDate = PackedDate.Invalid,
        aliases = emptyList(),
        tags = emptyList(),
        collectionStats = SubjectCollectionStats.Zero,
        ratingInfo = RatingInfo.Empty,
        completeDate = PackedDate.Invalid,
        selfRatingInfo = SelfRatingInfo(score = 0, comment = null, tags = emptyList(), isPrivate = false),
        collectionType = UnifiedCollectionType.DOING,
        recurrence = null,
        lastUpdated = subjectId.toLong(),
        lastFetched = 0,
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )

    /** `lastFetched = 0`: 早就过期, 读它会先去 bangumi 刷新. */
    private fun episode(subjectId: Int, episodeId: Int) = EpisodeCollectionEntity(
        subjectId = subjectId,
        episodeId = episodeId,
        episodeType = EpisodeType.MainStory,
        name = "ep",
        nameCn = "第 $episodeId 集",
        airDate = PackedDate.Invalid,
        comment = 0,
        desc = "",
        sort = EpisodeSort(episodeId),
        sortNumber = episodeId.toFloat(),
        selfCollectionType = UnifiedCollectionType.WISH,
        lastFetched = 0,
    )
}
