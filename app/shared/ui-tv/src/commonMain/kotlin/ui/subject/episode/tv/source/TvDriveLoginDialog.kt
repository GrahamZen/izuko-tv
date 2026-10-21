/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveQrLoginState
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveService
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_cloud_drive_login_qr_description
import me.him188.ani.app.ui.lang.tv_settings_drive_qr_confirmed
import me.him188.ani.app.ui.lang.tv_settings_drive_qr_expired
import me.him188.ani.app.ui.lang.tv_settings_drive_qr_failed
import me.him188.ani.app.ui.lang.tv_settings_drive_qr_success
import me.him188.ani.app.ui.lang.tv_settings_qr_preparing
import me.him188.ani.app.ui.lang.tv_source_drive_login_password
import me.him188.ani.app.ui.lang.tv_source_drive_login_title
import me.him188.ani.app.ui.lang.tv_source_retry
import me.him188.ani.app.ui.remote.RemoteQrCode
import me.him188.ani.app.ui.remote.TvRemoteControl
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.milliseconds

/**
 * 选源面板里没登录网盘的数据源 (胶囊写「未登录」) 按确定弹的登录窗: 扫码登录走设置里同一套 ([CloudDriveService.qrLogin]),
 * 成功后 [onLoggedIn] (面板让这些源重新查), 停一下让人看到「已登录」再关. 账号密码登录的网盘 (WebDAV) 电视上填不了,
 * 给 Web 控制台的码, 在那里的「数据源」里填.
 */
@Composable
internal fun TvDriveLoginDialog(driveId: String, onLoggedIn: () -> Unit, onDismiss: () -> Unit) {
    val service = remember(driveId) { KoinPlatform.getKoin().get<CloudDriveRegistry>().service(driveId) }
    if (service == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val name = service.protocol.displayName(Locale.current.toLanguageTag())
    AniCenteredPanelDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.tv_source_drive_login_title, name)) },
        heightFraction = null,
        maxWidth = DRIVE_LOGIN_MAX_WIDTH,
    ) {
        Column(
            // 播放器里: 播放 / 暂停键照常管播放, 长按返回收干净. 等扫码时没有按钮, 焦点停在这一栏上 (返回键才有人接)
            Modifier.tvOverlayWindowKeys(onDismiss).fillMaxWidth().tvWindowInitialFocus().focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (service.protocol.supportsPasswordLogin || !service.supportsQrLogin) {
                ConsoleLogin()
            } else {
                QrLogin(service, name, onLoggedIn, onDismiss)
            }
        }
    }
}

@Composable
private fun QrLogin(service: CloudDriveService, name: String, onLoggedIn: () -> Unit, onDismiss: () -> Unit) {
    // 过期 / 失败后按「重试」换一轮: 流在 Expired / Failed 处结束, 重新收集即拿新码
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<CloudDriveQrLoginState>(CloudDriveQrLoginState.Loading) }
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(service, attempt) {
        service.qrLogin().collect { state = it }
    }
    LaunchedEffect(state) {
        if (state is CloudDriveQrLoginState.Success) {
            currentOnLoggedIn()
            delay(SUCCESS_LINGER)
            currentOnDismiss()
        }
    }
    when (val s = state) {
        is CloudDriveQrLoginState.WaitingForScan -> {
            RemoteQrCode(s.qrContent, size = QR_SIZE, quietZone = QR_QUIET_ZONE)
            val app = service.protocol.login.qr?.appName?.takeIf { it.isNotBlank() } ?: name
            Message(stringResource(Lang.settings_media_cloud_drive_login_qr_description, app))
        }

        CloudDriveQrLoginState.Loading -> Message(stringResource(Lang.tv_settings_qr_preparing))
        CloudDriveQrLoginState.Confirmed -> Message(stringResource(Lang.tv_settings_drive_qr_confirmed))
        is CloudDriveQrLoginState.Success -> Message(stringResource(Lang.tv_settings_drive_qr_success, s.nickname))
        CloudDriveQrLoginState.Expired -> {
            Message(stringResource(Lang.tv_settings_drive_qr_expired))
            RetryButton { attempt++ }
        }

        is CloudDriveQrLoginState.Failed -> {
            Message(stringResource(Lang.tv_settings_drive_qr_failed, s.message))
            RetryButton { attempt++ }
        }
    }
}

/** 账号密码登录: 电视上没法输, 扫码打开 Web 控制台的「数据源」去填. */
@Composable
private fun ConsoleLogin() {
    val url by TvRemoteControl.url.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    RemoteQrCode(url?.let { "$it#sources" }, size = QR_SIZE, quietZone = QR_QUIET_ZONE)
    Message(stringResource(Lang.tv_source_drive_login_password))
}

@Composable
private fun RetryButton(onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    AniFocusActionButton(onClick = onClick, modifier = Modifier.focusRequester(requester)) {
        Text(stringResource(Lang.tv_source_retry))
    }
    LaunchedEffect(Unit) { requester.requestFocus() }
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

private val DRIVE_LOGIN_MAX_WIDTH = 560.dp
private val QR_SIZE = 240.dp
private val QR_QUIET_ZONE = 12.dp
private val SUCCESS_LINGER = 1500.milliseconds
