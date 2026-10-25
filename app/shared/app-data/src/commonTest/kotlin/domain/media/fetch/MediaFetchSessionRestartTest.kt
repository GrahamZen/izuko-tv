/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.mediasource.instance.createTestMediaSourceInstance
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.paging.SinglePagePagedSource
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.TestHttpMediaSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MediaFetchSessionRestartTest {
    private val request = MediaFetchRequest(
        subjectId = "1",
        episodeId = "2",
        subjectNames = listOf("test"),
        episodeSort = EpisodeSort("01"),
        episodeName = "ep",
    )

    /** 一个启用的源与一个禁用的源, 各自数被查了几次 */
    private class Sources {
        val enabledFetches = atomic(0)
        val disabledFetches = atomic(0)
    }

    private suspend fun session(sources: Sources): MediaFetchSession {
        fun source(counter: AtomicInt) = TestHttpMediaSource(
            fetch = {
                counter.incrementAndGet()
                SinglePagePagedSource { TestMediaList.map { MediaMatch(it, MatchKind.EXACT) }.asFlow() }
            },
        )
        return MediaSourceMediaFetcher(
            { MediaFetcherConfig.Default },
            listOf(
                createTestMediaSourceInstance(source(sources.enabledFetches)),
                createTestMediaSourceInstance(source(sources.disabledFetches), isEnabled = false),
            ),
            currentCoroutineContext()[ContinuationInterceptor] ?: EmptyCoroutineContext,
        ).newSession(request)
    }

    @Test
    fun `refresh leaves disabled sources unsearched`() = runTest {
        val sources = Sources()
        val session = session(sources)
        session.awaitCompletedResults()

        session.restartSources(includeDisabled = false)
        session.awaitCompletedResults()

        assertEquals(2, sources.enabledFetches.value)
        assertEquals(0, sources.disabledFetches.value)
        assertIs<MediaSourceFetchState.Disabled>(session.mediaSourceResults[1].state.value)
    }

    @Test
    fun `refresh with full search also searches disabled sources`() = runTest {
        val sources = Sources()
        val session = session(sources)
        session.awaitCompletedResults()

        session.restartSources(includeDisabled = true)
        session.awaitCompletedResults()

        assertEquals(2, sources.enabledFetches.value)
        assertEquals(1, sources.disabledFetches.value)
    }

    @Test
    fun `changing the request does not start disabled sources`() = runTest {
        val sources = Sources()
        val session = session(sources)
        session.awaitCompletedResults()

        session.setFetchRequest(request.copy(subjectNames = listOf("renamed")))
        session.awaitCompletedResults()

        assertEquals(2, sources.enabledFetches.value)
        assertEquals(0, sources.disabledFetches.value)
    }

    @Test
    fun `starting disabled sources leaves the others alone`() = runTest {
        val sources = Sources()
        val session = session(sources)
        session.awaitCompletedResults()

        session.startDisabledSources()
        session.awaitCompletedResults()

        assertEquals(1, sources.enabledFetches.value)
        assertEquals(1, sources.disabledFetches.value)
    }

    @Test
    fun `a disabled source started by hand is refreshed again`() = runTest {
        val sources = Sources()
        val session = session(sources)
        session.mediaSourceResults[1].restart()
        session.awaitCompletedResults()
        assertEquals(1, sources.disabledFetches.value)

        session.restartSources(includeDisabled = false)
        session.awaitCompletedResults()

        assertEquals(2, sources.disabledFetches.value)
    }
}
