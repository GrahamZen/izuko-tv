/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.oauth

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.platform.navigation.rememberAsyncBrowserNavigator
import me.him188.ani.app.ui.login.EmailLoginScreenLayout
import me.him188.ani.app.ui.lang.*
import org.jetbrains.compose.resources.*

@Composable
fun BangumiAuthorizeScreen(
    vm: BangumiAuthorizeViewModel,
    onNavigateBack: () -> Unit,
    onNavigateSettings: () -> Unit,
    onAuthorizeSuccess: () -> Unit,
    contactActions: @Composable () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle(AuthState.NoAniAccount)
    val scope = rememberCoroutineScope()
    val browserNavigator = rememberAsyncBrowserNavigator()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.collectNewLoginEvent {
            onAuthorizeSuccess()
        }
    }

    BangumiAuthorizeScreen(
        state = state,
        onClickAuthorize = {
            if (state !is AuthState.AwaitingResult) {
                // 应用内浏览器优先 (电视上唯一可行的一条: 跳去外部浏览器就回不来了);
                // 没有应用内浏览器的平台才落到外部浏览器 + deep link 回调
                vm.startAuthorize { url ->
                    scope.launch { browserNavigator.openBrowser(context, url) }
                }
            }
        },
        onCancelAuthorize = { vm.cancelCurrentOAuth() },
        onNavigateSettings = onNavigateSettings,
        onNavigateBack = onNavigateBack,
        contactActions = contactActions,
    )
}

@Composable
internal fun BangumiAuthorizeScreen(
    state: AuthState,
    onClickAuthorize: () -> Unit,
    onCancelAuthorize: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateBack: () -> Unit,
    contactActions: @Composable () -> Unit,
) {
    EmailLoginScreenLayout(
        onBangumiLoginClick = {},
        onNavigateSettings = onNavigateSettings,
        onNavigateBack = onNavigateBack,
        title = { Text(stringResource(Lang.oauth_bangumi_authorize_title)) },
        showThirdPartyLogin = false,
    ) { scrollState ->
        BangumiAuthorizeLayout(
            authorizeState = state,
            contactActions = contactActions,
            onClickAuthorize = onClickAuthorize,
            onCancelAuthorize = onCancelAuthorize,
            scrollState = scrollState,
        )
    }
}
