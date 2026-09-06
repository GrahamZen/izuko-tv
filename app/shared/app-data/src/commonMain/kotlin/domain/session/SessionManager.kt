/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.repository.RepositoryAuthorizationException
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.data.repository.RepositoryRateLimitedException
import me.him188.ani.app.data.repository.RepositoryServiceUnavailableException
import me.him188.ani.app.data.repository.RepositoryUnknownException
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.data.repository.user.GuestSession
import me.him188.ani.app.data.repository.user.Session
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.domain.session.auth.BangumiOAuthClient
import me.him188.ani.app.domain.session.auth.OAuthResult
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.thisLogger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** 续不上时界面显示的原因: 连不上、被限流与 bangumi 暂时不可用都算网络问题. */
private fun RepositoryException.toInvalidSessionReason(): InvalidSessionReason = when (this) {
    is RepositoryNetworkException,
    is RepositoryRateLimitedException,
    is RepositoryServiceUnavailableException -> InvalidSessionReason.NETWORK_ERROR

    else -> InvalidSessionReason.UNKNOWN
}

/**
 * 用 bangumi 的 `grant_type=refresh_token` 续期.
 *
 * **bangumi 的 accessToken 只活 7 天** (Ani 服务器给的是一个月, 且它自己替你续), 所以这条路
 * 是直连之后的必需品 —— 不实现的话用户每周被踢下线一次. 见 [SessionManager.Config].
 */
class BangumiSessionRefresher(
    private val getClient: () -> BangumiOAuthClient,
) : SessionManager.SessionRefresher {
    override suspend fun refresh(refreshToken: String): OAuthResult {
        return getClient().refresh(refreshToken)
    }
}

/**
 * 维护 [AccessTokenPair] 的管理器.
 *
 * 它负责持久化 [AccessTokenPair] 和 refreshToken, 以及在 accessToken 过期前使用 refreshToken 刷新两个 token (调用 [refreshSession]).
 * [SessionManager] 不处理登录和登出, 只负责维护 token 的有效性.
 *
 * 注意, [SessionManager] 已经涉及登录的内部逻辑. 如果你只需要知道当前用户是否有登录, 使用 [SessionStateProvider].
 * @since 5.0
 */
class SessionManager(
    private val tokenRepository: TokenRepository,
    private val coroutineScope: CoroutineScope,
    private val refreshSession: SessionRefresher,
    private val clock: Clock = Clock.System,
    private val config: Config = Config(),
    /**
     * 新登录**写入会话之前**调用: 让本地的条目与分集缓存全部过期. 登录前匿名取回的缓存 (分集一律「没看过」)
     * 在过期前都算新鲜, 不作废的话登录后的刷新会跳过它们, 进度一直停在第一集, 要进一次详情页才对.
     * 放在写入会话之前: 登录后立刻开始的刷新不会抢在作废之前判断「新鲜」.
     */
    private val beforeNewLogin: suspend () -> Unit = {},
) {
    fun interface SessionRefresher {
        /**
         * @throws RepositoryException
         */
        suspend fun refresh(refreshToken: String): OAuthResult
    }

    data class Config(
        /**
         * 在 accessToken 过期前多久提前刷新 accessToken.
         *
         * 刷新失败会在一段时间后自动重试. [refreshTokenBefore] 时间长一点可以增加更多重试机会.
         */
        // bangumi 的 accessToken 只有 7 天 (Ani 服务器给的是 31 天, 那时这里填 7 天是合理的).
        // 照搬过来等于"一拿到 token 就该刷新了", 每次冷启动都刷一遍; 提前 1 天足够重试.
        val refreshTokenBefore: Duration = 1.days,
        /**
         * 在刷新失败后, 等待多久再尝试刷新.
         */
        val refreshAttemptInterval: Duration = 1.hours,
    )

    private val logger = thisLogger()

    val sessionFlow: StateFlow<Session> = tokenRepository.session
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), initialValue = GuestSession)

    private val _stateProvider = object : SessionStateProvider {
        override val stateFlow =
            MutableSharedFlow<SessionState>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        override val eventFlow =
            MutableSharedFlow<SessionEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        suspend fun emitEvent(event: SessionEvent) {
            eventFlow.emit(event)
        }
    }

    val stateProvider get() = _stateProvider

    private val backgroundJob by lazy {
        fun emitState(state: SessionState) {
            check(_stateProvider.stateFlow.tryEmit(state))
        }

        /**
         * 续到成功为止: 成功后 [sessionFlow] 发出新会话, 调用方随之被取消、以新会话重来.
         * refreshToken 被拒 (过期或被撤销) 就退出登录; 其他失败 (连不上、bangumi 5xx…) 每隔
         * [Config.refreshAttemptInterval] 重试, 旧 token 已经过期时让界面知道现在续不上, 而不是还登录着.
         */
        suspend fun refreshUntilDone(session: AccessTokenSession) {
            while (true) {
                try {
                    refreshSession() // This is expected to throw RepositoryException
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val re = RepositoryException.wrapOrThrowCancellation(e)
                    if (re is RepositoryAuthorizationException) {
                        logger.warn { "SessionManager: refresh token rejected, clearing session" }
                        clearSession()
                        return
                    }
                    if (re is RepositoryUnknownException) {
                        logger.error(
                            "Refresh session failed with unknown exception, see cause. Retrying in ${config.refreshAttemptInterval}",
                            e,
                        )
                    } else {
                        // 对于已知的错误, 不要记录冗长的堆栈
                        logger.warn("Refresh session failed with $re. Retrying in ${config.refreshAttemptInterval}")
                    }
                    if (session.tokens.isExpired(clock)) {
                        emitState(SessionState.Invalid(re.toInvalidSessionReason()))
                    }
                    delay(config.refreshAttemptInterval)
                }
            }
        }

        /**
         * 维护 accessToken 的有效性. 此函数可在有新的 session 时被 cancel.
         *
         * 只有这里会修改 [stateProvider].
         *
         * 有 refreshToken 的会话在过期前 [Config.refreshTokenBefore] 续期, 已经过期的立即续 (见 [refreshUntilDone]).
         * 没有 refreshToken 的会话 (个人令牌) 续不了, 到期就退出登录.
         */
        suspend fun maintainAccessTokenLoop(session: AccessTokenSession) {
            logger.debug {
                "SessionManager: maintainAccessTokenLoop started with session: $session"
            }
            val canRefresh = tokenRepository.refreshToken.first() != null

            if (session.tokens.isExpired(clock)) {
                if (canRefresh) {
                    // 目前不支持检查 refreshToken 是否过期, 所以直接请求刷新
                    refreshUntilDone(session)
                } else {
                    logger.info { "SessionManager: access token expired and cannot be refreshed, clearing session" }
                    clearSession()
                }
                return
            }

            // token 还没有过期, 直接发出有效的状态
            emitState(SessionState.Valid(bangumiConnected = session.tokens.bangumiAccessToken != null))

            if (!canRefresh) {
                // 续不了: 到期就退出登录, 界面回到「未登录」
                delay(session.tokens.timeUntilExpired(clock))
                logger.info { "SessionManager: access token expired and cannot be refreshed, clearing session" }
                clearSession()
                return
            }

            // Token 会在未来过期, 提前 refreshTokenBefore 续期
            val ttl = (session.tokens.expiresAtMillis - clock.now().toEpochMilliseconds()).milliseconds
                .minus(config.refreshTokenBefore)

            logger.debug {
                "SessionManager: access token is valid, will refresh in $ttl ms"
            }

            delay(ttl)

            logger.info {
                "SessionManager: access token is about to expire, refreshing now"
            }
            // 醒来时 token 通常还没过期 (提前了 refreshTokenBefore), 所以不能拿 isExpired 当续期的循环条件 ——
            // 那样一次都不会续
            refreshUntilDone(session)
        }


        // 启动后台任务, 定时刷新 token
        coroutineScope.launch(CoroutineName("SessionManager auto refresh")) {
            sessionFlow.collectLatest { session ->
                when (session) {
                    is GuestSession -> emitState(SessionState.Invalid(InvalidSessionReason.NO_TOKEN))
                    is AccessTokenSession -> maintainAccessTokenLoop(session)
                }
            }
        }

        Unit // 不存储 Job
    }

    fun startBackgroundJob() {
        backgroundJob // lazy init
    }

    /**
     * 启动时作废老流程写下的会话 (见 [me.him188.ani.app.data.repository.user.TokenSave.loginFlowVersion]).
     */
    suspend fun clearLegacySessionOnStartup() {
        if (tokenRepository.clearLegacySession()) {
            logger.info { "SessionManager: 清掉了老登录流程 (Ani 服务器) 的会话, 需要重新登录 bangumi" }
        }
    }

    /**
     * 启动时: 已经过期、又续不了 (没有 refreshToken) 的会话直接清掉. 能续的留给后台任务去续 ——
     * bangumi 的 token 只有 7 天, 启动时清掉的话, 隔一周打开应用就得重新登录.
     */
    suspend fun clearSessionIfAccessTokenExpired() {
        val session = tokenRepository.session.first()
        if (session is AccessTokenSession && session.tokens.isExpired(clock)) {
            if (tokenRepository.refreshToken.first() != null) {
                logger.info { "SessionManager: saved access token is expired on startup, will refresh it" }
                return
            }
            logger.info { "SessionManager: saved access token is expired on startup and cannot be refreshed, clearing session" }
            clearSession()
        }
    }

    /**
     * 登录成功后调用, 设置一个会话. 这也会导致 [stateProvider] [SessionStateProvider.stateFlow] 更新.
     */
    suspend fun setSession(
        session: AccessTokenSession,
        /** 每次登录 / 续期都换新的; `null` = 续不了 (个人令牌), 到期就退出登录. */
        refreshToken: String?,
        isNewLogin: Boolean = true,
    ) {
        if (isNewLogin) beforeNewLogin()
        tokenRepository.setSession(session, refreshToken)
        if (isNewLogin) {
            _stateProvider.emitEvent(SessionEvent.NewLogin)
        }
    }


    /**
     * 设置为未登录状态. 同时清空 accessToken 和 refreshToken. 这也会导致 [stateProvider] [SessionStateProvider.stateFlow] 更新.
     */
    suspend fun clearSession() {
        tokenRepository.clear()
        // 注意, 我们这里不修改公开的 state. background task 会帮我们修改.
    }

    private val refreshSessionLock = Mutex()

    /**
     * 使用 refreshToken 刷新 accessToken. 刷新成功后会自动持久化. 这也会导致 [stateProvider] [SessionStateProvider.stateFlow] 更新.
     *
     * 只有当 [SessionStateProvider.stateFlow] 为网络错误, 并且用户主动点击了刷新按钮时, 才应当调用此函数.
     *
     * @throws RepositoryException
     */
    suspend fun refreshSession() = refreshSessionLock.withLock {
        val refreshToken = tokenRepository.refreshToken.first() ?: return@withLock

        try {
            val result = refreshSession.refresh(refreshToken)
            setSession(
                session = AccessTokenSession(
                    tokens = result.tokens,
                ),
                refreshToken = result.refreshToken,
                isNewLogin = false,
            )
            // 注意, 我们这里不修改公开的 state. background task 会帮我们修改.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // refresh 只应该 throw RepositoryException, 但是我们还是保险起见封装
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }
}
