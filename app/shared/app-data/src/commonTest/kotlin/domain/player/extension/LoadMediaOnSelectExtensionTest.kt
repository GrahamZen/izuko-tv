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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.domain.episode.EpisodeFetchSelectPlayState
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.hls.HlsPlaybackOptions
import me.him188.ani.app.domain.media.hls.HlsPlaybackPreparer
import me.him188.ani.app.domain.media.hls.HlsPlaybackPreparerResult
import me.him188.ani.app.domain.media.player.data.AniSystemFileMediaData
import me.him188.ani.app.domain.media.resolver.LocalFileMediaResolver
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.settings.GetVideoScaffoldConfigUseCase
import me.him188.ani.utils.coroutines.childScope
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.writeBytes
import org.openani.mediamp.source.UriMediaData
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * @see EpisodeFetchSelectPlayState.LoadMediaOnSelectExtension
 */
class LoadMediaOnSelectExtensionTest : AbstractPlayerExtensionTest() {
    /**
     * @param hlsPreparer 非空时开启 HLS 广告过滤并使用它.
     */
    private fun TestScope.createCase(
        mediaResolver: MediaResolver = TestUniversalMediaResolver,
        hlsPreparer: HlsPlaybackPreparer? = null,
        extensions: List<EpisodePlayerExtensionFactory<*>> = listOf(),
    ): Triple<CoroutineScope, EpisodePlayerTestSuite, EpisodeFetchSelectPlayState> {
        val testScope = this.childScope()
        val suite = EpisodePlayerTestSuite(this, testScope)
        suite.registerComponent<GetVideoScaffoldConfigUseCase> {
            GetVideoScaffoldConfigUseCase {
                flowOf(
                    VideoScaffoldConfig.AllDisabled.copy(
                        autoPlayNext = true,
                        enableHlsAdFiltering = hlsPreparer != null,
                    ),
                )
            }
        }
        suite.registerComponent<MediaResolver> {
            mediaResolver
        }
        if (hlsPreparer != null) {
            suite.registerComponent<HlsPlaybackPreparer> { hlsPreparer }
        }

        val state = suite.createState(extensions) // LoadMediaOnSelectExtension is intrinsic
        state.onUIReady()
        advanceUntilIdle()
        return Triple(testScope, suite, state)
    }

    /** 只给起始位置的扩展. */
    private fun startAt(positionMillis: (episodeId: Int) -> Long?) = EpisodePlayerExtensionFactory { _, _ ->
        object : PlayerExtension("StartAt") {
            override suspend fun startPositionMillis(episodeId: Int): Long? = positionMillis(episodeId)
        }
    }

    @Test
    fun `opens the selected media where an extension says to start`() = runTest {
        val (testScope, suite, state) = createCase(extensions = listOf(startAt { 42_000L }))

        val ms1 = suite.mediaSelectorTestBuilder.delayedMediaSource("1")
        val myMedia = TestMediaList[0]
        ms1.complete(listOf(myMedia))
        state.mediaSelectorFlow.filterNotNull().first().select(myMedia)
        advanceUntilIdle()

        assertEquals(42_000, suite.player.currentPositionMillis.value)
        testScope.cancel()
    }

    @Test
    fun `an extension failing to give a start position still plays from the start`() = runTest {
        val (testScope, suite, state) = createCase(extensions = listOf(startAt { error("broken extension") }))

        val ms1 = suite.mediaSelectorTestBuilder.delayedMediaSource("1")
        val myMedia = TestMediaList[0]
        ms1.complete(listOf(myMedia))
        state.mediaSelectorFlow.filterNotNull().first().select(myMedia)
        advanceUntilIdle()

        assertIs<VideoLoadingState.Succeed>(state.playerSession.videoLoadingState.value)
        assertEquals(0, suite.player.currentPositionMillis.value)
        testScope.cancel()
    }

    @Test
    fun `can load media on select`() = runTest {
        val (testScope, suite, state) =
            createCase()

        val ms1 = suite.mediaSelectorTestBuilder.delayedMediaSource("1")

        val myMedia = TestMediaList[0]
        ms1.complete(listOf(myMedia))
        state.mediaSelectorFlow.filterNotNull().first().select(myMedia)
        advanceUntilIdle()

        assertIs<UriMediaData>(suite.player.mediaData.first())
        assertEquals(0, suite.player.currentPositionMillis.value)

        testScope.cancel()
    }

    @Test
    fun `passes the start position to the hls preparer as hint`() = runTest {
        val preparer = HintRecordingHlsPlaybackPreparer()
        // 起播位置由扩展给 (实际是 RememberPlayProgressExtension 读的播放进度), 播放器直接从这里打开, HLS 预缓存也从这里开始
        val (testScope, suite, state) = createCase(extensions = listOf(startAt { 30_000L }), hlsPreparer = preparer)

        val ms1 = suite.mediaSelectorTestBuilder.delayedMediaSource("1")
        ms1.complete(listOf(TestMediaList[0]))
        state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceUntilIdle()

        assertEquals(listOf<Long?>(30_000), preparer.hints)
        assertEquals(30_000, suite.player.currentPositionMillis.value)

        testScope.cancel()
    }

    @Test
    fun `no start position means no hls hint`() = runTest {
        val preparer = HintRecordingHlsPlaybackPreparer()
        val (testScope, suite, state) = createCase(hlsPreparer = preparer)

        val ms1 = suite.mediaSelectorTestBuilder.delayedMediaSource("1")
        ms1.complete(listOf(TestMediaList[0]))
        state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceUntilIdle()

        assertEquals(listOf<Long?>(null), preparer.hints)
        assertEquals(0, suite.player.currentPositionMillis.value)

        testScope.cancel()
    }

    private class HintRecordingHlsPlaybackPreparer : HlsPlaybackPreparer {
        val hints = mutableListOf<Long?>()

        override suspend fun prepare(
            data: UriMediaData,
            options: HlsPlaybackOptions,
            startPositionHintMillis: Long?,
        ): HlsPlaybackPreparerResult {
            hints += startPositionHintMillis
            return HlsPlaybackPreparerResult(data)
        }
    }

    @Test
    fun `plays a dropped file while media sources are still fetching`() = runTest {
        val (testScope, suite, state) =
            createCase(LocalFileMediaResolver())

        suite.mediaSelectorTestBuilder.delayedMediaSource("1") // 一直未完成查询

        // 播放器会打开文件, 因此需要一个真实存在的文件
        val file = Path(SystemTemporaryDirectory, "ani-dropped-${Random.nextLong()}.mkv").inSystem
        file.writeBytes(byteArrayOf(0))
        try {
            state.mediaSelectorFlow.filterNotNull().first().selectTemporarily(DroppedFileMedia.create(file))
            advanceUntilIdle()

            assertIs<VideoLoadingState.Succeed>(state.playerSession.videoLoadingState.value)
            val data = assertIs<AniSystemFileMediaData>(suite.player.mediaData.first())
            assertEquals(file.name, data.filename)
        } finally {
            testScope.cancel()
            file.delete()
        }
    }

    @Test
    fun `can load media and reset player on select`() = runTest {
        val (testScope, suite, state) =
            createCase()

        val ms1 = suite.mediaSelectorTestBuilder.delayedMediaSource("1")

        // v2: give the player prior playback state that the select must replace.
        suite.player.loadMedia(durationMs = 100_000L, playWhenReady = true, uri = "file://old.mp4")
        suite.player.injectPosition(1000)
        advanceUntilIdle()

        val myMedia = TestMediaList[0]
        ms1.complete(listOf(myMedia))
        state.mediaSelectorFlow.filterNotNull().first().select(myMedia)
        advanceUntilIdle()

        assertIs<UriMediaData>(suite.player.mediaData.first())
        assertEquals(0, suite.player.currentPositionMillis.value)

        testScope.cancel()
    }

    @Test
    fun `switch media resets player`() = runTest {
        val (testScope, suite, state) =
            createCase()

        val ms1 = suite.mediaSelectorTestBuilder.delayedMediaSource("1")

        // v2: give the player prior playback state that the select must replace.
        suite.player.loadMedia(durationMs = 100_000L, playWhenReady = true, uri = "file://old.mp4")
        suite.player.injectPosition(1000)
        advanceUntilIdle()

        // Fetch complete
        ms1.complete(TestMediaList.take(2))

        // Select media        
        state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceUntilIdle() // should reset player 
        val previousData = suite.player.mediaData.first()
        assertIs<UriMediaData>(previousData)
        assertEquals(0, suite.player.currentPositionMillis.value)


        // Let's play it for a while
        suite.player.seekTo(2000)


        // Switch media
        state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[1])
        advanceUntilIdle()
        assertNotSame(previousData, suite.player.mediaData.first())
        assertEquals(0, suite.player.currentPositionMillis.value) // should reset

        testScope.cancel()
    }

    @Test
    fun `noop when unselect`() = runTest {
        val (testScope, suite, state) =
            createCase()

        val ms1 = suite.mediaSelectorTestBuilder.delayedMediaSource("1")

        // v2: give the player prior playback state that the select must replace.
        suite.player.loadMedia(durationMs = 100_000L, playWhenReady = true, uri = "file://old.mp4")
        suite.player.injectPosition(1000)
        advanceUntilIdle()

        // Fetch complete
        ms1.complete(TestMediaList.take(2))

        // Select media        
        state.mediaSelectorFlow.filterNotNull().first().select(TestMediaList[0])
        advanceUntilIdle() // should reset player 
        val previousData = suite.player.mediaData.first()
        assertIs<UriMediaData>(previousData)
        assertEquals(0, suite.player.currentPositionMillis.value)


        // Let's play it for a while
        suite.player.seekTo(2000)

        // Unselect media
        state.mediaSelectorFlow.filterNotNull().first().unselect()
        advanceUntilIdle() // State should not change
        assertSame(previousData, suite.player.mediaData.first())
        assertEquals(2000, suite.player.currentPositionMillis.value)


        testScope.cancel()
    }
}