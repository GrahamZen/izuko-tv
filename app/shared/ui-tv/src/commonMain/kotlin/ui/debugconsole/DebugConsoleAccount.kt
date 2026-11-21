/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.debugconsole

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.domain.usecase.GlobalKoin
import kotlin.time.Duration.Companion.seconds

/**
 * 调试接口的账号部分: 查看登录状态、用 Bangumi 个人令牌登录、退出登录. 与安卓 Web 控制台的 `api/account*` 同一套做法,
 * 测试时不用在 iPad 上走授权页就能登上测试账号.
 *
 * 令牌放在表单里 POST 过来; 调试接口不记请求参数, 令牌不进日志.
 */
internal object DebugConsoleAccount {
    private val sessionStateProvider: SessionStateProvider get() = GlobalKoin.get()
    private val userRepository: UserRepository get() = GlobalKoin.get()
    private val oauthManager: BangumiOAuthManager get() = GlobalKoin.get()

    suspend fun state(): JsonObject {
        val session = withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() }
        val self = if (session is SessionState.Valid) {
            withTimeoutOrNull(STATE_TIMEOUT) { userRepository.selfInfoFlow.first() }
        } else {
            null
        }
        return buildJsonObject {
            put("loggedIn", session is SessionState.Valid)
            put("session", session?.toString() ?: "unknown")
            self?.let {
                put("nickname", it.nickname)
                it.bangumiUsername?.let { name -> put("username", name) }
            }
        }
    }

    /**
     * 参数: `token` 个人令牌, `days` 生成时选的有效天数; `logout=1` 先退出当前账号再登录 (换号用).
     *
     * @return 失败原因; `null` = 登录成功
     */
    suspend fun tokenLogin(request: DebugConsoleRequest): String? {
        val token = request.params["token"].orEmpty().trim()
        if (token.isEmpty()) return "missing token"
        val days = request.int("days")?.takeIf { it in 1..MAX_TOKEN_DAYS } ?: return "invalid days"
        if (request.params["logout"] == "1") {
            logout()?.let { return it }
        } else {
            val session = withTimeoutOrNull(STATE_TIMEOUT) { sessionStateProvider.stateFlow.first() }
            if (session is SessionState.Valid) return "already logged in; pass logout=1 to switch account"
        }
        oauthManager.cancel()
        // 包一层: 登录函数成功时返回 null, 直接交给 withTimeoutOrNull 就分不清成功与超时
        val outcome = withTimeoutOrNull(OP_TIMEOUT) { TokenOutcome(oauthManager.loginWithPersonalToken(token, days)) }
            ?: return "token check timed out"
        return outcome.error?.let { "login failed: $it" }
    }

    /** 同设置里的「退出登录」: 清掉本地用户信息与会话, 顺带放弃正在等的授权. @return 失败原因; `null` = 成功 */
    suspend fun logout(): String? {
        oauthManager.cancel()
        withTimeoutOrNull(OP_TIMEOUT) { userRepository.clearSelfInfo() } ?: return "logout timed out"
        return null
    }

    private class TokenOutcome(val error: LoadError?)

    private val STATE_TIMEOUT = 5.seconds
    private val OP_TIMEOUT = 20.seconds
    private const val MAX_TOKEN_DAYS = 365
}
