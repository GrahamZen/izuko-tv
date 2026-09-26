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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.mediaFetchSessionFlow
import me.him188.ani.app.domain.episode.mediaSelectorFlow
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.coroutines.childScope
import kotlin.test.Test
import kotlin.test.assertIs

class PauseMediaFetchWhilePlayingExtensionTest : AbstractPlayerExtensionTest() {
    @Test
    fun `sources still searching are paused once playback starts`() = runTest {
        playWhileWeb2Searching(canPause = { true }) { session ->
            assertIs<MediaSourceFetchState.Paused>(session.stateOf("web2"))
            assertIs<MediaSourceFetchState.Succeed>(session.stateOf("web1"))
        }
    }

    @Test
    fun `sources keep searching when pausing is not allowed`() = runTest {
        // 选源面板开着 / 开了完整搜索
        playWhileWeb2Searching(canPause = { false }) { session ->
            assertIs<MediaSourceFetchState.Working>(session.stateOf("web2"))
        }
    }

    /**
     * web1 查完并被选中播放, web2 一直查不完. 播放开始后用 [verify] 检查查询会话.
     */
    private suspend fun TestScope.playWhileWeb2Searching(
        canPause: () -> Boolean,
        verify: (MediaFetchSession) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val testScope = this.childScope()
        val suite = EpisodePlayerTestSuite(this, testScope)
        suite.registerComponent<MediaResolver> { TestUniversalMediaResolver }
        val web1 = suite.mediaSelectorTestBuilder.delayedMediaSource("web1", kind = MediaSourceKind.WEB)
        suite.mediaSelectorTestBuilder.delayedMediaSource("web2", kind = MediaSourceKind.WEB) // 一直查不完

        val state = suite.createState(listOf(PauseMediaFetchWhilePlayingExtension.Factory(canPause)))
        state.onUIReady()
        // 查询是惰性的: 在前台收集结果, advanceUntilIdle 才会把查询跑完
        state.mediaFetchSessionFlow.filterNotNull().flatMapLatest { it.cumulativeResults }.launchIn(testScope)

        val media = suite.mediaSelectorTestBuilder.createMedia("web1", kind = MediaSourceKind.WEB)
        web1.complete(listOf(media))
        advanceUntilIdle()
        val session = state.mediaFetchSessionFlow.filterNotNull().first()
        assertIs<MediaSourceFetchState.Working>(session.stateOf("web2"))

        state.mediaSelectorFlow.filterNotNull().first().selectAutomatically(media, null)
        advanceUntilIdle()
        suite.player.loadMedia(durationMs = 10_000L, playWhenReady = true)
        advanceUntilIdle()
        verify(session)

        testScope.cancel()
    }

    private fun MediaFetchSession.stateOf(mediaSourceId: String): MediaSourceFetchState =
        mediaSourceResults.single { it.mediaSourceId == mediaSourceId }.state.value
}
