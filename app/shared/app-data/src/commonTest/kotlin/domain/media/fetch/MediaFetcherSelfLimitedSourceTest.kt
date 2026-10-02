/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.mediasource.instance.createTestMediaSourceInstance
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.TestHttpMediaSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

class MediaFetcherSelfLimitedSourceTest {
    private val started = mutableListOf<String>()
    private val finish = CompletableDeferred<Unit>()

    private val request = MediaFetchRequest(
        subjectId = "1",
        episodeId = "1",
        subjectNames = listOf("葬送的芙莉莲"),
        episodeSort = EpisodeSort("01"),
        episodeName = "冒险的结束",
    )

    /** 开始查就记进 [started], 等 [finish] 才查完. */
    private suspend fun search(name: String): SizedSource<MediaMatch> {
        started += name
        finish.await()
        return emptySizedSource()
    }

    private open inner class Source(name: String) : TestHttpMediaSource(
        mediaSourceId = name,
        kind = MediaSourceKind.WEB,
        fetch = { search(name) },
        info = MediaSourceInfo(displayName = name),
    )

    private inner class DriveSource(name: String) : Source(name), SelfLimitedMediaSource

    private fun TestScope.fetcher(vararg sources: Source) = MediaSourceMediaFetcher(
        configProvider = { MediaFetcherConfig.Default },
        mediaSources = sources.map { createTestMediaSourceInstance(it) },
        flowContext = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext,
        searchLimiter = MediaSourceSearchLimiter(permits = 1, holdLimit = 1.hours, timerScope = backgroundScope),
    )

    @Test
    fun `self limited sources start without waiting for a permit`() = runTest {
        val session = fetcher(Source("web"), Source("queued"), DriveSource("drive")).newSession(request)
        backgroundScope.launch { session.cumulativeResults.collect() }
        runCurrent()
        // 唯一的名额被 web 占着: 网盘源照样开查, 另一个网页源排队
        assertEquals(setOf("web", "drive"), started.toSet())

        finish.complete(Unit)
        runCurrent()
        assertEquals(setOf("web", "drive", "queued"), started.toSet())
    }
}
