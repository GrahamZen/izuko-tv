/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(UnsafeEpisodeSessionApi::class)

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.persistent.createTestPreferencesDataStore
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.mediaFetchSessionFlow
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.media.continuation.EpisodeContinuation
import me.him188.ani.app.domain.media.continuation.LineageResolution
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.app.domain.media.continuation.WebSourceLineage
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.app.domain.settings.GetMediaSelectorSettingsFlowUseCase
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.coroutines.childScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * @see PlaybackLineageExtension
 */
class PlaybackLineageExtensionTest : AbstractPlayerExtensionTest() {
    private val repository = PlaybackLineageRepository(createTestPreferencesDataStore())
    private val mediaSelectorSettings = MutableStateFlow(MediaSelectorSettings.Default)

    /** 每个资源的线索就是它所在的数据源 */
    private object SourceContinuation : EpisodeContinuation {
        override fun owns(media: Media) = true
        override suspend fun lineageOf(media: Media): PlaybackLineage = WebSourceLineage(media.mediaSourceId, "条目", "")
        override fun continues(lineage: PlaybackLineage, candidate: Media) = (lineage as WebSourceLineage).mediaSourceId == candidate.mediaSourceId
        override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest) = LineageResolution.NotFound
    }

    private data class Case(val scope: CoroutineScope, val suite: EpisodePlayerTestSuite, val state: EpisodeFetchSelectPlayState)

    private fun TestScope.createCase(sourceId: String, kind: MediaSourceKind): Pair<Case, CompletableDeferred<List<Media>>> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val testScope = childScope()
        val suite = EpisodePlayerTestSuite(this, testScope)
        suite.registerComponent<MediaResolver> { TestUniversalMediaResolver }
        suite.registerComponent<PlaybackLineageRepository> { repository }
        suite.registerComponent<EpisodeContinuation> { SourceContinuation }
        suite.registerComponent<GetMediaSelectorSettingsFlowUseCase> { GetMediaSelectorSettingsFlowUseCase { mediaSelectorSettings } }
        val source = suite.mediaSelectorTestBuilder.delayedMediaSource(sourceId, kind = kind)
        val state = suite.createState(listOf(PlaybackLineageExtension))
        state.onUIReady()
        state.mediaFetchSessionFlow.filterNotNull().flatMapLatest { it.cumulativeResults }.launchIn(testScope)
        return Case(testScope, suite, state) to source
    }

    /** [sourceId] 的一条资源被选上 ([manual] 为用户手动选) 并播起来. */
    private suspend fun TestScope.play(case: Case, source: CompletableDeferred<List<Media>>, sourceId: String, kind: MediaSourceKind, manual: Boolean) {
        val media = case.suite.mediaSelectorTestBuilder.createMedia(sourceId, kind = kind)
        source.complete(listOf(media))
        advanceUntilIdle()
        val selector = case.state.mediaSelectorFlow.filterNotNull().first()
        if (manual) selector.select(media) else selector.selectAutomatically(media, null)
        advanceUntilIdle()
        case.suite.player.loadMedia(durationMs = 10_000L, playWhenReady = true)
        advanceUntilIdle()
    }

    @Test
    fun `with BT preferred - an automatically selected web media is not recorded as the lineage`() = runTest {
        mediaSelectorSettings.value = MediaSelectorSettings.Default.copy(preferKind = MediaSourceKind.BitTorrent)
        val (case, source) = createCase("web1", MediaSourceKind.WEB)
        play(case, source, "web1", MediaSourceKind.WEB, manual = false)
        assertNull(repository.lineageOf(subjectId))
        case.scope.cancel()
    }

    @Test
    fun `an automatically selected media of the preferred kind becomes the lineage`() = runTest {
        mediaSelectorSettings.value = MediaSelectorSettings.Default.copy(preferKind = MediaSourceKind.WEB)
        val (case, source) = createCase("web1", MediaSourceKind.WEB)
        play(case, source, "web1", MediaSourceKind.WEB, manual = false)
        assertEquals(WebSourceLineage("web1", "条目", ""), repository.lineageOf(subjectId))
        case.scope.cancel()
    }

    @Test
    fun `a manual pick becomes the lineage whatever its kind`() = runTest {
        mediaSelectorSettings.value = MediaSelectorSettings.Default.copy(preferKind = MediaSourceKind.BitTorrent)
        val (case, source) = createCase("web1", MediaSourceKind.WEB)
        play(case, source, "web1", MediaSourceKind.WEB, manual = true)
        assertEquals(WebSourceLineage("web1", "条目", ""), repository.lineageOf(subjectId))
        case.scope.cancel()
    }
}
