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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.network.EpisodeServiceImpl
import me.him188.ani.app.data.network.schedule.AnimeScheduleCache
import me.him188.ani.app.data.network.schedule.BangumiScheduleSource
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.repository.subject.GetEpisodeTypeFiltersUseCase
import me.him188.ani.app.data.repository.subject.RewatchStartResult
import me.him188.ani.app.data.repository.subject.SubjectRewatch
import me.him188.ani.app.data.repository.testSubjectCollectionEntity
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.toKtPath
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.ktor.asScopedHttpClient
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 重温中的条目标看过只写本地、不进同步队列 (同本地档); 别的条目照旧入队.
 */
class EpisodeCollectionRewatchTest {
    private object UnusedBangumiApi : ApiInvoker<DefaultApi> {
        override suspend fun <R> invoke(action: suspend DefaultApi.() -> R): R = error("ApiInvoker not expected")
    }

    private val unusedScheduleSource = BangumiScheduleSource(
        HttpClient(MockEngine { error("HTTP not expected") }).asScopedHttpClient(),
        MemoryDataStore(AnimeScheduleCache()),
    )

    private class Fixture(val database: AniDatabase, val repository: EpisodeCollectionRepository, val rewatch: SubjectRewatch, val dirtyCalls: () -> Int)

    private fun runRepositoryTest(block: suspend Fixture.() -> Unit) = runBlocking {
        val database = createTestAniDatabase()
        val dir = createTempDirectory("episode-rewatch").toFile()
        var dirty = 0
        try {
            val rewatch = SubjectRewatch(database, File(dir, SubjectRewatch.FILE_NAME).toKtPath().inSystem)
            val repository = EpisodeCollectionRepository(
                subjectDao = database.subjectCollection(),
                episodeCollectionDao = database.episodeCollection(),
                pendingOpDao = database.episodeCollectionPendingOpDao(),
                episodeService = EpisodeServiceImpl(UnusedBangumiApi),
                animeScheduleRepository = AnimeScheduleRepository(unusedScheduleSource),
                subjectCollectionRepository = lazy { error("SubjectCollectionRepository not expected") },
                getEpisodeTypeFiltersUseCase = GetEpisodeTypeFiltersUseCase { flowOf(EpisodeType.entries) },
                onDirtyChanged = { dirty++ },
                rewatch = rewatch,
            )
            Fixture(database, repository, rewatch) { dirty }.block()
        } finally {
            database.close()
            dir.deleteRecursively()
        }
    }

    private fun episode(subjectId: Int, episodeId: Int, type: UnifiedCollectionType) = EpisodeCollectionEntity(
        subjectId = subjectId, episodeId = episodeId, episodeType = EpisodeType.MainStory, name = "ep", nameCn = "",
        airDate = PackedDate.Invalid, comment = 0, desc = "", sort = EpisodeSort(episodeId % 100),
        sortNumber = (episodeId % 100).toFloat(), selfCollectionType = type, lastFetched = 1,
    )

    private suspend fun Fixture.seedDone(subjectId: Int) {
        database.subjectCollection().upsert(listOf(testSubjectCollectionEntity(subjectId, UnifiedCollectionType.DONE)))
        database.episodeCollection().upsert(listOf(episode(subjectId, subjectId * 100 + 1, UnifiedCollectionType.DONE)))
    }

    private suspend fun Fixture.pendingSubjects() =
        database.episodeCollectionPendingOpDao().getPendingOps().map { it.subjectId }

    @Test
    fun `marks on a rewatching subject stay local`() = runRepositoryTest {
        seedDone(1)
        assertEquals(RewatchStartResult.STARTED, rewatch.start(1))

        repository.setEpisodeCollectionType(1, 101, UnifiedCollectionType.DONE)
        repository.setAllEpisodesWatched(1)

        assertEquals(UnifiedCollectionType.DONE, database.episodeCollection().findByEpisodeId(101).first()!!.selfCollectionType)
        assertEquals(emptyList(), pendingSubjects())
        assertEquals(0, dirtyCalls())
        assertEquals(UnifiedCollectionType.DONE, repository.getEpisodeCollectionType(1, 101, allowNetwork = true))
    }

    @Test
    fun `marks on other subjects are still queued for Bangumi`() = runRepositoryTest {
        seedDone(1)
        seedDone(2)
        rewatch.start(1)

        repository.setEpisodeCollectionType(2, 201, UnifiedCollectionType.WISH)

        assertEquals(listOf(2), pendingSubjects())
        assertEquals(1, dirtyCalls())
    }
}
