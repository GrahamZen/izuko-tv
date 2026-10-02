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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDrivePlaybackMode
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveAuthException
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveProtocol
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveQrLoginState
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.ui.foundation.lan.QrCodeImage
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_account
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_app_fallback
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_close
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_cookie_failed
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_description
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_expired
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_logged_out
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_login_cookie
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_login_cookie_description
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_login_qr
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_login_qr_description
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_logout
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_confirmed
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_expired
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_expires_in
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_failed
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_loading
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_refresh
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_success
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_title
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_qr_waiting
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_transcode
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_transcode_description
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem
import me.him188.ani.app.ui.settings.framework.components.TextItem
import me.him188.ani.utils.platform.currentTimeMillis
import org.jetbrains.compose.resources.stringResource

/**
 * 设置页一个网盘分组的状态: 登录 (扫码或填 Cookie)、账号信息、退出登录、播放方式. 网盘名、登录方式、档位名都来自网盘的协议.
 */
@Stable
class CloudDriveGroupState(
    private val service: CloudDriveService,
    private val scope: CoroutineScope,
) {
    val driveId: String get() = service.driveId

    /** 是不是网盘服务 [service] 的分组 (网盘重新配置后服务会换). */
    fun isFor(service: CloudDriveService): Boolean = this.service === service
    val protocol: CloudDriveProtocol get() = service.protocol

    val account: StateFlow<CloudDriveAccount> = service.account.stateIn(scope, SharingStarted.Eagerly, CloudDriveAccount.Default)

    private val _qrLogin = MutableStateFlow<CloudDriveQrLoginState?>(null)

    /**
     * 扫码弹窗的状态, null 表示弹窗关着.
     */
    val qrLogin: StateFlow<CloudDriveQrLoginState?> get() = _qrLogin
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
                if (state is CloudDriveQrLoginState.Success) _loginExpired.value = false
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
            } catch (_: CloudDriveAuthException) {
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
            service.setPlaybackMode(if (enabled) CloudDrivePlaybackMode.TRANSCODED else CloudDrivePlaybackMode.ORIGINAL)
        }
    }
}

@Composable
internal fun SettingsScope.CloudDriveGroup(state: CloudDriveGroupState) {
    val account by state.account.collectAsStateWithLifecycle()
    val cookieError by state.cookieError.collectAsStateWithLifecycle()
    val loginExpired by state.loginExpired.collectAsStateWithLifecycle()
    val qrLogin by state.qrLogin.collectAsStateWithLifecycle()
    val protocol = state.protocol
    val language = Locale.current.toLanguageTag()
    val name = protocol.displayName(language)
    val appName = protocol.login.qr?.appName?.takeIf { it.isNotBlank() }
        ?: stringResource(Lang.settings_media_cloud_drive_app_fallback, name)

    LaunchedEffect(account.isLoggedIn) {
        if (account.isLoggedIn) state.refreshAccount()
    }

    Group(
        title = { Text(name) },
        description = { Text(stringResource(Lang.settings_media_cloud_drive_description, name)) },
        useThinHeader = true,
    ) {
        if (account.isLoggedIn) {
            val tier = protocol.tierOf(account.tier)?.displayLabel(language).orEmpty()
            TextItem(
                title = { Text(stringResource(Lang.settings_media_cloud_drive_account)) },
                description = {
                    Text(
                        if (loginExpired) {
                            stringResource(Lang.settings_media_cloud_drive_expired)
                        } else {
                            listOf(account.nickname, tier).filter { it.isNotBlank() }.joinToString(" · ")
                        },
                    )
                },
            )
        }
        if (!account.isLoggedIn || loginExpired) {
            if (protocol.login.qr != null) {
                TextItem(
                    title = { Text(stringResource(Lang.settings_media_cloud_drive_login_qr)) },
                    description = { Text(stringResource(Lang.settings_media_cloud_drive_login_qr_description, appName)) },
                    onClick = { state.startQrLogin() },
                )
            }
            TextFieldItem(
                value = "",
                title = { Text(stringResource(Lang.settings_media_cloud_drive_login_cookie)) },
                description = {
                    Text(
                        cookieError?.let { stringResource(Lang.settings_media_cloud_drive_cookie_failed, it) }
                            ?: protocol.login.cookieHint.takeIf { it.isNotBlank() }
                            ?: stringResource(Lang.settings_media_cloud_drive_login_cookie_description, name),
                    )
                },
                exposedItem = {
                    if (!account.isLoggedIn) Text(stringResource(Lang.settings_media_cloud_drive_logged_out))
                },
                onValueChangeCompleted = { cookie ->
                    if (cookie.isNotBlank()) state.loginWithCookie(cookie)
                },
            )
        }
        if (account.isLoggedIn) {
            if (protocol.supportsTranscoded) {
                SwitchItem(
                    checked = account.playbackMode == CloudDrivePlaybackMode.TRANSCODED,
                    onCheckedChange = { state.setTranscoded(it) },
                    title = { Text(stringResource(Lang.settings_media_cloud_drive_transcode)) },
                    description = { Text(stringResource(Lang.settings_media_cloud_drive_transcode_description)) },
                )
            }
            TextItem(
                title = { Text(stringResource(Lang.settings_media_cloud_drive_logout)) },
                onClick = { state.logout() },
            )
        }
    }

    qrLogin?.let { login ->
        CloudDriveQrLoginDialog(
            state = login,
            driveName = name,
            appName = appName,
            onRefresh = { state.startQrLogin() },
            onDismiss = { state.dismissQrLogin() },
        )
    }
}

@Composable
private fun CloudDriveQrLoginDialog(
    state: CloudDriveQrLoginState,
    driveName: String,
    appName: String,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canRefresh = state is CloudDriveQrLoginState.Expired || state is CloudDriveQrLoginState.Failed
    AniAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.settings_media_cloud_drive_qr_title, driveName)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(QR_SIZE), contentAlignment = Alignment.Center) {
                    when (state) {
                        is CloudDriveQrLoginState.WaitingForScan -> QrCodeImage(
                            state.qrContent,
                            Modifier.size(QR_SIZE),
                            quietZone = 12.dp,
                            // 深色码浅色底, 各种扫码器都认
                            foreground = Color.Black,
                            background = Color.White,
                        )

                        CloudDriveQrLoginState.Loading, CloudDriveQrLoginState.Confirmed -> CircularProgressIndicator()
                        else -> {}
                    }
                }
                Text(
                    when (state) {
                        CloudDriveQrLoginState.Loading -> stringResource(Lang.settings_media_cloud_drive_qr_loading)
                        is CloudDriveQrLoginState.WaitingForScan -> stringResource(Lang.settings_media_cloud_drive_qr_waiting, appName)
                        CloudDriveQrLoginState.Confirmed -> stringResource(Lang.settings_media_cloud_drive_qr_confirmed)
                        is CloudDriveQrLoginState.Success -> stringResource(Lang.settings_media_cloud_drive_qr_success)
                        CloudDriveQrLoginState.Expired -> stringResource(Lang.settings_media_cloud_drive_qr_expired)
                        is CloudDriveQrLoginState.Failed -> stringResource(Lang.settings_media_cloud_drive_qr_failed, state.message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (state is CloudDriveQrLoginState.WaitingForScan) {
                    QrExpiryCountdown(state.expiresAtMillis)
                }
            }
        },
        confirmButton = {
            if (canRefresh) {
                AniTextButton(onClick = onRefresh) { Text(stringResource(Lang.settings_media_cloud_drive_qr_refresh)) }
            }
        },
        dismissButton = {
            AniTextButton(onClick = onDismiss) { Text(stringResource(Lang.settings_media_cloud_drive_close)) }
        },
    )
}

/**
 * 二维码下面的「4:12 后过期」: 每秒更新一次, 重组只落在这一行字上.
 */
@Composable
private fun QrExpiryCountdown(expiresAtMillis: Long) {
    val remaining by produceState(expiresAtMillis - currentTimeMillis(), expiresAtMillis) {
        while (true) {
            value = expiresAtMillis - currentTimeMillis()
            if (value <= 0) break
            // 对齐到整秒再跳, 数字不会一下停一秒多一下停不到一秒
            delay(value % 1000 + 1)
        }
    }
    Text(
        stringResource(Lang.settings_media_cloud_drive_qr_expires_in, formatCountdown(remaining)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 倒计时文字「4:12」(分:秒): 剩余毫秒按秒向上取整, 到期那一刻正好是「0:00」; 已过期也是「0:00」.
 */
internal fun formatCountdown(remainingMillis: Long): String {
    val seconds = (remainingMillis.coerceAtLeast(0) + 999) / 1000
    val s = seconds % 60
    return "${seconds / 60}:${if (s < 10) "0" else ""}$s"
}

private val QR_SIZE = 220.dp
