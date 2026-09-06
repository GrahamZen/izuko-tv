/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.oauth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.session.SessionEvent
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.ui.foundation.AbstractViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * bangumi 授权页. 真正的编排在 [BangumiOAuthManager] (进程内单例) 里 —— 授权中要显示的浏览器
 * 是**全屏叠层**, 由 app 根部的 host 画, 不属于本页; 外部浏览器那条路的回调更是从 Activity
 * 的 deep link 进来, 本页可能早就不在了.
 */
class BangumiAuthorizeViewModel : AbstractViewModel(), KoinComponent {
    private val manager: BangumiOAuthManager by inject()
    private val sessionManager: SessionManager by inject()
    private val sessionStateProvider: SessionStateProvider by inject()

    init {
        // 上一次授权的结果不该挡住这一次: 单例的状态会一直停在成功/失败上
        manager.resetIfFinished()
    }

    /** 应用内浏览器能不能用. 电视上只有它能用 (跳出去就回不来). */
    val inAppBrowserSupported: Boolean get() = manager.inAppBrowserSupported

    val state: Flow<AuthState> =
        combine(sessionStateProvider.stateFlow, manager.state) { sessionState, authState ->
            val loggedIn = sessionState is SessionState.Valid
            when (authState) {
                is BangumiOAuthManager.State.Idle ->
                    if (loggedIn) AuthState.LoggedInAni(true) else AuthState.NoAniAccount

                // 构建没带凭据: 当成失败态展示, 免得用户在一个必然报错的授权页上反复试
                is BangumiOAuthManager.State.NotConfigured ->
                    AuthState.Failed(LoadError.UnknownError(null), loggedIn)

                is BangumiOAuthManager.State.Authorizing,
                is BangumiOAuthManager.State.Exchanging -> AuthState.AwaitingResult

                // 只有"确实还登录着"才算已授权: 退出登录之后这个单例仍停在 Success,
                // 照搬会让界面显示"已授权"而按钮禁用
                is BangumiOAuthManager.State.Success ->
                    if (loggedIn) AuthState.Success else AuthState.NoAniAccount
                is BangumiOAuthManager.State.Failed -> AuthState.Failed(authState.error, loggedIn)
            }
        }

    /**
     * 开始授权.
     *
     * @param openExternally 应用内浏览器用不了 (或用户主动选) 时, 用它打开外部浏览器;
     * 返回的地址由平台去开, 回调走 deep link 回到 [BangumiOAuthManager.submitCallbackUrl].
     */
    fun startAuthorize(openExternally: (String) -> Unit) {
        if (manager.inAppBrowserSupported) {
            manager.startInAppBrowser()
        } else {
            manager.startExternalBrowser()?.let(openExternally)
        }
    }

    suspend fun collectNewLoginEvent(block: () -> Unit) {
        sessionManager.stateProvider
            .eventFlow
            .filterIsInstance<SessionEvent.NewLogin>()
            .collect { block() }
    }

    fun cancelCurrentOAuth() {
        manager.cancel()
    }
}
