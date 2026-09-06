/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.user

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.him188.ani.app.data.models.user.SelfInfo
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.profile.UserProfile
import me.him188.ani.app.domain.profile.UserProfileKind
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.utils.platform.annotations.TestOnly
import org.koin.core.Koin
import kotlin.coroutines.CoroutineContext

@Immutable
data class SelfInfoUiState(
    val selfInfo: SelfInfo?,
    val isLoading: Boolean,
    /**
     * `null` means loading
     */
    val isSessionValid: Boolean?,
    /**
     * `null means loading
     */
    val bangumiConnected: Boolean?,
    /**
     * 本地档 (见 [UserProfileKind.LOCAL]): 不登录 Bangumi, 收藏、看过与评分记在本机. 这时 [isSessionValid] 为 `true` (收藏类的功能照常能用),
     * [selfInfo] 按用户起的名字合成, [bangumiConnected] 为 `false`. 登录 / 退出、账号资料、发评论这类只有 Bangumi 账号才有的入口看它藏起来.
     */
    val isLocalProfile: Boolean = false,
)

@TestOnly
val TestSelfInfoUiState
    get() = SelfInfoUiState(
        SelfInfo(
            id = 209100,
            nickname = "TestUser",
            email = "test@animeko.org",
            hasPassword = false,
            avatarUrl = null,
            bangumiUsername = "TestBangumiUser",
            isBangumiSessionValid = true,
        ),
        isLoading = false,
        isSessionValid = true,
        bangumiConnected = true,
    )

class SelfInfoStateProducer(
    flowContext: CoroutineContext = Dispatchers.Default,
    koin: Koin = GlobalKoin,
) {
    private val sessionStateProvider: SessionStateProvider by koin.inject()
    private val userRepository: UserRepository by koin.inject()

    /**
     * 如果重新 collect 这个 flow, 会导致多次网络请求.
     */
    val flow = if (UserProfiles.current.isLocal) {
        // 本地档不经会话: 名字跟着用户列表走 (改名后立刻变)
        UserProfiles.registry.state
            .map { save -> localProfileState(save.find(UserProfiles.currentId) ?: UserProfiles.current) }
            .stateIn(
                CoroutineScope(flowContext),
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = localProfileState(UserProfiles.current),
            )
    } else {
        combine(sessionStateProvider.stateFlow, userRepository.selfInfoFlow) { sessionState, selfInfo ->
            val isSessionValid = sessionState is SessionState.Valid
            SelfInfoUiState(
                selfInfo = if (isSessionValid) selfInfo else null,
                isLoading = false,
                isSessionValid = isSessionValid,
                bangumiConnected = isSessionValid && sessionState.bangumiConnected,
            )
        }.stateIn(
            CoroutineScope(flowContext),
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SelfInfoUiState(
                selfInfo = null,
                isLoading = true,
                isSessionValid = null,
                bangumiConnected = null,
            ),
        )
    }
}

/** 本地档的状态, 见 [SelfInfoUiState.isLocalProfile]. 没有 Bangumi 账号, 名字是用户自己起的. */
private fun localProfileState(profile: UserProfile) = SelfInfoUiState(
    selfInfo = SelfInfo(
        id = 0,
        nickname = profile.name,
        email = null,
        hasPassword = false,
        avatarUrl = null,
        bangumiUsername = null,
        isBangumiSessionValid = false,
    ),
    isLoading = false,
    isSessionValid = true,
    bangumiConnected = false,
    isLocalProfile = true,
)
