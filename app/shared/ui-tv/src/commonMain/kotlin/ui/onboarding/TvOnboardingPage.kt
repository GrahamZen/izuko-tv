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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.BangumiEndpointMode
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TvPosterConfirmAction
import me.him188.ani.app.data.models.preference.TvVisualEffectsLevel
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.main.LocalTvStartupLogo
import me.him188.ani.app.ui.settings.account.ConvertToLocalProfileDialog
import me.him188.ani.app.ui.settings.tabs.network.MirrorSwitchConsentDialog
import me.him188.ani.app.data.models.preference.EndpointUrls
import me.him188.ani.app.domain.foundation.Reachability
import me.him188.ani.app.domain.profile.SelfCollectionRecords
import me.him188.ani.app.domain.profile.UserProfile
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
import me.him188.ani.app.ui.lang.oauth_bangumi_stage_authorizing
import me.him188.ani.app.ui.lang.oauth_bangumi_stage_exchanging
import me.him188.ani.app.ui.lang.oauth_bangumi_stage_opening
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
import me.him188.ani.app.ui.lang.tv_onboarding_login_local
import me.him188.ani.app.ui.lang.tv_onboarding_login_local_failed
import me.him188.ani.app.ui.lang.tv_onboarding_login_local_hint
import me.him188.ani.app.ui.lang.tv_profile_default_name
import me.him188.ani.app.ui.lang.tv_onboarding_login_on_tv
import me.him188.ani.app.ui.lang.tv_onboarding_login_on_tv_hint
import me.him188.ani.app.ui.lang.tv_onboarding_login_phone_hint
import me.him188.ani.app.ui.lang.tv_onboarding_login_skip
import me.him188.ani.app.ui.lang.tv_onboarding_login_title
import me.him188.ani.app.ui.lang.tv_onboarding_login_via_mirror
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
import me.him188.ani.app.ui.lang.settings_theme_mode_dark
import me.him188.ani.app.ui.lang.settings_theme_mode_light
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_details
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_hero
import me.him188.ani.app.ui.lang.settings_theme_tv_poster_confirm_play
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_balanced
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_full
import me.him188.ani.app.ui.lang.settings_theme_tv_visual_effects_smooth
import me.him188.ani.app.ui.lang.tv_onboarding_step_theme
import me.him188.ani.app.ui.lang.tv_onboarding_theme_blur
import me.him188.ani.app.ui.lang.tv_onboarding_theme_colors
import me.him188.ani.app.ui.lang.tv_onboarding_theme_confirm
import me.him188.ani.app.ui.lang.tv_onboarding_theme_confirm_details_hint
import me.him188.ani.app.ui.lang.tv_onboarding_theme_confirm_hero_hint
import me.him188.ani.app.ui.lang.tv_onboarding_theme_confirm_play_hint
import me.him188.ani.app.ui.lang.tv_onboarding_theme_description
import me.him188.ani.app.ui.lang.tv_onboarding_theme_off
import me.him188.ani.app.ui.lang.tv_onboarding_theme_on
import me.him188.ani.app.ui.lang.tv_onboarding_theme_title
import me.him188.ani.app.ui.lang.tv_onboarding_welcome_step_theme
import me.him188.ani.app.ui.lang.tv_remote_control_panel_hint
import me.him188.ani.app.ui.lang.tv_remote_control_title
import me.him188.ani.app.ui.remote.CONNECTED_GREEN_DARK
import me.him188.ani.app.ui.remote.CONNECTED_GREEN_LIGHT
import me.him188.ani.app.ui.remote.RemoteConnectionStatus
import me.him188.ani.app.ui.remote.RemoteQrCode
import me.him188.ani.app.ui.remote.RemoteTroubleshootHint
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.utils.platform.currentTimeMillis
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.combine
import me.him188.ani.app.ui.foundation.navigation.LocalPageIsForeground

/**
 * 首次启动引导 (没做过才出现, 做完不再出现, 见 `TvOnboardingGate`): 先是欢迎页 (图标 + 接下来要做哪四步), 然后四步:
 *
 * 1. **检测网络** (本页, 导航里的一页): 分两页 —— Bangumi 连接方式、TMDB 图片 —— 共用同一次检测 (见 [TvOnboardingViewModel]),
 *    按结果推荐怎么连. 每一页第一次测完之前选项不能按 —— 这一步不能跳过; 测完必须选一个才往下走.
 *    用户在手机上存了代理会自动重测.
 * 2. **手机遥控**、3. **登录** 与 4. **外观与操作** ([TvOnboardingLoginHost], 盖在主页上的全屏层): 选好连接方式就换成主页, 主页在这一层下面
 *    照常加载, 做完出来就是加载好的探索页 (最后一步选的外观当场生效, 下面的主页跟着变). 检测那一步不提前加载主页: 那一波请求会和检测抢带宽, 把好网络测成连不上.
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
    // 已登录的人选了用镜像, 等他在询问里回答 (见 BangumiMirrorConsent)
    var askMirror by remember { mutableStateOf<BangumiEndpointMode?>(null) }
    askMirror?.let { mode ->
        fun choose(allowCredentials: Boolean?, logoutFirst: Boolean) {
            askMirror = null
            choosing = true
            scope.launch {
                if (logoutFirst) vm.logout()
                assumeViaMirror = vm.chooseMode(mode, allowCredentials)
                page = NetworkPage.Images
                choosing = false
            }
        }
        MirrorSwitchConsentDialog(
            automatic = false,
            onAllow = { choose(allowCredentials = true, logoutFirst = false) },
            onLogoutAndSwitch = { choose(allowCredentials = null, logoutFirst = true) },
            onDismissRequest = { askMirror = null },
        )
    }
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
                                // 已登录的人改用镜像: 先问 (见 BangumiMirrorConsent), 答完再存
                                if (vm.mirrorConsentNeeded(mode)) {
                                    askMirror = mode
                                } else {
                                    assumeViaMirror = vm.chooseMode(mode)
                                    page = NetworkPage.Images
                                }
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

/**
 * 登录层要不要显示. 首次引导时由选完连接方式的那一刻打开; 切进一个新建的 Bangumi 用户时也打开 (只有登录这一步, 见 [loginOnly]).
 * 登录层自己关.
 */
object TvOnboardingLogin {
    /** 非 null = 显示登录层; 值是「经镜像」判定 (见 [TvOnboardingViewModel.chooseMode]). */
    val request = MutableStateFlow<Boolean?>(null)

    /**
     * 新用户的登录: 手机遥控已经介绍过 (整机的首次引导里), 只剩登录这一步, 不显示三步进度; 返回键等于跳过.
     */
    var loginOnly by mutableStateOf(false)

    /** 这个进程里看过欢迎页了 (从登录层返回检测那一步时直接到检测). */
    var welcomeSeen = false
}

/**
 * 引导的后三步 (手机遥控、登录、外观与操作): 全屏盖在主页上 (独立窗口, 焦点与按键都在它里面). 装在 TV 根部 —— 引导页那一页已经出栈了.
 *
 * [onFinished]: 外观与操作那一步按了「开始使用」. [onBack]: 首次引导里的返回键, 回到检测网络那一步.
 * [onNewUserFinished]: 新用户那一步 ([TvOnboardingLogin.loginOnly]) 登录完或跳过 (返回键也算跳过).
 */
@Composable
fun TvOnboardingLoginHost(onFinished: () -> Unit, onBack: () -> Unit, onNewUserFinished: () -> Unit) {
    val assumeViaMirror = TvOnboardingLogin.request.collectAsState().value ?: return
    val loginOnly = TvOnboardingLogin.loginOnly
    val finishNewUser = {
        TvOnboardingLogin.request.value = null
        TvOnboardingLogin.loginOnly = false
        onNewUserFinished()
    }
    // 先介绍手机遥控 (单独一页, 不然新用户会以为那个码只能拿来登录), 再登录, 最后外观与操作.
    // 返回键: 外观与操作 → 登录 → 手机遥控 → 检测网络. 新用户只有登录这一步 (外观与操作是整机的设置, 首次引导时选过), 返回键等于跳过
    var layerStep by rememberSaveable { mutableStateOf(if (loginOnly) LayerStep.Login else LayerStep.Remote) }
    // 登录那一步选了「不登录，收藏存在这台电视上」(值 = 要不要清掉之前登录留下的记录): 改成本地用户要重启应用, 等外观与操作走完再改
    var pendingLocal by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // 首次引导里只有 1 号
    val defaultName = stringResource(Lang.tv_profile_default_name, UserProfile.PRIMARY_ID)
    Dialog(
        onDismissRequest = {
            when {
                loginOnly -> finishNewUser()
                layerStep == LayerStep.Theme -> {
                    pendingLocal = null
                    layerStep = LayerStep.Login
                }
                layerStep == LayerStep.Login -> layerStep = LayerStep.Remote
                // 登录层由回到的检测网络页画出来之后撤 (见 TvOnboardingPage), 这里只导航
                else -> onBack()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val vm = viewModel(key = "TvOnboardingLogin-$assumeViaMirror") { TvOnboardingLoginViewModel(assumeViaMirror) }
        val focus = rememberTvFocusScope()
        OnboardingSurface(focus, Modifier) {
            when (layerStep) {
                LayerStep.Remote -> RemoteStep(focus, onNext = { layerStep = LayerStep.Login })
                LayerStep.Login -> LoginStep(
                    vm,
                    focus,
                    showSteps = !loginOnly,
                    onNext = { if (loginOnly) finishNewUser() else layerStep = LayerStep.Theme },
                    onUseLocal = if (loginOnly) {
                        null
                    } else {
                        { clearRecords ->
                            pendingLocal = clearRecords
                            layerStep = LayerStep.Theme
                        }
                    },
                )
                LayerStep.Theme -> ThemeStep(
                    focus,
                    onUpdate = vm::updateTheme,
                    onFinished = {
                        val clearRecords = pendingLocal
                        if (clearRecords == null) {
                            TvOnboardingLogin.request.value = null
                            onFinished()
                        } else {
                            // 改成本地用户会重启应用: 先记下引导走完 (不撤登录层, 免得重启前露出主页一下); 没改成就回到登录那一步看原因
                            onFinished()
                            vm.useLocal(clearRecords, defaultName, onFailed = {
                                pendingLocal = null
                                layerStep = LayerStep.Login
                            })
                        }
                    },
                )
            }
        }
    }
}

/** 登录层 (盖在主页上) 的三步. */
private enum class LayerStep { Remote, Login, Theme }

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

/** 欢迎页: 图标 + 标题 + 接下来的四步 + 「开始设置」. 居中; 间距按 540dp 高排满 (四步再加一行就放不下按钮). */
@Composable
private fun WelcomeStep(focus: TvFocusScope, onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // 冷启动的启动页会把它的图标移到这里再撤 (见 TvStartupLogoState.handOffToWelcome): 报位置, 它落地之前本页的图标先不画
    val startupLogo = LocalTvStartupLogo.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(Res.drawable.app_icon),
            contentDescription = null,
            Modifier.size(WELCOME_ICON_SIZE)
                .onGloballyPositioned { if (startupLogo != null) startupLogo.welcomeIcon = it.boundsInRoot() }
                .graphicsLayer { alpha = if (startupLogo?.coversWelcomeIcon == true) 0f else 1f }
                .clip(RoundedCornerShape(28.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Lang.tv_onboarding_welcome_title), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Lang.tv_onboarding_welcome_intro),
            Modifier.widthIn(max = WELCOME_INTRO_MAX_WIDTH),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(Lang.tv_onboarding_welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WelcomeStepLine(1, stringResource(Lang.tv_onboarding_welcome_step_network))
            WelcomeStepLine(2, stringResource(Lang.tv_onboarding_welcome_step_remote))
            WelcomeStepLine(3, stringResource(Lang.tv_onboarding_welcome_step_login))
            WelcomeStepLine(4, stringResource(Lang.tv_onboarding_welcome_step_theme))
        }
        Spacer(Modifier.height(24.dp))
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
internal fun OnboardingSurface(focus: TvFocusScope, modifier: Modifier, content: @Composable () -> Unit) {
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
    Welcome, Next, Auto, Mirror, Direct, ImagesAuto, ImagesOff, Proxy, Recheck, TvLogin, Skip, Local, Start,
    ThemeHero, ThemePlay, ThemeDetails, ThemeDone,
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
    val deadline by vm.bangumi.deadlineMillis.collectAsStateWithLifecycle()
    val secondsLeft = rememberSecondsLeft(deadline, counting = !result.completed)
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
        summary = if (!result.completed) {
            stringResource(Lang.tv_onboarding_network_summary_checking, secondsLeft)
        } else {
            stringResource(
                when (recommended) {
                    BangumiEndpointMode.AUTO -> Lang.tv_onboarding_network_summary_origin
                    BangumiEndpointMode.MIRROR -> Lang.tv_onboarding_network_summary_mirror
                    else -> Lang.tv_onboarding_network_summary_none
                },
            )
        },
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
    val deadline by vm.tmdbImages.deadlineMillis.collectAsStateWithLifecycle()
    val secondsLeft = rememberSecondsLeft(deadline, counting = !result.completed)
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
            !result.completed -> stringResource(Lang.tv_onboarding_images_summary_checking, secondsLeft)
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

/**
 * 离 [deadlineMillis] 还有几秒 (见 [onboardingSecondsLeft]); [counting] 时每跨过一个整秒刷新一次.
 * 检测各路并行、各自封顶, 这个数就是「最多再等多久」.
 */
@Composable
private fun rememberSecondsLeft(deadlineMillis: Long, counting: Boolean): Int {
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(deadlineMillis, counting) {
        if (!counting) return@LaunchedEffect
        while (true) {
            now = currentTimeMillis()
            // 等到显示的秒数该变的那一刻再刷新
            val untilNextSecond = (deadlineMillis - now) % 1000
            delay(if (untilNextSecond <= 0) 1000 else untilNextSecond)
        }
    }
    return onboardingSecondsLeft(deadlineMillis, now)
}

/** 向上取整到秒, 最少 1 (到点还没出结论时显示「1 秒」, 不显示 0 或负数). */
internal fun onboardingSecondsLeft(deadlineMillis: Long, nowMillis: Long): Int =
    ((deadlineMillis - nowMillis + 999) / 1000).coerceAtLeast(1).toInt()

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
private fun LoginStep(
    vm: TvOnboardingLoginViewModel,
    focus: TvFocusScope,
    showSteps: Boolean,
    onNext: () -> Unit,
    /** 选了「不登录，收藏存在这台电视上」(参数: 要不要清掉之前登录留下的记录); `null` = 不给这个选项. */
    onUseLocal: ((clearRecords: Boolean) -> Unit)?,
) {
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
            step = if (showSteps) 2 else null,
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
                            stringResource(Lang.tv_onboarding_next),
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            filled = true,
                            onClick = onNext,
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
                        SkipOrLocal(vm, focus, onNext, onUseLocal)
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
                            // 同一个位置三态: 说明 / 授权进行到哪一步 (打开登录页 / 等授权 / 换凭证) / 没成功
                            Text(
                                stringResource(
                                    when (oauth.stage) {
                                        BangumiOAuthManager.Stage.OpeningBrowser -> Lang.oauth_bangumi_stage_opening
                                        BangumiOAuthManager.Stage.AwaitingAuthorization -> Lang.oauth_bangumi_stage_authorizing
                                        BangumiOAuthManager.Stage.Exchanging -> Lang.oauth_bangumi_stage_exchanging
                                        null -> if (oauth is BangumiOAuthManager.State.Failed) {
                                            Lang.tv_onboarding_login_failed
                                        } else {
                                            Lang.tv_onboarding_login_on_tv_hint
                                        }
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
                        SkipOrLocal(vm, focus, onNext, onUseLocal)
                    }
                }
            }
            Spacer(Modifier.width(COLUMN_GAP))
            PhoneCard(Modifier.width(PHONE_CARD_WIDTH))
        }
    }

    // 登录成功 (电视上或手机上) 后落到「下一步」
    val target = when {
        loggedIn -> OnboardingFocus.Start
        viaMirror || !tvLogin -> OnboardingFocus.Skip
        else -> OnboardingFocus.TvLogin
    }
    LaunchedEffect(target) { focus.request(target) }
}

/**
 * 「跳过」(先不登录, 和原来一样匿名浏览, 以后可以再登录), 旁边是「不登录，收藏存在这台电视上」: 选了交给 [onUseLocal], 引导走完
 * 再把 1 号改成本地用户 (见 [me.him188.ani.app.domain.profile.LocalProfileConversion], 应用随后重启). 从老版本升级上来的库里可能
 * 留着之前登录的收藏记录, 有就先问留不留 (同设置里改成本地用户). 默认焦点不变 (还在登录或跳过上).
 */
@Composable
private fun SkipOrLocal(
    vm: TvOnboardingLoginViewModel,
    focus: TvFocusScope,
    onSkip: () -> Unit,
    onUseLocal: ((clearRecords: Boolean) -> Unit)?,
) {
    val localShown = onUseLocal != null && vm.localOffered
    val scope = rememberCoroutineScope()
    var leftover by remember { mutableStateOf<SelfCollectionRecords.Counts?>(null) }
    val useLocal = { clearRecords: Boolean -> onUseLocal?.invoke(clearRecords) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SkipButton(focus, onSkip)
        if (localShown) {
            TvHeroButton(
                stringResource(Lang.tv_onboarding_login_local),
                Icons.Rounded.Tv,
                filled = false,
                onClick = {
                    scope.launch {
                        val records = vm.leftoverRecords()
                        if (records.isEmpty) useLocal(false) else leftover = records
                    }
                },
                onFocused = {},
                modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.Local),
            )
        }
    }
    if (localShown) {
        Spacer(Modifier.height(8.dp))
        val error by vm.localError.collectAsStateWithLifecycle()
        Text(
            error?.let { stringResource(Lang.tv_onboarding_login_local_failed, it) } ?: stringResource(Lang.tv_onboarding_login_local_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    leftover?.let { records ->
        ConvertToLocalProfileDialog(
            records,
            onConvert = { clearRecords ->
                leftover = null
                useLocal(clearRecords)
            },
            onDismissRequest = { leftover = null },
        )
    }
}

@Composable
private fun SkipButton(focus: TvFocusScope, onNext: () -> Unit) {
    TvHeroButton(
        stringResource(Lang.tv_onboarding_login_skip),
        Icons.AutoMirrored.Rounded.ArrowForward,
        filled = false,
        onClick = onNext,
        onFocused = {},
        modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.Skip),
    )
}

/**
 * 外观与操作 (最后一步): 海报上按确定做什么 (三档各一张示意图), 下面一排颜色 / 模糊背景 / 视觉效果. 按确定当场写设置,
 * 这一层与下面的主页跟着变. 模糊背景只对「先看简介」有用, 另两档时那一组隐去 (位置留着, 旁边的组不挪).
 */
@Composable
internal fun ThemeStep(
    focus: TvFocusScope,
    onUpdate: (ThemeSettings.() -> ThemeSettings) -> Unit,
    onFinished: () -> Unit,
) {
    val theme = LocalThemeSettings.current
    val confirm = theme.tvPosterConfirm
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(Lang.tv_onboarding_theme_title),
            stringResource(Lang.tv_onboarding_theme_description),
            step = 3,
        )
        Spacer(Modifier.height(SECTION_GAP))
        Text(stringResource(Lang.tv_onboarding_theme_confirm), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CONFIRM_CARD_GAP)) {
            for (action in TvPosterConfirmAction.entries) {
                ConfirmOption(
                    action,
                    selected = action == confirm,
                    onClick = { onUpdate { copy(tvPosterConfirm = action) } },
                    modifier = Modifier.weight(1f).tvFocusAnchor(focus, action.focusKey),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CHOICE_GROUP_GAP),
            verticalAlignment = Alignment.Bottom,
        ) {
            // 电视上「跟随系统」永远是浅色, 这里只给两档
            ChoiceGroup(
                stringResource(Lang.tv_onboarding_theme_colors),
                options = listOf(DarkMode.DARK, DarkMode.LIGHT),
                selected = if (theme.darkMode == DarkMode.DARK) DarkMode.DARK else DarkMode.LIGHT,
                text = {
                    stringResource(if (it == DarkMode.DARK) Lang.settings_theme_mode_dark else Lang.settings_theme_mode_light)
                },
                onSelect = { mode -> onUpdate { copy(darkMode = mode) } },
            )
            ChoiceGroup(
                stringResource(Lang.tv_onboarding_theme_blur),
                options = listOf(true, false),
                selected = theme.tvHeroBlurBackdrop,
                text = { stringResource(if (it) Lang.tv_onboarding_theme_on else Lang.tv_onboarding_theme_off) },
                onSelect = { on -> onUpdate { copy(tvHeroBlurBackdrop = on) } },
                visible = confirm == TvPosterConfirmAction.Hero,
            )
            ChoiceGroup(
                stringResource(Lang.settings_theme_tv_visual_effects),
                options = TvVisualEffectsLevel.entries,
                selected = theme.visualEffects,
                text = {
                    stringResource(
                        when (it) {
                            TvVisualEffectsLevel.Smooth -> Lang.settings_theme_tv_visual_effects_smooth
                            TvVisualEffectsLevel.Balanced -> Lang.settings_theme_tv_visual_effects_balanced
                            TvVisualEffectsLevel.Full -> Lang.settings_theme_tv_visual_effects_full
                        },
                    )
                },
                onSelect = { level -> onUpdate { copy(tvVisualEffects = level) } },
            )
            Spacer(Modifier.weight(1f))
            TvHeroButton(
                stringResource(Lang.tv_onboarding_start),
                Icons.Rounded.Check,
                filled = true,
                onClick = onFinished,
                onFocused = {},
                modifier = Modifier.tvFocusAnchor(focus, OnboardingFocus.ThemeDone),
            )
        }
    }
    LaunchedEffect(Unit) { focus.request(confirm.focusKey) }
}

private val TvPosterConfirmAction.focusKey: OnboardingFocus
    get() = when (this) {
        TvPosterConfirmAction.Hero -> OnboardingFocus.ThemeHero
        TvPosterConfirmAction.Play -> OnboardingFocus.ThemePlay
        TvPosterConfirmAction.Details -> OnboardingFocus.ThemeDetails
    }

/** 「海报上按确定」一档: 示意图 + 名字 + 两行说明. 示焦同 [ModeOption]; 选中的只在名字后面打勾. */
@Composable
private fun ConfirmOption(
    action: TvPosterConfirmAction,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .tvTouchFocusOnTap(),
        shape = RoundedCornerShape(12.dp),
        color = when {
            focused -> scheme.primary
            dark -> OPTION_COLOR_DARK
            else -> OPTION_COLOR_LIGHT
        },
        contentColor = if (focused) scheme.onPrimary else scheme.onSurface,
    ) {
        Column(Modifier.padding(10.dp)) {
            PosterConfirmIllustration(action, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        when (action) {
                            TvPosterConfirmAction.Hero -> Lang.settings_theme_tv_poster_confirm_hero
                            TvPosterConfirmAction.Play -> Lang.settings_theme_tv_poster_confirm_play
                            TvPosterConfirmAction.Details -> Lang.settings_theme_tv_poster_confirm_details
                        },
                    ),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        Modifier.size(20.dp),
                        tint = if (focused) scheme.onPrimary else scheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(
                    when (action) {
                        TvPosterConfirmAction.Hero -> Lang.tv_onboarding_theme_confirm_hero_hint
                        TvPosterConfirmAction.Play -> Lang.tv_onboarding_theme_confirm_play_hint
                        TvPosterConfirmAction.Details -> Lang.tv_onboarding_theme_confirm_details_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.78f),
                minLines = 2,
                maxLines = 2,
            )
        }
    }
}

/**
 * 按确定之后看到的画面, 画成简图 (跟深浅色走): 先看简介 = 右上大图、左边标题与简介、底下一排海报;
 * 直接播放 = 整屏画面、中间播放键、底下进度条; 直接进详情页 = 封面、标题、两个按钮、底下一排分集.
 */
@Composable
private fun PosterConfirmIllustration(action: TvPosterConfirmAction, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val screen = scheme.background
    val ink = scheme.onBackground
    val accent = scheme.primary
    val image = Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
    Canvas(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
        drawRect(screen)
        when (action) {
            TvPosterConfirmAction.Hero -> drawHeroSketch(screen, ink, image)
            TvPosterConfirmAction.Play -> drawPlayerSketch(image)
            TvPosterConfirmAction.Details -> drawDetailsSketch(ink, accent, image)
        }
    }
}

private fun DrawScope.drawHeroSketch(screen: Color, ink: Color, image: Brush) {
    // 右上大图, 左缘与下缘渐隐到底色. 两层渐隐铺满整张画布 (渐变外侧就是底色), 盖住大图边缘的抗锯齿细边
    drawRect(image, Offset(size.width * 0.35f, 0f), Size(size.width * 0.65f, size.height * 0.62f), alpha = 0.85f)
    drawRect(Brush.horizontalGradient(listOf(screen, Color.Transparent), startX = size.width * 0.35f, endX = size.width * 0.6f))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, screen), startY = size.height * 0.4f, endY = size.height * 0.62f))
    sketchBar(0.06f, 0.16f, 0.30f, 0.07f, ink.copy(alpha = 0.9f))
    sketchBar(0.06f, 0.29f, 0.34f, 0.035f, ink.copy(alpha = 0.4f))
    sketchBar(0.06f, 0.36f, 0.30f, 0.035f, ink.copy(alpha = 0.4f))
    sketchBar(0.06f, 0.43f, 0.22f, 0.035f, ink.copy(alpha = 0.4f))
    // 一排竖版海报, 第一张是按了确定的那张
    val posterHeight = 0.30f
    val posterWidth = posterHeight * 2f / 3f * size.height / size.width
    sketchCardRow(0.06f, 0.66f, posterWidth, posterHeight, count = 6, ink, image)
}

private fun DrawScope.drawPlayerSketch(image: Brush) {
    drawRect(image, alpha = 0.85f)
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
            startY = size.height * 0.55f,
            endY = size.height,
        ),
    )
    val center = Offset(size.width * 0.5f, size.height * 0.42f)
    val radius = size.height * 0.13f
    drawCircle(Color.Black.copy(alpha = 0.35f), radius, center)
    drawPath(
        Path().apply {
            moveTo(center.x - radius * 0.32f, center.y - radius * 0.45f)
            lineTo(center.x + radius * 0.5f, center.y)
            lineTo(center.x - radius * 0.32f, center.y + radius * 0.45f)
            close()
        },
        Color.White,
    )
    sketchBar(0.06f, 0.70f, 0.28f, 0.06f, Color.White.copy(alpha = 0.9f))
    sketchBar(0.06f, 0.84f, 0.88f, 0.025f, Color.White.copy(alpha = 0.35f))
    sketchBar(0.06f, 0.84f, 0.35f, 0.025f, Color.White)
    drawCircle(Color.White, size.height * 0.035f, Offset(size.width * 0.41f, size.height * 0.8525f))
}

private fun DrawScope.drawDetailsSketch(ink: Color, accent: Color, image: Brush) {
    // 左边竖版封面
    val coverHeight = 0.5f
    val coverWidth = coverHeight * 2f / 3f * size.height / size.width
    drawRoundRect(
        image,
        Offset(size.width * 0.06f, size.height * 0.1f),
        Size(size.width * coverWidth, size.height * coverHeight),
        CornerRadius(size.height * 0.03f),
    )
    val textX = 0.06f + coverWidth + 0.05f
    sketchBar(textX, 0.12f, 0.32f, 0.07f, ink.copy(alpha = 0.9f))
    sketchBar(textX, 0.24f, 0.20f, 0.035f, ink.copy(alpha = 0.4f))
    sketchBar(textX, 0.31f, 0.40f, 0.035f, ink.copy(alpha = 0.4f))
    sketchBar(textX, 0.38f, 0.34f, 0.035f, ink.copy(alpha = 0.4f))
    sketchBar(textX, 0.49f, 0.14f, 0.09f, accent)
    sketchBar(textX + 0.16f, 0.49f, 0.12f, 0.09f, ink.copy(alpha = 0.2f))
    // 一排横版分集
    val episodeHeight = 0.18f
    val episodeWidth = episodeHeight * 16f / 9f * size.height / size.width
    sketchCardRow(0.06f, 0.72f, episodeWidth, episodeHeight, count = 4, ink, image)
}

/** 一条圆头横条, 坐标与尺寸都是画布的比例. */
private fun DrawScope.sketchBar(x: Float, y: Float, width: Float, height: Float, color: Color) {
    drawRoundRect(
        color,
        Offset(size.width * x, size.height * y),
        Size(size.width * width, size.height * height),
        CornerRadius(size.height * height / 2),
    )
}

/** 一排卡片 (坐标与尺寸是画布的比例): 第一张有图、带聚焦框, 其余是空卡. 超出右缘的被画布裁掉. */
private fun DrawScope.sketchCardRow(x: Float, y: Float, width: Float, height: Float, count: Int, ink: Color, image: Brush) {
    val gap = 0.025f
    val corner = CornerRadius(size.height * 0.025f)
    val cardSize = Size(size.width * width, size.height * height)
    repeat(count) { index ->
        val topLeft = Offset(size.width * (x + index * (width + gap)), size.height * y)
        if (index == 0) {
            drawRoundRect(image, topLeft, cardSize, corner)
            drawRoundRect(ink.copy(alpha = 0.9f), topLeft, cardSize, corner, style = Stroke(1.5.dp.toPx()))
        } else {
            drawRoundRect(ink.copy(alpha = 0.16f), topLeft, cardSize, corner)
        }
    }
}

/** 一组小选项: 上面一行名字, 下面一排胶囊. [visible] = false 时整组隐去且不可聚焦, 位置照留. */
@Composable
private fun <T> ChoiceGroup(
    label: String,
    options: List<T>,
    selected: T,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    visible: Boolean = true,
) {
    Column(Modifier.alpha(if (visible) 1f else 0f)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                ChoiceChip(text(option), selected = option == selected, enabled = visible, onClick = { onSelect(option) })
            }
        }
    }
}

/** 胶囊选项: 示焦同 [ModeOption]; 选中的前面打勾 (勾的位置一直留着, 换了选中宽度不变). */
@Composable
private fun ChoiceChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .tvTouchFocusOnTap(),
        shape = CircleShape,
        color = when {
            focused -> scheme.primary
            dark -> OPTION_COLOR_DARK
            else -> OPTION_COLOR_LIGHT
        },
        contentColor = if (focused) scheme.onPrimary else scheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                Modifier.size(18.dp).alpha(if (selected) 1f else 0f),
                tint = if (focused) scheme.onPrimary else scheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** 标题 (左) 与四步的进度 (右, [step] 从 0 起; `null` = 不显示进度), 下面一行说明占满整宽. */
@Composable
private fun StepHeader(title: String, description: String, step: Int?) {
    val labels = listOf(
        stringResource(Lang.tv_onboarding_step_network),
        stringResource(Lang.tv_onboarding_step_remote),
        stringResource(Lang.tv_onboarding_step_login),
        stringResource(Lang.tv_onboarding_step_theme),
    )
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(COLUMN_GAP))
            if (step != null) labels.forEachIndexed { index, label ->
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

/**
 * 「设置代理」: 扫码到手机控制台填 (电视上打字难), 或者去电视的设置页. 存了代理之后调用方会关掉它并重测.
 * 版式同 Web 控制台的启动弹窗 (TvRemoteControlDialog): 左码右字, 外壳是 [AniCenteredPanelDialog].
 */
@Composable
private fun ProxyDialog(onOpenTvSettings: () -> Unit, onDismissRequest: () -> Unit) {
    val url by TvRemoteControl.url.collectAsState()
    val hostChanged by TvRemoteControl.hostChanged.collectAsState()
    val phoneConnected by TvRemoteControl.phoneConnected.collectAsState()
    LaunchedEffect(Unit) { TvRemoteControl.refreshAddress() }

    val scheme = MaterialTheme.colorScheme
    AniCenteredPanelDialog(
        onDismissRequest = onDismissRequest,
        heightFraction = null,
        maxWidth = PROXY_DIALOG_WIDTH,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteQrCode(url, PHONE_QR_SIZE, PHONE_QR_QUIET_ZONE)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Lang.tv_onboarding_proxy), style = MaterialTheme.typography.titleLarge)
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
                    AniFocusActionButton(onDismissRequest, Modifier.tvWindowInitialFocus()) {
                        Text(stringResource(Lang.tv_remote_control_close), style = MaterialTheme.typography.labelLarge)
                    }
                    AniFocusActionButton(onOpenTvSettings) {
                        Text(stringResource(Lang.tv_onboarding_proxy_on_tv), style = MaterialTheme.typography.labelLarge)
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
private val CONFIRM_CARD_GAP = 16.dp
private val CHOICE_GROUP_GAP = 24.dp

private const val DISABLED_ALPHA = 0.38f

/** 选项未聚焦时的底色: 与 TvHeroButton 主按钮同一档中性灰 (深 / 浅主题各一). */
private val OPTION_COLOR_DARK = Color(0xFF31363D)
private val OPTION_COLOR_LIGHT = Color(0xFFDBE0E6)
