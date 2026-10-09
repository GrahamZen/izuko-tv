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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
import me.him188.ani.app.domain.player.SeekPreviewDecoderFault
import me.him188.ani.utils.coroutines.childScope
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 拖动预览换输出弄坏了解码器之后: 拖动预览改成画在全屏上、提示一次; 同一轮里接着出的解码错误不重复改、不重复提示;
 * 不是这种错 (没换过输出、或网络错误) 不动设置.
 */
class SeekPreviewDecoderFaultExtensionTest : AbstractPlayerExtensionTest() {
    private var alreadyFullScreen = false
    private var switchCalls = 0

    @BeforeTest
    fun setUp() {
        SeekPreviewDecoderFault.reset()
    }

    @AfterTest
    fun tearDown() {
        SeekPreviewDecoderFault.reset()
    }

    private fun decodingFailed() = PlaybackException(
        PlaybackErrorCode.DECODING,
        "ExoPlayer playback failed: ERROR_CODE_DECODING_FAILED (4003): MediaCodecVideoRenderer error, index=0",
    )

    private fun networkFailed() = PlaybackException(
        PlaybackErrorCode.IO,
        "ExoPlayer playback failed: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001): Source error",
    )

    private fun TestScope.createCase(): Triple<CoroutineScope, EpisodePlayerTestSuite, EpisodeFetchSelectPlayState> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val testScope = this.childScope()
        val suite = EpisodePlayerTestSuite(this, testScope)
        suite.registerComponent<MediaResolver> { TestUniversalMediaResolver }
        val factory = EpisodePlayerExtensionFactory { context, _ ->
            SeekPreviewDecoderFaultExtension(context) {
                switchCalls++
                val switched = !alreadyFullScreen
                alreadyFullScreen = true
                switched
            }
        }
        val state = suite.createState(listOf(factory))
        state.onUIReady()
        return Triple(testScope, suite, state)
    }

    @OptIn(UnsafeEpisodeSessionApi::class)
    private suspend fun TestScope.loadSelectedMedia(suite: EpisodePlayerTestSuite, state: EpisodeFetchSelectPlayState) {
        val media = TestMediaList[0]
        suite.mediaSelectorTestBuilder.delayedMediaSource("fault").complete(listOf(media))
        suite.setMediaDuration(100_000L)
        state.mediaSelectorFlow.filterNotNull().first().select(media)
        advanceUntilIdle()
    }

    @Test
    fun `预览换输出后解码出错, 改成全屏并提示一次`() = runTest {
        val (testScope, suite, state) = createCase()
        val notices = mutableListOf<Unit>()
        testScope.launch { SeekPreviewDecoderFault.switchedToFullScreen.collect { notices += it } }
        advanceUntilIdle()
        loadSelectedMedia(suite, state)

        SeekPreviewDecoderFault.markOutputSwitched()
        suite.player.injectError(decodingFailed())
        advanceUntilIdle()
        assertEquals(1, switchCalls)
        assertEquals(1, notices.size)

        // 同一轮里重载后又坏了: 不再改设置, 也不再提示
        loadSelectedMedia(suite, state)
        suite.player.injectError(decodingFailed())
        advanceUntilIdle()
        assertEquals(1, switchCalls)
        assertEquals(1, notices.size)

        testScope.cancel()
    }

    @Test
    fun `原来就是全屏时不提示`() = runTest {
        alreadyFullScreen = true
        val (testScope, suite, state) = createCase()
        val notices = mutableListOf<Unit>()
        testScope.launch { SeekPreviewDecoderFault.switchedToFullScreen.collect { notices += it } }
        advanceUntilIdle()
        loadSelectedMedia(suite, state)

        SeekPreviewDecoderFault.markOutputSwitched()
        suite.player.injectError(decodingFailed())
        advanceUntilIdle()
        assertEquals(1, switchCalls)
        assertEquals(0, notices.size)

        testScope.cancel()
    }

    @Test
    fun `没换过输出的解码错误和网络错误不动设置`() = runTest {
        val (testScope, suite, state) = createCase()
        advanceUntilIdle()
        loadSelectedMedia(suite, state)

        suite.player.injectError(decodingFailed())
        advanceUntilIdle()
        assertEquals(0, switchCalls)

        loadSelectedMedia(suite, state)
        SeekPreviewDecoderFault.markOutputSwitched()
        suite.player.injectError(networkFailed())
        advanceUntilIdle()
        assertEquals(0, switchCalls)

        testScope.cancel()
    }
}
