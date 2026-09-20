/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import kotlinx.coroutines.delay

/** 入口更新提示卡无操作自动消失时长 (毫秒). */
private const val UPDATE_CARD_AUTO_DISMISS_MILLIS = 20_000L

/** 本次发布在 GitHub 上的页面; 详情弹窗底部那颗按钮跳这里. */
private fun releaseNotesUrl(version: String) =
    "https://github.com/$FORK_OWNER/$FORK_REPO/releases/tag/v$version"

/**
 * 检测新版本并在右下角显示更新卡片 (上游同款带按钮样式): 详情 / 自动更新 / 关闭.
 * 点自动更新走与设置页完全相同的下载流程 (下载卡片可取消/重试), TV 上下载完自动安装.
 * TV: 卡片出现时初始焦点直接落在 "自动更新" 按钮上.
 */
@Composable
fun BoxScope.UpdateNotifier(
    viewModel: AppUpdateViewModel = viewModel { AppUpdateViewModel() },
) {
    SideEffect {
        viewModel.startAutomaticCheckLatestVersion()
    }

    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val presentation by viewModel.presentationFlow.collectAsStateWithLifecycle()
    val newVersion = presentation.newVersion
    val state = presentation.state

    // Per-version dismiss state
    var dismissed by rememberSaveable(newVersion?.name) { mutableStateOf(false) }

    // TV: 下载完成后自动安装 (与设置页一致), 遥控器用户不必再按一次安装
    val autoInstall = LocalAniUiBehavior.current.autoInstallUpdates
    val downloaded = state is AppUpdateState.Downloaded
    LaunchedEffect(autoInstall, downloaded) {
        if (autoInstall && downloaded) {
            viewModel.install(context)
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

    val showCard = !dismissed && (state is AppUpdateState.HasUpdate || presentation.isDownloading)
    val hasUpdateCard = showCard && state is AppUpdateState.HasUpdate

    // TV: 气泡出现时把初始焦点送到"自动更新"按钮. 卡片动画尚未组合按钮时请求会悬挂,
    // 等锚点真正附着后由 Resolver 送达, 不再逐帧重发 requestFocus.
    val focus = rememberTvFocusScope()
    LaunchedEffect(autoInstall, hasUpdateCard, newVersion?.name) {
        if (autoInstall && hasUpdateCard) {
            focus.request(UpdateNotifierFocus.AutoUpdate)
        }
    }

    // "查看详情": 先在应用内看完整更新内容 (气泡上只放得下前几条), 弹窗底部才是跳浏览器的按钮
    var detailsVisible by remember(newVersion?.name) { mutableStateOf(false) }

    // 无操作自动消失: 提示卡出现一段时间后自行关闭, 不永久挡住右下角内容.
    // 开始下载后 hasUpdateCard 变 false, 本效应取消 —— 下载进度卡不受影响.
    // 详情弹窗开着时不计时: 用户正在读那几十条更新, 背后把气泡撤掉的话关掉弹窗就没有入口了
    // (再点"自动更新"要重新等一轮检查). 关掉弹窗后重新计满 20 秒.
    LaunchedEffect(hasUpdateCard, detailsVisible, newVersion?.name) {
        if (hasUpdateCard && !detailsVisible) {
            delay(UPDATE_CARD_AUTO_DISMISS_MILLIS)
            dismissed = true
        }
    }

    newVersion?.takeIf { detailsVisible }?.let { version ->
        NewVersionDetailsDialog(
            version = version.name,
            changes = version.detailedChanges,
            onOpenInBrowser = { uriHandler.openUri(releaseNotesUrl(version.name)) },
            onDismissRequest = { detailsVisible = false },
        )
    }

    // 返回键等同点关闭按钮: 不接的话返回会穿到下层页面 (退出当前页) 而气泡还挡在右下角 ——
    // 遥控器上"返回 = 关掉眼前这个东西"是最强的预期.
    // 只对"有更新"这张卡生效: 下载中那张卡的按钮是"取消下载", 会真的中止下载,
    // 返回键不该承担破坏性动作 (让它照常穿到页面).
    //
    // **焦点导航形态下不看焦点在哪, 卡在场就接**: 气泡出现后要等动画组合出按钮锚点,
    // 那中间仍有一段几十到几百毫秒的窗口. 按 cardFocused 判的话, 落在这段窗口里的
    // 一次返回不归气泡管, 而是穿到页面 —— 主页上那正好是"连按两次退出"的第一下, 于是弹出
    // 「再按一次返回键退出」; 等气泡抢到了焦点, 第二下却被它吃掉去关气泡了, toast 就成了谎话
    // (2026-08-18 用户实测: 冷启动撞上更新提示时必现). 卡在场就接, 这段竞态窗口整个消失,
    // 返回键的含义也不再取决于一个用户看不见的状态.
    //
    // 触屏形态维持原判据: 那里没有焦点概念 (cardFocused 基本恒 false), 等于气泡不碰返回键,
    // 与改动前一致 —— 手机上用户按返回多半是想退出当前页, 不是关这张卡.
    var cardFocused by remember { mutableStateOf(false) }
    val backClosesCard = hasUpdateCard &&
            (LocalAniUiBehavior.current.focusDrivenNavigation || cardFocused)
    BackHandler(enabled = backClosesCard) { dismissed = true }

    // 焦点导航设备: 卡片在场期间焦点锁在卡片内, 方向键走到边界即取消这次焦点搜索.
    // 不锁的话卡片抢到初始焦点后用户随手一按方向键焦点就滑进下层页面, 而卡片还挡在右下角 ——
    // 既看不出焦点在哪, 也不知道怎么把它关掉. 锁上后出口只剩三个按钮和返回键, 全是一按之遥.
    // 只锁"有更新"这张卡 (与上面返回键同理): 下载中那张要挂几分钟, 锁住等于扣着整个应用不放.
    // 20 秒无操作自动消失仍然有效, 是这个模态的兜底时限; 届时焦点由 NavHost 的兜底监视
    // (见 AniAppContent 的 navHostModifier) 送回页面, 不会丢在根上.
    val trapFocus = LocalAniUiBehavior.current.focusDrivenNavigation && hasUpdateCard

    AniAnimatedVisibility(
        visible = showCard,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(24.dp)
            .tvFocusNavSignal(focus)
            // 观察整棵子树的焦点 (卡片里的按钮), 而不是本节点自己
            .onFocusChanged { cardFocused = it.hasFocus }
            // focusProperties 只作用于链上其后的 focusTarget 与子布局节点, 且子节点向上取属性时
            // 会停在最近的一个 focusTarget 上 —— 也就是这里的 focusGroup, 因此卡片内部按钮之间
            // 的左右移动不会被 onExit 拦截, 只有跨出整张卡的那一步才会.
            .ifThen(trapFocus) {
                focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup()
            },
    ) {
        when {
            state is AppUpdateState.HasUpdate -> {
                NewVersionPopupCard(
                    version = newVersion?.name ?: "",
                    changes = newVersion?.majorChanges ?: emptyList(),
                    onDetailsClick = { detailsVisible = true },
                    onAutoUpdateClick = {
                        newVersion?.let { viewModel.startDownload(it, uriHandler) }
                    },
                    onDismissRequest = { dismissed = true },
                    autoUpdateButtonModifier = Modifier.tvFocusAnchor(
                        focus,
                        UpdateNotifierFocus.AutoUpdate,
                    ),
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

private enum class UpdateNotifierFocus : TvFocusKey { AutoUpdate }

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
            viewModel.install(context)
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

    val showCard = !dismissed && (state is AppUpdateState.HasUpdate || presentation.isDownloading)

    // 与入口气泡一致: "查看详情"先在应用内看全文 (设置页这张卡不会自动消失, 无需暂停计时)
    var detailsVisible by remember(newVersion?.name) { mutableStateOf(false) }
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
