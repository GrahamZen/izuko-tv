/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import me.him188.ani.app.tools.update.FileDownloadStage
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_background_task_app_update_stop
import me.him188.ani.app.ui.lang.tv_background_task_install_confirm
import me.him188.ani.app.ui.lang.tv_background_task_install_dismiss
import me.him188.ani.app.ui.lang.tv_background_task_install_failed
import me.him188.ani.app.ui.lang.tv_background_task_install_preparing
import me.him188.ani.app.ui.lang.tv_background_task_install_retry
import me.him188.ani.app.ui.lang.tv_background_task_install_test
import me.him188.ani.app.ui.lang.tv_background_task_install_update
import me.him188.ani.app.ui.lang.tv_background_task_install_upload
import me.him188.ani.app.ui.lang.tv_background_task_install_verifying
import me.him188.ani.app.ui.lang.tv_background_task_install_waiting_player
import me.him188.ani.app.ui.remote.AppInstallKind
import me.him188.ani.app.ui.remote.AppInstallPhase
import me.him188.ani.app.ui.remote.RemoteAppUpdate
import me.him188.ani.app.ui.update.downloadStageText
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * 动作面板顶上那一格里的「下载并安装」(见 [rememberTvBackgroundTasks]): 读 [RemoteAppUpdate.status], 电视上按的「安装测试包」与手机控制台发起的
 * 下载安装 (新版本、测试包、传上来的安装包) 都在这里看进度. 标题写装的是什么, 说明写走到哪一步或没装上的原因.
 * ✕ 下载、核对、等退出播放时是取消下载, 等确认时是不装了, 没装上时是关掉这一格 (都是 [RemoteAppUpdate.cancel]); 写入安装会话时停不了.
 * 没装上、安装包还在时「继续」那一颗是再装一次 ([RemoteAppUpdate.retryInstall]). 装上了不显示 (装自己时应用会被系统关掉).
 */
@Composable
internal fun rememberTvAppInstallTask(): TvBackgroundTask? {
    // 下载时进度跳得很勤, 面板上半秒更新一次就够
    val flow = remember { RemoteAppUpdate.status.atMostEvery(500.milliseconds) }
    val status by flow.collectAsState(RemoteAppUpdate.status.value)
    val s = status
    if (s.phase == AppInstallPhase.Idle || s.phase == AppInstallPhase.Success) return null
    val name = s.name.orEmpty()
    return TvBackgroundTask(
        key = "app-install",
        title = when (s.kind) {
            AppInstallKind.TestPackage -> stringResource(Lang.tv_background_task_install_test, s.issue ?: 0)
            AppInstallKind.Upload -> stringResource(Lang.tv_background_task_install_upload, name)
            AppInstallKind.Update -> stringResource(Lang.tv_background_task_install_update, name)
        },
        detail = when (s.phase) {
            AppInstallPhase.Downloading -> s.stage?.let { downloadStageText(it) }
            AppInstallPhase.Verifying -> stringResource(Lang.tv_background_task_install_verifying)
            AppInstallPhase.WaitingPlayer -> stringResource(Lang.tv_background_task_install_waiting_player)
            AppInstallPhase.Preparing -> stringResource(Lang.tv_background_task_install_preparing)
            AppInstallPhase.Confirm, AppInstallPhase.WaitingFront -> stringResource(Lang.tv_background_task_install_confirm)
            AppInstallPhase.Failed -> stringResource(Lang.tv_background_task_install_failed, s.error.orEmpty())
            AppInstallPhase.Idle, AppInstallPhase.Success -> null
        },
        // 挑线路、换线路、校验时进度不动, 画不定式的; 写入安装会话照写了多少画; 等着的、没装上的不画
        progress = when (s.phase) {
            AppInstallPhase.Downloading -> s.progress?.takeIf { s.stage is FileDownloadStage.Transferring }
            AppInstallPhase.Preparing -> s.progress
            AppInstallPhase.WaitingPlayer, AppInstallPhase.Confirm, AppInstallPhase.WaitingFront -> 1f
            else -> null
        },
        resume = if (s.phase == AppInstallPhase.Failed && s.canRetry) ({ RemoteAppUpdate.retryInstall() }) else null,
        resumeLabel = if (s.phase == AppInstallPhase.Failed && s.canRetry) stringResource(Lang.tv_background_task_install_retry) else null,
        resumeIcon = Icons.Rounded.Refresh,
        stop = if (s.phase == AppInstallPhase.Preparing) null else ({ RemoteAppUpdate.cancel() }),
        stopLabel = stringResource(
            when (s.phase) {
                AppInstallPhase.Downloading, AppInstallPhase.Verifying, AppInstallPhase.WaitingPlayer -> Lang.tv_background_task_app_update_stop
                else -> Lang.tv_background_task_install_dismiss
            },
        ),
    )
}
