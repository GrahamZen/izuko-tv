/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.state

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.repository.RepositoryNetworkException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.ui.subject.details.SubjectDetailsUIState
import me.him188.ani.utils.platform.annotations.TestOnly

/**
 * 按需加载条目详情. [state] 由当前请求的条目派生, 只要 loader 所在的 scope 活着, 当前条目的 [SubjectDetailsState] 就一直在更新.
 *
 * @see SubjectDetailsState
 */
@Stable
class SubjectDetailsStateLoader(
    private val subjectDetailsStateFactory: SubjectDetailsStateFactory,
    backgroundScope: CoroutineScope,
) {
    /**
     * @param attempt 让同一条目的重新加载也能触发 [flatMapLatest].
     */
    private data class Request(
        val subjectId: Int,
        val placeholder: SubjectInfo?,
        val attempt: Int,
    )

    private val request = MutableStateFlow<Request?>(null)

    /**
     * 当前请求的加载状态. 没有请求时为 `null`.
     *
     * [SubjectDetailsState] 内部的 flow 都跑在加载它的协程里, 因此这里用 [flatMapLatest] 让它与请求绑定:
     * 换条目或重新加载时取消上一个, 其余时候持续收集, 数据库里的更新 (例如播放页标记看过) 才能一直传到页面上.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SubjectDetailsUIState?> = request
        .flatMapLatest { req ->
            if (req == null) return@flatMapLatest flowOf(null)
            flow {
                emit(SubjectDetailsUIState.Placeholder(req.subjectId, req.placeholder))
                emitAll(
                    subjectDetailsStateFactory.create(req.subjectId, req.placeholder)
                        .map { SubjectDetailsUIState.Ok(it.subjectId, it) }
                        .retryUntilFirstEmission(FIRST_LOAD_TIMEOUT, MAX_LOAD_ATTEMPTS),
                )
            }.catch { e ->
                emit(SubjectDetailsUIState.Err(req.subjectId, req.placeholder, LoadError.fromException(e)))
            }
        }
        .stateIn(backgroundScope, SharingStarted.Eagerly, null)

    /**
     * 确保 [subjectId] 已加载. 已在加载或已加载完成时什么都不做, 上次加载失败则重试.
     *
     * 从播放页等返回时会再次调用, 此时不应该重新加载: 页面的内容会闪一下占位, 角色/制作人员/评论等请求也会全部重发.
     */
    fun load(
        subjectId: Int,
        placeholder: SubjectInfo? = null
    ) {
        if (request.value?.subjectId == subjectId && state.value !is SubjectDetailsUIState.Err) {
            return
        }
        request.value = Request(subjectId, placeholder, nextAttempt())
    }

    /**
     * 无论当前状态如何都重新加载 [subjectId]. 用于加载失败后重试.
     */
    fun reload(
        subjectId: Int,
        placeholder: SubjectInfo? = null
    ) {
        request.value = Request(subjectId, placeholder, nextAttempt())
    }

    /**
     * 取消加载, [state] 变为 `null`.
     */
    fun clear() {
        request.value = null
    }

    private fun nextAttempt(): Int = (request.value?.attempt ?: 0) + 1

    private companion object {
        /**
         * 首屏内容 (第一次发射) 的最长等待时间.
         *
         * 首屏依赖一次 Bangumi 请求, 而它挂住时全局 ktor 超时长达 5 分钟 —— 页面就那么一直转圈.
         * 超过这个时间就重新订阅一次, 比干等有效得多.
         */
        private val FIRST_LOAD_TIMEOUT = 5.seconds

        /** 首屏加载总尝试次数 (含第一次), 全部超时后交给外层 catch 变成错误页. */
        private const val MAX_LOAD_ATTEMPTS = 5
    }
}

/**
 * 只给**第一次发射**限时: 超过 [timeout] 还没有第一个元素就取消这次订阅重来, 最多 [maxAttempts] 次;
 * 第一个元素到了之后不再限时, 后续更新照常流过 (详情页要靠它持续收数据库的变化).
 *
 * 全部尝试都超时则抛 [RepositoryNetworkException], 由调用方的 catch 转成错误页.
 */
private fun <T> Flow<T>.retryUntilFirstEmission(
    timeout: Duration,
    maxAttempts: Int,
): Flow<T> = channelFlow {
    var attempts = 0
    while (true) {
        attempts++
        val firstEmission = CompletableDeferred<Unit>()
        val job = launch {
            this@retryUntilFirstEmission.collect {
                firstEmission.complete(Unit)
                send(it)
            }
        }
        try {
            withTimeout(timeout) { firstEmission.await() }
            job.join() // 首屏已到, 剩下的持续收到上游自己结束
            return@channelFlow
        } catch (e: TimeoutCancellationException) {
            job.cancelAndJoin()
            if (attempts >= maxAttempts) {
                throw RepositoryNetworkException("加载超时", e)
            }
        }
    }
}

@TestOnly
fun createTestSubjectDetailsLoader(
    backgroundScope: CoroutineScope,
    subjectDetailsStateFactory: SubjectDetailsStateFactory = TestSubjectDetailsStateFactory(),
): SubjectDetailsStateLoader {
    return SubjectDetailsStateLoader(subjectDetailsStateFactory, backgroundScope)
}
