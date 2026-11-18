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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.him188.ani.app.domain.foundation.AppForeground
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvContainDirectionalKeys
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.widgets.AniButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlin.time.Duration.Companion.minutes

/** 入口更新提示卡无操作自动消失时长 (毫秒). */
/** 自动检查更新多久看一眼 (真查另有一小时的门槛). */
private val UPDATE_CHECK_LOOK_INTERVAL = 10.minutes

private const val UPDATE_CARD_AUTO_DISMISS_MILLIS = 20_000L

/** 本次发布在 GitHub 上的页面; 详情弹窗底部那颗按钮跳这里. */
private fun releaseNotesUrl(version: String) =
    "https://github.com/$FORK_OWNER/$FORK_REPO/releases/tag/v$version"

/**
 * 检测新版本; 右下角那张卡 (见 [UpdatePromptState]): 下载中、下好待安装、下载失败时是下载卡 (取消 / 安装 / 重试),
 * 别的时候画 [UpdatePromptState.bubble] 那个提示气泡 (TV 的通知气泡).
 * 检查到新版本不单独出卡, 交给 [UpdatePromptState.newVersion] (TV 上是一条通知); 那边按了「自动更新」([UpdatePromptState.requestDownload])
 * 走与设置页完全相同的下载流程, TV 上下载完自动安装.
 * TV: 气泡出来时焦点落在它的主按钮上, 下载完落在「安装」上, 下载失败落在「重试」上.
 */
@Composable
fun BoxScope.UpdateNotifier(
    viewModel: AppUpdateViewModel = viewModel { AppUpdateViewModel() },
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

    // 检查到、还没开始下载的新版本交出去 (TV 上列成一条通知); 开始下载后不列, 下载失败又列.
    // 这张卡只在首页上: 离开首页 (组合拆掉) 时不清, 通知中心从设置里打开时这一条照样在
    val offered = newVersion?.takeIf { state is AppUpdateState.HasUpdate || state is AppUpdateState.DownloadFailed }
    LaunchedEffect(offered) {
        UpdatePromptState.setNewVersion(offered)
    }
    DisposableEffect(Unit) {
        UpdatePromptState.setNotifierActive(true)
        onDispose { UpdatePromptState.setNotifierActive(false) }
    }
    // 通知里按了「自动更新」: 是此刻检查到的那一版才下; 不在首页时按的, 回到首页 (这里重新组合) 时取走
    val currentOffer by rememberUpdatedState(offered)
    LaunchedEffect(viewModel) {
        UpdatePromptState.pendingDownload.filterNotNull().collect { name ->
            if (!UpdatePromptState.takeDownload(name)) return@collect
            currentOffer?.takeIf { it.name == name }?.let {
                dismissed = false
                viewModel.startDownload(it, uriHandler)
            }
        }
    }

    // TV: 下载完成后自动安装 (与设置页一致), 遥控器用户不必再按一次安装
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

    // 下载卡在场时 TV 的通知气泡先等着 (见 UpdatePromptState)
    val downloadCard = !dismissed && presentation.isDownloading
    DisposableEffect(downloadCard) {
        UpdatePromptState.setCardShown(downloadCard)
        onDispose { UpdatePromptState.setCardShown(false) }
    }
    val bubble by UpdatePromptState.bubble.collectAsState()
    val bubbleShown = !downloadCard && bubble != null
    // 气泡撤掉后的退场动画里接着画最后那一个
    val lastBubble = remember { mutableStateOf<CornerBubble?>(null) }
    bubble?.let { lastBubble.value = it }

    // TV: 气泡出现时把初始焦点送到它的主按钮. 卡片动画尚未组合按钮时请求会悬挂,
    // 等锚点真正附着后由 Resolver 送达, 不再逐帧重发 requestFocus.
    val focus = rememberTvFocusScope()
    LaunchedEffect(autoInstall, bubbleShown, bubble) {
        if (autoInstall && bubbleShown) {
            focus.request(UpdateNotifierFocus.BubbleAction)
        }
    }
    // 下载完成后焦点送到"安装"按钮: 电视上这时会自动拉起系统安装器, 那边失败或被取消回来时, 按一下确定就能重来.
    // 不送的话焦点留在页面里, 遥控器很难走到右下角这张卡上 (2026-09-22 真机: 安装器 ANR 回来后只能重启应用)
    LaunchedEffect(autoInstall, downloaded) {
        if (autoInstall && downloaded) {
            focus.request(UpdateNotifierFocus.Install)
        }
    }
    // 下载失败后焦点送到"重试"按钮: 开始下载后焦点被兜底送回页面,
    // 用户不会知道要从哪一排按下去才能走到右下角的卡片 (2026-09-26 真机)
    val downloadFailed = downloadCard && state is AppUpdateState.DownloadFailed
    LaunchedEffect(autoInstall, downloadFailed) {
        if (autoInstall && downloadFailed) {
            focus.request(UpdateNotifierFocus.Retry)
        }
    }

    // 气泡无操作自动消失: 出现一段时间后自行关闭 (同按关闭), 不永久挡住右下角内容.
    // 安装授权的说明开着时不计时. 窗口没有焦点时也不计时: 启动时弹出的 Web 控制台二维码 (独立窗口) 正好盖在这里,
    // 计时照走的话, 用户关掉二维码时气泡往往已经没了 (2026-09-23 真机); 应用切到后台同理
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(bubble, bubbleShown, installPermissionRequest, windowFocused) {
        val current = bubble
        if (current != null && bubbleShown && installPermissionRequest == null && windowFocused) {
            delay(UPDATE_CARD_AUTO_DISMISS_MILLIS)
            current.onDismiss()
        }
    }

    // 返回键等同点关闭按钮: 不接的话返回会穿到下层页面 (退出当前页) 而气泡还挡在右下角 ——
    // 遥控器上"返回 = 关掉眼前这个东西"是最强的预期.
    // 只对气泡生效: 下载卡的按钮是"取消下载", 会真的中止下载, 返回键不该承担破坏性动作 (让它照常穿到页面).
    //
    // **焦点导航形态下不看焦点在哪, 气泡在场就接**: 气泡出现后要等动画组合出按钮锚点,
    // 那中间仍有一段几十到几百毫秒的窗口. 按 cardFocused 判的话, 落在这段窗口里的
    // 一次返回不归气泡管, 而是穿到页面 —— 主页上那正好是"连按两次退出"的第一下, 于是弹出
    // 「再按一次返回键退出」; 等气泡抢到了焦点, 第二下却被它吃掉去关气泡了, toast 就成了谎话
    // (2026-08-18 用户实测: 冷启动撞上更新提示时必现). 气泡在场就接, 这段竞态窗口整个消失,
    // 返回键的含义也不再取决于一个用户看不见的状态.
    //
    // 触屏形态维持原判据: 那里没有焦点概念 (cardFocused 基本恒 false), 等于气泡不碰返回键 ——
    // 手机上用户按返回多半是想退出当前页, 不是关这张卡.
    var cardFocused by remember { mutableStateOf(false) }
    val backClosesBubble = bubbleShown &&
            (LocalAniUiBehavior.current.focusDrivenNavigation || cardFocused)
    BackHandler(enabled = backClosesBubble) { bubble?.onDismiss() }

    // 焦点导航设备: 气泡在场期间焦点锁在气泡内, 方向键走到边界即取消这次焦点搜索.
    // 不锁的话气泡抢到初始焦点后用户随手一按方向键焦点就滑进下层页面, 而气泡还挡在右下角 ——
    // 既看不出焦点在哪, 也不知道怎么把它关掉. 锁上后出口只剩两个按钮和返回键, 全是一按之遥.
    // 只锁气泡 (与上面返回键同理): 下载卡要挂几分钟, 锁住等于扣着整个应用不放.
    // 20 秒无操作自动消失仍然有效, 是这个模态的兜底时限; 届时焦点由 NavHost 的兜底监视
    // (见 AniAppContent 的 navHostModifier) 送回页面, 不会丢在根上.
    // 光挡 Compose 的焦点搜索不够: 搜索被取消后方向键没人消费, 会交给 Android 的 FocusFinder 按屏幕位置在整个窗口里找,
    // 气泡背后是原生页面 (探索 / 追番 / 搜索) 时就挑中它们的视图, 焦点照样跑出气泡. 所以方向键在气泡里自己移焦点并吞掉
    // (tvContainDirectionalKeys).
    val trapFocus = LocalAniUiBehavior.current.focusDrivenNavigation && bubbleShown

    AniAnimatedVisibility(
        visible = downloadCard || bubbleShown,
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
                    .tvContainDirectionalKeys()
            },
    ) {
        if (presentation.isDownloading) {
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
                installButtonModifier = Modifier.tvFocusAnchor(focus, UpdateNotifierFocus.Install),
                retryButtonModifier = Modifier.tvFocusAnchor(focus, UpdateNotifierFocus.Retry),
            )
        } else {
            lastBubble.value?.let { shown ->
                CornerBubbleCard(shown, actionModifier = Modifier.tvFocusAnchor(focus, UpdateNotifierFocus.BubbleAction))
            }
        }
    }
}

private enum class UpdateNotifierFocus : TvFocusKey { BubbleAction, Install, Retry }

/** 右下角的提示气泡 (见 [CornerBubble]): 标题、一条条字 (同新版本卡上的更新条目: 前面一个点, 各至多两行), 右上角关闭, 底下一颗主按钮. */
@Composable
private fun CornerBubbleCard(bubble: CornerBubble, actionModifier: Modifier) {
    BasicNotificationPopupCard(
        title = { Text(bubble.title) },
        dismissButton = { NotificationPopupDefaults.DismissButton(bubble.onDismiss) },
        actions = {
            AniButton(onClick = bubble.onAction, modifier = actionModifier) {
                Text(bubble.actionLabel)
            }
        },
        content = {
            bubble.lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•")
                    Spacer(Modifier.width(8.dp))
                    Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    )
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
