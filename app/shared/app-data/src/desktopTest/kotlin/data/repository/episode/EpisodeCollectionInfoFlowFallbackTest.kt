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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [EpisodeCollectionRepository.episodeCollectionInfoFlow] 在 bangumi 取不到时的行为: 本地有过期的那份就照给, 没有才报错.
 */
class EpisodeCollectionInfoFlowFallbackTest {
    /** bangumi 那头一直超时 (如 Cloudflare 522). */
    private object FailingBangumiApi : ApiInvoker<DefaultApi> {
        override suspend fun <R> invoke(action: suspend DefaultApi.() -> R): R = throw IOException("Connection timed out")
    }

    private object LoggedOutSession : SessionStateProvider {
        override val stateFlow: Flow<SessionState> = MutableStateFlow(SessionState.Invalid(InvalidSessionReason.NO_TOKEN))
        override val eventFlow: Flow<SessionEvent> = emptyFlow()
    }

    private fun runRepositoryTest(block: suspend (AniDatabase, EpisodeCollectionRepository) -> Unit) = runBlocking {
        startKoin { modules(module { single<SessionStateProvider> { LoggedOutSession } }) }
        val database = createTestAniDatabase()
        try {
            val repository = EpisodeCollectionRepository(
                subjectDao = database.subjectCollection(),
                episodeCollectionDao = database.episodeCollection(),
                pendingOpDao = database.episodeCollectionPendingOpDao(),
                episodeService = EpisodeServiceImpl(FailingBangumiApi),
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
