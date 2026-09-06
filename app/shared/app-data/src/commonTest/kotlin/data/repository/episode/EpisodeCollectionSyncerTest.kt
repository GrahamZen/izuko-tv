/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.domain.session.InvalidSessionReason
import me.him188.ani.app.domain.session.SessionEvent
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlinx.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EpisodeCollectionSyncerTest {
    private class FakeSource(initial: List<EpisodeCollectionPendingOp>) : EpisodeCollectionPendingOpSource {
        val ops = MutableStateFlow(initial)
        override val pendingOpsFlow: Flow<List<EpisodeCollectionPendingOp>> get() = ops
        override suspend fun deletePendingOps(ids: Collection<Long>) {
            ops.value = ops.value.filterNot { it.id in ids }
        }
    }

    private class FakeSession(valid: Boolean = true) : SessionStateProvider {
        override val stateFlow: Flow<SessionState> =
            MutableStateFlow(if (valid) SessionState.Valid(bangumiConnected = true) else SessionState.Invalid(InvalidSessionReason.NO_TOKEN))
        override val eventFlow: Flow<SessionEvent> = emptyFlow()
    }

    private data class Call(val subjectId: Int, val episodeIds: List<Int>, val type: UnifiedCollectionType)

    /** 记下每一次推送, 按 [failures] 决定每次调用的结果: 异常就抛, `false` 就返回 false, `null` 表示成功. */
    private class FakePusher(
        private val failures: ArrayDeque<Any?>,
    ) : EpisodeCollectionPusher {
        val calls = mutableListOf<Call>()

        override suspend fun push(subjectId: Int, episodeIds: List<Int>, collectionType: UnifiedCollectionType): Boolean {
            calls += Call(subjectId, episodeIds, collectionType)
            return when (val f = failures.removeFirstOrNull()) {
                is Throwable -> throw f
                false -> false
                else -> true
            }
        }
    }

    private suspend fun clientRequestException(status: HttpStatusCode): ClientRequestException {
        val client = HttpClient(MockEngine { respond("rejected", status) }) { expectSuccess = true }
        return runCatching { client.get("http://test/") }.exceptionOrNull() as ClientRequestException
    }

    private fun op(id: Long, subjectId: Int, episodeId: Int, type: UnifiedCollectionType = UnifiedCollectionType.DONE) =
        EpisodeCollectionPendingOp(id = id, subjectId = subjectId, episodeId = episodeId, collectionType = type, updatedAtMillis = id)

    private fun createSyncer(
        source: FakeSource,
        api: FakePusher,
        session: SessionStateProvider = FakeSession(),
    ) = EpisodeCollectionSyncer(
        repository = source,
        pusher = api,
        sessionStateProvider = session,
        scope = kotlinx.coroutines.CoroutineScope(EmptyCoroutineContext),
        ioDispatcher = EmptyCoroutineContext,
    )

    @Test
    fun `batches ops by subject and type then removes them`() = runTest {
        val source = FakeSource(
            listOf(
                op(1, subjectId = 1, episodeId = 11),
                op(2, subjectId = 1, episodeId = 12),
                op(3, subjectId = 2, episodeId = 21),
                op(4, subjectId = 1, episodeId = 13, type = UnifiedCollectionType.WISH),
            ),
        )
        val api = FakePusher(ArrayDeque())

        createSyncer(source, api).syncOnce()

        assertEquals(
            listOf(
                Call(1, listOf(11, 12), UnifiedCollectionType.DONE),
                Call(2, listOf(21), UnifiedCollectionType.DONE),
                Call(1, listOf(13), UnifiedCollectionType.WISH),
            ),
            api.calls,
        )
        assertEquals(emptyList(), source.ops.value)
    }

    @Test
    fun `network failure keeps remaining ops for next time`() = runTest {
        val source = FakeSource(listOf(op(1, subjectId = 1, episodeId = 11), op(2, subjectId = 2, episodeId = 21)))
        val api = FakePusher(ArrayDeque(listOf(null, IOException("offline"))))

        assertFailsWith<RepositoryNetworkException> { createSyncer(source, api).syncOnce() }

        assertEquals(listOf(2L), source.ops.value.map { it.id })
    }

    @Test
    fun `server rejection drops the batch and continues`() = runTest {
        val source = FakeSource(listOf(op(1, subjectId = 1, episodeId = 11), op(2, subjectId = 2, episodeId = 21)))
        val api = FakePusher(ArrayDeque(listOf(clientRequestException(HttpStatusCode.NotFound), null)))

        createSyncer(source, api).syncOnce()

        assertEquals(2, api.calls.size)
        assertEquals(emptyList(), source.ops.value)
    }

    @Test
    fun `unauthorized keeps ops`() = runTest {
        val source = FakeSource(listOf(op(1, subjectId = 1, episodeId = 11)))
        val api = FakePusher(ArrayDeque(listOf(clientRequestException(HttpStatusCode.Unauthorized))))

        assertFailsWith<RepositoryException> { createSyncer(source, api).syncOnce() }

        assertEquals(listOf(1L), source.ops.value.map { it.id })
    }

    @Test
    fun `batch the service cannot push is dropped`() = runTest {
        // 条目或剧集在 Bangumi 上已经没了: 服务返回 false, 这批丢掉, 后面的照推
        val source = FakeSource(listOf(op(1, subjectId = 1, episodeId = 11), op(2, subjectId = 2, episodeId = 21)))
        val api = FakePusher(ArrayDeque(listOf(false, null)))

        createSyncer(source, api).syncOnce()

        assertEquals(2, api.calls.size)
        assertEquals(emptyList(), source.ops.value)
    }

    @Test
    fun `does nothing without a valid session`() = runTest {
        val source = FakeSource(listOf(op(1, subjectId = 1, episodeId = 11)))
        val api = FakePusher(ArrayDeque())

        createSyncer(source, api, session = FakeSession(valid = false)).syncOnce()

        assertEquals(emptyList(), api.calls)
        assertEquals(listOf(1L), source.ops.value.map { it.id })
    }
}
