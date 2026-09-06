/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.episode

import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.player.LeadingTrailingSyncGate
import me.him188.ani.app.domain.session.SessionEvent
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 把 [EpisodeCollectionRepository] 里的待同步操作推到服务端.
 *
 * 同条目同状态的剧集合并成一次批量请求. 网络或服务端故障时保留操作等下次; 服务端明确拒绝 (4xx, 例如条目没收藏或剧集不存在)
 * 时丢弃, 因为重试也不会成功.
 */
class EpisodeCollectionSyncer(
    private val repository: EpisodeCollectionPendingOpSource,
    private val pusher: EpisodeCollectionPusher,
    private val sessionStateProvider: SessionStateProvider,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
    requestCooldown: Duration = 5.seconds,
) : Repository() {
    private val syncMutex = Mutex()
    private val requestGate = LeadingTrailingSyncGate(
        scope = scope,
        cooldown = requestCooldown,
        task = ::syncOnceCatching,
        name = "EpisodeCollectionSyncer.requestGate",
    )

    fun start() {
        scope.launch(CoroutineName("EpisodeCollectionSyncer")) {
            sessionStateProvider.stateFlow
                .filterIsInstance<SessionState.Valid>()
                .first()
            requestSync()

            sessionStateProvider.eventFlow.collect { event ->
                if (event is SessionEvent.NewLogin) {
                    requestSync()
                }
            }
        }
    }

    fun requestSync() {
        requestGate.request()
    }

    /**
     * 推送一轮. 遇到网络或服务端故障时抛出 [RepositoryException] 并保留剩余操作.
     */
    suspend fun syncOnce() = syncMutex.withLock {
        if (sessionStateProvider.stateFlow.first() !is SessionState.Valid) return@withLock

        val pendingOps = repository.pendingOpsFlow.first()
        if (pendingOps.isEmpty()) return@withLock

        for (batch in pendingOps.batchBySubjectAndType()) {
            val pushed = try {
                withContext(ioDispatcher) {
                    pusher.push(batch.subjectId, batch.ops.map { it.episodeId }, batch.collectionType)
                }
            } catch (e: ClientRequestException) {
                if (e.response.status.isRetryable()) throw RepositoryException.wrapOrThrowCancellation(e)
                logger.warn { "Server rejected episode collection ops for subject ${batch.subjectId}, dropping: ${e.message}" }
                false
            } catch (e: Exception) {
                throw RepositoryException.wrapOrThrowCancellation(e)
            }
            if (pushed) {
                logger.info { "Synced ${batch.ops.size} episode collection ops for subject ${batch.subjectId}" }
            } else if (sessionStateProvider.stateFlow.first() !is SessionState.Valid) {
                // 推的途中登出了: 剩下的留到下次登录再推
                return@withLock
            }
            repository.deletePendingOps(batch.ops.map { it.id })
        }
    }

    private suspend fun syncOnceCatching() {
        try {
            syncOnce()
        } catch (e: Exception) {
            RepositoryException.wrapOrThrowCancellation(e)
            logger.info { "Failed to sync episode collections: ${e.message}" }
        }
    }

    private fun HttpStatusCode.isRetryable(): Boolean {
        return this == HttpStatusCode.Unauthorized ||
                this == HttpStatusCode.Forbidden ||
                this == HttpStatusCode.TooManyRequests ||
                this == HttpStatusCode.RequestTimeout
    }
}

/**
 * 把一批同条目、同状态的剧集看过状态推到 Bangumi.
 */
fun interface EpisodeCollectionPusher {
    /**
     * @return `false` 表示这批推不了且不必重试 (例如条目或剧集在 Bangumi 上已不存在); 未登录时也返回 `false`,
     * 由 [EpisodeCollectionSyncer] 按登录状态决定保留还是丢弃.
     * @throws ClientRequestException 服务端拒绝; 是否重试见 [EpisodeCollectionSyncer]
     */
    suspend fun push(subjectId: Int, episodeIds: List<Int>, collectionType: UnifiedCollectionType): Boolean
}

internal data class EpisodeCollectionOpBatch(
    val subjectId: Int,
    val collectionType: UnifiedCollectionType,
    val ops: List<EpisodeCollectionPendingOp>,
)

/**
 * 按 (条目, 状态) 分组, 组的顺序按组内最早的操作排. 状态到 Bangumi 剧集状态的换算由 [EpisodeCollectionPusher] 的实现负责.
 */
internal fun List<EpisodeCollectionPendingOp>.batchBySubjectAndType(): List<EpisodeCollectionOpBatch> {
    return groupBy { it.subjectId to it.collectionType }
        .values
        .map { ops -> EpisodeCollectionOpBatch(ops.first().subjectId, ops.first().collectionType, ops) }
        .sortedBy { batch -> batch.ops.minOf { it.id } }
}
