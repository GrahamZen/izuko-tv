/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.user

import androidx.datastore.core.DataStore
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.models.user.SelfInfo
import me.him188.ani.app.data.repository.RepositoryAuthorizationException
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.RepositoryRequestError
import me.him188.ani.app.domain.session.AccessTokenPair
import me.him188.ani.app.domain.session.InvalidSessionReason
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.datasources.bangumi.next.apis.MiscBangumiNextApi
import me.him188.ani.datasources.bangumi.next.models.BangumiNextProfile
import me.him188.ani.utils.coroutines.flows.FlowRestarter
import me.him188.ani.utils.coroutines.flows.catching
import me.him188.ani.utils.coroutines.flows.restartable
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

class UserRepository(
    private val dataStore: DataStore<SelfInfo?>,
    private val bangumiMiscApi: ApiInvoker<MiscBangumiNextApi>,
    private val sessionStateProvider: SessionStateProvider,
    private val sessionManager: SessionManager,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) {
    private val logger = logger<UserRepository>()
    private val scope = CoroutineScope(coroutineContext)

    private val selfInfoRefresher = FlowRestarter()

    /**
     * 先读缓存, 然后网络.
     */
    val selfInfoFlow: Flow<SelfInfo?> = sessionStateProvider.stateFlow.transformLatest { state ->
        when (state) {
            is SessionState.Invalid -> {
                when (state.reason) {
                    InvalidSessionReason.NETWORK_ERROR -> {
                        emit(dataStore.data.firstOrNull())
                    }

                    InvalidSessionReason.NO_TOKEN,
                    InvalidSessionReason.UNKNOWN -> {
                        emit(null)
                    }
                }
            }

            is SessionState.Valid -> {
                emit(dataStore.data.firstOrNull())
                suspend {
                    // 直连之后"我是谁"来自 bangumi 自己: /p1/me 给 id/用户名/昵称/头像.
                    // Ani 那个 /v1/me 还给 email、是否设过密码、bangumi 绑没绑 —— 直连之后
                    // 这些概念都不存在了 (账号就是 bangumi 账号), 见 toSelfInfo
                    bangumiMiscApi.invoke { getCurrentUser().body() }.toSelfInfo()
                }
                    .asFlow()
                    // 首次登录这里的 http client 可能还是旧的, 添加重试机制确保 user info 能够正确获取.
                    .retryWhen { e, attempt ->
                        val wrapped = RepositoryException.wrapOrThrowCancellation(e)
                        (wrapped is RepositoryAuthorizationException && attempt < 3).also {
                            if (it) {
                                logger.warn(wrapped) { "Failed to get user info, retried $attempt, max retries: 3" }
                                delay(125L)
                            }
                        }
                    }
                    .catching()
                    .restartable(selfInfoRefresher)
                    .collectLatest { result ->
                        result
                            .onSuccess { self ->
                                coroutineScope {
                                    launch { dataStore.updateData { self } }
                                    emit(self)
                                }
                            }
                            .onFailure { e ->
                                logger.error(RepositoryException.wrapOrThrowCancellation(e)) {
                                    "Failed to refresh user profile info."
                                }
                            }
                    }
            }
        }
    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /**
     * @throws me.him188.ani.app.data.repository.RepositoryRateLimitedException
     */
    /**
     * 所有参数都是 `nullable`, 传入 `null` 则表示不修改对应的字段.
     */
    suspend fun clearSelfInfo() {
        dataStore.updateData {
            null
        }
        sessionManager.clearSession()
    }

}

/**
 * `/p1/me` -> [SelfInfo].
 *
 * `email` / `hasPassword` 是 Ani 账号体系的东西, 直连之后恒为空: 账号就是 bangumi 账号,
 * 密码在 bangumi 那边. `isBangumiSessionValid` 恒为 true —— 能拿到这个响应本身就是证明.
 */
private fun BangumiNextProfile.toSelfInfo(): SelfInfo {
    return SelfInfo(
        id = id,
        nickname = nickname,
        email = null,
        hasPassword = false,
        avatarUrl = avatar.large,
        bangumiUsername = username,
        isBangumiSessionValid = true,
    )
}
