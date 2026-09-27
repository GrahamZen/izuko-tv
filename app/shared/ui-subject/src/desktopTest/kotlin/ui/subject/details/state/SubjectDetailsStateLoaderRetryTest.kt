/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.TestSubjectInfo
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadAttempt
import me.him188.ani.app.ui.subject.details.SubjectDetailsUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * 首屏每次最多等 5 秒, 超时就重来, 最多 5 次. 每次重来都要让页面知道 "上一次超时了, 现在是第几次",
 * 否则慢网络下要对着转圈干等二十多秒才看到错误页.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubjectDetailsStateLoaderRetryTest {
    private val subjectId = TestSubjectInfo.subjectId

    /** 首屏永远不到: 每次订阅都挂起到被取消. */
    private class NeverLoadingFactory : SubjectDetailsStateFactory {
        var createCount = 0
            private set

        override fun create(subjectId: Int, placeholder: SubjectInfo?): Flow<SubjectDetailsState> = flow {
            createCount++
            awaitCancellation()
        }

        override fun create(subjectInfoFlow: Flow<SubjectInfo>): Flow<SubjectDetailsState> = emptyFlow()
        override fun create(subjectInfo: SubjectInfo): Flow<SubjectDetailsState> = emptyFlow()
        override fun create(subjectCollectionInfo: SubjectCollectionInfo, scope: CoroutineScope): SubjectDetailsState =
            throw UnsupportedOperationException()
    }

    @Test
    fun `each first screen timeout reports the next attempt until the error page`() = runTest {
        val factory = NeverLoadingFactory()
        val loader = SubjectDetailsStateLoader(factory, backgroundScope)
        loader.load(subjectId)
        runCurrent()
        assertEquals(
            SubjectDetailsLoadAttempt.First,
            assertIs<SubjectDetailsUIState.Placeholder>(loader.state.value).loadAttempt,
        )

        for (attempt in 2..5) {
            advanceTimeBy(5.seconds)
            runCurrent()
            val placeholder = assertIs<SubjectDetailsUIState.Placeholder>(loader.state.value)
            assertEquals(SubjectDetailsLoadAttempt(attempt, maxAttempts = 5), placeholder.loadAttempt)
            assertEquals(attempt, factory.createCount)
        }

        advanceTimeBy(5.seconds)
        runCurrent()
        assertIs<SubjectDetailsUIState.Err>(loader.state.value)
    }
}
