/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.continuation

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.persistent.createTestPreferencesDataStore
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.selector.testFramework.FetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.tier
import me.him188.ani.app.domain.mediasource.clouddrive.DriveShareLocation
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind.LocalCache
import me.him188.ani.datasources.api.source.MediaSourceKind.WEB
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisabledOnNative
class PlaybackLineageSelectionTest {
    private val lineage = DriveShareLineage("drive-share", "testdrive", DriveShareLocation("s1", "", "番", "d1", emptyList()))

    /** 线索上的资源 = 字幕组位置写着「线索」的; 直接去取时给出 [resolution]. */
    private class FakeContinuation(var resolution: LineageResolution = LineageResolution.NotFound) : EpisodeContinuation {
        var resolves = 0
        override fun owns(media: Media) = false
        override suspend fun lineageOf(media: Media): PlaybackLineage? = null
        override fun continues(lineage: PlaybackLineage, candidate: Media) = candidate.properties.alliance == ON_LINEAGE
        override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution {
            resolves++
            return resolution
        }
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
        continuation: EpisodeContinuation,
        session: MediaFetchSession,
    ): Boolean = PlaybackLineageSelection(repository, continuation)
        .select(SUBJECT_ID, preferenceApi.mediaSelectorContext.value.episodeInfo!!, session, selector)

    @Test
    fun `the lineage candidate wins over source tiers and preferences`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = PlaybackLineageRepository(createTestPreferencesDataStore()).apply { setLineage(SUBJECT_ID, lineage) }
        val (_, session, sources) = configureFetchSession {
            object {
                val best by web { tier = 0 }
                val drive by web { tier = 3 }
            }
        }
        sources.best.complete(media(kind = WEB, subjectName = "Example Series", alliance = "线路"))
        sources.drive.complete(
            media(kind = WEB, subjectName = "Example Series", alliance = "别的版本"),
            media(kind = WEB, subjectName = "Example Series", alliance = ON_LINEAGE),
        )
        testScope().runCurrent()

        assertTrue(select(repository, FakeContinuation(), session))
        assertEquals(ON_LINEAGE, selector.selected.value?.properties?.alliance)
    }

    @Test
    fun `a cache on the lineage comes before the network copy`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = PlaybackLineageRepository(createTestPreferencesDataStore()).apply { setLineage(SUBJECT_ID, lineage) }
        val (_, session, sources) = configureFetchSession {
            object {
                val drive by web()
                val cache by localCache()
            }
        }
        sources.drive.complete(media(kind = WEB, subjectName = "Example Series", alliance = ON_LINEAGE))
        sources.cache.complete(media(kind = LocalCache, subjectName = "Example Series", alliance = ON_LINEAGE))
        testScope().runCurrent()

        assertTrue(select(repository, FakeContinuation(), session))
        assertEquals(LocalCache, selector.selected.value?.kind)
    }

    @Test
    fun `not among the candidates - resolved directly from the lineage`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = PlaybackLineageRepository(createTestPreferencesDataStore()).apply { setLineage(SUBJECT_ID, lineage) }
        val (_, session, sources) = configureFetchSession {
            object {
                val other by web()
            }
        }
        sources.other.complete(media(kind = WEB, subjectName = "Example Series", alliance = "线路"))
        testScope().runCurrent()
        val resolved = media(kind = WEB, subjectName = "Example Series", alliance = ON_LINEAGE)

        assertTrue(select(repository, FakeContinuation(LineageResolution.Found(resolved)), session))
        assertEquals(resolved, selector.selected.value)
    }

    @Test
    fun `a lineage without this episode is kept - a gone one is forgotten`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = PlaybackLineageRepository(createTestPreferencesDataStore()).apply { setLineage(SUBJECT_ID, lineage) }
        val (_, session, sources) = configureFetchSession {
            object {
                val other by web()
            }
        }
        sources.other.complete(media(kind = WEB, subjectName = "Example Series", alliance = "线路"))
        testScope().runCurrent()

        // 只是没有这一集 (新的一集还没更新): 交给自动选源, 线索留着
        assertFalse(select(repository, FakeContinuation(LineageResolution.NotFound), session))
        assertNull(selector.selected.value)
        assertEquals(lineage, repository.lineageOf(SUBJECT_ID))

        // 线索彻底失效: 忘掉
        assertFalse(select(repository, FakeContinuation(LineageResolution.Gone), session))
        assertNull(repository.lineageOf(SUBJECT_ID))
    }

    @Test
    fun `no lineage - nothing is selected and nothing is resolved`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = PlaybackLineageRepository(createTestPreferencesDataStore())
        val (_, session, sources) = configureFetchSession {
            object {
                val drive by web()
            }
        }
        sources.drive.complete(media(kind = WEB, subjectName = "Example Series", alliance = ON_LINEAGE))
        testScope().runCurrent()
        val continuation = FakeContinuation()

        assertFalse(select(repository, continuation, session))
        assertNull(selector.selected.value)
        assertEquals(0, continuation.resolves)
    }

    @Test
    fun `a playback error switches to another resource on the lineage first`() = runFetchMediaSelectorTestSuite {
        initWeb()
        val repository = PlaybackLineageRepository(createTestPreferencesDataStore()).apply { setLineage(SUBJECT_ID, lineage) }
        val (_, session, sources) = configureFetchSession {
            object {
                val best by web { tier = 0 }
                val drive by web { tier = 3 }
            }
        }
        val failing = media(kind = WEB, subjectName = "Example Series", alliance = ON_LINEAGE)
        val alternative = media(kind = WEB, subjectName = "Example Series", alliance = ON_LINEAGE)
        sources.best.complete(media(kind = WEB, subjectName = "Example Series", alliance = "线路"))
        sources.drive.complete(failing, alternative)
        testScope().runCurrent()
        val selection = PlaybackLineageSelection(repository, FakeContinuation())
        val playing = assertNotNull(selector.filteredCandidatesMedia.first().firstOrNull { it.mediaId == failing.mediaId })
        selector.select(playing)

        assertTrue(selection.switchOnError(SUBJECT_ID, session, selector, setOf(playing.mediaId), playing))
        assertEquals(alternative.mediaId, selector.selected.value?.mediaId)
        // 线索上没有别的了: 交给原来的换源
        val now = assertNotNull(selector.selected.value)
        assertFalse(selection.switchOnError(SUBJECT_ID, session, selector, setOf(playing.mediaId, now.mediaId), now))
        assertEquals(now, selector.selected.value)
    }

    context(scope: TestScope)
    private fun testScope(): TestScope = scope

    private companion object {
        const val SUBJECT_ID = 400602
        const val ON_LINEAGE = "线索"
    }
}
