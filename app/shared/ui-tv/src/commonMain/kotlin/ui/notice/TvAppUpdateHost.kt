/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.foundation.AppForeground
import me.him188.ani.app.ui.foundation.animation.ProvideAniMotionCompositionLocals
import me.him188.ani.app.ui.foundation.tv.TvActionPanelRequests
import me.him188.ani.app.ui.remote.RemoteAppUpdate
import me.him188.ani.app.ui.update.AppUpdateState
import me.him188.ani.app.ui.update.AppUpdateViewModel
import me.him188.ani.app.ui.update.LocalAppUpdateViewModel
import me.him188.ani.app.ui.update.UpdateNotifier
import me.him188.ani.app.ui.update.UpdatePromptState
import kotlin.time.Duration.Companion.seconds

/**
 * 电视上的应用更新挂在界面最外层, 哪一页都在, 只管功能: 查新版本、按「自动更新」开始下载 (通知里与设置的「检查更新」都交到这里,
 * 见 UpdatePromptState.requestDownload)、下完自动安装、安装授权与安装失败的弹窗 (见 [UpdateNotifier]). 用的是 [viewModel] 这一份,
 * 外面经 [LocalAppUpdateViewModel] 交给首页 (版本过期时的强制检查)、设置页与动作面板.
 *
 * 什么都不画: 下载的进度、取消、重试与安装在动作面板顶上 (见 TvBackgroundTasks), 新版本是通知中心里的一条 (见 [TvUpdateNotice]).
 * 要用户动一下的时候弹动作面板、焦点在下载那一格: 开始下载时 ([showDownloadInActionPanel]); 下载失败了 (「重新下载」一按就到);
 * 拉起了安装器又回到应用、还没装上 (安装器被取消或出错; 「安装」一按就到, 装上了进程会被系统结束, 回不来). 用户收起了这一版
 * ([UpdatePromptState.dismissedDownload]) 就不弹.
 * [inPlayer]: 播放时不自动安装 (下载照常)、不弹面板, 退出播放再装、再弹.
 * 这一层在页面框架 (AniAppContent) 外面, 弹窗的动画参数要自己提供 ([ProvideAniMotionCompositionLocals]).
 */
@Composable
fun BoxScope.TvAppUpdateHost(viewModel: AppUpdateViewModel, inPlayer: Boolean) {
    val scope = rememberCoroutineScope()
    val presentation by viewModel.presentationFlow.collectAsStateWithLifecycle()
    val state = presentation.state
    val currentInPlayer by rememberUpdatedState(inPlayer)
    val dismissedDownload by UpdatePromptState.dismissedDownload.collectAsStateWithLifecycle()
    val dismissed = dismissedDownload != null && presentation.newVersion?.name == dismissedDownload
    // 通知弹窗在这一份外面, 「刷新」检查新版本时用它 (见 TvNoticeRefresh.appUpdate)
    DisposableEffect(viewModel) {
        TvNoticeRefresh.appUpdate = viewModel
        onDispose { if (TvNoticeRefresh.appUpdate === viewModel) TvNoticeRefresh.appUpdate = null }
    }

    // 下载失败: 每次失败弹一次 (播放时等退出播放)
    var shownFailure by remember { mutableStateOf<Throwable?>(null) }
    val failure = (state as? AppUpdateState.DownloadFailed)?.throwable
    LaunchedEffect(failure, inPlayer, dismissed) {
        if (failure != null && failure !== shownFailure && !inPlayer && !dismissed) {
            shownFailure = failure
            TvActionPanelRequests.showTasks("an app update failed to download")
        }
    }
    // 下好了还没装上: 拉起的安装器把应用盖到后台, 回来时 (被取消、出错) 弹一次. 一份只弹一次, 之后从别处回来不再打扰
    val waitingForInstall = state is AppUpdateState.Downloaded && !dismissed
    LaunchedEffect(waitingForInstall) {
        if (!waitingForInstall) return@LaunchedEffect
        AppForeground.started.first { !it }
        AppForeground.started.first { it }
        if (!currentInPlayer) TvActionPanelRequests.showTasks("an app update downloaded but not installed")
    }

    // 电视上发起的测试包安装同样等退出播放再弹安装确认 (见 RemoteAppUpdate.installTestPackage)
    LaunchedEffect(inPlayer) { RemoteAppUpdate.playerShowing.value = inPlayer }

    ProvideAniMotionCompositionLocals {
        UpdateNotifier(
            viewModel,
            suspended = inPlayer,
            showDownloadCard = false,
            onDownloadStarted = { scope.launch { viewModel.showDownloadInActionPanel() } },
        )
    }
}

/**
 * 开始下载新版本之后弹一次动作面板, 焦点在顶上下载的那一格 (同开始搬缓存, 见 [TvActionPanelRequests]).
 * 等那一格有了再弹, 免得弹出来时还空着、焦点落到别处. 要先给安装授权时, 授权弹窗开着的那段不算 (答完才开始下); 几秒内都没开始下就不弹.
 */
private suspend fun AppUpdateViewModel.showDownloadInActionPanel() {
    while (true) {
        if (withTimeoutOrNull(5.seconds) { presentationFlow.first { it.isDownloading } } != null) {
            TvActionPanelRequests.showTasks("downloading an app update")
            return
        }
        if (installPermissionRequest.value == null) return
        installPermissionRequest.first { it == null }
    }
}
