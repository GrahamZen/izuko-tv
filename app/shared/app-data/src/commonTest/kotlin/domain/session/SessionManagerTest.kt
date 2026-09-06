/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.RepositoryAuthorizationException
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.data.repository.RepositoryServiceUnavailableException
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.data.repository.user.GuestSession
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.data.repository.user.TokenSave
import me.him188.ani.app.domain.session.auth.OAuthResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SessionManagerTest {
    private val nowMillis = 1_000_000L

    private fun save(expiresInMillis: Long, refreshToken: String?) = TokenSave(
        refreshToken = refreshToken,
        accessTokens = TokenSave.AccessTokens(
            bangumiAccessToken = "bangumi",
            aniAccessToken = "",
            expiresAtMillis = nowMillis + expiresInMillis,
        ),
    )

    @Test
    fun `startup clears expired session that cannot be refreshed`() = runTest {
        val store = MemoryDataStore(save(expiresInMillis = 0, refreshToken = null))
        val repository = TokenRepository(store)
        val manager = createSessionManager(repository, backgroundScope)

        manager.clearSessionIfAccessTokenExpired()

        assertEquals(TokenSave.Initial, store.data.value)
        assertEquals(GuestSession, repository.session.value())
    }

    @Test
    fun `startup keeps expired session that can be refreshed`() = runTest {
        // bangumi 的 token 只有 7 天: 启动时清掉的话, 隔一周打开应用就得重新登录
        val saved = save(expiresInMillis = 0, refreshToken = "refresh")
        val store = MemoryDataStore(saved)
        val manager = createSessionManager(TokenRepository(store), backgroundScope)

        manager.clearSessionIfAccessTokenExpired()

        assertEquals(saved, store.data.value)
    }

    @Test
    fun `startup keeps valid access token session`() = runTest {
        val saved = save(expiresInMillis = 2.hours.inWholeMilliseconds, refreshToken = "refresh")
        val store = MemoryDataStore(saved)
        val repository = TokenRepository(store)
        val manager = createSessionManager(repository, backgroundScope)

        manager.clearSessionIfAccessTokenExpired()

        assertEquals(saved, store.data.value)
        assertEquals(
            AccessTokenSession(
                AccessTokenPair(
                    aniAccessToken = "",
                    expiresAtMillis = nowMillis + 2.hours.inWholeMilliseconds,
                    bangumiAccessToken = "bangumi",
                ),
            ),
            repository.session.value(),
        )
    }

    @Test
    fun `refreshes one day before the token expires`() = runTest {
        val store = MemoryDataStore(save(expiresInMillis = 7.days.inWholeMilliseconds, refreshToken = "refresh"))
        val refresher = FakeRefresher(this)
        val manager = createSessionManager(TokenRepository(store), backgroundScope, refresher, virtualClock())
        manager.startBackgroundJob()

        advanceTimeBy(6.days - 1.minutes)
        runCurrent()
        assertEquals(0, refresher.calls)

        advanceTimeBy(2.minutes)
        runCurrent()
        assertEquals(1, refresher.calls)
        assertEquals("refresh-1", store.data.value.refreshToken)
        assertEquals("bangumi-1", store.data.value.accessTokens?.bangumiAccessToken)
    }

    @Test
    fun `refresh keeps retrying while the network is down`() = runTest {
        val store = MemoryDataStore(save(expiresInMillis = 7.days.inWholeMilliseconds, refreshToken = "refresh"))
        val refresher = FakeRefresher(this, failures = mutableListOf(RepositoryNetworkException(), RepositoryNetworkException()))
        val manager = createSessionManager(TokenRepository(store), backgroundScope, refresher, virtualClock())
        manager.startBackgroundJob()

        advanceTimeBy(6.days + 1.minutes)
        runCurrent()
        assertEquals(1, refresher.calls)
        advanceTimeBy(2.hours)
        runCurrent()
        assertEquals(3, refresher.calls)
        assertEquals("refresh-3", store.data.value.refreshToken)
    }

    @Test
    fun `rejected refresh token logs out`() = runTest {
        val store = MemoryDataStore(save(expiresInMillis = 7.days.inWholeMilliseconds, refreshToken = "refresh"))
        val refresher = FakeRefresher(this, failures = mutableListOf(RepositoryAuthorizationException()))
        val manager = createSessionManager(TokenRepository(store), backgroundScope, refresher, virtualClock())
        manager.startBackgroundJob()

        advanceTimeBy(6.days + 1.minutes)
        runCurrent()

        assertEquals(1, refresher.calls)
        assertEquals(TokenSave.Initial, store.data.value)
    }

    @Test
    fun `expired session is refreshed at startup`() = runTest {
        val store = MemoryDataStore(save(expiresInMillis = 0, refreshToken = "refresh"))
        val refresher = FakeRefresher(this)
        val manager = createSessionManager(TokenRepository(store), backgroundScope, refresher, virtualClock())
        manager.clearSessionIfAccessTokenExpired()
        manager.startBackgroundJob()

        runCurrent()

        assertEquals(1, refresher.calls)
        assertEquals("bangumi-1", store.data.value.accessTokens?.bangumiAccessToken)
    }

    @Test
    fun `expired session keeps retrying while bangumi is unavailable`() = runTest {
        // 旧 token 已经过期时 bangumi 回 5xx: 隔一阵再续, 不能一次失败就放弃到下次启动
        val store = MemoryDataStore(save(expiresInMillis = 0, refreshToken = "refresh"))
        val refresher = FakeRefresher(this, failures = mutableListOf(RepositoryServiceUnavailableException()))
        val manager = createSessionManager(TokenRepository(store), backgroundScope, refresher, virtualClock())
        manager.startBackgroundJob()

        runCurrent()
        assertEquals(1, refresher.calls)
        assertEquals("refresh", store.data.value.refreshToken)

        advanceTimeBy(1.hours + 1.minutes)
        runCurrent()
        assertEquals(2, refresher.calls)
        assertEquals("bangumi-2", store.data.value.accessTokens?.bangumiAccessToken)
    }

    @Test
    fun `session without refresh token logs out when it expires`() = runTest {
        // 个人令牌: 没有 refresh token, 到期就退出登录, 不去续
        val store = MemoryDataStore(save(expiresInMillis = 30.days.inWholeMilliseconds, refreshToken = null))
        val refresher = FakeRefresher(this)
        val repository = TokenRepository(store)
        val manager = createSessionManager(repository, backgroundScope, refresher, virtualClock())
        manager.startBackgroundJob()

        advanceTimeBy(29.days)
        runCurrent()
        assertEquals("bangumi", store.data.value.accessTokens?.bangumiAccessToken)

        advanceTimeBy(1.days)
        runCurrent()
        assertEquals(0, refresher.calls)
        assertEquals(GuestSession, repository.session.value())
    }

    @Test
    fun `new login invalidates local caches before the session is written`() = runTest {
        // 登录前匿名取回的收藏/分集缓存要先作废, 否则登录后的刷新判它们「新鲜」而跳过, 进度停在第一集
        val repository = TokenRepository(MemoryDataStore(TokenSave.Initial))
        val sessionsSeenByHook = mutableListOf<Any>()
        val manager = SessionManager(
            tokenRepository = repository,
            coroutineScope = backgroundScope,
            refreshSession = { error("refresh should not be called") },
            clock = FixedClock(nowMillis),
            beforeNewLogin = { sessionsSeenByHook += repository.session.value() },
        )
        val session = AccessTokenSession(
            AccessTokenPair(aniAccessToken = "", expiresAtMillis = nowMillis + 7.days.inWholeMilliseconds, bangumiAccessToken = "new"),
        )

        manager.setSession(session, refreshToken = null)
        assertEquals(listOf<Any>(GuestSession), sessionsSeenByHook)
        assertEquals(session, repository.session.value())

        // 续期换 token 不是新登录, 不作废
        manager.setSession(session, refreshToken = null, isNewLogin = false)
        assertEquals(1, sessionsSeenByHook.size)
    }

    /** 依次抛 [failures] 里的异常, 抛完后每次都成功, 发一对新的 7 天 token. */
    private class FakeRefresher(
        private val scope: TestScope,
        private val failures: MutableList<Exception> = mutableListOf(),
    ) : SessionManager.SessionRefresher {
        var calls = 0

        override suspend fun refresh(refreshToken: String): OAuthResult {
            calls++
            if (failures.isNotEmpty()) throw failures.removeAt(0)
            val now = BASE_MILLIS + scope.testScheduler.currentTime
            return OAuthResult(
                tokens = AccessTokenPair(
                    aniAccessToken = "",
                    expiresAtMillis = now + 7.days.inWholeMilliseconds,
                    bangumiAccessToken = "bangumi-$calls",
                ),
                expiresInSeconds = 7.days.inWholeSeconds,
                refreshToken = "refresh-$calls",
            )
        }
    }

    /** 跟着测试调度器的虚拟时间走的钟: [TestScope.advanceTimeBy] 推进 delay 的同时也推进 now. */
    private fun TestScope.virtualClock(): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(BASE_MILLIS + testScheduler.currentTime)
    }

    private fun createSessionManager(
        repository: TokenRepository,
        coroutineScope: CoroutineScope,
        refresher: SessionManager.SessionRefresher = SessionManager.SessionRefresher {
            error("refresh should not be called")
        },
        clock: Clock = FixedClock(nowMillis),
    ): SessionManager {
        return SessionManager(
            tokenRepository = repository,
            coroutineScope = coroutineScope,
            refreshSession = refresher,
            clock = clock,
        )
    }

    private suspend fun <T> Flow<T>.value(): T {
        return first()
    }

    private class FixedClock(private val millis: Long) : Clock {
        override fun now(): Instant {
            return Instant.fromEpochMilliseconds(millis)
        }
    }

    private companion object {
        /** 与 [nowMillis] 相同: 虚拟时间从 0 开始, 两种钟在测试开始时一致. */
        const val BASE_MILLIS = 1_000_000L
    }
}
