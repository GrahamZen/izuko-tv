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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import me.him188.ani.app.ui.foundation.AniImageLoadSuccess
import com.kmpalette.palette.graphics.Palette
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.models.subject.Tag
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeRequest
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.platform.AppTerminator
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.LocalTvBackLongPressHost
import me.him188.ani.app.ui.foundation.LocalTvPageRefreshHost
import me.him188.ani.app.ui.foundation.LocalTvPlayLongPressHost
import me.him188.ani.app.ui.foundation.TV_PLAY_KEYS
import me.him188.ani.app.ui.foundation.TvBackLongPressHandler
import me.him188.ani.app.ui.foundation.TvBackLongPressHost
import me.him188.ani.app.ui.foundation.TvKeyLongPressHandler
import me.him188.ani.app.ui.foundation.tv.LocalTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.ProvideTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.rememberTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.tvNavKeyInterceptor
import me.him188.ani.app.ui.foundation.tv.tvTouchKeyboardMode
import me.him188.ani.app.ui.foundation.TvKeyLongPressHost
import me.him188.ani.app.ui.foundation.TvPageRefreshHost
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionEntry
import me.him188.ani.app.data.models.preference.TvLongPressAction
import me.him188.ani.app.data.models.preference.TvScheduleLayout
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tvKeyLongPressInterceptor
import me.him188.ani.app.ui.foundation.watchtogether.WatchTogetherEntryState
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.playback_session_none
import me.him188.ani.app.ui.main.TvQuickActionMenu
import me.him188.ani.app.ui.main.TvUpNextStore
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
import me.him188.ani.app.ui.settings.tabs.log.getLogsDir
import me.him188.ani.app.ui.subject.collection.CollectionPageVariant
import me.him188.ani.app.ui.subject.collection.LocalCollectionPageVariant
import me.him188.ani.app.ui.subject.collection.TvCollectionPage
import me.him188.ani.app.ui.subject.details.LocalSubjectDetailsPageVariant
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
import me.him188.ani.app.ui.foundation.tv.LocalTvPlayerChromeEditorVariant
import me.him188.ani.app.ui.foundation.tv.TvPlayerChromeEditorVariant
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerChromeLayoutPage
import me.him188.ani.app.ui.subject.episode.tv.TvEpisodeScreenContent
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.app.ui.remote.RegisterTvRemoteBackgroundPlayer
import me.him188.ani.app.ui.remote.TrackTvRemoteForeground
import me.him188.ani.app.ui.remote.TvRemoteControl
import me.him188.ani.app.ui.remote.TvRemoteControlDialogHost
import me.him188.ani.app.ui.exploration.schedule.grid.TvScheduleGridPage
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

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
    // 各页把自己的强制刷新动作注册进来, 给快捷菜单的「刷新本页」用
    val pageRefresh = remember { TvPageRefreshHost() }
    // 触屏设备 (平板装了 TV 包) 才打开触摸适配; 电视上为 false, 相关 modifier 一个节点都不装 (见 TvTouchInput.kt)
    val appContext = LocalContext.current
    val touchInput = remember(appContext) {
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    // 搜索页「手机扫码输入」的常驻服务 (固定地址, 手机可加书签): 进程活着就监听, 收到提交而搜索页不在场时
    // 用 navigator 把电视带过去. 见 TvRemoteControl
    DisposableEffect(aniNavigator) {
        // 「退出 Ani 后保留 Web 控制台」的常驻前台服务在本模块 (见 RemoteKeepAliveService)
        val app = appContext.applicationContext
        TvRemoteControl.keepAliveService = { on -> RemoteKeepAliveService.set(app, on) }
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
        LocalExplorationPageVariant provides ExplorationPageVariant { state, modifier ->
            ProvideTvScrollActivity { TvExplorationPage(state, modifier) }
        },
        LocalSchedulePageVariant provides SchedulePageVariant { presentation, onRetry, modifier ->
            // 两版 TV 版式在这里分流 (Upstream 那一档在 ScheduleScreen 就挡住了, 到不了这里).
            // 改版换掉的东西未必人人都想要, 所以旧版留着可选, 见 TvScheduleLayout
            ProvideTvScrollActivity {
                when (LocalThemeSettings.current.tvScheduleLayout) {
                    TvScheduleLayout.Grid -> TvScheduleGridPage(presentation, onRetry, modifier)
                    else -> TvSchedulePage(presentation, onRetry, modifier)
                }
            }
        },
        LocalSearchPageVariant provides SearchPageVariant { state, onIntent, suggestionsPager, modifier ->
            ProvideTvScrollActivity { TvSearchPage(state, onIntent, suggestionsPager, modifier) }
        },
        LocalCollectionPageVariant provides CollectionPageVariant { state, modifier ->
            ProvideTvScrollActivity { TvCollectionPage(state, modifier) }
        },
        // 「自定义播放器按钮」页 (设置 - 播放器里的入口据此决定出不出现)
        LocalTvPlayerChromeEditorVariant provides TvPlayerChromeEditorVariant { onNavigateBack, modifier ->
            TvPlayerChromeLayoutPage(onNavigateBack, modifier)
        },
        // 这个变体有两个方法 (页面 + 首屏占位), 不能用 SAM lambda 写法
        LocalSubjectDetailsPageVariant provides TvSubjectDetailsPageVariant,
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
                    route !is NavRoutes.EmailLoginStart &&
                    route !is NavRoutes.EmailLoginVerify &&
                    route !is NavRoutes.BangumiAuthorize
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
        // 「一起看」入口把手: 同样是 Activity 级 ViewModel, 与 AniAppContent 里 provide 给
        // LocalWatchTogetherEntry 的是同一个实例 —— 本处在那个 provider 的**外面**, 读
        // CompositionLocal 只会拿到默认空实例 (见 WatchTogetherEntryState)
        val watchTogetherEntry = viewModel { WatchTogetherEntryState() }
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
        // 「Web 控制台」二维码弹窗: 侧边栏 (主页 / 搜索页 / 详情页) 与头像菜单都只调 TvRemoteControl.showDialog
        TvRemoteControlDialogHost()
        // 打开应用时弹一次二维码 (设置-界面 / 弹窗里都能关), 见 TvRemoteControl.showDialogOnLaunch.
        // 等地址期间可能已经不在首页了 (休眠后进程重建会恢复到离开时那个页), 那就不弹
        LaunchedEffect(Unit) {
            TvRemoteControl.showDialogOnLaunch {
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
                watchTogether = watchTogetherEntry,
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
        Box(
            Modifier
                .tvKeyLongPressInterceptor(backLongPress)
                .tvKeyLongPressInterceptor(playLongPress)
                .tvNavKeyInterceptor(navKeys)
                .tvTouchKeyboardMode(),
        ) {
            if (touchInput) {
                // 触屏设备上动作面板的入口 (侧边栏条目读它), 与两个长按走同一道"本页可认领"判据.
                // 电视上不包这一层, content 原样组合
                val openActionPanel = remember { { if (currentDestinationClaimable()) showQuickMenu = true } }
                CompositionLocalProvider(LocalTvOpenActionPanel provides openActionPanel) { content() }
            } else {
                content()
            }
        }
    }
}

/**
 * 条目详情页的 TV 变体. 与其他插槽不同, 它有两个方法 (页面本体 + 首屏占位),
 * 不能用 SAM lambda 写法.
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
    ) {
        SubjectDetailsTvLoadingPlaceholder(subjectInfo, layoutParams, modifier, windowInsets)
    }

    @Composable
    override fun Underlay() {
        TvHeroZoomLayer()
    }

    @Composable
    override fun holdPlaceholder(subjectId: Int): Boolean = tvHeroZoomHoldsPlaceholder(subjectId)

    @Composable
    override fun Overlay() {
        TvHeroShrinkLayer()
    }
}
