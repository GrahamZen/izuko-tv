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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.domain.media.fetch.MediaFetchSession
import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState
import me.him188.ani.app.domain.media.fetch.isFailedOrAbandoned
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 播放页持有的条目级查询会话: 同一条目的各集共用一个 [MediaFetchSession], 切集只重建选择器.
 *
 * 会话由本类在 [scope] 内保持订阅, 与剧集作用域无关: 切集取消旧作用域不会把进行中的数据源标记为
 * [MediaSourceFetchState.Abandoned]. 条目级请求变化 ([MediaFetchRequest.isSameSubjectQuery] 为 `false`,
 * 例如剧集列表新增了一集) 时创建新会话, 旧会话的订阅随之结束.
 *
 * 复用会话时, 对已失败或被中途取消的数据源自动重试一次; 需要验证码或被限流的源不重试.
 */
class SubjectMediaFetchSessions(
    private val scope: CoroutineScope,
    private val createSession: suspend (MediaFetchRequest) -> MediaFetchSession,
) : AutoCloseable {
    private val lock = Mutex()
    private var current: Pair<MediaFetchRequest, MediaFetchSession>? = null
    private var currentGeneration = 0
    private var subscription: Job? = null

    /**
     * 取得可用于 [request] 的会话: 与当前会话查询同一条目、且 [generation] 相同时复用, 否则创建.
     *
     * [generation] 是「重新搜索(含新数据源)」的计数 (见
     * [me.him188.ani.app.domain.media.fetch.MediaFetchSessionRefresh]). 会话创建时对数据源列表取快照,
     * 因此「同一条目」不足以作为复用的唯一条件: 用户刚启用的数据源只有换一个会话才能参与查询.
     */
    suspend fun get(request: MediaFetchRequest, generation: Int = 0): MediaFetchSession = lock.withLock {
        current?.let { (currentRequest, session) ->
            if (currentGeneration == generation && currentRequest.isSameSubjectQuery(request)) {
                retryFailedSources(session)
                return@withLock session
            }
        }
        val session = createSession(request)
        subscription?.cancel()
        subscription = scope.launch { session.cumulativeResults.collect() }
        current = request to session
        currentGeneration = generation
        session
    }

    private fun retryFailedSources(session: MediaFetchSession) {
        for (result in session.mediaSourceResults) {
            if (result.state.value.isFailedOrAbandoned) {
                result.restart()
            }
        }
    }

    override fun close() {
        subscription?.cancel()
        subscription = null
        current = null
    }
}
