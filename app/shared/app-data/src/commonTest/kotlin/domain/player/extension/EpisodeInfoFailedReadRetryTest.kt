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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.models.subject.TestSubjectCollections
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.domain.episode.EpisodePlayerTestSuite
import me.him188.ani.app.domain.episode.GetSubjectEpisodeInfoBundleFlowUseCase
import me.him188.ani.app.domain.episode.SubjectEpisodeInfoBundle
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.app.domain.episode.createExceptionCapturingSupervisorScope
import me.him188.ani.app.domain.episode.infoBundleFlow
import me.him188.ani.app.domain.episode.infoLoadErrorFlow
import me.him188.ani.utils.coroutines.flows.FailedReadRetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 分集信息加载失败后, 收到 [FailedReadRetry] 再取一次. */
@OptIn(UnsafeEpisodeSessionApi::class)
class EpisodeInfoFailedReadRetryTest : AbstractPlayerExtensionTest() {
    private var failing = true
    private var loads = 0

    private fun TestScope.createCase(): Pair<EpisodePlayerTestSuite, CoroutineScope> {
        // 不放 backgroundScope: advanceUntilIdle 不等那里的协程
        val (scope, _) = createExceptionCapturingSupervisorScope(this)
        val suite = EpisodePlayerTestSuite(this, scope)
        suite.registerComponent<GetSubjectEpisodeInfoBundleFlowUseCase> {
            GetSubjectEpisodeInfoBundleFlowUseCase { idsFlow ->
                idsFlow.map {
                    loads++
                    if (failing) throw RepositoryNetworkException()
                    SubjectEpisodeInfoBundle(
                        it.subjectId,
                        it.episodeId,
                        TestSubjectCollections[0].run { copy(subjectInfo = subjectInfo.copy(subjectId = it.subjectId)) },
                        TestSubjectCollections[0].episodes[0].run { copy(episodeInfo = episodeInfo.copy(episodeId = it.episodeId)) },
                        seriesInfo = SubjectSeriesInfo.Fallback,
                        subjectCompleted = false,
                    )
                }
            }
        }
        return suite to scope
    }

    @Test
    fun `a failed info load is loaded again on a retry request`() = runTest {
        val (suite, scope) = createCase()
        val state = suite.createState()
        state.onUIReady()
        scope.launch { state.infoBundleFlow.collect {} }
        advanceUntilIdle()
        assertEquals(1, loads)
        assertNotNull(state.infoLoadErrorFlow.first())

        failing = false
        FailedReadRetry.request()
        advanceUntilIdle()

        assertEquals(2, loads)
        assertNull(state.infoLoadErrorFlow.first())
        assertNotNull(state.infoBundleFlow.filterNotNull().first())
        scope.cancel()
    }

    @Test
    fun `a loaded info is not loaded again on a retry request`() = runTest {
        failing = false
        val (suite, scope) = createCase()
        val state = suite.createState()
        state.onUIReady()
        scope.launch { state.infoBundleFlow.collect {} }
        advanceUntilIdle()
        assertEquals(1, loads)

        FailedReadRetry.request()
        advanceUntilIdle()

        assertEquals(1, loads)
        scope.cancel()
    }
}
