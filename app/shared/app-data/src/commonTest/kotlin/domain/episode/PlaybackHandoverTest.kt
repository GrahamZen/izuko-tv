/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.hls.HlsPlaybackOptions
import me.him188.ani.app.domain.media.hls.HlsPlaybackPreparer
import me.him188.ani.app.domain.media.hls.HlsPlaybackPreparerResult
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolutionException
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.ResolutionFailures
import me.him188.ani.app.domain.mediasource.instance.GetMediaSourceInstancesUseCase
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.settings.GetVideoScaffoldConfigUseCase
import me.him188.ani.app.videoplayer.link.PlaybackLinks
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import org.koin.core.Koin
import org.koin.dsl.module
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.errorOrNull
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 正在播的资源确认接不上了时换源 ([PlaybackHandover]): 旧的接着放已缓冲的, 新的解析好了再换下来, 从旧的最后播到的位置接着放.
 */
class PlaybackHandoverTest {
    private val episode = EpisodeMetadata(title = "EP1", ep = EpisodeSort(1), sort = EpisodeSort(1))
    private val first = TestMediaList[0]
    private val second = TestMediaList[1]
    private val third = TestMediaList[2]

    /** 解析 [held] 里的资源时等着放行, 别的立刻给 */
    private val held = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** 解析不出的资源 */
    private val unresolvable = mutableSetOf<String>()

    private fun uriOf(media: Media) = "https://handover.test/${media.mediaId}"

    private fun hold(media: Media) = CompletableDeferred<Unit>().also { held[media.mediaId] = it }

    private val TestMediampPlayer.uri get() = (mediaData.value as? UriMediaData)?.uri

    /** 播着 [first], 放到 [positionMillis] 后数据源确认它接不上了 */
    private suspend fun TestScope.playUnreachable(session: PlayerSession, positionMillis: Long = 315_000L) {
        session.loadMedia(first, episode)
        session.player.seekTo(positionMillis)
        advanceUntilIdle()
        assertNotNull(PlaybackLinks.of(uriOf(first))).markUnreachable()
    }

    @Test
    fun `an unreachable source keeps playing until the next one is ready and hands over where it got to`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        var announced = 0
        backgroundScope.launch { PlaybackHandover.started.collect { announced++ } }
        playUnreachable(session)
        val gate = hold(second)

        val loading = launch { session.loadMedia(second, episode, startPositionMillis = 1_000L) }
        advanceUntilIdle()
        // 新的还在解析: 旧的照常放
        assertEquals(uriOf(first), player.uri)
        assertTrue(player.state.value.playWhenReady)
        assertIs<VideoLoadingState.Succeed>(session.videoLoadingState.value)
        assertEquals(first, session.loadedMedia.value)
        assertEquals(second, session.handover.preparing.value)
        assertEquals(1, announced)

        player.seekTo(330_000L)
        advanceUntilIdle()
        gate.complete(Unit)
        loading.join()

        assertEquals(uriOf(second), player.uri)
        assertEquals(330_000L, player.currentPositionMillis.value)
        assertTrue(player.state.value.playWhenReady)
        assertEquals(second, session.loadedMedia.value)
        assertIs<VideoLoadingState.Succeed>(session.videoLoadingState.value)
        assertNull(session.handover.preparing.value)
    }

    @Test
    fun `the position at handover comes from the caller when it knows`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        playUnreachable(session)

        session.loadMedia(second, episode, positionAtHandover = { 320_000L })

        assertEquals(uriOf(second), player.uri)
        assertEquals(320_000L, player.currentPositionMillis.value)
    }

    @Test
    fun `a paused old source hands over paused`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        playUnreachable(session)
        val gate = hold(second)

        val loading = launch { session.loadMedia(second, episode) }
        advanceUntilIdle()
        player.pause()
        advanceUntilIdle()
        gate.complete(Unit)
        loading.join()

        assertEquals(uriOf(second), player.uri)
        assertFalse(player.state.value.playWhenReady)
    }

    @Test
    fun `the old source running out of buffer first shows loading and the next one opens where it stopped`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        playUnreachable(session)
        val gate = hold(second)

        val loading = launch { session.loadMedia(second, episode) }
        advanceUntilIdle()
        player.seekTo(330_000L)
        advanceUntilIdle()
        player.injectError(PlaybackException(PlaybackErrorCode.IO, "Source error"))
        advanceUntilIdle()
        // 旧的停了, 画面改成正在加载; 仍算在换
        assertNull(player.state.value.errorOrNull)
        assertEquals(VideoLoadingState.ResolvingSource, session.videoLoadingState.value)
        assertEquals(second, session.handover.preparing.value)

        gate.complete(Unit)
        loading.join()
        assertEquals(uriOf(second), player.uri)
        assertEquals(330_000L, player.currentPositionMillis.value)
        assertTrue(player.state.value.playWhenReady)
        assertNull(session.handover.preparing.value)
    }

    @Test
    fun `the next one failing to resolve keeps the old one playing and is reported`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        val failures = mutableListOf<PlaybackHandover.Failure>()
        backgroundScope.launch { session.handover.failures.collect { failures += it } }
        playUnreachable(session)
        unresolvable += second.mediaId

        session.loadMedia(second, episode)
        advanceUntilIdle()
        // 旧的照常放, 不报失败; 换源那边据此换下一条
        assertEquals(uriOf(first), player.uri)
        assertTrue(player.state.value.playWhenReady)
        assertIs<VideoLoadingState.Succeed>(session.videoLoadingState.value)
        assertEquals(first, session.loadedMedia.value)
        assertNull(session.handover.preparing.value)
        assertEquals(listOf(second), failures.map { it.media })
        assertEquals(VideoLoadingState.NoMatchingFile, failures.single().state)

        // 下一条照样在后台装好再换下来
        session.loadMedia(third, episode)
        assertEquals(uriOf(third), player.uri)
        assertEquals(315_000L, player.currentPositionMillis.value)
    }

    @Test
    fun `picking another one while handing over keeps the old one playing and is announced once`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        var announced = 0
        backgroundScope.launch { PlaybackHandover.started.collect { announced++ } }
        playUnreachable(session)
        hold(second)

        val loading = launch { session.loadMedia(second, episode) }
        advanceUntilIdle()
        // 改选 (选中项变了, 上一次装被取消)
        loading.cancelAndJoin()
        assertEquals(uriOf(first), player.uri)
        assertIs<VideoLoadingState.Succeed>(session.videoLoadingState.value)
        assertNull(session.handover.preparing.value)

        session.loadMedia(third, episode)
        assertEquals(uriOf(third), player.uri)
        assertEquals(315_000L, player.currentPositionMillis.value)
        assertEquals(1, announced)
    }

    @Test
    fun `the same source failing again after it was handed over is announced again`() = runTest {
        val session = createPlayerSession()
        var announced = 0
        backgroundScope.launch { PlaybackHandover.started.collect { announced++ } }
        playUnreachable(session)
        session.loadMedia(second, episode)
        advanceUntilIdle()
        assertEquals(1, announced)

        // 选回原来那条, 它又接不上了
        playUnreachable(session)
        session.loadMedia(second, episode)
        advanceUntilIdle()
        assertEquals(2, announced)
    }

    @Test
    fun `a rejected link is resolved again while the old one keeps playing and hands over where it got to`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        var announced = 0
        backgroundScope.launch { PlaybackHandover.started.collect { announced++ } }
        session.loadMedia(first, episode)
        player.seekTo(315_000L)
        advanceUntilIdle()
        // 地址过期被拒, 数据源换不了: 重新解析同一条
        assertNotNull(PlaybackLinks.of(uriOf(first))).markRejected()
        val gate = hold(first)

        val loading = launch { session.loadMedia(first, episode) }
        advanceUntilIdle()
        assertTrue(player.state.value.playWhenReady)
        assertIs<VideoLoadingState.Succeed>(session.videoLoadingState.value)
        assertEquals(first, session.handover.preparing.value)

        player.seekTo(330_000L)
        advanceUntilIdle()
        gate.complete(Unit)
        loading.join()
        assertEquals(uriOf(first), player.uri)
        assertEquals(330_000L, player.currentPositionMillis.value)
        assertTrue(player.state.value.playWhenReady)
        assertNull(session.handover.preparing.value)
        // 换的是同一条的新地址: 不提示
        assertEquals(0, announced)
    }

    @Test
    fun `a next one that cannot be opened is not handed over and is reported`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        session.checkHandoverLink = { data -> if ((data as UriMediaData).uri == uriOf(second)) HandoverLinkCheckException("HTTP 404") else null }
        val failures = mutableListOf<PlaybackHandover.Failure>()
        backgroundScope.launch { session.handover.failures.collect { failures += it } }
        playUnreachable(session)

        session.loadMedia(second, episode)
        advanceUntilIdle()
        // 换下来之前就试出打不开: 旧的照常放, 报给换源那边
        assertEquals(uriOf(first), player.uri)
        assertIs<VideoLoadingState.Succeed>(session.videoLoadingState.value)
        assertEquals(listOf(second), failures.map { it.media })
        assertIs<HandoverLinkCheckException>((failures.single().state as VideoLoadingState.UnknownError).cause)

        session.loadMedia(third, episode)
        assertEquals(uriOf(third), player.uri)
    }

    @Test
    fun `reloading the same media without a rejected link stops it first as before`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        playUnreachable(session)
        val gate = hold(first)

        // 接不上的这条再原地装一次 (不是地址被拒): 照常先停
        val loading = launch { session.loadMedia(first, episode) }
        advanceUntilIdle()
        assertNull(player.uri)
        assertNull(session.handover.preparing.value)
        gate.complete(Unit)
        loading.join()
    }

    @Test
    fun `a source that is not confirmed unreachable stops before the next one loads`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        session.loadMedia(first, episode)
        advanceUntilIdle()
        val gate = hold(second)

        val loading = launch { session.loadMedia(second, episode) }
        advanceUntilIdle()
        assertNull(player.uri)
        assertEquals(VideoLoadingState.ResolvingSource, session.videoLoadingState.value)
        assertNull(session.handover.preparing.value)

        gate.complete(Unit)
        loading.join()
        assertEquals(uriOf(second), player.uri)
    }

    private fun TestScope.createPlayerSession(): PlayerSession {
        val koin = Koin()
        koin.loadModules(
            listOf(
                module {
                    single<MediaResolver> { HeldMediaResolver() }
                    single<GetVideoScaffoldConfigUseCase> {
                        GetVideoScaffoldConfigUseCase { flowOf(VideoScaffoldConfig.AllDisabled) }
                    }
                    single<GetMediaSourceInstancesUseCase> {
                        object : GetMediaSourceInstancesUseCase {
                            override fun invoke(): Flow<List<MediaSourceInstance>> = flowOf(emptyList())
                        }
                    }
                    single<HlsPlaybackPreparer> { UnusedHlsPlaybackPreparer }
                },
            ),
        )
        val player = TestMediampPlayer(StandardTestDispatcher(testScheduler))
        player.defaultMediaProperties = player.defaultMediaProperties.copy(durationMillis = 1_000_000L)
        return PlayerSession(player, koin, backgroundScope, mainDispatcher = EmptyCoroutineContext)
    }

    private inner class HeldMediaResolver : MediaResolver {
        override fun supports(media: Media): Boolean = true

        override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
            held[media.mediaId]?.await()
            if (media.mediaId in unresolvable) throw MediaResolutionException(ResolutionFailures.NO_MATCHING_RESOURCE)
            val data = UriMediaData(uriOf(media), emptyMap())
            return object : MediaDataProvider<UriMediaData> {
                override val extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY

                override suspend fun open(scopeForCleanup: CoroutineScope): UriMediaData = data
            }
        }
    }

    private object UnusedHlsPlaybackPreparer : HlsPlaybackPreparer {
        override suspend fun prepare(
            data: UriMediaData,
            options: HlsPlaybackOptions,
            startPositionHintMillis: Long?,
        ): HlsPlaybackPreparerResult = error("HLS preparation is off in these tests")
    }
}
