/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.foundation.BangumiMirrorConsentRequests
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.settings.tabs.network.MirrorSwitchConsentDialog
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 「官方连不上时用镜像」要自动改用镜像、而已登录的用户得先点头时 (见 [BangumiMirrorConsentRequests]), 在 TV 根组合里问他.
 * 不管停在哪一页都要能弹, 所以挂在根上 (同 Web 控制台的二维码弹窗).
 */
@Composable
fun TvMirrorConsentHost() {
    val vm = viewModel { TvMirrorConsentViewModel() }
    val pending by vm.pending.collectAsStateWithLifecycle()
    if (pending) {
        MirrorSwitchConsentDialog(
            automatic = true,
            onAllow = vm::allow,
            onLogoutAndSwitch = vm::logoutAndSwitch,
            onDismissRequest = vm::decline,
        )
    }
}

private class TvMirrorConsentViewModel : AbstractViewModel(), KoinComponent {
    private val requests: BangumiMirrorConsentRequests by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val userRepository: UserRepository by inject()

    val pending: StateFlow<Boolean> get() = requests.pending

    /** 允许凭证经过镜像, 改用镜像. */
    fun allow() {
        requests.resolve(declined = false)
        backgroundScope.launch {
            settingsRepository.bangumiEndpointSettings.update {
                afterOriginUnreachable().copy(allowCredentialsViaMirror = true)
            }
        }
    }

    /** 退出登录, 改用镜像 (匿名浏览). */
    fun logoutAndSwitch() {
        requests.resolve(declined = false)
        backgroundScope.launch {
            userRepository.clearSelfInfo()
            settingsRepository.bangumiEndpointSettings.update { afterOriginUnreachable() }
        }
    }

    /** 暂不: 这次运行不再问. */
    fun decline() = requests.resolve(declined = true)
}
