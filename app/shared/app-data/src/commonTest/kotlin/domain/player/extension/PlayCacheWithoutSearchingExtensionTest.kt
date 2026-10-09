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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.coroutines.childScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * 「有缓存时直接播, 不搜索」: 有下载完的缓存时缓存以外的源一个都不查 (建会话时 [holdForFinishedCache] 停住);
 * 换成别的源 / 一直没选中时放开照常搜 ([PlayCacheWithoutSearchingExtension]).
 */
class PlayCacheWithoutSearchingExtensionTest : AbstractPlayerExtensionTest() {
    @Test
    fun `other sources are held while the finished cache plays`() = runTest {
        withSession(enabled = true, cached = true) { session, selector, cache, web ->
            assertIs<MediaSourceFetchState.Paused>(session.stateOf("web1"))
            selector.selectAutomatically(cache, null)
            advanceUntilIdle()
            assertIs<MediaSourceFetchState.Paused>(session.stateOf("web1"))

            // 换成别的源 (缓存播不了被换掉 / 手动换): 放开照常搜
            selector.select(web)
            advanceUntilIdle()
            assertIs<MediaSourceFetchState.Working>(session.stateOf("web1"))
        }
    }

    @Test
    fun `sources search as usual without a finished cache`() = runTest {
        withSession(enabled = true, cached = false) { session, _, _, _ ->
            assertIs<MediaSourceFetchState.Working>(session.stateOf("web1"))
        }
    }

    @Test
    fun `with the option off nothing changes and caches are not even looked up`() = runTest {
        var lookups = 0
        withSession(enabled = false, cached = true, onLookup = { lookups++ }) { session, selector, cache, _ ->
            assertIs<MediaSourceFetchState.Working>(session.stateOf("web1"))
            selector.selectAutomatically(cache, null)
            advanceTimeBy(11.seconds)
            advanceUntilIdle()
            assertIs<MediaSourceFetchState.Working>(session.stateOf("web1"))
        }
        assertEquals(0, lookups)
    }

    @Test
    fun `searching resumes when nothing gets selected`() = runTest {
        withSession(enabled = true, cached = true) { session, _, _, _ ->
            assertIs<MediaSourceFetchState.Paused>(session.stateOf("web1"))
            advanceTimeBy(11.seconds)
            advanceUntilIdle()
            assertIs<MediaSourceFetchState.Working>(session.stateOf("web1"))
        }
    }

    /**
     * 本地缓存源查完给出 cache, 在线源 web1 一直查不完. 会话建好后用 [verify] 检查.
     */
    private suspend fun TestScope.withSession(
        enabled: Boolean,
        cached: Boolean,
        onLookup: () -> Unit = {},
        verify: suspend TestScope.(session: MediaFetchSession, selector: MediaSelector, cache: Media, web: Media) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val testScope = this.childScope()
        val suite = EpisodePlayerTestSuite(this, testScope)
        suite.registerComponent<MediaResolver> { TestUniversalMediaResolver }
        val builder = suite.mediaSelectorTestBuilder
        val local = builder.delayedMediaSource("local", kind = MediaSourceKind.LocalCache)
        builder.delayedMediaSource("web1", kind = MediaSourceKind.WEB) // 一直查不完
        val state = suite.createState(
            listOf(
                PlayCacheWithoutSearchingExtension.Factory(
                    isEnabled = { enabled },
                    hasFinishedCache = { _, _ -> onLookup(); cached },
                ),
            ),
        )
        state.onUIReady()
        // 查询是惰性的: 在前台收集结果, advanceUntilIdle 才会把查询跑完
        state.mediaFetchSessionFlow.filterNotNull().flatMapLatest { it.cumulativeResults }.launchIn(testScope)
        val cache = builder.createMedia("local", kind = MediaSourceKind.LocalCache)
        local.complete(listOf(cache))
        // 只往前走一点: 一口气跑到空闲会越过「一直没选中就放开」的超时
        advanceTimeBy(1.seconds)
        runCurrent()
        val session = state.mediaFetchSessionFlow.filterNotNull().first()
        // 建会话的地方在交出去之前做的那一步 (见 CreateMediaFetchSelectBundleFlowUseCase)
        session.holdForFinishedCache(1, isEnabled = { enabled }, hasFinishedCache = { onLookup(); cached })
        val selector = state.mediaSelectorFlow.filterNotNull().first()
        verify(session, selector, cache, builder.createMedia("web1", kind = MediaSourceKind.WEB))
        testScope.cancel()
    }

    private fun MediaFetchSession.stateOf(mediaSourceId: String): MediaSourceFetchState =
        mediaSourceResults.single { it.mediaSourceId == mediaSourceId }.state.value
}
