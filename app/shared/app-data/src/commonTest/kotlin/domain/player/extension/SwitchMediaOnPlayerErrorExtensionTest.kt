/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.data.persistent.createTestPreferencesDataStore
import me.him188.ani.app.data.repository.media.PlaybackLineageRepository
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.episode.player
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.continuation.EpisodeCacheRecords
import me.him188.ani.app.domain.media.continuation.EpisodeContinuation
import me.him188.ani.app.domain.media.continuation.LineageResolution
import me.him188.ani.app.domain.media.continuation.PlaybackLineageSelection
import me.him188.ani.app.domain.media.continuation.PlaybackLineage
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolutionException
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.ResolutionFailures
import me.him188.ani.app.domain.media.resolver.TestMediaDataProvider
import me.him188.ani.app.domain.media.selector.MediaSelectorSourceTiers
import me.him188.ani.app.domain.mediasource.GetMediaSelectorSourceTiersUseCase
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.settings.GetMediaSelectorSettingsFlowUseCase
import me.him188.ani.app.domain.settings.GetVideoScaffoldConfigUseCase
import me.him188.ani.app.platform.NetworkStatus
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.utils.coroutines.childScope
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 播放器报错、加载状态到换源判断这一整条路: 判定本身见 [PlaybackFailureRecoveryTest], 这里看接上真的加载流程之后对不对.
 * 走到换源那一步的次数用 [Case.switchAttempts] 数 (只有 [PlayerLoadErrorHandler.handleError] 读选源设置).
 */
@OptIn(UnsafeEpisodeSessionApi::class, InternalForInheritanceMediampApi::class)
class SwitchMediaOnPlayerErrorExtensionTest : AbstractPlayerExtensionTest() {
    private class FakeNetwork(private val time: TimeSource) : NetworkStatus {
        val available = MutableStateFlow(true)
        override val isAvailable: StateFlow<Boolean> get() = available
        override var lastLostAt: TimeMark? = null

        fun lose() {
            available.value = false
            lastLostAt = time.markNow()
        }

        fun restore() {
            available.value = true
        }
    }

    private class FakeThrowable(override val message: String?, override val cause: Throwable? = null) : Exception()

    private class NoContinuation : EpisodeContinuation {
        override fun owns(media: Media) = false
        override suspend fun lineageOf(media: Media): PlaybackLineage? = null
        override fun continues(lineage: PlaybackLineage, candidate: Media) = false
        override suspend fun resolve(lineage: PlaybackLineage, request: MediaFetchRequest): LineageResolution = LineageResolution.NotFound
        override fun isQuickToResolve(lineage: PlaybackLineage) = false
    }

    /** 解析: 排着队的失败先抛完, 之后正常. */
    private class ScriptedResolver : MediaResolver {
        val failures = ArrayDeque<Throwable>()
        var resolves = 0
        override fun supports(media: Media): Boolean = true
        override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
            resolves++
            failures.removeFirstOrNull()?.let { throw it }
            return TestMediaDataProvider()
        }
    }

    private class Case(
        val scope: CoroutineScope,
        val suite: EpisodePlayerTestSuite,
        val state: EpisodeFetchSelectPlayState,
        val network: FakeNetwork,
        val resolver: ScriptedResolver,
        val loadingStatesAtStop: List<VideoLoadingState>,
        val switchAttempts: () -> Int,
    ) {
        val player: TestMediampPlayer get() = suite.player
    }

    private fun httpError(status: Int) = PlaybackException(
        PlaybackErrorCode.IO,
        "ExoPlayer playback failed: ERROR_CODE_IO_BAD_HTTP_STATUS (2004): Source error",
        FakeThrowable("Source error", FakeThrowable("Response code: $status")),
    )

    private fun TestScope.createCase(): Case {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = childScope()
        val suite = EpisodePlayerTestSuite(this, scope)
        val network = FakeNetwork(testScheduler.timeSource)
        val resolver = ScriptedResolver()
        var attempts = 0
        suite.registerComponent<MediaResolver> { resolver }
        suite.registerComponent<NetworkStatus> { network }
        suite.registerComponent<GetVideoScaffoldConfigUseCase> {
            GetVideoScaffoldConfigUseCase { flowOf(VideoScaffoldConfig.AllDisabled.copy(autoSwitchMediaOnPlayerError = true)) }
        }
        suite.registerComponent<GetMediaSelectorSettingsFlowUseCase> {
            GetMediaSelectorSettingsFlowUseCase {
                attempts++
                flowOf(MediaSelectorSettings.Default)
            }
        }
        suite.registerComponent<GetMediaSelectorSourceTiersUseCase> {
            GetMediaSelectorSourceTiersUseCase { flowOf(MediaSelectorSourceTiers(emptyMap())) }
        }
        suite.registerComponent<PlaybackLineageSelection> {
            PlaybackLineageSelection(PlaybackLineageRepository(createTestPreferencesDataStore()), NoContinuation(), EpisodeCacheRecords { _, _ -> false })
        }
        val loadingStatesAtStop = mutableListOf<VideoLoadingState>()
        lateinit var state: EpisodeFetchSelectPlayState
        // 真播放器一停就同步清掉错误, 测试用的播放器要等一轮调度: 记下停的那一刻加载状态是什么
        val player = object : MediampPlayer by suite.player {
            override fun stopPlayback() {
                loadingStatesAtStop += state.playerSession.videoLoadingState.value
                suite.player.stopPlayback()
            }
        }
        state = EpisodeFetchSelectPlayState(
            subjectId,
            initialEpisodeId,
            player,
            scope,
            extensions = listOf(SwitchMediaOnPlayerErrorExtension),
            suite.koin,
            mainDispatcher = Dispatchers.Main,
        )
        state.onUIReady()
        advanceUntilIdle()
        return Case(scope, suite, state, network, resolver, loadingStatesAtStop) { attempts }
    }

    private suspend fun TestScope.loadAndPlay(case: Case, positionMillis: Long = 315_000L) {
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(2))
        case.suite.setMediaDuration(1_000_000L)
        case.state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceUntilIdle()
        assertTrue(case.player.state.value.isPlaying)
        case.player.seekTo(positionMillis)
        advanceUntilIdle()
    }

    private suspend fun Case.selected() = state.mediaSelectorFlow.filterNotNull().first().selected.value

    @Test
    fun `selecting another media while one is loading is not a failure`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(2))
        case.player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()
        val selector = case.state.mediaSelectorFlow.filterNotNull().first()

        selector.select(TestMediaList[0])
        advanceUntilIdle()
        selector.select(TestMediaList[1])
        advanceUntilIdle()

        assertEquals(TestMediaList[1], selector.selected.value)
        assertEquals(0, case.switchAttempts())
        case.scope.cancel()
    }

    @Test
    fun `a rejected link after playing resolves the link again and stays on the source`() = runTest {
        val case = createCase()
        loadAndPlay(case)
        val resolves = case.resolver.resolves

        case.player.injectError(httpError(401))
        advanceUntilIdle()

        assertEquals(0, case.switchAttempts())
        assertEquals(resolves + 1, case.resolver.resolves)
        assertEquals(TestMediaList[0], case.selected())
        assertEquals(315_000L, case.player.currentPositionMillis.value)
        assertTrue(case.player.state.value.isPlaying)
        case.scope.cancel()
    }

    @Test
    fun `a link rejected again right after resolving it goes to switching`() = runTest {
        val case = createCase()
        loadAndPlay(case)

        case.player.injectError(httpError(401))
        advanceUntilIdle()
        assertEquals(0, case.switchAttempts())

        case.player.injectError(httpError(401))
        advanceUntilIdle()
        assertEquals(1, case.switchAttempts())
        case.scope.cancel()
    }

    @Test
    fun `a rejected link that never played goes to switching`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(2))
        case.player.openBehavior = TestMediampPlayer.OpenBehavior.Fail(httpError(403))

        case.state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceUntilIdle()

        assertEquals(1, case.switchAttempts())
        assertEquals(1, case.resolver.resolves)
        case.scope.cancel()
    }

    @Test
    fun `a playback error without network waits and resumes on the same source`() = runTest {
        val case = createCase()
        loadAndPlay(case)
        val resolves = case.resolver.resolves
        val stopsBefore = case.loadingStatesAtStop.size

        case.network.lose()
        case.player.injectError(httpError(500))
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(VideoLoadingState.WaitingForNetwork, case.state.playerSession.videoLoadingState.value)
        // 播放器一停错误就没了: 这时要已经是在等网, 否则「错误消失」会把等待本身取消, 网回来也没人接着放
        assertEquals(listOf<VideoLoadingState>(VideoLoadingState.WaitingForNetwork), case.loadingStatesAtStop.drop(stopsBefore))
        assertEquals(0, case.switchAttempts())
        assertEquals(resolves, case.resolver.resolves)

        case.network.restore()
        advanceTimeBy(2.seconds)
        advanceUntilIdle()

        assertEquals(0, case.switchAttempts())
        assertEquals(resolves + 1, case.resolver.resolves)
        assertEquals(TestMediaList[0], case.selected())
        assertEquals(315_000L, case.player.currentPositionMillis.value)
        assertTrue(case.player.state.value.isPlaying)
        case.scope.cancel()
    }

    @Test
    fun `a resolve timeout without network waits and resolves again`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(2))
        case.suite.setMediaDuration(1_000_000L)
        case.resolver.failures += MediaResolutionException(ResolutionFailures.FETCH_TIMEOUT)
        case.network.lose()

        case.state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceTimeBy(5.minutes)
        runCurrent()

        // 解析失败后播放器是停着的, 没有播放器错误撑着: 等网不能被当成「错误消失了」取消
        assertEquals(VideoLoadingState.WaitingForNetwork, case.state.playerSession.videoLoadingState.value)
        assertEquals(1, case.resolver.resolves)
        assertEquals(0, case.switchAttempts())

        case.network.restore()
        advanceTimeBy(2.seconds)
        advanceUntilIdle()

        assertEquals(2, case.resolver.resolves)
        assertEquals(0, case.switchAttempts())
        assertTrue(case.player.state.value.isPlaying)
        case.scope.cancel()
    }

    @Test
    fun `a resolve timeout right after an outage resolves again without switching`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(2))
        case.suite.setMediaDuration(1_000_000L)
        case.network.lose()
        case.network.restore()
        advanceTimeBy(5.seconds)
        case.resolver.failures += MediaResolutionException(ResolutionFailures.FETCH_TIMEOUT)

        case.state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceTimeBy(2.seconds)
        advanceUntilIdle()

        assertEquals(2, case.resolver.resolves)
        assertEquals(0, case.switchAttempts())
        assertTrue(case.player.state.value.isPlaying)
        case.scope.cancel()
    }

    @Test
    fun `selecting another media while waiting for the network stops waiting`() = runTest {
        val case = createCase()
        loadAndPlay(case)

        case.network.lose()
        case.player.injectError(httpError(500))
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(VideoLoadingState.WaitingForNetwork, case.state.playerSession.videoLoadingState.value)

        // 手动换一条: 这条要是也解析失败, 还是继续等网
        val selector = case.state.mediaSelectorFlow.filterNotNull().first()
        selector.select(TestMediaList[1])
        case.network.restore()
        advanceTimeBy(2.seconds)
        advanceUntilIdle()

        assertEquals(TestMediaList[1], selector.selected.value)
        assertTrue(case.player.state.value.isPlaying)
        case.scope.cancel()
    }

    @Test
    fun `a reclaimed decoder while paused comes back paused at the same position`() = runTest {
        val case = createCase()
        loadAndPlay(case)
        case.player.pause()
        advanceUntilIdle()

        case.player.injectError(
            PlaybackException(
                PlaybackErrorCode.DECODING,
                "ExoPlayer playback failed: ERROR_CODE_DECODING_RESOURCES_RECLAIMED (4006): MediaCodecVideoRenderer error",
            ),
        )
        advanceUntilIdle()

        assertEquals(0, case.switchAttempts())
        assertEquals(315_000L, case.player.currentPositionMillis.value)
        assertFalse(case.player.state.value.playWhenReady)
        case.scope.cancel()
    }
}
