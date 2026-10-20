/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.domain.profile.SelfCollectionRecords
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.widgets.AniAlertDialog
import me.him188.ani.app.ui.foundation.widgets.AniTextButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.profile_clear_records_done
import me.him188.ani.app.ui.lang.profile_clear_records_empty
import me.him188.ani.app.ui.lang.settings_pikpak_legacy_delete
import me.him188.ani.app.ui.lang.settings_pikpak_legacy_failed
import me.him188.ani.app.ui.lang.settings_pikpak_legacy_keep
import me.him188.ani.app.ui.lang.settings_pikpak_legacy_message
import me.him188.ani.app.ui.lang.settings_pikpak_legacy_title
import me.him188.ani.app.ui.lang.tv_profile_default_name
import me.him188.ani.app.ui.settings.SettingsViewModel
import me.him188.ani.app.ui.settings.account.AccountLogoutDialog
import me.him188.ani.app.ui.settings.account.ClearSelfRecordsDialog
import me.him188.ani.app.ui.settings.account.ConvertToBangumiProfileDialog
import me.him188.ani.app.ui.settings.account.ConvertToLocalProfileDialog
import me.him188.ani.app.ui.settings.account.ProfileViewModel
import me.him188.ani.app.ui.update.AppUpdateState
import me.him188.ani.app.ui.update.AppUpdateViewModel
import me.him188.ani.app.ui.update.FailedToInstallDialog
import me.him188.ani.app.ui.update.InstallPermissionDialog
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * 应用更新在设置页里要弹的: 安装授权、安装失败; 下载完自动安装 (遥控器上不再要按一次). 同原来设置页里的更新卡片 (UpdateSettingsNotifier),
 * 新版本的提示与下载进度在「检查更新」那一行上.
 */
@Composable
fun TvSettingsUpdateHost(viewModel: AppUpdateViewModel) {
    val context = LocalContext.current
    val presentation by viewModel.presentationFlow.collectAsStateWithLifecycle()
    val state = presentation.state
    val autoInstall = LocalAniUiBehavior.current.autoInstallUpdates
    val downloaded = state is AppUpdateState.Downloaded
    LaunchedEffect(autoInstall, downloaded) {
        if (autoInstall && downloaded) viewModel.autoInstall(context)
    }
    presentation.installationFailure?.let { failure ->
        FailedToInstallDialog(
            message = failure.reason.toString(),
            onDismissRequest = { viewModel.dismissInstallationFailure() },
            state = state,
        )
    }
    val installPermissionRequest by viewModel.installPermissionRequest.collectAsStateWithLifecycle()
    installPermissionRequest?.let { request ->
        InstallPermissionDialog(
            offerInstallWithoutPermission = request.offerInstallWithoutPermission,
            onOpenSettings = { viewModel.requestInstallPermission(context) },
            onInstallWithoutPermission = { viewModel.startDownloadWithoutPermission() },
            onDismissRequest = { viewModel.dismissInstallPermissionRequest() },
        )
    }
}

/**
 * PikPak 开着、登录了、还没回答过时, 看一眼云盘上旧版留下的缓存, 有就问删不删 (同原来设置页 PikPak 那一组的弹窗).
 */
@Composable
fun TvPikPakLegacyNoticeHost(vm: SettingsViewModel) {
    val config: PikPakConfig = vm.pikpakSettingsState.value
    val notice = vm.pikpakLegacyNoticeState
    val signedIn = config.username.isNotEmpty() && (config.password.isNotEmpty() || config.refreshToken.isNotEmpty())
    if (config.enabled && !config.legacyNoticeAnswered && signedIn) {
        LaunchedEffect(config.username, config.refreshToken.isNotEmpty()) { notice.check(config.username) }
    }
    if (notice.items.isEmpty()) return
    val markAnswered = { vm.pikpakSettingsState.update(vm.pikpakSettingsState.value.copy(legacyNoticeAnswered = true)) }
    AniAlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Lang.settings_pikpak_legacy_title)) },
        text = {
            Column {
                Text(stringResource(Lang.settings_pikpak_legacy_message, notice.items.size))
                notice.error?.let { Text(stringResource(Lang.settings_pikpak_legacy_failed, it)) }
            }
        },
        confirmButton = {
            AniTextButton(enabled = !notice.deleting, onClick = { notice.deleteAll(markAnswered) }) {
                Text(stringResource(Lang.settings_pikpak_legacy_delete))
            }
        },
        dismissButton = {
            AniTextButton(enabled = !notice.deleting, onClick = { notice.keep(markAnswered) }) {
                Text(stringResource(Lang.settings_pikpak_legacy_keep))
            }
        },
    )
}

/**
 * 弹窗里确认之后的操作跑在设置页 ViewModel 的作用域里 (主线程): 弹窗先关再做, 弹窗自己的作用域随它离开组合就取消,
 * 放在那里的操作会被半路打断.
 */
private fun ProfileViewModel.launchAfterDialog(block: suspend () -> Unit) {
    backgroundScope.launch(Dispatchers.Main) { block() }
}

/** 退出 Bangumi 登录 (同账号页). */
@Composable
internal fun TvLogoutDialog(profile: ProfileViewModel, dismiss: () -> Unit) {
    AccountLogoutDialog(
        onConfirm = {
            dismiss()
            profile.launchAfterDialog { profile.logout() }
        },
        onCancel = dismiss,
    )
}

/**
 * 改成本地用户: 先数一下收藏 (登录着数账号里的, 否则数库里存着的; 对话框里写着有多少), 再问; 登录着的写明会先退出.
 * 选了之后对话框开着显示进度 (登录着、留下记录时要先取账号里的收藏), 成功时应用重启, 失败时写明原因.
 */
@Composable
internal fun TvConvertToLocalDialog(profile: ProfileViewModel, dismiss: () -> Unit) {
    val counts by produceState<SelfCollectionRecords.Counts?>(null) { value = profile.selfRecordCounts() }
    val state by profile.stateFlow.collectAsStateWithLifecycle()
    val loggedIn = state.selfInfo.isSessionValid == true
    val accountCollections by produceState<Int?>(null, loggedIn) { value = if (loggedIn) profile.accountCollectionCount() else null }
    val progress by profile.convertToLocalProgress.collectAsStateWithLifecycle()
    val defaultName = stringResource(Lang.tv_profile_default_name, UserProfiles.currentId)
    val leftover = counts ?: return
    ConvertToLocalProfileDialog(
        leftover,
        onConvert = { clearRecords -> profile.startConvertToLocal(clearRecords, defaultName) },
        onDismissRequest = {
            profile.resetConvertToLocal()
            dismiss()
        },
        loggedIn = loggedIn,
        accountCollections = accountCollections,
        progress = progress,
    )
}

/** 本地用户改成 Bangumi 用户: 先数一下收藏 (对话框里写着会留下多少), 再问. 成功时应用重启. */
@Composable
internal fun TvConvertToBangumiDialog(profile: ProfileViewModel, dismiss: () -> Unit) {
    val counts by produceState<SelfCollectionRecords.Counts?>(null) { value = profile.selfRecordCounts() }
    val records = counts ?: return
    ConvertToBangumiProfileDialog(
        records,
        onConvert = {
            dismiss()
            profile.launchAfterDialog { profile.convertToBangumi() }
        },
        onDismissRequest = dismiss,
    )
}

/** 清除本地用户的收藏记录: 先数一下, 一条都没有就只提示一句. */
@Composable
internal fun TvClearRecordsDialog(profile: ProfileViewModel, dismiss: () -> Unit) {
    val toaster = LocalToaster.current
    val counts by produceState<SelfCollectionRecords.Counts?>(null) { value = profile.selfRecordCounts() }
    val loaded = counts ?: return
    if (loaded.isEmpty) {
        LaunchedEffect(Unit) {
            toaster.toast(getString(Lang.profile_clear_records_empty))
            dismiss()
        }
        return
    }
    ClearSelfRecordsDialog(
        loaded,
        onConfirm = {
            dismiss()
            profile.launchAfterDialog {
                profile.clearSelfRecords()
                toaster.toast(getString(Lang.profile_clear_records_done))
            }
        },
        onDismissRequest = dismiss,
    )
}
