/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.tv

import android.content.pm.PackageManager
import me.him188.ani.app.ui.foundation.tv.LocalTvOpenActionPanel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import me.him188.ani.app.ui.foundation.AniImageLoadSuccess
import com.kmpalette.palette.graphics.Palette
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeRequest
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.navigation.BangumiAuthorizeRedirect
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.AppTerminator
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.subject.person.LocalPeoplePreviewRows
import me.him188.ani.app.ui.subject.person.TvPeoplePreviewRows
import me.him188.ani.app.ui.foundation.LocalTvBackLongPressHost
import me.him188.ani.app.ui.foundation.LocalTvPageRefreshHost
import me.him188.ani.app.ui.foundation.LocalTvPageAdjustHost
import me.him188.ani.app.ui.foundation.LocalTvPageShuffleHost
import me.him188.ani.app.ui.foundation.LocalTvPlayLongPressHost
import me.him188.ani.app.ui.foundation.TV_PLAY_KEYS
import me.him188.ani.app.ui.foundation.TvBackLongPressHandler
import me.him188.ani.app.ui.foundation.TvBackLongPressHost
import me.him188.ani.app.ui.foundation.TvKeyLongPressHandler
import me.him188.ani.app.ui.foundation.tv.LocalTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.ProvideTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TvPosterWallTheme
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.rememberTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.tvNavKeyInterceptor
import me.him188.ani.app.ui.foundation.tv.tvTouchKeyboardMode
import me.him188.ani.app.ui.foundation.TvKeyLongPressHost
import me.him188.ani.app.ui.foundation.TvPageActionHost
import me.him188.ani.app.ui.foundation.TvPageAdjustHost
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionEntry
import me.him188.ani.app.data.models.preference.TvLongPressAction
import me.him188.ani.app.data.models.preference.TvScheduleLayout
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tvKeyLongPressInterceptor
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.playback_session_none
import me.him188.ani.app.ui.main.LocalTvAdjustWindows
import me.him188.ani.app.ui.main.TvAdjustWindowHost
import me.him188.ani.app.ui.main.TvAdjustWindows
import me.him188.ani.app.ui.main.TvMarkedDoneCacheDeletionToasts
import me.him188.ani.app.ui.main.TvMirrorConsentHost
import me.him188.ani.app.ui.main.TvQuickActionMenu
import me.him188.ani.app.ui.main.LocalTvStartupLogo
import me.him188.ani.app.ui.main.TvStartupLogo
import me.him188.ani.app.ui.main.TvStartupLogoHost
import me.him188.ani.app.ui.main.tvStartupLogoColors
import me.him188.ani.app.ui.main.TvUpNextStore
import me.him188.ani.app.ui.subject.details.layout.TvDetailsTheme
import me.him188.ani.app.ui.subject.episode.RetainedPlaybackSessionHolder
import me.him188.ani.app.ui.subject.episode.rememberRetainedPlaybackNoticeTexts
import me.him188.ani.app.ui.exploration.ExplorationPageVariant
import me.him188.ani.app.ui.exploration.LocalExplorationPageVariant
import me.him188.ani.app.ui.exploration.TvExplorationPage
import me.him188.ani.app.ui.exploration.schedule.LocalSchedulePageVariant
import me.him188.ani.app.ui.exploration.schedule.SchedulePageVariant
import me.him188.ani.app.ui.exploration.schedule.TvSchedulePage
import me.him188.ani.app.ui.exploration.search.LocalSearchPageVariant
import me.him188.ani.app.ui.exploration.search.SearchPageVariant
import me.him188.ani.app.ui.exploration.search.TvSearchPage
import me.him188.ani.app.ui.main.LocalMainScreenShellVariant
import me.him188.ani.app.ui.main.MainScreenShellVariant
import me.him188.ani.app.ui.main.TvMainScreenLayout
import me.him188.ani.app.ui.onboarding.TvOnboardingGate
import me.him188.ani.app.ui.onboarding.TvOnboardingLogin
import me.him188.ani.app.ui.onboarding.TvOnboardingLoginHost
import me.him188.ani.app.ui.onboarding.TvOnboardingPage
import me.him188.ani.app.ui.settings.LocalSettingsScreenVariant
import me.him188.ani.app.ui.settings.SettingsScreenVariant
import me.him188.ani.app.ui.settings.tabs.log.getLogsDir
import me.him188.ani.app.ui.settings.tv.TvSettingsPage
import me.him188.ani.app.ui.settings.tv.ensureTvInAppUpdateDownload
import me.him188.ani.app.ui.subject.collection.CollectionPageVariant
import me.him188.ani.app.ui.subject.collection.LocalCollectionPageVariant
import me.him188.ani.app.ui.subject.collection.TvCollectionPage
import me.him188.ani.app.ui.subject.details.LocalSubjectDetailsPageVariant
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadAttempt
import me.him188.ani.app.ui.subject.details.SubjectDetailsPageVariant
import me.him188.ani.app.ui.subject.details.layout.SubjectDetailsLayoutParams
import me.him188.ani.app.ui.subject.details.layout.SubjectDetailsTvLoadingPlaceholder
import me.him188.ani.app.ui.subject.details.layout.SubjectDetailsTvPage
import me.him188.ani.app.ui.subject.details.layout.TvHeroShrinkLayer
import me.him188.ani.app.ui.subject.details.layout.TvHeroZoomLayer
import me.him188.ani.app.ui.subject.details.layout.tvHeroZoomHoldsPlaceholder
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState
import me.him188.ani.app.ui.subject.episode.EpisodeScreenVariant
import me.him188.ani.app.ui.subject.episode.LocalEpisodeScreenVariant
import me.him188.ani.app.ui.foundation.tv.LocalTvLoginSidePanel
import me.him188.ani.app.ui.foundation.tv.LocalTvOnboardingVariant
import me.him188.ani.app.ui.foundation.tv.TvOnboardingVariant
import me.him188.ani.app.ui.subject.episode.tv.TvEpisodeScreenContent
import me.him188.ani.app.ui.subject.episode.tv.source.TvDownloadSourcePanel
import me.him188.ani.app.ui.download.subject.LocalTvDownloadMediaPickerVariant
import me.him188.ani.app.ui.download.subject.TvDownloadMediaPickerVariant
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.app.ui.remote.RegisterTvRemoteBackgroundPlayer
import me.him188.ani.app.ui.remote.TrackTvRemoteForeground
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.remote.TvRemoteControlDialogHost
import me.him188.ani.app.ui.remote.TvRemoteLoginCard
import me.him188.ani.app.ui.exploration.schedule.grid.TvScheduleGridPage
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.profile.UserProfileManager
import me.him188.ani.app.domain.profile.UserProfiles
import me.him188.ani.app.ui.profile.TvProfileSwitchLandingHost
import me.him188.ani.app.ui.profile.TvUserProfilePicker
import me.him188.ani.app.ui.profile.TvUserProfilePickerHost

/**
 * TV 页面变体装配: 把遥控器形态的页面实现注入各共享页面的变体插槽.
 *
 * 共享代码只认识插槽 (`Local*Variant`), 不认识 TV; 是否安装变体由应用入口决定
 * (见 MainActivity 的 UI mode 判断).
 */
/** [InstallTvPageVariants] 的条件版: 非 TV 直接组合 [content], 零影响. */
@Composable
fun MaybeInstallTvPageVariants(isTv: Boolean, aniNavigator: AniNavigator, content: @Composable () -> Unit) {
    if (isTv) InstallTvPageVariants(aniNavigator, content) else content()
}

@Composable
fun InstallTvPageVariants(aniNavigator: AniNavigator, content: @Composable () -> Unit) {
    // 遥控器全局长按手势 (机制与分层见 TvKeyLongPressHost 的 KDoc): 每个键集一份跟踪器,
    // 挂在下方根 Box 上; "长按之后干什么"由在场的界面注册 (播放器收叠层在栈顶, 这里只有兜底)
    //
    // **不给作用域 = 只按系统连发计数** (2026-09-19 退回): 早先给了它, 让"按住满 TV_LONG_PRESS_HOLD
    // 当场触发"以求更跟手 (用户 2026-09-15 嫌动作面板反应慢). 但那条路的前提是"280ms 远长于正常
    // 短按", 而**主线程一卡这个前提就不成立** —— 从详情页返回时 KeyUp 挤在卡住的主线程后面,
    // 计时器先到点, 短按就被判成长按、弹出动作面板 (用户 2026-09-19). 系统连发不会凭空出现,
    // 按连发计数天然免疫这一类误判; 代价是首发连发要 ~400ms, 面板慢一点.
    val backLongPress = remember { TvBackLongPressHost() }
    val playLongPress = remember { TvKeyLongPressHost(TV_PLAY_KEYS) }
    // 方向键按住的真信号 (hero 文字 / 背景图 / 集信息行按住期间不换), 见 TvNavKeyTracker
    val navKeys = rememberTvNavKeyTracker()
    // 放大转场的导航规则要在详情页组合之前知道目标背景 URL: 注册进程内热表的取法 (见 TvHeroZoomHandoff.willZoom)
    val tmdbForZoom = remember { GlobalKoin.get<TmdbImageService>() }
    LaunchedEffect(tmdbForZoom) { TvHeroZoomHandoff.detailsUrlProvider = { id -> tmdbForZoom.peekBackdropUrl(id) } }
    LaunchedEffect(Unit) { ensureTvInAppUpdateDownload() }
    // 「去登录 Bangumi」都去设置页的账号那一类 (扫码登录、在电视上登录、须知都在那里), 不开单独的授权页
    remember { BangumiAuthorizeRedirect.target = { it.navigateSettings(SettingsTab.PROFILE) } }
    // 各页把自己的强制刷新动作注册进来, 给快捷菜单的「刷新本页」用
    val pageRefresh = remember { TvPageActionHost() }
    // 「换一批」: 目前只有探索页的推荐区注册
    val pageShuffle = remember { TvPageActionHost() }
    // 「调整本页」的圆钮 (海报墙大小 / 标签顺序): 海报墙三页注册, 按下开下面那个编辑窗口
    val pageAdjust = remember { TvPageAdjustHost() }
    // 页面里的调整入口打开的编辑页 (盖在页面上的全屏窗口, 见 TvAdjustWindows)
    val adjustWindows = remember { TvAdjustWindows() }
    // 触屏设备 (平板装了 TV 包) 才打开触摸适配; 电视上为 false, 相关 modifier 一个节点都不装 (见 TvTouchInput.kt)
    val appContext = LocalContext.current
    val touchInput = remember(appContext) {
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    // 引导还没做过就先进引导页 (网络检测 + 手机遥控 + 登录), 见 TvOnboardingGate
    val onboardingPending = remember(appContext) { TvOnboardingGate.isPending(appContext) }
    val onboarding = remember(onboardingPending) { TvOnboardingVariantImpl(onboardingPending) }
    // 冷启动的启动页 (应用图标 + 进度条): 一打开应用就由入口的占位先画上 (见 FormFactorStartupPlaceholder), 这里接着盖同一份,
    // 首屏的封面出来了再撤; 走引导时交接给欢迎页的图标. 只在进程第一次建界面时出 (Activity 重建时图都在内存里, 没有可等的)
    val startupLogo = remember { TvStartupLogoHost.coldStart(onboarding = onboardingPending) }
    // 多用户 (见 UserProfiles): 两个以上用户时每次打开应用先选人 (设置的账号页里能关, 见 UserProfilesSave.chooseOnLaunch).
    // 刚在选人页选过、重启进来的不弹; 首次引导期间不弹 (那时只有一个用户). rememberSaveable: 休眠后进程重建恢复界面时也不再弹
    val profileManager = remember { GlobalKoin.get<UserProfileManager>() }
    var launchPickerHandled by rememberSaveable { mutableStateOf(false) }
    // 应用为换人 / 改成本地用户自己重启进来的 (见 ProfileRestartActivity)
    val profileRestart = UserProfiles.launchedBySwitch
    val pickerOnLaunch = remember(appContext) {
        val profiles = profileManager.state.value
        !launchPickerHandled && !onboardingPending && profileManager.isSupported &&
                profiles.profiles.size >= 2 && profiles.chooseOnLaunch && !profileRestart
    }
    // 新建的 Bangumi 用户第一次进来: 先弹登录那一步 (登录或跳过), 见 TvOnboardingLogin.loginOnly
    val newUserLoginPending = remember { !onboardingPending && UserProfiles.current.pendingLogin }
    val rootScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        launchPickerHandled = true
        if (pickerOnLaunch) {
            if (startupLogo != null) {
                // 主页在启动页背后加载完再放出选人页 (那几秒主线程很忙, 进场动画会掉帧); 选人页先建好藏着, 放出来时不用再等它组合
                TvUserProfilePicker.show(held = true)
                startupLogo.handOffToPicker { TvUserProfilePicker.reveal() }
            } else {
                TvUserProfilePicker.show()
            }
            // 选了别人会重启; 选的还是自己时选人页关掉, 再接着往下
            TvUserProfilePicker.visible.first { !it }
        }
        if (newUserLoginPending) {
            TvOnboardingLogin.loginOnly = true
            TvOnboardingLogin.request.value = false
        }
    }
    // 搜索页「手机扫码输入」的常驻服务 (固定地址, 手机可加书签): 进程活着就监听, 收到提交而搜索页不在场时
    // 用 navigator 把电视带过去. 见 TvRemoteControl
    DisposableEffect(aniNavigator) {
        TvRemoteControl.install(appContext, aniNavigator)
        // 界面销毁 (退出后进程留着): 旧的导航入口作废, 见 TvRemoteControl.detachNavigator
        onDispose { TvRemoteControl.detachNavigator(aniNavigator) }
    }
    // 手机「设置」标签底部的日志下载, 与设置 → 日志 →「扫码传到手机」同一个目录
    DisposableEffect(appContext) {
        TvRemoteControl.logsDirProvider = { appContext.getLogsDir() }
        onDispose { TvRemoteControl.logsDirProvider = null }
    }
    // Ani 退到后台 (屏保 / 别的应用 / 息屏) 时 Web 控制台顶上一条提示: 操作照样生效, 只是电视上看不到
    TrackTvRemoteForeground()
    CompositionLocalProvider(
        LocalTvBackLongPressHost provides backLongPress,
        // 下发播放键宿主只为让独立窗口的桥接够得着 (处理器仍只有下面那一个)
        LocalTvPlayLongPressHost provides playLongPress,
        LocalTvPageRefreshHost provides pageRefresh,
        LocalTvNavKeyTracker provides navKeys,
        LocalTvTouchInputEnabled provides touchInput,
        LocalTvPageShuffleHost provides pageShuffle,
        LocalTvPageAdjustHost provides pageAdjust,
        LocalTvAdjustWindows provides adjustWindows,
        LocalMainScreenShellVariant provides MainScreenShellVariant {
                page, selfInfo, navigator, onNavigateToPage, onNavigateToSettings,
                onNavigateToSearch, onLogout, modifier, pageContent,
            ->
            // 退出确认弹窗的「确定」= 真退出: AppTerminator 会先收掉 torrent 服务再退进程
            // (Android 上光 finish Activity 的话 :torrent_service 进程还挂着); 开了「退出 Ani 后保留 Web 控制台」时留着进程, 见 exitTvApp
            val context = LocalContext.current
            val appTerminator = remember { KoinPlatform.getKoin().get<AppTerminator>() }
            TvMainScreenLayout(
                page, selfInfo, navigator, onNavigateToPage, onNavigateToSettings,
                onNavigateToSearch, onLogout,
                onExitApp = { exitTvApp(context, appTerminator) },
                modifier = modifier, pageContent = pageContent,
            )
        },
        LocalEpisodeScreenVariant provides EpisodeScreenVariant {
                vm, page, danmakuHostState, danmakuEditorState,
                setShowEditCommentSheet, pauseOnPlaying, modifier,
            ->
            TvEpisodeScreenContent(
                vm, page, danmakuHostState, danmakuEditorState,
                setShowEditCommentSheet, pauseOnPlaying, modifier,
            )
        },
        // ProvideTvScrollActivity: 每个带卡片的 TV 页一份"有卡片在滚动"的信号, 低特效档下
        // hero 文字 / 集信息行据此在滚动期间隐藏, 背景图也等停稳才换 (时间表页只有后一项).
        // TvPosterWallTheme: 海报墙页面 (探索 / 搜索 / 追番三页) 换上 Apple TV 那套灰阶底色, 不留近黑的块
        LocalExplorationPageVariant provides ExplorationPageVariant { state, modifier ->
            ProvideTvScrollActivity {
                TvPosterWallTheme { TvExplorationPage(state, modifier) }
            }
        },
        LocalSchedulePageVariant provides SchedulePageVariant { presentation, onRetry, modifier ->
            // 两版 TV 版式在这里分流 (Upstream 那一档在 ScheduleScreen 就挡住了, 到不了这里).
            // 改版换掉的东西未必人人都想要, 所以旧版留着可选, 见 TvScheduleLayout
            ProvideTvScrollActivity {
                when (LocalThemeSettings.current.tvScheduleLayout) {
                    // 海报墙: 同追番页的卡片墙, 换上海报墙那套灰阶底色
                    TvScheduleLayout.Grid -> TvPosterWallTheme { TvScheduleGridPage(presentation, onRetry, modifier) }
                    else -> TvSchedulePage(presentation, onRetry, modifier)
                }
            }
        },
        LocalSearchPageVariant provides SearchPageVariant { state, onIntent, suggestionsPager, modifier ->
            ProvideTvScrollActivity {
                TvPosterWallTheme {
                    TvSearchPage(state, onIntent, suggestionsPager, modifier)
                }
            }
        },
        LocalCollectionPageVariant provides CollectionPageVariant { state, modifier ->
            ProvideTvScrollActivity {
                TvPosterWallTheme { TvCollectionPage(state, modifier) }
            }
        },
        // 这个变体有两个方法 (页面 + 首屏占位), 不能用 SAM lambda 写法
        LocalSubjectDetailsPageVariant provides TvSubjectDetailsPageVariant,
        LocalTvOnboardingVariant provides onboarding,
        // 登录页右侧的手机控制台码 (扫码在手机上登录)
        LocalTvLoginSidePanel provides { TvRemoteLoginCard() },
        // 人物 / 角色预览弹窗与整页里的横滑行用原生实现 (长按左右连发不卡)
        LocalPeoplePreviewRows provides TvPeoplePreviewRows,
        // 缓存页的选源: 与播放器同一块选源面板
        LocalTvDownloadMediaPickerVariant provides TvDownloadMediaPickerVariant { TvDownloadSourcePanel(it) },
        // 设置页: 原生三栏 (要打字的设置在手机 Web 控制台里)
        LocalSettingsScreenVariant provides SettingsScreenVariant { vm, initialTab, legacy, licenses, modifier ->
            TvSettingsPage(vm, initialTab, legacy, licenses, modifier)
        },
    ) {
        // 长按手势兜不兜、菜单开不开, 都要先看当前在哪个目的地:
        //  - 播放页: 长按返回归播放器自己 (收叠层, 注册在栈顶), 播放键本来就在播放器语义里;
        //  - 登录/授权这类流程页: 中途跳走会把没做完的流程整个丢掉, 长按保持普通语义
        // Navigation 3: 当前目的地就是返回栈栈顶那个路由对象 (原先是 currentBackStackEntry.destination).
        // runCatching 仍要留着: 返回栈由 AniAppContent 组合时才 setBackStack, 本函数在它外面, 冷启动
        // 那几帧读它会抛 (见 AniNavigator.backStack)
        val currentDestinationClaimable = {
            val route = runCatching { aniNavigator.backStack.lastOrNull() }.getOrNull()
            route != null &&
                    route !is NavRoutes.EpisodeDetail &&
                    route !is NavRoutes.OAuthAuthorize &&
                    route !is NavRoutes.TvOnboarding
        }
        // **两个长按各配各的** (设置-界面, 见 [TvLongPressAction]), 默认都开动作面板:
        //
        // - **长按播放键**几乎不存在误触 (没人会按住播放键不放), 而"长按媒体键弹出媒体面板"本身
        //   也讲得通. 它原先是"直接跳回正在播放"的盲跳, 默认改成开面板 = "先看一眼再确认";
        //   想要旧手感的人把它设回 [TvLongPressAction.Resume] 即可.
        // - **长按返回**是精简遥控器 (Chromecast 那类, 没有播放键) 唯一够得到面板的入口, 所以
        //   三档里唯独它允许 [TvLongPressAction.None] (完全不认领).
        //
        // 之所以按"每个键做什么"配而不是"哪些键能开面板"三选一: 后者有个空档 —— 选"只有返回键
        // 开面板"时长按播放键就闲置了, 而那个手势本身有用. 拆开之后还多出一个组合:
        // 返回开面板 + 播放直接回去, 两个手势分工而不是重复.
        //
        // 长按返回这条是栈底兜底 (最先注册), 播放器的"收叠层"处理器比它优先.
        var showQuickMenu by remember { mutableStateOf(false) }
        val themeSettings = LocalThemeSettings.current
        val backLongPressAction by rememberUpdatedState(themeSettings.tvBackLongPress)
        val playLongPressAction by rememberUpdatedState(themeSettings.tvPlayLongPress)
        // 保留的会话与 AniAppContent 里是同一个 Activity 级 ViewModel (viewModel 同 owner 同 key
        // 返回同一实例), 这里拿它只为把把手传给面板 (读 session/progress/status + close).
        val retainSession = LocalAniUiBehavior.current.retainPlaybackSession &&
                LocalThemeSettings.current.tvRetainPlaybackSession
        val sessionHolder = viewModel { RetainedPlaybackSessionHolder() }
        val playbackEntry: PlaybackSessionEntry =
            if (retainSession) sessionHolder else PlaybackSessionEntry.None
        // Web 控制台: 播放页不在前台时, 网页上的「在电视上打开播放器」要知道回哪个会话
        val currentPlaybackEntry by rememberUpdatedState(playbackEntry)
        DisposableEffect(Unit) {
            TvRemoteControl.playbackSessionProvider = { currentPlaybackEntry.session }
            TvRemoteControl.playbackStatusProvider = { currentPlaybackEntry.status }
            // 手机「缓存」标签删正在播的那条缓存时多提示一句 (同电视缓存页的删除确认框)
            TvRemoteControl.playingCacheProvider = { currentPlaybackEntry.playingCache }
            onDispose {
                TvRemoteControl.playbackSessionProvider = null
                TvRemoteControl.playbackStatusProvider = null
                TvRemoteControl.playingCacheProvider = null
            }
        }
        // 后台会话的提示 (准备好了 / 出问题) 同步给 Web 控制台, 与电视上的 toast 同一份文案.
        // notices 是 SharedFlow, 与 AniAppContent 那个收集者各收各的
        val noticeTexts = rememberRetainedPlaybackNoticeTexts()
        LaunchedEffect(sessionHolder, noticeTexts) {
            sessionHolder.notices.collect { TvRemoteControl.postNotice(noticeTexts.textOf(it)) }
        }
        // 播放页不在前台时手机上照样能换源: 后台会话照常搜源解析 (见 RegisterTvRemoteBackgroundPlayer).
        // key(vm): 换会话时旧把手注销、新把手登记
        if (retainSession) {
            sessionHolder.currentViewModel?.let { vm ->
                key(vm) { RegisterTvRemoteBackgroundPlayer(vm) }
            }
        }
        // 两个键的动作走同一段逻辑, 只是各读各的设置.
        //
        // Panel 档没有会话时照样开面板 (不 toast"没有正在播放"): 面板里那张压暗的占位卡把同一句话
        // 说了, 还顺带演示了这手势是干什么的 —— 一个只在没会话时才冒出来的 toast, 恰好只有不知道
        // 这手势的人会看到, 却什么也没让他们看见.
        //
        // Resume 档没有会话时**认领 + toast**: 那一档就是"一步跳回去", 没得跳时手势不能像死了一样.
        val toaster = LocalToaster.current
        val noSessionText = stringResource(Lang.playback_session_none)
        val performLongPress: (TvLongPressAction) -> Boolean = perform@{ action ->
            if (!currentDestinationClaimable()) return@perform false
            when (action) {
                TvLongPressAction.Panel -> {
                    showQuickMenu = true
                    true
                }

                TvLongPressAction.Resume -> {
                    val session = playbackEntry.session
                    if (session != null) {
                        // force: 回到已经在播的这一集, 跳过一起看跟随模式的导航守卫
                        aniNavigator.navigateEpisodeDetails(session.subjectId, session.episodeId, force = true)
                    } else {
                        toaster.toast(noSessionText)
                    }
                    true
                }

                TvLongPressAction.None -> false
            }
        }
        TvBackLongPressHandler { performLongPress(backLongPressAction) }
        // 动作面板顶上那张卡在没有会话时显示「接下来播放」, 它的目标就由这条链算.
        // 起在根部而不是面板里: 面板要**同步**读到结果 (卡片能不能按决定了默认焦点落点),
        // 面板打开时才现算的话, 数据晚到就会把落点挪走 —— 见 TvUpNextStore 的文档
        LaunchedEffect(Unit) { TvUpNextStore.run() }
        TvKeyLongPressHandler(playLongPress) { performLongPress(playLongPressAction) }
        // 首次启动引导的登录那一步: 盖在主页上, 做完 (登录或跳过) 才算引导做完; 返回 = 回到检测网络那一页
        TvOnboardingLoginHost(
            onFinished = { TvOnboardingGate.markDone(appContext) },
            onBack = { aniNavigator.navigate(NavRoutes.TvOnboarding) },
            onNewUserFinished = { rootScope.launch { profileManager.finishPendingLogin() } },
        )
        // 选人页 (启动时或侧边栏头像的「切换用户」打开)
        TvUserProfilePickerHost()
        // 「Web 控制台」二维码弹窗: 侧边栏 (主页 / 搜索页 / 详情页) 与头像菜单都只调 TvRemoteControl.showDialog
        TvRemoteControlDialogHost()
        // 官方连不上、要自动改用镜像而用户登录着: 先问他 (见 BangumiMirrorConsent)
        TvMirrorConsentHost()
        // 「标记看过后删除缓存」删了缓存时弹提示 (标记可能在任何一页或 Web 控制台发生)
        TvMarkedDoneCacheDeletionToasts()
        // 打开应用时弹一次二维码 (设置-界面 / 弹窗里都能关), 见 TvRemoteControl.showDialogOnLaunch.
        // 等地址期间可能已经不在首页了 (休眠后进程重建会恢复到离开时那个页), 那就不弹;
        // 这次启动走了引导页也不弹 —— 引导的登录那一步刚给过同一个码; 先选人或新用户先登录时同理;
        // 应用为换人 / 改成本地用户自己重启进来的也不弹 —— 那不是用户打开应用
        LaunchedEffect(Unit) {
            // 启动页盖着的时候先不弹, 等它撤了再说
            if (startupLogo != null) snapshotFlow { startupLogo.visible }.first { !it }
            TvRemoteControl.showDialogOnLaunch {
                !onboardingPending && !pickerOnLaunch && !newUserLoginPending && !profileRestart &&
                        runCatching { aniNavigator.backStack.lastOrNull() }.getOrNull() is NavRoutes.Main
            }
        }
        if (showQuickMenu) {
            val context = LocalContext.current
            val appTerminator = remember { KoinPlatform.getKoin().get<AppTerminator>() }
            TvQuickActionMenu(
                navigator = aniNavigator,
                playback = playbackEntry,
                refreshHost = pageRefresh,
                shuffleHost = pageShuffle,
                adjustHost = pageAdjust,
                onGoHome = {
                    // 焦点交接走标志 (探索页消费, 见 TvBackLongPressHost.pendingHomeFocus);
                    // 不在 Main 上时先 pop 回去, 落在别的 tab 上由主壳看着标志补一步切换
                    backLongPress.pendingHomeFocus = true
                    val onMain = runCatching {
                        aniNavigator.backStack.lastOrNull() is NavRoutes.Main
                    }.getOrNull() == true
                    if (!onMain) aniNavigator.popBackOrNavigateToMain(MainScreenPage.Exploration)
                },
                onExitApp = { exitTvApp(context, appTerminator) },
                onDismissRequest = { showQuickMenu = false },
            )
        }
        // 页面里的调整入口打开的编辑窗口 (海报墙大小 / 标签顺序 / 播放器按钮). 装在这一层: 用应用的主题,
        // 不带上某一页自己的配色与局部状态
        TvAdjustWindowHost(adjustWindows)
        Box(
            Modifier
                // 首次启动引导的后两步盖在主页上 (独立窗口): 那个窗口刚出现的零点几秒还没接管按键, 这时按的键会落到
                // 下面的主页 (返回键弹出退出确认 / 确认键点开条目). 引导层与选人页显示期间主页一个键都不处理; 启动页盖着时同理
                .onPreviewKeyEvent {
                    TvOnboardingLogin.request.value != null || TvUserProfilePicker.visible.value || startupLogo?.visible == true
                }
                .tvKeyLongPressInterceptor(backLongPress)
                .tvKeyLongPressInterceptor(playLongPress)
                .tvNavKeyInterceptor(navKeys)
                .tvTouchKeyboardMode(),
        ) {
            // 引导的欢迎页据此报图标位置, 启动页交接给它 (见 TvStartupLogoState.handOffToWelcome)
            CompositionLocalProvider(LocalTvStartupLogo provides startupLogo) {
                if (touchInput) {
                    // 触屏设备上动作面板的入口 (侧边栏条目读它), 与两个长按走同一道"本页可认领"判据.
                    // 电视上不包这一层, content 原样组合
                    val openActionPanel = remember { { if (currentDestinationClaimable()) showQuickMenu = true } }
                    CompositionLocalProvider(LocalTvOpenActionPanel provides openActionPanel) { content() }
                } else {
                    content()
                }
            }
            if (startupLogo != null) {
                LaunchedEffect(Unit) {
                    // 返回栈要等应用状态读出来才有 (见 AniAppContent), 在那之前下面是空的, 先盖着.
                    // 进程重建恢复到播放器、详情页这些页时没有首屏封面可等, 栈一就位就撤
                    when (aniNavigator.awaitBackStack().lastOrNull()) {
                        // 打开应用先选人时由选人页那边撤 (见上面 pickerOnLaunch 那段)
                        is NavRoutes.Main -> if (!pickerOnLaunch) startupLogo.dismissWhenFirstScreenReady()
                        is NavRoutes.TvOnboarding -> startupLogo.handOffToWelcome()
                        else -> startupLogo.dismiss()
                    }
                }
                TvStartupLogo(startupLogo, tvStartupLogoColors())
            }
            // 换人重启进来时盖在最上面的那一帧 (与重启途中显示的是同一张), 首页封面加载好了再淡出
            TvProfileSwitchLandingHost()
        }
    }
}

/** 首次启动引导的 TV 变体: 检测网络那一页. 登录那一步做完才记下标记 (见根部的 TvOnboardingLoginHost 与 [TvOnboardingGate]). */
private class TvOnboardingVariantImpl(override val pendingOnLaunch: Boolean) : TvOnboardingVariant {
    @Composable
    override fun Page(onFinished: () -> Unit, modifier: Modifier) {
        // 选好连接方式就换成主页, 登录层 (TvOnboardingLoginHost, 装在根部) 盖在上面, 主页在下面照常加载
        TvOnboardingPage(
            onModeChosen = { assumeViaMirror ->
                TvOnboardingLogin.request.value = assumeViaMirror
                onFinished()
            },
            modifier = modifier,
        )
    }
}

/**
 * 条目详情页的 TV 变体. 与其他插槽不同, 它有两个方法 (页面本体 + 首屏占位),
 * 不能用 SAM lambda 写法.
 *
 * 页面本体、加载占位、放大与缩回那两层都在 [TvDetailsTheme] 里 (本体经 [Theme] 由调用方包): 浅色主题下底色换成电视的浅灰阶,
 * 几处必须同一个底色.
 */
private object TvSubjectDetailsPageVariant : SubjectDetailsPageVariant {
    @Composable
    override fun Page(
        state: SubjectDetailsState,
        selfInfo: SelfInfoUiState,
        layoutParams: SubjectDetailsLayoutParams,
        onPlay: (episodeId: Int) -> Unit,
        onClickTag: (Tag) -> Unit,
        onClickLogin: () -> Unit,
        /** 参数 = 打开后落在第几条评论 (详情页评价卡点进来时是那一条). */
        onShowComments: (initialFocusIndex: Int) -> Unit,
        modifier: Modifier,
        onEpisodeCollectionUpdate: (SetEpisodeCollectionTypeRequest) -> Unit,
        showTopBar: Boolean,
        windowInsets: WindowInsets,
        backgroundPalette: Palette?,
        onClickOpenExternal: () -> Unit,
        onCoverImageSuccess: (AniImageLoadSuccess) -> Unit,
        onClickCache: (() -> Unit)?,
        videoBackground: Boolean,
        onVideoBackgroundExitUp: (() -> Unit)?,
    ) {
        ProvideTvScrollActivity {
            SubjectDetailsTvPage(
                state = state,
                selfInfo = selfInfo,
                layoutParams = layoutParams,
                onPlay = onPlay,
                onClickTag = onClickTag,
                onClickLogin = onClickLogin,
                onShowComments = onShowComments,
                modifier = modifier,
                onEpisodeCollectionUpdate = onEpisodeCollectionUpdate,
                showTopBar = showTopBar,
                windowInsets = windowInsets,
                backgroundPalette = backgroundPalette,
                onClickOpenExternal = onClickOpenExternal,
                onCoverImageSuccess = onCoverImageSuccess,
                onClickCache = onClickCache,
                videoBackground = videoBackground,
                onVideoBackgroundExitUp = onVideoBackgroundExitUp,
            )
        }
    }

    @Composable
    override fun LoadingPlaceholder(
        subjectInfo: SubjectInfo?,
        layoutParams: SubjectDetailsLayoutParams,
        modifier: Modifier,
        windowInsets: WindowInsets,
        loadAttempt: SubjectDetailsLoadAttempt,
    ) {
        TvDetailsTheme {
            SubjectDetailsTvLoadingPlaceholder(subjectInfo, layoutParams, modifier, windowInsets, loadAttempt)
        }
    }

    @Composable
    override fun Underlay() {
        TvDetailsTheme { TvHeroZoomLayer() }
    }

    @Composable
    override fun holdPlaceholder(subjectId: Int): Boolean = tvHeroZoomHoldsPlaceholder(subjectId)

    @Composable
    override fun Overlay() {
        TvDetailsTheme { TvHeroShrinkLayer() }
    }

    @Composable
    override fun Theme(content: @Composable () -> Unit) {
        TvDetailsTheme(content)
    }
}
