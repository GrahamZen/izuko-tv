/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.mediasource.instance.GetMediaSourceInstancesUseCase
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.settings.GetVideoScaffoldConfigUseCase
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import org.koin.core.Koin
import org.koin.dsl.module
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PlayerSession.loadMedia] 装好就播之前问 [PlayerSession.startGate]: 不让播就停着装, 之后谁要求播放都照常生效。
 */
class PlayerSessionStartGateTest {
    private val episode = EpisodeMetadata(title = "EP1", ep = EpisodeSort(1), sort = EpisodeSort(1))

    @Test
    fun `opens paused when the gate holds the start`() = runTest {
        val session = createPlayerSession()
        var asked = 0
        session.startGate = PlaybackStartGate { asked++; false }

        session.loadMedia(TestMediaList.first(), episode)

        assertEquals(1, asked)
        assertEquals(MediaStatus.Ready, session.player.state.value.mediaStatus)
        assertFalse(session.player.state.value.playWhenReady)
        assertEquals("https://example.com/video.mp4", (session.player.mediaData.first() as UriMediaData).uri)
    }

    @Test
    fun `opens playing when the gate admits the start`() = runTest {
        val session = createPlayerSession()
        session.startGate = PlaybackStartGate { true }

        session.loadMedia(TestMediaList.first(), episode)

        assertEquals(MediaStatus.Ready, session.player.state.value.mediaStatus)
        assertTrue(session.player.state.value.playWhenReady)
    }

    @Test
    fun `does not ask the gate when loading paused on purpose`() = runTest {
        val session = createPlayerSession()
        var asked = 0
        session.startGate = PlaybackStartGate { asked++; true }

        session.loadMedia(TestMediaList.first(), episode, playWhenReady = false)

        assertEquals(0, asked)
        assertFalse(session.player.state.value.playWhenReady)
    }

    @Test
    fun `play requested while a held start is still opening is not lost`() = runTest {
        val session = createPlayerSession()
        val player = session.player as TestMediampPlayer
        val hold = TestMediampPlayer.OpenBehavior.Hold()
        player.openBehavior = hold
        session.startGate = PlaybackStartGate { false }

        val loading = launch { session.loadMedia(TestMediaList.first(), episode) }
        advanceUntilIdle()
        assertEquals(MediaStatus.Opening, player.state.value.mediaStatus)
        assertFalse(player.state.value.playWhenReady)

        // 回答「不播」之后画面回来了: 宿主要求播放时片子还在打开
        player.play()
        assertTrue(player.state.value.playWhenReady)

        hold.release()
        loading.join()

        assertEquals(MediaStatus.Ready, player.state.value.mediaStatus)
        assertTrue(player.state.value.playWhenReady)
    }

    @Test
    fun `play after a held start resumes the opened media`() = runTest {
        val session = createPlayerSession()
        session.startGate = PlaybackStartGate { false }
        session.loadMedia(TestMediaList.first(), episode)

        session.player.play()
        advanceUntilIdle()

        assertTrue(session.player.state.value.playWhenReady)
    }

    private fun TestScope.createPlayerSession(): PlayerSession {
        val koin = Koin()
        koin.loadModules(
            listOf(
                module {
                    single<MediaResolver> { StaticMediaResolver(UriMediaData("https://example.com/video.mp4", emptyMap())) }
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
        return PlayerSession(player, koin, backgroundScope, mainDispatcher = EmptyCoroutineContext)
    }

    private class StaticMediaResolver(
        private val data: UriMediaData,
    ) : MediaResolver {
        override fun supports(media: Media): Boolean = true

        override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
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
