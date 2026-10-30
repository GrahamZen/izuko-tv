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
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.episode.player
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.settings.GetMediaSelectorSettingsFlowUseCase
import me.him188.ani.app.domain.settings.GetVideoScaffoldConfigUseCase
import me.him188.ani.app.platform.NetworkStatus
import me.him188.ani.utils.coroutines.childScope
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** 真机现场: 断网时切到下一集, 所有在线源搜索失败, 提示「都没有找到这一集」, 网回来也不再搜. */
@OptIn(UnsafeEpisodeSessionApi::class)
class SearchAfterNetworkOutageExtensionTest : AbstractPlayerExtensionTest() {
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

    private class Case(
        val scope: CoroutineScope,
        val suite: EpisodePlayerTestSuite,
        val state: EpisodeFetchSelectPlayState,
        val network: FakeNetwork,
        val searches: () -> Int,
        val switchAttempts: () -> Int,
    ) {
        val loading get() = state.playerSession.videoLoadingState.value
    }

    private fun TestScope.createCase(): Case {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = childScope()
        val suite = EpisodePlayerTestSuite(this, scope)
        val network = FakeNetwork(testScheduler.timeSource)
        var searches = 0
        var attempts = 0
        suite.registerComponent<MediaResolver> { TestUniversalMediaResolver }
        suite.registerComponent<NetworkStatus> { network }
        suite.registerComponent<GetVideoScaffoldConfigUseCase> {
            GetVideoScaffoldConfigUseCase { flowOf(VideoScaffoldConfig.AllDisabled.copy(autoSwitchMediaOnPlayerError = true)) }
        }
        // 只有换源那一步读选源设置
        suite.registerComponent<GetMediaSelectorSettingsFlowUseCase> {
            GetMediaSelectorSettingsFlowUseCase {
                attempts++
                flowOf(MediaSelectorSettings.Default)
            }
        }
        val state = suite.createState(
            listOf(
                EpisodePlayerExtensionFactory { context, _ -> SearchAfterNetworkOutageExtension(context, network) { searches++ } },
                SwitchMediaOnPlayerErrorExtension,
            ),
        )
        state.onUIReady()
        advanceUntilIdle()
        return Case(scope, suite, state, network, { searches }, { attempts })
    }

    @Test
    fun `nothing selected without network waits and searches again when it returns`() = runTest {
        val case = createCase()

        case.network.lose()
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(VideoLoadingState.WaitingForNetwork, case.loading)
        assertEquals(0, case.searches())
        // 等网的提示不能被当成播放失败去换源
        assertEquals(0, case.switchAttempts())

        case.network.restore()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, case.searches())
        assertEquals(VideoLoadingState.Initial, case.loading)
        assertEquals(0, case.switchAttempts())
        case.scope.cancel()
    }

    @Test
    fun `a short drop during the settle does not search twice`() = runTest {
        val case = createCase()

        case.network.lose()
        runCurrent()
        case.network.restore()
        advanceTimeBy(500.milliseconds)
        runCurrent()
        // 等 DNS 好的这一秒里又断了
        case.network.lose()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(0, case.searches())
        assertEquals(VideoLoadingState.WaitingForNetwork, case.loading)

        case.network.restore()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, case.searches())
        case.scope.cancel()
    }

    @Test
    fun `selecting a media while waiting does not search again`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(1))
        case.suite.setMediaDuration(1_000_000L)

        case.network.lose()
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(VideoLoadingState.WaitingForNetwork, case.loading)

        // 本地缓存或手动选的
        case.state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(case.loading is VideoLoadingState.Succeed)
        assertTrue(case.suite.player.state.value.isPlaying)

        case.network.restore()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(0, case.searches())
        case.scope.cancel()
    }

    @Test
    fun `an automatic pick that has not started is searched again when the network returns`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(1))
        case.suite.player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()

        case.network.lose()
        advanceTimeBy(1.seconds)
        runCurrent()
        // 手上有旧结果的源被选中了, 地址取不到
        assertEquals(TestMediaList[0], case.state.mediaSelectorFlow.filterNotNull().first().selectAutomatically(TestMediaList[0], null))
        advanceTimeBy(1.minutes)
        runCurrent()

        case.network.restore()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, case.searches())
        case.scope.cancel()
    }

    @Test
    fun `a pick by the user that has not started is not searched again`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(1))
        case.suite.player.openBehavior = TestMediampPlayer.OpenBehavior.Hold()

        case.network.lose()
        advanceTimeBy(1.seconds)
        runCurrent()
        case.state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceTimeBy(1.minutes)
        runCurrent()

        case.network.restore()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(0, case.searches())
        case.scope.cancel()
    }

    @Test
    fun `an automatic pick that is playing is not interrupted when the network returns`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(1))
        case.suite.setMediaDuration(1_000_000L)

        case.network.lose()
        advanceTimeBy(1.seconds)
        runCurrent()
        // 本地缓存不要网
        case.state.mediaSelectorFlow.filterNotNull().first().selectAutomatically(TestMediaList[0], null)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertTrue(case.loading is VideoLoadingState.Succeed)

        case.network.restore()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(0, case.searches())
        assertTrue(case.suite.player.state.value.isPlaying)
        case.scope.cancel()
    }

    @Test
    fun `a playing media losing the network keeps playing`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(1))
        case.suite.setMediaDuration(1_000_000L)
        case.state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceTimeBy(1.seconds)
        runCurrent()

        // 缓冲还撑得住
        case.network.lose()
        advanceTimeBy(1.minutes)
        runCurrent()
        assertTrue(case.loading is VideoLoadingState.Succeed)
        assertTrue(case.suite.player.state.value.isPlaying)

        case.network.restore()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(0, case.searches())
        case.scope.cancel()
    }

    @Test
    fun `a player error with nothing selected and no network is not a switch`() = runTest {
        val case = createCase()
        case.suite.mediaSelectorTestBuilder.delayedMediaSource("source").complete(TestMediaList.take(1))
        case.suite.setMediaDuration(1_000_000L)
        val selector = case.state.mediaSelectorFlow.filterNotNull().first()
        selector.select(TestMediaList[0])
        advanceTimeBy(1.seconds)
        runCurrent()

        selector.unselect()
        case.network.lose()
        case.suite.player.injectError(PlaybackException(PlaybackErrorCode.IO, "Source error"))
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(0, case.switchAttempts())
        case.scope.cancel()
    }
}
