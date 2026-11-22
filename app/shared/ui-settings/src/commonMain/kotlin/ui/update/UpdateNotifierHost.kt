/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.him188.ani.app.domain.foundation.AppForeground
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlin.time.Duration.Companion.minutes

/** 自动检查更新多久看一眼 (真查另有一小时的门槛). */
private val UPDATE_CHECK_LOOK_INTERVAL = 10.minutes

/** 本次发布在 GitHub 上的页面; 详情弹窗底部那颗按钮跳这里. */
private fun releaseNotesUrl(version: String) =
    "https://github.com/$FORK_OWNER/$FORK_REPO/releases/tag/v$version"

/**
 * 检测新版本; 下载中、下好待安装、下载失败时右下角是下载卡 (取消 / 安装 / 重试).
 * 检查到新版本不单独出卡, 交给 [UpdatePromptState.newVersion] (TV 上是一条通知); 那边按了「自动更新」([UpdatePromptState.requestDownload])
 * 走与设置页完全相同的下载流程, TV 上下载完自动安装.
 * [suspended]: 暂时不画、不自动安装 (电视上播放时不弹安装器; 下载照常, 退出播放再装).
 * [showDownloadCard] 为 false 时不画下载卡 (电视: 进度、取消、重试与安装在动作面板顶上, 见 TvAppUpdateHost);
 * [onDownloadStarted]: 按「自动更新」开始下载之后、或正在下载时又按了调 (电视: 弹动作面板看进度).
 */
@Composable
fun BoxScope.UpdateNotifier(
    viewModel: AppUpdateViewModel = viewModel { AppUpdateViewModel() },
    suspended: Boolean = false,
    showDownloadCard: Boolean = true,
    onDownloadStarted: () -> Unit = {},
) {
    // 冷启动先查一次; 之后应用在前台时每隔一会儿看一眼, 回到前台也看一眼: 离上次查超过一小时才真查 (见 startAutomaticCheckLatestVersion).
    // 电视上的应用常常几天不重启, 没有这个就一直不知道有新版本. 不在前台时不看
    LaunchedEffect(viewModel) {
        viewModel.startAutomaticCheckLatestVersion()
        while (true) {
            delay(UPDATE_CHECK_LOOK_INTERVAL)
            if (AppForeground.started.value) viewModel.startAutomaticCheckLatestVersion()
        }
    }
    LaunchedEffect(viewModel) {
        AppForeground.started.drop(1).filter { it }.collect { viewModel.startAutomaticCheckLatestVersion() }
    }

    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val presentation by viewModel.presentationFlow.collectAsStateWithLifecycle()
    val newVersion = presentation.newVersion
    val state = presentation.state

    // 下载卡按「取消」后这一版不再显示
    var dismissed by rememberSaveable(newVersion?.name) { mutableStateOf(false) }

    // 检查到的新版本交出去 (TV 上列成一条通知), 装上之前一直列着
    LaunchedEffect(newVersion) {
        UpdatePromptState.setNewVersion(newVersion)
    }
    // 按了「自动更新」(通知里, 或电视设置的「检查更新」): 是此刻检查到的那一版才做
    val currentVersion by rememberUpdatedState(newVersion)
    val currentState by rememberUpdatedState(state)
    val currentOnDownloadStarted by rememberUpdatedState(onDownloadStarted)
    LaunchedEffect(viewModel) {
        UpdatePromptState.pendingDownload.filterNotNull().collect { name ->
            if (!UpdatePromptState.takeDownload(name)) return@collect
            val version = currentVersion?.takeIf { it.name == name } ?: return@collect
            dismissed = false
            when (updateRequestStep(currentState)) {
                UpdateRequestStep.Download -> {
                    viewModel.startDownload(version, uriHandler)
                    currentOnDownloadStarted()
                }

                UpdateRequestStep.ShowProgress -> currentOnDownloadStarted()
                UpdateRequestStep.Install -> viewModel.install(context)
            }
        }
    }

    // TV: 下载完成后自动安装, 遥控器用户不必再按一次安装; 暂停着 (播放时) 等恢复了再装
    val autoInstall = LocalAniUiBehavior.current.autoInstallUpdates
    val downloaded = state is AppUpdateState.Downloaded
    LaunchedEffect(autoInstall, downloaded, suspended) {
        if (autoInstall && downloaded && !suspended) {
            viewModel.autoInstall(context)
        }
    }

    // 安装失败对话框: 失败由 ViewModel 状态承载 (install 本身立即返回)
    presentation.installationFailure?.let { failure ->
        FailedToInstallDialog(
            message = failure.reason.toString(),
            onDismissRequest = { viewModel.dismissInstallationFailure() },
            state = state,
        )
    }

    // 下载之前先要安装授权, 见 AppUpdateViewModel.installPermissionRequest
    val installPermissionRequest by viewModel.installPermissionRequest.collectAsStateWithLifecycle()
    installPermissionRequest?.let { request ->
        InstallPermissionDialog(
            offerInstallWithoutPermission = request.offerInstallWithoutPermission,
            onOpenSettings = { viewModel.requestInstallPermission(context) },
            onInstallWithoutPermission = { viewModel.startDownloadWithoutPermission() },
            onDismissRequest = { viewModel.dismissInstallPermissionRequest() },
        )
    }

    AniAnimatedVisibility(
        visible = showDownloadCard && !dismissed && presentation.isDownloading && !suspended,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(24.dp),
    ) {
        DownloadingUpdatePopupCard(
            version = newVersion ?: return@AniAnimatedVisibility,
            fileDownloaderStats = presentation.fileDownloaderStats,
            error = presentation.downloadError,
            isInstalling = state is AppUpdateState.Installing,
            onInstallClick = { viewModel.install(context) },
            onCancelClick = {
                viewModel.cancelDownload()
                dismissed = true
            },
            onRetryClick = { viewModel.restartDownload(uriHandler) },
        )
    }
}

/** 新版本的完整更新内容 (TV 通知里「查看更新内容」开的), 弹窗底部一颗按钮去发布页. */
@Composable
fun NewVersionDetails(version: NewVersion, onDismissRequest: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    NewVersionDetailsDialog(
        version = version.name,
        changes = version.detailedChanges,
        onOpenInBrowser = { uriHandler.openUri(releaseNotesUrl(version.name)) },
        onDismissRequest = onDismissRequest,
    )
}

/**
 * 设置页中的更新提示卡片，带下载和安装按钮，永久显示直到手动关闭.
 */
@Composable
fun BoxScope.UpdateSettingsNotifier(
    viewModel: AppUpdateViewModel = viewModel { AppUpdateViewModel() },
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val presentation by viewModel.presentationFlow.collectAsStateWithLifecycle()
    val newVersion = presentation.newVersion
    val state = presentation.state

    // Per-version dismiss state
    var dismissed by rememberSaveable(newVersion?.name) { mutableStateOf(false) }

    // TV: 下载完成后自动安装, 避免遥控器用户在下载结束后还要再操作一次按钮.
    // 用状态变为 Downloaded (而非定时器) 触发, 以适配不同设备的下载耗时.
    val autoInstall = LocalAniUiBehavior.current.autoInstallUpdates
    val downloaded = state is AppUpdateState.Downloaded
    LaunchedEffect(autoInstall, downloaded) {
        if (autoInstall && downloaded) {
            viewModel.autoInstall(context)
        }
    }

    // 安装失败对话框: 失败由 ViewModel 状态承载 (install 本身立即返回)
    presentation.installationFailure?.let { failure ->
        FailedToInstallDialog(
            message = failure.reason.toString(),
            onDismissRequest = { viewModel.dismissInstallationFailure() },
            state = state,
        )
    }

    // 下载之前先要安装授权, 见 AppUpdateViewModel.installPermissionRequest
    val installPermissionRequest by viewModel.installPermissionRequest.collectAsStateWithLifecycle()
    installPermissionRequest?.let { request ->
        InstallPermissionDialog(
            offerInstallWithoutPermission = request.offerInstallWithoutPermission,
            onOpenSettings = { viewModel.requestInstallPermission(context) },
            onInstallWithoutPermission = { viewModel.startDownloadWithoutPermission() },
            onDismissRequest = { viewModel.dismissInstallPermissionRequest() },
        )
    }

    // 与入口气泡一致: "查看详情"先在应用内看全文 (设置页这张卡不会自动消失, 无需暂停计时)
    var detailsVisible by remember(newVersion?.name) { mutableStateOf(false) }
    val showCard = !dismissed && (state is AppUpdateState.HasUpdate || presentation.isDownloading)
    newVersion?.takeIf { detailsVisible }?.let { version ->
        NewVersionDetailsDialog(
            version = version.name,
            changes = version.detailedChanges,
            onOpenInBrowser = { uriHandler.openUri(releaseNotesUrl(version.name)) },
            onDismissRequest = { detailsVisible = false },
        )
    }

    AniAnimatedVisibility(
        visible = showCard,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(24.dp),
    ) {
        when {
            state is AppUpdateState.HasUpdate -> {
                NewVersionPopupCard(
                    version = newVersion?.name ?: "",
                    changes = newVersion?.majorChanges ?: emptyList(),
                    showFeedbackGroupHint = newVersion?.hasFeedbackGroup == true,
                    onDetailsClick = { detailsVisible = true },
                    onAutoUpdateClick = {
                        newVersion?.let { viewModel.startDownload(it, uriHandler) }
                    },
                    onDismissRequest = { dismissed = true },
                )
            }

            presentation.isDownloading -> {
                DownloadingUpdatePopupCard(
                    version = newVersion ?: return@AniAnimatedVisibility,
                    fileDownloaderStats = presentation.fileDownloaderStats,
                    error = presentation.downloadError,
                    isInstalling = state is AppUpdateState.Installing,
                    onInstallClick = { viewModel.install(context) },
                    onCancelClick = {
                        viewModel.cancelDownload()
                        dismissed = true
                    },
                    onRetryClick = { viewModel.restartDownload(uriHandler) },
                )
            }
        }
    }
}
