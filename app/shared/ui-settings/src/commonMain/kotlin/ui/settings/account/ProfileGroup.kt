/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import me.him188.ani.app.ui.foundation.widgets.HeroIcon
import me.him188.ani.app.ui.foundation.layout.currentWindowAdaptiveInfo1
import me.him188.ani.app.ui.foundation.layout.isHeightAtLeastExpanded
import me.him188.ani.app.ui.foundation.layout.isWidthCompact
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.rememberAsyncHandler
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_account_logout
import me.him188.ani.app.ui.lang.settings_account_profile_nickname
import me.him188.ani.app.ui.lang.settings_account_profile_not_set
import me.him188.ani.app.ui.lang.settings_account_profile_user_id
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.TextItem
import me.him188.ani.app.ui.external.placeholder.placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * 账号页.
 *
 * 直连 bangumi 之后这一页只剩"你是谁"和"退出登录": 昵称/头像/邮箱/第三方账号绑定原先都是
 * **Ani 自己账号体系**的东西 (改昵称、传头像、绑邮箱、绑 bangumi 都要打 Ani 服务器),
 * 而现在账号就是 bangumi 账号 —— 昵称头像去 bangumi 网站改, 这里只读展示。
 */
@Composable
fun SettingsScope.ProfileGroup(
    vm: ProfileViewModel = viewModel<ProfileViewModel> { ProfileViewModel() },
    modifier: Modifier = Modifier
) {
    val state by vm.stateFlow.collectAsStateWithLifecycle(initialValue = AccountSettingsState.Empty)
    val asyncHandler = rememberAsyncHandler()
    ProfileGroupImpl(
        state,
        onLogout = {
            asyncHandler.launch {
                vm.logout()
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun SettingsScope.ProfileGroupImpl(
    state: AccountSettingsState,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfo1().windowSizeClass,
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    val currentInfo = state.selfInfo.selfInfo
    val currentState by rememberUpdatedState(state.selfInfo)
    val notSetText = stringResource(Lang.settings_account_profile_not_set)
    val nicknameText = stringResource(Lang.settings_account_profile_nickname)
    val userIdText = stringResource(Lang.settings_account_profile_user_id)

    Column(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (windowSizeClass.isWidthCompact) {
                Alignment.CenterHorizontally
            } else {
                Alignment.Start
            },
        ) {
            HeroIcon(
                Modifier.padding(vertical = if (windowSizeClass.isHeightAtLeastExpanded) 36.dp else 24.dp),
            ) {
                AvatarImage(
                    url = currentInfo?.avatarUrl,
                    Modifier
                        .clip(CircleShape)
                        .fillMaxSize()
                        .placeholder(state.selfInfo.isLoading),
                )
            }

            Column {
                val isPlaceholder = currentState.isSessionValid == null

                TextItem(
                    title = {
                        SelectionContainer {
                            Text(
                                currentInfo?.nickname?.takeIf { it.isNotBlank() } ?: notSetText,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                    },
                    description = { Text(nicknameText) },
                    modifier = Modifier.placeholder(isPlaceholder),
                )
                TextItem(
                    title = {
                        SelectionContainer {
                            Text(currentInfo?.bangumiUsername ?: notSetText)
                        }
                    },
                    description = { Text("Bangumi") },
                    modifier = Modifier.placeholder(isPlaceholder),
                )
                TextItem(
                    title = {
                        SelectionContainer {
                            Text(currentInfo?.id?.toString() ?: notSetText)
                        }
                    },
                    description = { Text(userIdText) },
                    modifier = Modifier.placeholder(isPlaceholder),
                )
                if (currentState.isSessionValid == true) {
                    TextItem(
                        title = {
                            Text(
                                stringResource(Lang.settings_account_logout),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = { showLogoutDialog = true },
                    )
                }
            }
        }
    }

    if (showLogoutDialog) {
        AccountLogoutDialog(
            {
                onLogout()
                showLogoutDialog = false
            },
            onCancel = { showLogoutDialog = false },
        )
    }
}
