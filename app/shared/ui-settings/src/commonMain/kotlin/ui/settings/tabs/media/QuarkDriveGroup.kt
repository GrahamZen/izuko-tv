/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.QuarkConfig
import me.him188.ani.app.data.models.preference.QuarkPlaybackMode
import me.him188.ani.app.domain.mediasource.quark.QuarkAuthException
import me.him188.ani.app.domain.mediasource.quark.QuarkDriveService
import me.him188.ani.app.domain.mediasource.quark.QuarkQrLoginState
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_quark_account
import me.him188.ani.app.ui.lang.settings_media_quark_close
import me.him188.ani.app.ui.lang.settings_media_quark_cookie_failed
import me.him188.ani.app.ui.lang.settings_media_quark_description
import me.him188.ani.app.ui.lang.settings_media_quark_expired
import me.him188.ani.app.ui.lang.settings_media_quark_logged_out
import me.him188.ani.app.ui.lang.settings_media_quark_login_cookie
import me.him188.ani.app.ui.lang.settings_media_quark_login_cookie_description
import me.him188.ani.app.ui.lang.settings_media_quark_login_qr
import me.him188.ani.app.ui.lang.settings_media_quark_login_qr_description
import me.him188.ani.app.ui.lang.settings_media_quark_logout
import me.him188.ani.app.ui.lang.settings_media_quark_member_normal
import me.him188.ani.app.ui.lang.settings_media_quark_member_svip
import me.him188.ani.app.ui.lang.settings_media_quark_member_trial
import me.him188.ani.app.ui.lang.settings_media_quark_member_vip
import me.him188.ani.app.ui.lang.settings_media_quark_qr_expired
import me.him188.ani.app.ui.lang.settings_media_quark_qr_failed
import me.him188.ani.app.ui.lang.settings_media_quark_qr_loading
import me.him188.ani.app.ui.lang.settings_media_quark_qr_refresh
import me.him188.ani.app.ui.lang.settings_media_quark_qr_success
import me.him188.ani.app.ui.lang.settings_media_quark_qr_title
import me.him188.ani.app.ui.lang.settings_media_quark_qr_waiting
import me.him188.ani.app.ui.lang.settings_media_quark_title
import me.him188.ani.app.ui.lang.settings_media_quark_transcode
import me.him188.ani.app.ui.lang.settings_media_quark_transcode_description
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem
import me.him188.ani.app.ui.settings.framework.components.TextItem
import org.jetbrains.compose.resources.stringResource

/**
 * 设置页「夸克网盘」分组的状态: 登录 (扫码或填 Cookie)、账号信息、退出登录、播放方式.
 *
 * @param onLoggedIn 登录成功后调用, 用来在还没有「夸克网盘」数据源时自动添加.
 */
@Stable
class QuarkDriveGroupState(
    val config: StateFlow<QuarkConfig>,
    private val service: QuarkDriveService,
    private val onLoggedIn: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    private val _qrLogin = MutableStateFlow<QuarkQrLoginState?>(null)

    /**
     * 扫码弹窗的状态, null 表示弹窗关着.
     */
    val qrLogin: StateFlow<QuarkQrLoginState?> get() = _qrLogin
    private var qrJob: Job? = null

    private val _cookieError = MutableStateFlow<String?>(null)
    val cookieError: StateFlow<String?> get() = _cookieError

    private val _loginExpired = MutableStateFlow(false)

    /**
     * 服务端说登录已失效. 这时仍保留旧 Cookie, 等用户重新登录覆盖.
     */
    val loginExpired: StateFlow<Boolean> get() = _loginExpired

    fun startQrLogin() {
        qrJob?.cancel()
        qrJob = scope.launch {
            service.qrLogin().collect { state ->
                _qrLogin.value = state
                if (state is QuarkQrLoginState.Success) {
                    _loginExpired.value = false
                    onLoggedIn()
                }
            }
        }
    }

    fun dismissQrLogin() {
        qrJob?.cancel()
        qrJob = null
        _qrLogin.value = null
    }

    fun loginWithCookie(cookie: String) {
        scope.launch {
            try {
                service.loginWithCookie(cookie)
                _cookieError.value = null
                _loginExpired.value = false
                onLoggedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _cookieError.value = e.message ?: e.toString()
            }
        }
    }

    fun refreshAccount() {
        scope.launch {
            try {
                service.refreshAccount()
                _loginExpired.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: QuarkAuthException) {
                _loginExpired.value = true
            } catch (_: Throwable) {
                // 网络问题不影响已保存的登录态, 下次打开设置再核对
            }
        }
    }

    fun logout() {
        scope.launch {
            service.logout()
            _loginExpired.value = false
        }
    }

    fun setTranscoded(enabled: Boolean) {
        scope.launch {
            service.setPlaybackMode(if (enabled) QuarkPlaybackMode.TRANSCODED else QuarkPlaybackMode.ORIGINAL)
        }
    }
}

@Composable
internal fun SettingsScope.QuarkDriveGroup(state: QuarkDriveGroupState) {
    val config by state.config.collectAsStateWithLifecycle()
    val cookieError by state.cookieError.collectAsStateWithLifecycle()
    val loginExpired by state.loginExpired.collectAsStateWithLifecycle()
    val qrLogin by state.qrLogin.collectAsStateWithLifecycle()

    LaunchedEffect(config.isLoggedIn) {
        if (config.isLoggedIn) state.refreshAccount()
    }

    Group(
        title = { Text(stringResource(Lang.settings_media_quark_title)) },
        description = { Text(stringResource(Lang.settings_media_quark_description)) },
        useThinHeader = true,
    ) {
        if (config.isLoggedIn) {
            val member = memberLabel(config.memberType)
            TextItem(
                title = { Text(stringResource(Lang.settings_media_quark_account)) },
                description = {
                    Text(
                        if (loginExpired) {
                            stringResource(Lang.settings_media_quark_expired)
                        } else {
                            listOf(config.nickname, member).filter { it.isNotBlank() }.joinToString(" · ")
                        },
                    )
                },
            )
        }
        if (!config.isLoggedIn || loginExpired) {
            TextItem(
                title = { Text(stringResource(Lang.settings_media_quark_login_qr)) },
                description = { Text(stringResource(Lang.settings_media_quark_login_qr_description)) },
                onClick = { state.startQrLogin() },
            )
            TextFieldItem(
                value = "",
                title = { Text(stringResource(Lang.settings_media_quark_login_cookie)) },
                description = {
                    Text(
                        cookieError?.let { stringResource(Lang.settings_media_quark_cookie_failed, it) }
                            ?: stringResource(Lang.settings_media_quark_login_cookie_description),
                    )
                },
                exposedItem = {
                    if (!config.isLoggedIn) Text(stringResource(Lang.settings_media_quark_logged_out))
                },
                onValueChangeCompleted = { cookie ->
                    if (cookie.isNotBlank()) state.loginWithCookie(cookie)
                },
            )
        }
        if (config.isLoggedIn) {
            SwitchItem(
                checked = config.playbackMode == QuarkPlaybackMode.TRANSCODED,
                onCheckedChange = { state.setTranscoded(it) },
                title = { Text(stringResource(Lang.settings_media_quark_transcode)) },
                description = { Text(stringResource(Lang.settings_media_quark_transcode_description)) },
            )
            TextItem(
                title = { Text(stringResource(Lang.settings_media_quark_logout)) },
                onClick = { state.logout() },
            )
        }
    }

    qrLogin?.let { login ->
        QuarkQrLoginDialog(
            state = login,
            onRefresh = { state.startQrLogin() },
            onDismiss = { state.dismissQrLogin() },
        )
    }
}

@Composable
private fun QuarkQrLoginDialog(
    state: QuarkQrLoginState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canRefresh = state is QuarkQrLoginState.Expired || state is QuarkQrLoginState.Failed
    AniAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.settings_media_quark_qr_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(QR_SIZE), contentAlignment = Alignment.Center) {
                    when (state) {
                        is QuarkQrLoginState.WaitingForScan -> QrCodeImage(
                            state.qrContent,
                            Modifier.size(QR_SIZE),
                            quietZone = 12.dp,
                            // 深色码浅色底, 各种扫码器都认
                            foreground = Color.Black,
                            background = Color.White,
                        )

                        QuarkQrLoginState.Loading -> CircularProgressIndicator()
                        else -> {}
                    }
                }
                Text(
                    when (state) {
                        QuarkQrLoginState.Loading -> stringResource(Lang.settings_media_quark_qr_loading)
                        is QuarkQrLoginState.WaitingForScan -> stringResource(Lang.settings_media_quark_qr_waiting)
                        is QuarkQrLoginState.Success -> stringResource(Lang.settings_media_quark_qr_success)
                        QuarkQrLoginState.Expired -> stringResource(Lang.settings_media_quark_qr_expired)
                        is QuarkQrLoginState.Failed -> stringResource(Lang.settings_media_quark_qr_failed, state.message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            if (canRefresh) {
                AniTextButton(onClick = onRefresh) { Text(stringResource(Lang.settings_media_quark_qr_refresh)) }
            }
        },
        dismissButton = {
            AniTextButton(onClick = onDismiss) { Text(stringResource(Lang.settings_media_quark_close)) }
        },
    )
}

@Composable
private fun memberLabel(memberType: String): String = when {
    memberType.isBlank() -> ""
    memberType.startsWith("EXP_") -> stringResource(Lang.settings_media_quark_member_trial)
    memberType.contains("SVIP") || memberType == "SUPER_VIP" -> stringResource(Lang.settings_media_quark_member_svip)
    memberType.contains("VIP") -> stringResource(Lang.settings_media_quark_member_vip)
    else -> stringResource(Lang.settings_media_quark_member_normal)
}

private val QR_SIZE = 220.dp
