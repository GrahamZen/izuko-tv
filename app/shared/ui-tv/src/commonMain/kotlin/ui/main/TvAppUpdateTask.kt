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
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.tools.update.FileDownloadStage
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_update_popup_installing
import me.him188.ani.app.ui.lang.tv_background_task_app_update
import me.him188.ani.app.ui.lang.tv_background_task_app_update_downloaded
import me.him188.ani.app.ui.lang.tv_background_task_app_update_failed
import me.him188.ani.app.ui.lang.tv_background_task_app_update_install
import me.him188.ani.app.ui.lang.tv_background_task_app_update_later
import me.him188.ani.app.ui.lang.tv_background_task_app_update_retry
import me.him188.ani.app.ui.lang.tv_background_task_app_update_stop
import me.him188.ani.app.ui.search.renderLoadErrorMessage
import me.him188.ani.app.ui.update.AppUpdateState
import me.him188.ani.app.ui.update.LocalAppUpdateViewModel
import me.him188.ani.app.ui.update.UpdatePromptState
import me.him188.ani.app.ui.update.downloadStageText
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * 动作面板顶上那一格里的「下载新版本」(见 [rememberTvBackgroundTasks]): 读界面最外层那一份更新状态 ([LocalAppUpdateViewModel]),
 * 下载中、下好待安装、下载失败、安装中时有. 说明写在做什么 (挑线路、已下多少与速度、换线路、校验) 或失败原因;
 * ✕ 下载中是取消下载, 下好了或失败了是「先不更新」; 按了这一格收起 ([UpdatePromptState.dismissDownload], 同下载卡上的「取消」),
 * 通知里那一条还在 (见 TvUpdateNotice), 再按「自动更新」又回来. 安装中停不了. 失败时「继续」那一颗是重新下载, 下好时是安装 (自动安装被取消或失败后再装一次).
 */
@Composable
internal fun rememberTvAppUpdateTask(): TvBackgroundTask? {
    val viewModel = LocalAppUpdateViewModel.current ?: return null
    // 下载时进度跳得很勤, 面板上半秒更新一次就够
    val flow = remember(viewModel) { viewModel.presentationFlow.atMostEvery(500.milliseconds) }
    val presentation by flow.collectAsState(viewModel.presentationFlow.value)
    val dismissed by UpdatePromptState.dismissedDownload.collectAsState()
    val p = presentation.takeIf { it.isDownloading && it.newVersion?.name != dismissed } ?: return null
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val version = p.newVersion?.name.orEmpty()
    val stats = p.fileDownloaderStats
    val stage = stats.stage
    val error = p.downloadError
    val installing = p.state is AppUpdateState.Installing
    val downloaded = p.state is AppUpdateState.Downloaded
    return TvBackgroundTask(
        key = "app-update",
        title = stringResource(
            when {
                error != null -> Lang.tv_background_task_app_update_failed
                downloaded || installing -> Lang.tv_background_task_app_update_downloaded
                else -> Lang.tv_background_task_app_update
            },
            version,
        ),
        detail = when {
            installing -> stringResource(Lang.settings_update_popup_installing)
            error != null -> renderLoadErrorMessage(error)
            downloaded -> null
            else -> stage?.let { downloadStageText(it) }
        },
        // 挑线路、换线路、校验时进度不动, 画不定式的; 失败与下好了画停住的
        progress = when {
            downloaded || installing -> 1f
            error != null -> stats.progress
            stage is FileDownloadStage.Transferring && stage.totalBytes != null -> stats.progress
            else -> null
        },
        resume = when {
            error != null -> ({ viewModel.restartDownload(uriHandler) })
            downloaded -> ({ viewModel.install(context) })
            else -> null
        },
        resumeLabel = when {
            error != null -> stringResource(Lang.tv_background_task_app_update_retry)
            downloaded -> stringResource(Lang.tv_background_task_app_update_install)
            else -> null
        },
        resumeIcon = if (error != null) Icons.Rounded.Refresh else Icons.Rounded.SystemUpdate,
        stop = if (installing) {
            null
        } else {
            {
                viewModel.cancelDownload()
                UpdatePromptState.dismissDownload(version)
            }
        },
        stopLabel = stringResource(
            if (downloaded || error != null) Lang.tv_background_task_app_update_later else Lang.tv_background_task_app_update_stop,
        ),
    )
}
