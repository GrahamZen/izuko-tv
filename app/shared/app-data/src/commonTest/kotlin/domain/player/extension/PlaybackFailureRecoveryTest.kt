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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.episode.player
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.platform.NetworkStatus
import me.him188.ani.utils.coroutines.childScope
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 真机现场: 暂停过夜后签名地址过期回 401, 接着 Wi-Fi 断了 27 秒, 期间报的是「网络不可达」与解析超时;
 * 换集、改选时的加载取消被当成失败, 把刚选的资源拉黑.
 */
@OptIn(UnsafeEpisodeSessionApi::class)
class PlaybackFailureRecoveryTest : AbstractPlayerExtensionTest() {
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

    /** 按类名认的网络异常 (见 isNetworkFailure), commonTest 里没有 java.net 的那个. */
    private class ConnectException(override val message: String) : Exception()

    private fun httpError(status: Int) = PlaybackException(
        PlaybackErrorCode.IO,
        "ExoPlayer playback failed: ERROR_CODE_IO_BAD_HTTP_STATUS (2004): Source error",
        FakeThrowable("Source error", FakeThrowable("Response code: $status")),
    )

    private fun unreachableError() = PlaybackException(
        PlaybackErrorCode.IO,
        "ExoPlayer playback failed: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001): Source error",
        FakeThrowable(
            "Source error",
            ConnectException("failed to connect to example.com/104.21.32.195 (port 443) after 30000ms: connect failed: ENETUNREACH (Network is unreachable)"),
        ),
    )

    private fun connectError() = PlaybackException(
        PlaybackErrorCode.IO,
        "ExoPlayer playback failed: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001): Source error",
        ConnectException("Failed to connect to example.com/104.21.32.195:443"),
    )

    private fun parsingError() = PlaybackException(
        PlaybackErrorCode.UNSUPPORTED_FORMAT,
        "ExoPlayer playback failed: ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003): Source error",
    )

    private fun decoderReclaimedError() = PlaybackException(
        PlaybackErrorCode.DECODING,
        "ExoPlayer playback failed: ERROR_CODE_DECODING_RESOURCES_RECLAIMED (4006): MediaCodecVideoRenderer error",
    )

    private fun decide(
        error: PlayerLoadError,
        canRefreshLink: Boolean = false,
        canReloadDecoder: Boolean = true,
        networkDown: Boolean = false,
    ) = decidePlaybackFailure(error, PlaybackFailureFacts(canRefreshLink, canReloadDecoder, networkDown))

    private fun failure(cause: Throwable) = PlayerLoadError("code=IO", cause)

    ///////////////////////////////////////////////////////////////////////////
    // 判定
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `a cancelled load is not a failure`() {
        assertFalse(PlayerLoadErrorHandler.canRecoverBySwitching(VideoLoadingState.Cancelled))
        assertEquals(
            PlaybackFailureAction.IGNORE,
            decide(PlayerLoadError("Cancelled", null, VideoLoadingState.Cancelled)),
        )
    }

    @Test
    fun `an unavailable web engine keeps the error on screen`() {
        assertEquals(
            PlaybackFailureAction.IGNORE,
            decide(PlayerLoadError("WebEngineUnavailable", null, VideoLoadingState.WebEngineUnavailable)),
        )
    }

    @Test
    fun `a surface timeout on leaving the page is ignored`() {
        assertEquals(PlaybackFailureAction.IGNORE, decide(failure(FakeThrowable("Detaching surface timed out."))))
    }

    @Test
    fun `a rejected link of a media that has played is resolved again`() {
        for (status in listOf(401, 403, 404, 410, 412)) {
            assertEquals(PlaybackFailureAction.REFRESH_LINK, decide(failure(httpError(status)), canRefreshLink = true), "HTTP $status")
        }
    }

    @Test
    fun `a page instead of the video of a media that has played is resolved again`() {
        assertEquals(PlaybackFailureAction.REFRESH_LINK, decide(failure(parsingError()), canRefreshLink = true))
    }

    @Test
    fun `a rejected link switches the source when it has not played or was just refreshed`() {
        assertEquals(PlaybackFailureAction.SWITCH_SOURCE, decide(failure(httpError(401)), canRefreshLink = false))
    }

    @Test
    fun `server errors and range errors switch the source even after playing`() {
        for (status in listOf(416, 429, 500, 502, 503)) {
            assertEquals(PlaybackFailureAction.SWITCH_SOURCE, decide(failure(httpError(status)), canRefreshLink = true), "HTTP $status")
        }
    }

    @Test
    fun `no network waits whatever the error says`() {
        for (error in listOf(failure(httpError(401)), failure(connectError()), PlayerLoadError("ResolutionTimedOut", null, VideoLoadingState.ResolutionTimedOut))) {
            assertEquals(PlaybackFailureAction.WAIT_FOR_NETWORK, decide(error, canRefreshLink = true, networkDown = true))
        }
    }

    @Test
    fun `a waiting state stays waiting when the session is rebuilt`() {
        assertEquals(
            PlaybackFailureAction.WAIT_FOR_NETWORK,
            decide(PlayerLoadError("WaitingForNetwork", null, VideoLoadingState.WaitingForNetwork)),
        )
    }

    @Test
    fun `a reclaimed decoder reloads once and then switches the source`() {
        assertEquals(PlaybackFailureAction.RELOAD_DECODER, decide(failure(decoderReclaimedError())))
        assertEquals(PlaybackFailureAction.SWITCH_SOURCE, decide(failure(decoderReclaimedError()), canReloadDecoder = false))
    }

    @Test
    fun `anything unknown switches the source`() {
        assertEquals(PlaybackFailureAction.SWITCH_SOURCE, decide(PlayerLoadError("too short", null)))
        assertEquals(PlaybackFailureAction.SWITCH_SOURCE, decide(failure(FakeThrowable("boom")), canRefreshLink = true))
        assertEquals(PlaybackFailureAction.SWITCH_SOURCE, decide(PlayerLoadError("NoMatchingFile", null, VideoLoadingState.NoMatchingFile)))
    }

    ///////////////////////////////////////////////////////////////////////////
    // 断网判定
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `network is down when the system says so or the connection reports unreachable`() {
        val network = FakeNetwork(TestTimeSource())
        assertFalse(network.isNetworkDownFor(failure(connectError())))
        assertTrue(network.isNetworkDownFor(failure(unreachableError())))
        assertFalse(network.isNetworkDownFor(failure(unreachableError()), trustUnreachable = false))
        network.lose()
        assertTrue(network.isNetworkDownFor(failure(connectError())))
    }

    @Test
    fun `a server that answered proves the network is up whatever the system says`() {
        val network = FakeNetwork(TestTimeSource())
        network.lose()
        assertFalse(network.isNetworkDownFor(failure(httpError(403))))
        assertFalse(network.isNetworkDownFor(failure(httpError(500))))
    }

    @Test
    fun `network failures soon after an outage are blamed on the outage`() {
        val time = TestTimeSource()
        val network = FakeNetwork(time)
        network.lose()
        network.restore()
        time += 10.seconds
        // 解析在断网时开始, 恢复后才超时
        assertTrue(network.isNetworkDownFor(PlayerLoadError("ResolutionTimedOut", null, VideoLoadingState.ResolutionTimedOut)))
        assertTrue(network.isNetworkDownFor(failure(connectError())))
        // 服务器回了话, 网是通的
        assertFalse(network.isNetworkDownFor(failure(httpError(403))))
        // 这次断网已经等过了
        assertFalse(network.isNetworkDownFor(failure(connectError()), handledLoss = network.lastLostAt))
        time += 11.seconds
        assertFalse(network.isNetworkDownFor(failure(connectError())))
    }

    ///////////////////////////////////////////////////////////////////////////
    // 处理
    ///////////////////////////////////////////////////////////////////////////

    private class Case(
        val scope: CoroutineScope,
        val suite: EpisodePlayerTestSuite,
        val state: EpisodeFetchSelectPlayState,
        val network: FakeNetwork,
        val recovery: PlaybackFailureRecovery,
    ) {
        val player: TestMediampPlayer get() = suite.player
        val media get() = TestMediaList[0]
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.createCase(rememberedPositionMillis: Long? = null): Case {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = childScope()
        val suite = EpisodePlayerTestSuite(this, scope)
        suite.registerComponent<MediaResolver> { TestUniversalMediaResolver }
        lateinit var context: PlayerExtensionContext
        val state = suite.createState(
            listOf(
                EpisodePlayerExtensionFactory { c, _ ->
                    context = c
                    object : PlayerExtension("Capture") {
                        override suspend fun startPositionMillis(episodeId: Int): Long? = rememberedPositionMillis
                    }
                },
            ),
        )
        state.onUIReady()
        advanceUntilIdle()
        val network = FakeNetwork(testScheduler.timeSource)
        val recovery = PlaybackFailureRecovery(context, network, testScheduler.timeSource)
        scope.launch { recovery.trackPlayback(context.sessionFlow.flatMapLatest { it.fetchSelectFlow }) }
        return Case(scope, suite, state, network, recovery)
    }

    private suspend fun TestScope.loadAndPlay(case: Case, positionMillis: Long = 315_000L) {
        val source = case.suite.mediaSelectorTestBuilder.delayedMediaSource("source")
        source.complete(listOf(case.media))
        case.suite.setMediaDuration(1_000_000L)
        case.state.mediaSelectorFlow.filterNotNull().first().select(case.media)
        advanceUntilIdle()
        assertTrue(case.player.state.value.isPlaying)
        case.player.seekTo(positionMillis)
        advanceUntilIdle()
    }

    @Test
    fun `a rejected link is resolved again and playback continues at the same position`() = runTest {
        val case = createCase()
        loadAndPlay(case)
        val opens = case.player.openCallCount

        val error = httpError(401)
        case.player.injectError(error)
        runCurrent()
        assertTrue(case.recovery.recover(failure(error), case.media))
        advanceUntilIdle()

        assertEquals(opens + 1, case.player.openCallCount)
        assertEquals(315_000L, case.player.currentPositionMillis.value)
        assertTrue(case.player.state.value.playWhenReady)
        assertEquals(case.media, case.state.mediaSelectorFlow.filterNotNull().first().selected.value)
        case.scope.cancel()
    }

    @Test
    fun `a paused player stays paused after the link is resolved again`() = runTest {
        val case = createCase()
        loadAndPlay(case)
        case.player.pause()
        advanceUntilIdle()

        val error = httpError(403)
        case.player.injectError(error)
        runCurrent()
        assertTrue(case.recovery.recover(failure(error), case.media))
        advanceUntilIdle()

        assertEquals(315_000L, case.player.currentPositionMillis.value)
        assertFalse(case.player.state.value.playWhenReady)
        case.scope.cancel()
    }

    @Test
    fun `a link rejected again right after refreshing switches the source and later expiry refreshes again`() = runTest {
        val case = createCase()
        loadAndPlay(case)

        assertTrue(case.recovery.recover(failure(httpError(401)), case.media))
        advanceUntilIdle()
        val opens = case.player.openCallCount

        // 刚取的地址又被拒绝: 不是过期, 不再重取
        assertFalse(case.recovery.recover(failure(httpError(401)), case.media))
        assertEquals(opens, case.player.openCallCount)

        // 又放了一夜, 新地址也过期了
        advanceTimeBy(31.minutes)
        runCurrent()
        assertTrue(case.recovery.recover(failure(httpError(401)), case.media))
        advanceUntilIdle()
        assertEquals(opens + 1, case.player.openCallCount)
        case.scope.cancel()
    }

    @Test
    fun `a link rejected before the media ever played switches the source`() = runTest {
        val case = createCase()
        advanceUntilIdle()
        assertFalse(case.recovery.recover(failure(httpError(401)), case.media))
        case.scope.cancel()
    }

    @Test
    fun `without network the player stops and resumes at the same position when it returns`() = runTest {
        val case = createCase()
        loadAndPlay(case)
        val opens = case.player.openCallCount

        case.network.lose()
        val error = connectError()
        case.player.injectError(error)
        runCurrent()
        var result: Boolean? = null
        val job = launch { result = case.recovery.recover(failure(error), case.media) }

        // 还没到再试一次的时候
        advanceTimeBy(20.seconds)
        runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(VideoLoadingState.WaitingForNetwork, case.state.playerSession.videoLoadingState.value)
        assertEquals(opens, case.player.openCallCount)

        case.network.restore()
        advanceTimeBy(2.seconds)
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(true, result)
        assertEquals(opens + 1, case.player.openCallCount)
        assertEquals(315_000L, case.player.currentPositionMillis.value)
        assertTrue(case.player.state.value.playWhenReady)
        assertTrue(case.state.playerSession.videoLoadingState.value is VideoLoadingState.Succeed)
        case.scope.cancel()
    }

    @Test
    fun `a system that keeps reporting no network still gets a try every so often`() = runTest {
        val case = createCase()
        loadAndPlay(case)
        val opens = case.player.openCallCount

        case.network.lose()
        val error = connectError()
        case.player.injectError(error)
        runCurrent()
        val job = launch { case.recovery.recover(failure(error), case.media) }
        advanceTimeBy(32.seconds)
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(opens + 1, case.player.openCallCount)
        assertEquals(315_000L, case.player.currentPositionMillis.value)
        assertTrue(case.player.state.value.isPlaying)
        case.scope.cancel()
    }

    @Test
    fun `a failure after the wait is the fault of the source`() = runTest {
        val case = createCase()
        loadAndPlay(case)

        case.network.lose()
        val job = launch { case.recovery.recover(failure(connectError()), case.media) }
        runCurrent()
        case.network.restore()
        advanceTimeBy(2.seconds)
        advanceUntilIdle()
        assertTrue(job.isCompleted)

        // 网回来了还连不上: 不能再怪刚才那次断网
        assertFalse(case.recovery.recover(failure(connectError()), case.media))
        case.scope.cancel()
    }

    @Test
    fun `a media that never played opens at the remembered position after the wait`() = runTest {
        val case = createCase(rememberedPositionMillis = 42_000L)
        val source = case.suite.mediaSelectorTestBuilder.delayedMediaSource("source")
        source.complete(listOf(case.media))
        case.suite.setMediaDuration(1_000_000L)
        // 打开就报错, 没播起来过
        case.player.openBehavior = TestMediampPlayer.OpenBehavior.Fail(connectError())
        case.state.mediaSelectorFlow.filterNotNull().first().select(case.media)
        advanceUntilIdle()
        assertFalse(case.player.state.value.isPlaying)

        case.network.lose()
        val job = launch { case.recovery.recover(PlayerLoadError("code=IO", connectError()), case.media) }
        advanceTimeBy(20.seconds)
        runCurrent()
        assertFalse(job.isCompleted)

        case.player.openBehavior = TestMediampPlayer.OpenBehavior.Immediate
        case.network.restore()
        advanceTimeBy(2.seconds)
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(42_000L, case.player.currentPositionMillis.value)
        assertTrue(case.player.state.value.playWhenReady)
        case.scope.cancel()
    }

    @Test
    fun `unreachable with the system catching up within the lag waits for the network`() = runTest {
        val case = createCase()
        loadAndPlay(case)

        val job = launch { case.recovery.recover(failure(unreachableError()), case.media) }
        advanceTimeBy(500)
        runCurrent()
        // 路由先没了, 系统这时才报
        case.network.lose()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(VideoLoadingState.WaitingForNetwork, case.state.playerSession.videoLoadingState.value)

        job.cancel()
        case.scope.cancel()
    }

    @Test
    fun `unreachable while the system keeps reporting a network switches the source`() = runTest {
        val case = createCase()
        loadAndPlay(case)

        // 例如连 IPv6 地址而这个网络没有 IPv6 路由
        var result: Boolean? = null
        launch { result = case.recovery.recover(failure(unreachableError()), case.media) }
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(false, result)
        case.scope.cancel()
    }

    @Test
    fun `a failure with nothing selected and no network does not go to switching`() = runTest {
        val case = createCase()
        case.network.lose()
        assertTrue(case.recovery.recover(failure(connectError()), null))
        case.scope.cancel()
    }

    @Test
    fun `a failure with nothing selected and a working network goes to switching`() = runTest {
        val case = createCase()
        assertFalse(case.recovery.recover(failure(httpError(500)), null))
        case.scope.cancel()
    }
}
