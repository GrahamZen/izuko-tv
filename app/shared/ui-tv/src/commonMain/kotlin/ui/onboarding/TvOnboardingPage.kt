/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.onboarding

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.domain.foundation.Reachability
import me.him188.ani.app.domain.session.auth.BangumiOAuthManager
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.TvFocusScope
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.Res
import me.him188.ani.app.ui.foundation.app_icon
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.tv.TvHeroButton
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_network_bangumi_auto
import me.him188.ani.app.ui.lang.settings_network_bangumi_direct
import me.him188.ani.app.ui.lang.settings_network_bangumi_mirror
import me.him188.ani.app.ui.lang.settings_network_endpoint_auto
import me.him188.ani.app.ui.lang.settings_network_tmdb_images_disable
import me.him188.ani.app.ui.lang.tv_onboarding_images_auto_description
import me.him188.ani.app.ui.lang.tv_onboarding_images_choose
import me.him188.ani.app.ui.lang.tv_onboarding_images_description
import me.him188.ani.app.ui.lang.tv_onboarding_images_hint
import me.him188.ani.app.ui.lang.tv_onboarding_images_off_description
import me.him188.ani.app.ui.lang.tv_onboarding_images_summary_checking
import me.him188.ani.app.ui.lang.tv_onboarding_images_summary_none
import me.him188.ani.app.ui.lang.tv_onboarding_images_summary_offline
import me.him188.ani.app.ui.lang.tv_onboarding_images_summary_ok
import me.him188.ani.app.ui.lang.tv_onboarding_images_title
import me.him188.ani.app.ui.lang.tv_onboarding_login_description
import me.him188.ani.app.ui.lang.tv_onboarding_login_done
import me.him188.ani.app.ui.lang.tv_onboarding_login_done_plain
import me.him188.ani.app.ui.lang.tv_onboarding_login_failed
import me.him188.ani.app.ui.lang.tv_onboarding_login_on_tv
import me.him188.ani.app.ui.lang.tv_onboarding_login_on_tv_hint
import me.him188.ani.app.ui.lang.tv_onboarding_login_phone_hint
import me.him188.ani.app.ui.lang.tv_onboarding_login_skip
import me.him188.ani.app.ui.lang.tv_onboarding_login_title
import me.him188.ani.app.ui.lang.tv_onboarding_login_via_mirror
import me.him188.ani.app.ui.lang.tv_onboarding_login_waiting
import me.him188.ani.app.ui.lang.tv_onboarding_mode_auto_description
import me.him188.ani.app.ui.lang.tv_onboarding_mode_direct_description
import me.him188.ani.app.ui.lang.tv_onboarding_mode_mirror_description
import me.him188.ani.app.ui.lang.tv_onboarding_network_checking
import me.him188.ani.app.ui.lang.tv_onboarding_network_choose
import me.him188.ani.app.ui.lang.tv_onboarding_network_custom_hint
import me.him188.ani.app.ui.lang.tv_onboarding_network_description
import me.him188.ani.app.ui.lang.tv_onboarding_network_mirror
import me.him188.ani.app.ui.lang.tv_onboarding_network_origin
import me.him188.ani.app.ui.lang.tv_onboarding_network_reachable
import me.him188.ani.app.ui.lang.tv_onboarding_network_summary_checking
import me.him188.ani.app.ui.lang.tv_onboarding_network_summary_mirror
import me.him188.ani.app.ui.lang.tv_onboarding_network_summary_none
import me.him188.ani.app.ui.lang.tv_onboarding_network_summary_origin
import me.him188.ani.app.ui.lang.tv_onboarding_network_title
import me.him188.ani.app.ui.lang.tv_onboarding_network_unreachable
import me.him188.ani.app.ui.lang.tv_onboarding_phone_title
import me.him188.ani.app.ui.lang.tv_onboarding_proxy
import me.him188.ani.app.ui.lang.tv_onboarding_proxy_description
import me.him188.ani.app.ui.lang.tv_onboarding_proxy_on_tv
import me.him188.ani.app.ui.lang.tv_onboarding_recheck
import me.him188.ani.app.ui.lang.tv_onboarding_recommended
import me.him188.ani.app.ui.lang.tv_onboarding_start
import me.him188.ani.app.ui.lang.tv_onboarding_step_login
import me.him188.ani.app.ui.lang.tv_onboarding_step_network
import me.him188.ani.app.ui.lang.tv_onboarding_welcome_description
import me.him188.ani.app.ui.lang.tv_onboarding_welcome_intro
import me.him188.ani.app.ui.lang.tv_onboarding_welcome_start
import me.him188.ani.app.ui.lang.tv_onboarding_welcome_step_login
import me.him188.ani.app.ui.lang.tv_onboarding_welcome_step_network
import me.him188.ani.app.ui.lang.tv_onboarding_welcome_title
import me.him188.ani.app.ui.lang.tv_remote_control_close
import me.him188.ani.app.ui.lang.tv_onboarding_next
import me.him188.ani.app.ui.lang.tv_onboarding_remote_description
import me.him188.ani.app.ui.lang.tv_onboarding_remote_feature_login
import me.him188.ani.app.ui.lang.tv_onboarding_remote_feature_manage
import me.him188.ani.app.ui.lang.tv_onboarding_remote_feature_player
import me.him188.ani.app.ui.lang.tv_onboarding_remote_feature_search
import me.him188.ani.app.ui.lang.tv_onboarding_step_remote
import me.him188.ani.app.ui.lang.tv_onboarding_welcome_step_remote
import me.him188.ani.app.ui.lang.tv_remote_control_panel_hint
import me.him188.ani.app.ui.lang.tv_remote_control_title
import me.him188.ani.app.ui.remote.CONNECTED_GREEN_DARK
import me.him188.ani.app.ui.remote.CONNECTED_GREEN_LIGHT
import me.him188.ani.app.ui.remote.RemoteConnectionStatus
import me.him188.ani.app.ui.remote.RemoteQrCode
import me.him188.ani.app.ui.remote.RemoteTroubleshootHint
import me.him188.ani.app.ui.remote.TvRemoteControl
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.combine
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground

/**
 * 首次启动引导 (没做过才出现, 做完不再出现, 见 `TvOnboardingGate`): 先是欢迎页 (图标 + 接下来要做哪三步), 然后三步:
 *
 * 1. **检测网络** (本页, 导航里的一页): 分两页 —— Bangumi 连接方式、TMDB 图片 —— 共用同一次检测 (见 [TvOnboardingViewModel]),
 *    按结果推荐怎么连. 每一页第一次测完之前选项不能按 —— 这一步不能跳过; 测完必须选一个才往下走.
 *    用户在手机上存了代理会自动重测.
 * 2. **手机遥控** 与 3. **登录** ([TvOnboardingLoginHost], 盖在主页上的全屏层): 选好连接方式就换成主页, 主页在这一层下面
 *    照常加载, 做完 (或跳过登录) 出来就是加载好的探索页. 检测那一步不提前加载主页: 那一波请求会和检测抢带宽, 把好网络测成连不上.
 *    手机遥控单独一页讲清楚扫码能干什么 —— 只在登录那步给码的话, 不登录的人不知道还有控制台.
 *
 * 检测在欢迎页就开始跑 (ViewModel 一建就测): 那时主页还没加载, 不抢带宽; 用户按「开始设置」时多半已经测完.
 *
 * [onModeChosen]: 连接方式已存好, 参数是登录那一步要不要按「经镜像」处理.
 */
@Composable
fun TvOnboardingPage(
    onModeChosen: (assumeViaMirror: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { TvOnboardingViewModel() }
    val focus = rememberTvFocusScope()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var showProxyDialog by remember { mutableStateOf(false) }
    // 从登录层按返回回来的不再看欢迎页, 直接回到检测网络的最后一页 (登录层这时还盖着, request 非空)
    var welcome by rememberSaveable { mutableStateOf(!TvOnboardingLogin.welcomeSeen) }
    var page by rememberSaveable {
        mutableStateOf(if (TvOnboardingLogin.request.value != null) NetworkPage.Images else NetworkPage.Bangumi)
    }
    // 选 Bangumi 连接方式时判定的「经镜像」(见 TvOnboardingViewModel.chooseMode), 图片那一页选完一起交出去
    var assumeViaMirror by rememberSaveable { mutableStateOf(TvOnboardingLogin.request.value ?: false) }
    // 用户在手机 / 电视设置里存了代理: 关掉弹窗, 让他看到重测
    LaunchedEffect(vm) { vm.proxyChanges.collect { showProxyDialog = false } }
    // 从登录层按返回回来的: 本页回到栈顶后先画出一帧再撤登录层, 不然两者之间会露出底下的主页.
    // 不能只在进场时做一次: 选完连接方式后本页刚出栈、退场动画还没播完就按返回, 导航会把正在退场的这一份
    // 接着用, 进场效果不会再跑 —— 登录层就一直盖着, 再按返回时栈顶已是本页, 导航成了空操作
    val pageForeground = LocalPageIsForeground.current
    // 同理, 「正在选」的防重复标记在本页回到栈顶时要清掉, 不然接着用的这一份按确认没反应
    LaunchedEffect(pageForeground) {
        snapshotFlow { pageForeground.value }.collect { if (it) choosing = false }
    }
    // 等的是本页的切换动画走完 (完全显示), 不是固定一帧: 选完连接方式立刻按返回时, 本页的退场刚走一半就折返,
    // 这时主窗口里还叠着一部分主页, 登录层一撤就露出来
    val navTransition = LocalNavAnimatedContentScope.current.transition
    LaunchedEffect(pageForeground, navTransition) {
        snapshotFlow { pageForeground.value }
            .combine(TvOnboardingLogin.request) { foreground, request -> foreground && request != null }
            .collectLatest { shouldClose ->
                if (!shouldClose) return@collectLatest
                snapshotFlow {
                    navTransition.currentState == EnterExitState.Visible && navTransition.targetState == EnterExitState.Visible
                }.first { it }
                withFrameNanos {}
                if (pageForeground.value) TvOnboardingLogin.request.value = null
            }
    }

    OnboardingSurface(focus, modifier) {
        if (welcome) {
            WelcomeStep(
                focus,
                onStart = {
                    TvOnboardingLogin.welcomeSeen = true
                    welcome = false
                },
            )
            return@OnboardingSurface
        }
        // 两页各自一份组合: 换页时焦点按新一页的规矩重新送 (见 CheckStep)
        key(page) {
            when (page) {
                NetworkPage.Bangumi -> BangumiStep(
                    vm,
                    focus,
                    onChoose = { mode ->
                        // 存设置要一小会儿, 连按两下别存两次
                        if (!choosing) {
                            choosing = true
                            scope.launch {
                                assumeViaMirror = vm.chooseMode(mode)
                                page = NetworkPage.Images
                                choosing = false
                            }
                        }
                    },
                    onOpenProxy = { showProxyDialog = true },
                )

                NetworkPage.Images -> TmdbImagesStep(
                    vm,
                    focus,
                    onChoose = { load ->
                        if (!choosing) {
                            choosing = true
                            scope.launch {
                                vm.chooseTmdbImages(load)
                                onModeChosen(assumeViaMirror)
                            }
                        }
                    },
                    onOpenProxy = { showProxyDialog = true },
                )
            }
        }
    }

    // 检测网络的第二页按返回回第一页, 第一页回欢迎页. 欢迎页: 从登录层返回来的, 本页下面压着主页, 返回键吞掉 ——
    // 不然弹出本页就露出主页, 连接方式都没选就离开了引导; 全新启动时下面没有别的页, 不接, 照常退出应用
    // (下次打开还是从引导开始)
    val navigator = LocalNavigator.current
    BackHandler(enabled = !welcome || navigator.backStack.size > 1) {
        when {
            welcome -> {}
            page == NetworkPage.Images -> page = NetworkPage.Bangumi
            else -> welcome = true
        }
    }

    if (showProxyDialog) {
        ProxyDialog(
            onOpenTvSettings = {
                showProxyDialog = false
                navigator.navigateSettings(SettingsTab.PROXY)
            },
            onDismissRequest = { showProxyDialog = false },
        )
    }
}

/** 登录层要不要显示. 由选完连接方式的那一刻打开, 登录层自己关. */
object TvOnboardingLogin {
    /** 非 null = 显示登录层; 值是「经镜像」判定 (见 [TvOnboardingViewModel.chooseMode]). */
    val request = MutableStateFlow<Boolean?>(null)

    /** 这个进程里看过欢迎页了 (从登录层返回检测那一步时直接到检测). */
    var welcomeSeen = false
}

/**
 * 引导的登录那一步: 全屏盖在主页上 (独立窗口, 焦点与按键都在它里面). 装在 TV 根部 —— 引导页那一页已经出栈了.
 *
 * [onFinished]: 登录完或跳过. [onBack]: 返回键, 回到检测网络那一步.
 */
@Composable
fun TvOnboardingLoginHost(onFinished: () -> Unit, onBack: () -> Unit) {
    val assumeViaMirror = TvOnboardingLogin.request.collectAsState().value ?: return
    // 先介绍手机遥控 (单独一页, 不然新用户会以为那个码只能拿来登录), 再登录. 返回键: 登录 → 手机遥控 → 检测网络
    var onRemote by rememberSaveable { mutableStateOf(true) }
    Dialog(
        onDismissRequest = {
            if (!onRemote) {
                onRemote = true
            } else {
                // 登录层由回到的检测网络页画出来之后撤 (见 TvOnboardingPage), 这里只导航
                onBack()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val vm = viewModel(key = "TvOnboardingLogin-$assumeViaMirror") { TvOnboardingLoginViewModel(assumeViaMirror) }
        val focus = rememberTvFocusScope()
        OnboardingSurface(focus, Modifier) {
            if (onRemote) {
                RemoteStep(focus, onNext = { onRemote = false })
                return@OnboardingSurface
            }
            LoginStep(
                vm,
                focus,
                onFinished = {
                    TvOnboardingLogin.request.value = null
                    onFinished()
                },
            )
        }
    }
}

/** 手机遥控: 讲清楚扫码之后能干什么 (不只是登录), 以及以后去哪再找这个码. */
@Composable
private fun RemoteStep(focus: TvFocusScope, onNext: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(Lang.tv_remote_control_title),
            stringResource(Lang.tv_onboarding_remote_description),
            step = 1,
        )
        Spacer(Modifier.height(SECTION_GAP))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (feature in listOf(
                        Lang.tv_onboarding_remote_feature_search,
                        Lang.tv_onboarding_remote_feature_player,
                        Lang.tv_onboarding_remote_feature_manage,
                        Lang.tv_onboarding_remote_feature_login,
                    )) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(20.dp), tint = scheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(feature), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(Lang.tv_remote_control_panel_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                TvHeroButton(
                    stringResource(Lang.tv_onboarding_next),
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    filled = true,
                    onClick = onNext,
                    onFocused = {},
                    modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.Next),
                )
            }
            Spacer(Modifier.width(COLUMN_GAP))
            PhoneCard(Modifier.width(PHONE_CARD_WIDTH))
        }
    }
    LaunchedEffect(Unit) { focus.request(OnboardingFocus.Next) }
}

/** 欢迎页: 图标 + 标题 + 接下来的三步 + 「开始设置」. 居中. */
@Composable
private fun WelcomeStep(focus: TvFocusScope, onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(Res.drawable.app_icon),
            contentDescription = null,
            Modifier.size(WELCOME_ICON_SIZE).clip(RoundedCornerShape(28.dp)),
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(Lang.tv_onboarding_welcome_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(Lang.tv_onboarding_welcome_intro),
            Modifier.widthIn(max = WELCOME_INTRO_MAX_WIDTH),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(Lang.tv_onboarding_welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WelcomeStepLine(1, stringResource(Lang.tv_onboarding_welcome_step_network))
            WelcomeStepLine(2, stringResource(Lang.tv_onboarding_welcome_step_remote))
            WelcomeStepLine(3, stringResource(Lang.tv_onboarding_welcome_step_login))
        }
        Spacer(Modifier.height(32.dp))
        TvHeroButton(
            stringResource(Lang.tv_onboarding_welcome_start),
            Icons.AutoMirrored.Rounded.ArrowForward,
            filled = true,
            onClick = onStart,
            onFocused = {},
            modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.Welcome),
        )
    }
    focus.InitialFocus(OnboardingFocus.Welcome)
}

@Composable
private fun WelcomeStepLine(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepMarker(number, "", active = true, done = false)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** 两步共用的底: 不透明底色 + 安全边距; 根上挂焦点的用户交互信号. */
@Composable
private fun OnboardingSurface(focus: TvFocusScope, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .tvFocusNavSignal(focus)
                .padding(horizontal = PAGE_HORIZONTAL_PADDING, vertical = PAGE_VERTICAL_PADDING),
        ) {
            content()
        }
    }
}

private enum class OnboardingFocus : TvFocusKey {
    Welcome, Next, Auto, Mirror, Direct, ImagesAuto, ImagesOff, Proxy, Recheck, TvLogin, Skip, Start
}

/** 检测网络那一步的两页, 共用同一次检测 (见 [TvOnboardingViewModel]). */
private enum class NetworkPage { Bangumi, Images }

/** 第一页: Bangumi 走官方还是镜像. */
@Composable
private fun BangumiStep(
    vm: TvOnboardingViewModel,
    focus: TvFocusScope,
    onChoose: (BangumiEndpointMode) -> Unit,
    onOpenProxy: () -> Unit,
) {
    val result by vm.bangumi.result.collectAsStateWithLifecycle()
    val unlocked by vm.bangumi.unlocked.collectAsStateWithLifecycle()
    val recommended = result.recommendedMode
    CheckStep(
        focus,
        title = stringResource(Lang.tv_onboarding_network_title),
        description = stringResource(Lang.tv_onboarding_network_description),
        rows = listOf(
            CheckRow(stringResource(Lang.tv_onboarding_network_origin), ORIGIN_DISPLAY_HOST, result.origin),
            CheckRow(stringResource(Lang.tv_onboarding_network_mirror), result.mirror ?: "—", result.mirrorReachability),
        ),
        completed = result.completed,
        summary = stringResource(
            when {
                !result.completed -> Lang.tv_onboarding_network_summary_checking
                recommended == BangumiEndpointMode.AUTO -> Lang.tv_onboarding_network_summary_origin
                recommended == BangumiEndpointMode.MIRROR -> Lang.tv_onboarding_network_summary_mirror
                else -> Lang.tv_onboarding_network_summary_none
            },
        ),
        summaryIsError = result.completed && recommended == null,
        chooseTitle = stringResource(Lang.tv_onboarding_network_choose),
        options = listOf(
            CheckOption(
                OnboardingFocus.Auto,
                stringResource(Lang.settings_network_bangumi_auto),
                stringResource(Lang.tv_onboarding_mode_auto_description),
            ) { onChoose(BangumiEndpointMode.AUTO) },
            CheckOption(
                OnboardingFocus.Mirror,
                stringResource(Lang.settings_network_bangumi_mirror),
                stringResource(Lang.tv_onboarding_mode_mirror_description),
            ) { onChoose(BangumiEndpointMode.MIRROR) },
            CheckOption(
                OnboardingFocus.Direct,
                stringResource(Lang.settings_network_bangumi_direct),
                stringResource(Lang.tv_onboarding_mode_direct_description),
            ) { onChoose(BangumiEndpointMode.DIRECT) },
        ),
        recommended = when (recommended) {
            BangumiEndpointMode.AUTO -> OnboardingFocus.Auto
            BangumiEndpointMode.MIRROR -> OnboardingFocus.Mirror
            else -> null
        },
        unlocked = unlocked,
        hint = stringResource(Lang.tv_onboarding_network_custom_hint),
        onOpenProxy = onOpenProxy,
        onRecheck = { vm.recheck() },
    )
}

/** 第二页: TMDB 图片 (背景图、剧照) 自动选入口, 还是不加载. */
@Composable
private fun TmdbImagesStep(
    vm: TvOnboardingViewModel,
    focus: TvFocusScope,
    onChoose: (load: Boolean) -> Unit,
    onOpenProxy: () -> Unit,
) {
    val result by vm.tmdbImages.result.collectAsStateWithLifecycle()
    val bangumi by vm.bangumi.result.collectAsStateWithLifecycle()
    val unlocked by vm.tmdbImages.unlocked.collectAsStateWithLifecycle()
    val reachable = result.firstReachable
    val recommended = when {
        !result.completed -> null
        reachable != null -> OnboardingFocus.ImagesAuto
        // 入口都连不上而 Bangumi 连得上: 本机联着网, 是 TMDB 被封了 —— 不加载, 省得每张图都白等一轮
        bangumi.online -> OnboardingFocus.ImagesOff
        else -> null
    }
    CheckStep(
        focus,
        title = stringResource(Lang.tv_onboarding_images_title),
        description = stringResource(Lang.tv_onboarding_images_description),
        rows = result.candidates.take(MAX_ENDPOINT_ROWS).map { (baseUrl, reachability) ->
            CheckRow(EndpointUrls.displayName(baseUrl), null, reachability)
        },
        completed = result.completed,
        summary = when {
            !result.completed -> stringResource(Lang.tv_onboarding_images_summary_checking)
            reachable != null -> stringResource(Lang.tv_onboarding_images_summary_ok, EndpointUrls.displayName(reachable))
            bangumi.online -> stringResource(Lang.tv_onboarding_images_summary_none)
            else -> stringResource(Lang.tv_onboarding_images_summary_offline)
        },
        summaryIsError = result.completed && reachable == null,
        chooseTitle = stringResource(Lang.tv_onboarding_images_choose),
        options = listOf(
            CheckOption(
                OnboardingFocus.ImagesAuto,
                stringResource(Lang.settings_network_endpoint_auto),
                stringResource(Lang.tv_onboarding_images_auto_description),
            ) { onChoose(true) },
            CheckOption(
                OnboardingFocus.ImagesOff,
                stringResource(Lang.settings_network_tmdb_images_disable),
                stringResource(Lang.tv_onboarding_images_off_description),
            ) { onChoose(false) },
        ),
        recommended = recommended,
        unlocked = unlocked,
        hint = stringResource(Lang.tv_onboarding_images_hint),
        onOpenProxy = onOpenProxy,
        onRecheck = { vm.recheck() },
    )
}

/** 检测结果里的一路: 名字、地址 (可以没有), 结论. */
private class CheckRow(val name: String, val host: String?, val reachability: Reachability)

/** 一个选项; [key] 同时是它的焦点锚点. */
private class CheckOption(
    val key: OnboardingFocus,
    val title: String,
    val description: String,
    val onClick: () -> Unit,
)

/**
 * 检测网络那一步的一页: 左边是各路的检测结果与结论, 右边是选项、「设置代理」与「重新检测」.
 *
 * 选项顺序固定 (位置是遥控器上的肌肉记忆), 推荐的那个带标记并在测完时拿到焦点; 第一次测完之前选项按不了.
 */
@Composable
private fun CheckStep(
    focus: TvFocusScope,
    title: String,
    description: String,
    rows: List<CheckRow>,
    completed: Boolean,
    summary: String,
    summaryIsError: Boolean,
    chooseTitle: String,
    options: List<CheckOption>,
    recommended: OnboardingFocus?,
    unlocked: Boolean,
    hint: String,
    onOpenProxy: () -> Unit,
    onRecheck: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        StepHeader(title, description, step = 0)
        Spacer(Modifier.height(SECTION_GAP))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            // 左: 测出来的情况. 各路的状态位恒在 (没出结论时写「检测中」), 结论一段定高, 换状态时版面不动
            Column(Modifier.weight(1f)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) Spacer(Modifier.height(18.dp))
                    ReachabilityRow(row.name, row.host, row.reachability)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        summaryIsError -> scheme.error
                        !completed -> scheme.onSurfaceVariant
                        else -> scheme.onSurface
                    },
                    minLines = 3,
                )
            }
            Spacer(Modifier.width(COLUMN_GAP))
            // 右: 选项
            Column(Modifier.width(OPTIONS_WIDTH)) {
                Text(chooseTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                options.forEachIndexed { index, option ->
                    if (index > 0) Spacer(Modifier.height(OPTION_GAP))
                    ModeOption(
                        option.title,
                        option.description,
                        recommended = option.key == recommended,
                        enabled = unlocked,
                        onClick = option.onClick,
                        modifier = Modifier.tvFocusAnchor(focus, option.key),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvHeroButton(
                        stringResource(Lang.tv_onboarding_proxy),
                        Icons.Rounded.VpnKey,
                        filled = false,
                        onClick = onOpenProxy,
                        onFocused = {},
                        modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.Proxy),
                    )
                    TvHeroButton(
                        stringResource(Lang.tv_onboarding_recheck),
                        Icons.Rounded.Refresh,
                        filled = false,
                        onClick = onRecheck,
                        onFocused = {},
                        modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.Recheck),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }

    // 检测中选项按不了, 焦点先放「设置代理」(等的时候唯一有意义的事); 测完送到推荐的选项, 没有推荐 (都连不上)
    // 送到「重新检测」. 回到这一页 (从登录那步、或从后一页按返回) 时同样送到推荐的选项
    focus.InitialFocus(OnboardingFocus.Proxy)
    LaunchedEffect(completed, recommended) {
        if (!completed) return@LaunchedEffect
        focus.request(recommended ?: OnboardingFocus.Recheck)
    }
}

@Composable
private fun LoginStep(vm: TvOnboardingLoginViewModel, focus: TvFocusScope, onFinished: () -> Unit) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val viaMirror by vm.viaMirror.collectAsStateWithLifecycle()
    val oauth by vm.oauthState.collectAsStateWithLifecycle()
    val selfInfo by vm.selfInfo.collectAsStateWithLifecycle()
    val tvLogin = vm.tvLoginSupported && oauth !is BangumiOAuthManager.State.NotConfigured
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(Lang.tv_onboarding_login_title),
            stringResource(Lang.tv_onboarding_login_description),
            step = 2,
        )
        Spacer(Modifier.height(SECTION_GAP))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.weight(1f)) {
                when {
                    loggedIn -> {
                        val nickname = selfInfo.selfInfo?.nickname
                        Text(
                            if (nickname != null) {
                                stringResource(Lang.tv_onboarding_login_done, nickname)
                            } else {
                                stringResource(Lang.tv_onboarding_login_done_plain)
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            color = scheme.primary,
                        )
                        Spacer(Modifier.height(24.dp))
                        TvHeroButton(
                            stringResource(Lang.tv_onboarding_start),
                            Icons.Rounded.Check,
                            filled = true,
                            onClick = onFinished,
                            onFocused = {},
                            modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.Start),
                        )
                    }

                    viaMirror -> {
                        Text(
                            stringResource(Lang.tv_onboarding_login_via_mirror),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.error,
                        )
                        Spacer(Modifier.height(24.dp))
                        SkipButton(focus, onFinished)
                    }

                    else -> {
                        if (tvLogin) {
                            TvHeroButton(
                                stringResource(Lang.tv_onboarding_login_on_tv),
                                Icons.AutoMirrored.Rounded.Login,
                                filled = true,
                                onClick = vm::startTvLogin,
                                onFocused = {},
                                modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.TvLogin),
                            )
                            Spacer(Modifier.height(8.dp))
                            // 同一个位置三态: 说明 / 等待结果 / 没成功
                            Text(
                                stringResource(
                                    when (oauth) {
                                        is BangumiOAuthManager.State.Authorizing,
                                        is BangumiOAuthManager.State.Exchanging -> Lang.tv_onboarding_login_waiting

                                        is BangumiOAuthManager.State.Failed -> Lang.tv_onboarding_login_failed
                                        else -> Lang.tv_onboarding_login_on_tv_hint
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (oauth is BangumiOAuthManager.State.Failed) scheme.error else scheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                        Text(
                            stringResource(Lang.tv_onboarding_login_phone_hint),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(24.dp))
                        SkipButton(focus, onFinished)
                    }
                }
            }
            Spacer(Modifier.width(COLUMN_GAP))
            PhoneCard(Modifier.width(PHONE_CARD_WIDTH))
        }
    }

    // 登录成功 (电视上或手机上) 后落到「开始使用」
    val target = when {
        loggedIn -> OnboardingFocus.Start
        viaMirror || !tvLogin -> OnboardingFocus.Skip
        else -> OnboardingFocus.TvLogin
    }
    LaunchedEffect(target) { focus.request(target) }
}

@Composable
private fun SkipButton(focus: TvFocusScope, onFinished: () -> Unit) {
    TvHeroButton(
        stringResource(Lang.tv_onboarding_login_skip),
        Icons.AutoMirrored.Rounded.ArrowForward,
        filled = false,
        onClick = onFinished,
        onFocused = {},
        modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.Skip),
    )
}

/** 标题 (左) 与三步的进度 (右, [step] 从 0 起), 下面一行说明占满整宽. */
@Composable
private fun StepHeader(title: String, description: String, step: Int) {
    val labels = listOf(
        stringResource(Lang.tv_onboarding_step_network),
        stringResource(Lang.tv_onboarding_step_remote),
        stringResource(Lang.tv_onboarding_step_login),
    )
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(COLUMN_GAP))
            labels.forEachIndexed { index, label ->
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(horizontal = 12.dp)
                            .width(28.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                StepMarker(index + 1, label, active = index == step, done = index < step)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepMarker(number: Int, label: String, active: Boolean, done: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val lit = active || done
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .background(if (lit) scheme.primary else scheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(16.dp), tint = scheme.onPrimary)
            } else {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (lit) scheme.onPrimary else scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

/** 一路的状态: 名字 + 域名, 下面一行「检测中 / 能连上 · 耗时 / 连不上」. 三态同高. */
@Composable
private fun ReachabilityRow(name: String, host: String?, reachability: Reachability) {
    val scheme = MaterialTheme.colorScheme
    val green = if (scheme.surface.luminance() < 0.5f) CONNECTED_GREEN_DARK else CONNECTED_GREEN_LIGHT
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (host != null) {
                Spacer(Modifier.width(10.dp))
                Text(host, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (reachability) {
                Reachability.Checking -> {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Lang.tv_onboarding_network_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }

                is Reachability.Reachable -> {
                    StatusDot(green)
                    Text(
                        stringResource(Lang.tv_onboarding_network_reachable, reachability.millis.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = green,
                    )
                }

                Reachability.Unreachable -> {
                    StatusDot(scheme.error)
                    Text(
                        stringResource(Lang.tv_onboarding_network_unreachable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    // 与转圈同宽 (14dp), 状态换了字的起点不动
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
    Spacer(Modifier.width(8.dp))
}

/**
 * 连接方式选项: 标题 + 一行说明. 示焦同 [TvHeroButton] (聚焦 = 主题色实底, 字反色; 未聚焦 = 中性灰底).
 * 没测完时 [enabled] = false: 不可聚焦也按不了, 半透明.
 */
@Composable
private fun ModeOption(
    title: String,
    description: String,
    recommended: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 聚焦样式读真实焦点, 理由同 TvHeroButton (一次性的 Focus 事件会丢)
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .onFocusChanged { focused = it.isFocused }
            .tvTouchFocusOnTap(),
        shape = RoundedCornerShape(12.dp),
        color = when {
            focused -> scheme.primary
            dark -> OPTION_COLOR_DARK
            else -> OPTION_COLOR_LIGHT
        },
        contentColor = if (focused) scheme.onPrimary else scheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (recommended) {
                    Spacer(Modifier.width(8.dp))
                    val badgeColor = if (focused) scheme.onPrimary else scheme.primary
                    Text(
                        stringResource(Lang.tv_onboarding_recommended),
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.78f),
            )
        }
    }
}

/** 右侧的手机控制台码: 登录那一步两条路之一 (经镜像时是唯一一条). */
@Composable
private fun PhoneCard(modifier: Modifier = Modifier) {
    val url by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Lang.tv_onboarding_phone_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        RemoteQrCode(url, PHONE_QR_SIZE, PHONE_QR_QUIET_ZONE)
        Spacer(Modifier.height(10.dp))
        RemoteConnectionStatus(url, hostChanged, phoneConnected, MaterialTheme.typography.labelLarge)
        url?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (url != null && !phoneConnected && !hostChanged) {
            Spacer(Modifier.height(6.dp))
            RemoteTroubleshootHint(MaterialTheme.typography.bodySmall, TextAlign.Center)
        }
    }
}

/** 「设置代理」: 扫码到手机控制台填 (电视上打字难), 或者去电视的设置页. 存了代理之后调用方会关掉它并重测. */
@Composable
private fun ProxyDialog(onOpenTvSettings: () -> Unit, onDismissRequest: () -> Unit) {
    val url by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.width(PROXY_DIALOG_WIDTH),
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ) {
            Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteQrCode(url, PHONE_QR_SIZE, PHONE_QR_QUIET_ZONE)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Lang.tv_onboarding_proxy), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    RemoteConnectionStatus(url, hostChanged, phoneConnected, MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(Lang.tv_onboarding_proxy_description), style = MaterialTheme.typography.bodyMedium)
                    url?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    if (url != null && !phoneConnected && !hostChanged) {
                        Spacer(Modifier.height(8.dp))
                        RemoteTroubleshootHint(MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onDismissRequest, Modifier.focusRequester(closeFocus)) {
                            Text(stringResource(Lang.tv_remote_control_close))
                        }
                        TextButton(onOpenTvSettings) { Text(stringResource(Lang.tv_onboarding_proxy_on_tv)) }
                    }
                }
            }
        }
    }
}

/** 官方那一行显示的域名 (检测实际打的是它的 API 子域). */
private const val ORIGIN_DISPLAY_HOST = "bgm.tv"

/** 1080p 电视约 960×540dp: 左右留 48dp、上下 32dp 的安全边距, 两栏之间 40dp. */
private val PAGE_HORIZONTAL_PADDING = 48.dp
private val PAGE_VERTICAL_PADDING = 32.dp
private val SECTION_GAP = 28.dp
private val COLUMN_GAP = 40.dp
private val OPTIONS_WIDTH = 420.dp
private val OPTION_GAP = 8.dp

/** TMDB 图片那一页最多列几个入口: 再多左栏就放不下了 (清单一般只有两三个). */
private const val MAX_ENDPOINT_ROWS = 4
private val PHONE_CARD_WIDTH = 280.dp
private val PHONE_QR_SIZE = 168.dp
private val PHONE_QR_QUIET_ZONE = 18.dp
private val PROXY_DIALOG_WIDTH = 620.dp
private val WELCOME_ICON_SIZE = 120.dp
private val WELCOME_INTRO_MAX_WIDTH = 720.dp

private const val DISABLED_ALPHA = 0.38f

/** 选项未聚焦时的底色: 与 TvHeroButton 主按钮同一档中性灰 (深 / 浅主题各一). */
private val OPTION_COLOR_DARK = Color(0xFF31363D)
private val OPTION_COLOR_LIGHT = Color(0xFFDBE0E6)
