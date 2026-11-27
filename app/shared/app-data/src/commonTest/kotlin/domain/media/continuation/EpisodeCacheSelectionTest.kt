/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.selector.testFramework.FetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.mediasource.clouddrive.DriveShareLocation
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind.LocalCache
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisabledOnNative
class EpisodeCacheSelectionTest {
    private val lineage = DriveShareLineage("drive-share", "testdrive", DriveShareLocation("s1", "", "番", "d1", emptyList()))

    /** 线索上的资源 = 字幕组位置写着「线索」的 */
    private object Continuation : EpisodeContinuation {
        override fun owns(media: Media) = false
        override suspend fun lineageOf(media: Media): PlaybackLineage? = null
        override fun continues(lineage: PlaybackLineage, candidate: Media) = candidate.properties.alliance == ON_LINEAGE
        override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest) = LineageResolution.NotFound
    }

    private fun FetchMediaSelectorTestSuite.initWeb() {
        initSubject("Example Series")
        preferenceApi.savedUserPreference.value = MediaPreference.Any
        preferenceApi.mediaSelectorSettings.value = MediaSelectorSettings.AllVisible.copy(
            preferKind = WEB, preferSeasons = false, hideSingleEpisodeForCompleted = false,
        )
    }

    private suspend fun FetchMediaSelectorTestSuite.select(
        repository: PlaybackLineageRepository,
        session: MediaFetchSession,
        finished: Set<String>,
        playUnfinished: Boolean = true,
        cacheRecords: EpisodeCacheRecords = HAS_CACHE_RECORD,
        fromCachePage: Boolean = false,
    ): EpisodeCacheSelection.Choice = EpisodeCacheSelection(repository, Continuation, cacheRecords, { _, _ -> finished }, { playUnfinished })
        .select(SUBJECT_ID, preferenceApi.mediaSelectorContext.value.episodeInfo!!, session, selector, fromCachePage)

    private fun FetchMediaSelectorTestSuite.cache(alliance: String) = media(kind = LocalCache, subjectName = "Example Series", alliance = alliance)

    @Test
    fun `a finished cache of another version is chosen before the network copy on the lineage`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = createTestLineageRepository().apply { setLineage(SUBJECT_ID, lineage) }
        val (_, session, sources) = configureFetchSession {
            object {
                val drive by web()
                val cache by localCache()
            }
        }
        val cache = cache("别的版本")
        sources.drive.complete(media(kind = WEB, subjectName = "Example Series", alliance = ON_LINEAGE))
        sources.cache.complete(cache)
        testScope().runCurrent()

        assertEquals(EpisodeCacheSelection.Choice.Selected, select(repository, session, finished = setOf(cache.mediaId)))
        assertEquals(cache.mediaId, selector.selected.value?.mediaId)
        assertEquals(lineage, repository.lineageOf(SUBJECT_ID))
    }

    @Test
    fun `among finished caches the lineage version comes first`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = createTestLineageRepository().apply { setLineage(SUBJECT_ID, lineage) }
        val (_, session, sources) = configureFetchSession {
            object {
                val cache by localCache()
            }
        }
        val other = cache("别的版本")
        val onLineage = cache(ON_LINEAGE)
        sources.cache.complete(other, onLineage)
        testScope().runCurrent()

        select(repository, session, finished = setOf(other.mediaId, onLineage.mediaId))
        assertEquals(onLineage.mediaId, selector.selected.value?.mediaId)
    }

    @Test
    fun `a finished cache wins over an unfinished one on the lineage`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = createTestLineageRepository().apply { setLineage(SUBJECT_ID, lineage) }
        val (_, session, sources) = configureFetchSession {
            object {
                val cache by localCache()
            }
        }
        val finished = cache("别的版本")
        sources.cache.complete(cache(ON_LINEAGE), finished)
        testScope().runCurrent()

        select(repository, session, finished = setOf(finished.mediaId))
        assertEquals(finished.mediaId, selector.selected.value?.mediaId)
    }

    @Test
    fun `an unfinished cache is played when the setting allows it`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = createTestLineageRepository()
        val (_, session, sources) = configureFetchSession {
            object {
                val drive by web()
                val cache by localCache()
            }
        }
        val unfinished = cache("字幕组")
        sources.drive.complete(media(kind = WEB, subjectName = "Example Series", alliance = "线路"))
        sources.cache.complete(unfinished)
        testScope().runCurrent()

        assertEquals(EpisodeCacheSelection.Choice.Selected, select(repository, session, finished = emptySet(), playUnfinished = true))
        assertEquals(unfinished.mediaId, selector.selected.value?.mediaId)
    }

    @Test
    fun `an unfinished cache waits for other sources when the setting says so`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = createTestLineageRepository()
        val (_, session, sources) = configureFetchSession {
            object {
                val drive by web()
                val cache by localCache()
            }
        }
        val unfinished = cache("字幕组")
        sources.drive.complete(media(kind = WEB, subjectName = "Example Series", alliance = "线路"))
        sources.cache.complete(unfinished)
        testScope().runCurrent()

        val choice = assertIs<EpisodeCacheSelection.Choice.Deferred>(select(repository, session, finished = emptySet(), playUnfinished = false))
        assertEquals(listOf(unfinished.mediaId), choice.unfinished.map { it.mediaId })
        assertNull(selector.selected.value)
    }

    @Test
    fun `from the cache page an unfinished cache is played even when the setting says wait`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = createTestLineageRepository()
        val (_, session, sources) = configureFetchSession {
            object {
                val cache by localCache()
            }
        }
        val unfinished = cache("字幕组")
        sources.cache.complete(unfinished)
        testScope().runCurrent()

        assertEquals(
            EpisodeCacheSelection.Choice.Selected,
            select(repository, session, finished = emptySet(), playUnfinished = false, fromCachePage = true),
        )
        assertEquals(unfinished.mediaId, selector.selected.value?.mediaId)
    }

    @Test
    fun `without a cache record nothing waits for the local caches`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = createTestLineageRepository()
        val (_, session, _) = configureFetchSession {
            object {
                val drive by web()
                val cache by localCache()
            }
        }
        // 本地缓存源迟迟查不完 (例如在等 BT 服务), 但这一集本机没有缓存记录
        testScope().runCurrent()
        val start = testScope().currentTime

        assertEquals(EpisodeCacheSelection.Choice.None, select(repository, session, finished = emptySet(), cacheRecords = NO_CACHE_RECORD))
        assertTrue(testScope().currentTime - start < 1_000)
    }

    @Test
    fun `a cache record whose cache is still loading is unknown`() = runFetchMediaSelectorTestSuite {
        // 没下完的 BT 缓存要等 BT 服务连上才查得出来: 不能当它不存在
        initWeb()
        val repository = createTestLineageRepository()
        val (_, session, sources) = configureFetchSession {
            object {
                val drive by web()
                val cache by localCache()
            }
        }
        sources.drive.complete(media(kind = WEB, subjectName = "Example Series", alliance = ON_LINEAGE))
        testScope().runCurrent()

        assertEquals(EpisodeCacheSelection.Choice.Unknown(cachesAllowed = true), select(repository, session, finished = emptySet()))
        assertNull(selector.selected.value)
        // 设置不让没下完的先播、又不知道有下完的: 后面的自动选源也别先选缓存
        assertEquals(EpisodeCacheSelection.Choice.Unknown(cachesAllowed = false), select(repository, session, finished = emptySet(), playUnfinished = false))
    }

    @Test
    fun `a cache record while the selector is not ready is unknown`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = createTestLineageRepository()
        val (_, session, sources) = configureFetchSession {
            object {
                val cache by localCache()
            }
        }
        sources.cache.complete(cache("别的版本"))
        preferenceApi.mediaSelectorContext.value = preferenceApi.mediaSelectorContext.value.copy(subjectFinished = null)
        testScope().runCurrent()

        assertIs<EpisodeCacheSelection.Choice.Unknown>(select(repository, session, finished = emptySet()))
        assertNull(selector.selected.value)
    }

    context(scope: TestScope)
    private fun testScope(): TestScope = scope

    private companion object {
        const val SUBJECT_ID = 400602
        const val ON_LINEAGE = "线索"
        val HAS_CACHE_RECORD = EpisodeCacheRecords { _, _ -> true }
        val NO_CACHE_RECORD = EpisodeCacheRecords { _, _ -> false }
    }
}
