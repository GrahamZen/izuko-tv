/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.ui.debugconsole.DebugConsoleTargets
import me.him188.ani.app.ui.debugconsole.withDebugConsole
import me.him188.ani.app.ui.exploration.ExplorationPageVariant
import me.him188.ani.app.ui.exploration.LocalExplorationPageVariant
import me.him188.ani.app.ui.exploration.TvExplorationPage
import me.him188.ani.app.ui.exploration.schedule.LocalSchedulePageVariant
import me.him188.ani.app.ui.exploration.schedule.SchedulePageVariant
import me.him188.ani.app.ui.exploration.schedule.TvScheduleSlot
import me.him188.ani.app.ui.exploration.search.LocalSearchPageVariant
import me.him188.ani.app.ui.exploration.search.SearchPageVariant
import me.him188.ani.app.ui.exploration.search.TvSearchPage
import me.him188.ani.app.ui.feedback.TouchFeedbackHost
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.LocalTvBackLongPressHost
import me.him188.ani.app.ui.foundation.LocalTvPageAdjustHost
import me.him188.ani.app.ui.foundation.LocalTvPageRefreshHost
import me.him188.ani.app.ui.foundation.LocalTvPageShuffleHost
import me.him188.ani.app.ui.foundation.TvBackLongPressHost
import me.him188.ani.app.ui.foundation.TvPageActionHost
import me.him188.ani.app.ui.foundation.TvPageAdjustHost
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionEntry
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvLoginSidePanel
import me.him188.ani.app.ui.foundation.tv.LocalTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.LocalTvOpenActionPanel
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.ProvideTvScrollActivity
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.TvPosterWallTheme
import me.him188.ani.app.ui.foundation.tv.rememberTvNavKeyTracker
import me.him188.ani.app.ui.foundation.tv.tvNavKeyInterceptor
import me.him188.ani.app.ui.foundation.tv.tvTouchKeyboardMode
import me.him188.ani.app.ui.notice.TvAppUpdateHost
import me.him188.ani.app.ui.notice.TvNoticeHost
import me.him188.ani.app.ui.notice.TvNotices
import me.him188.ani.app.ui.settings.LocalSettingsScreenVariant
import me.him188.ani.app.ui.settings.SettingsScreenVariant
import me.him188.ani.app.ui.settings.tv.TouchSettingsPage
import me.him188.ani.app.ui.settings.tv.TvSettingsPlatform
import me.him188.ani.app.ui.subject.collection.CollectionPageVariant
import me.him188.ani.app.ui.subject.collection.LocalCollectionPageVariant
import me.him188.ani.app.ui.subject.collection.TvCollectionPage
import me.him188.ani.app.ui.subject.details.LocalSubjectDetailsPageVariant
import me.him188.ani.app.ui.subject.details.layout.TvPadSubjectDetailsPageVariant
import me.him188.ani.app.ui.subject.details.layout.TvSubjectDetailsPageVariant
import me.him188.ani.app.ui.subject.episode.EpisodeScreenVariant
import me.him188.ani.app.ui.subject.episode.LocalEpisodeScreenPlaceholder
import me.him188.ani.app.ui.subject.episode.LocalEpisodeScreenVariant
import me.him188.ani.app.ui.subject.episode.RetainedPlaybackSessionHolder
import me.him188.ani.app.ui.subject.episode.tv.TvEpisodeScreenContent
import me.him188.ani.app.ui.subject.episode.tv.tablet.TvEpisodeScreenPlaceholder
import me.him188.ani.app.ui.together.TvTogetherHost
import me.him188.ani.app.ui.tv.TvAniUiBehavior
import me.him188.ani.app.ui.tv.withTouchNavigation
import me.him188.ani.app.ui.update.AppUpdateViewModel
import me.him188.ani.app.ui.update.LocalAppUpdateViewModel

/**
 * 触屏设备 (iPad) 上跑电视界面时的界面行为: 同安卓平板上的电视包 ([TvAniUiBehavior] + withTouchNavigation: 二级页面有带返回按钮的顶栏、
 * 播放页外面跟主题); 设置里给「切换到其他应用时」(系统小窗); 保留播放会话 (离开播放页后缩成应用内小窗接着放, 见 TouchMiniPlayer);
 * 不自动装更新 (应用商店管).
 */
val TvTouchAniUiBehavior: AniUiBehavior = TvAniUiBehavior.copy(
    supportsWindowedPlayback = AniUiBehavior.Default.supportsWindowedPlayback,
    autoInstallUpdates = false,
).withTouchNavigation()

/**
 * 把电视界面装进各共享页面的插槽, 给触屏设备 (iPad) 用, 样子同电视包装在平板上 (触摸适配打开, 见 LocalTvTouchInputEnabled): 主壳 (侧边栏)、
 * 探索 / 追番 / 搜索 / 新番时间表、详情页、播放页 (电视播放器的平板外框)、设置页 (触屏版). 电视包自己的装配见安卓的 TvPageVariants.
 */
@Composable
fun InstallTvTouchPageVariants(aniNavigator: AniNavigator, content: @Composable () -> Unit) {
    val backLongPress = remember { TvBackLongPressHost() }
    // 方向键按住的真信号 (接着键盘时 hero 文字 / 背景图按住期间不换), 见 TvNavKeyTracker
    val navKeys = rememberTvNavKeyTracker()
    // 放大转场的导航规则要在详情页组合之前知道目标背景 URL: 注册进程内热表的取法 (见 TvHeroZoomHandoff.willZoom)
    val tmdbForZoom = remember { GlobalKoin.get<TmdbImageService>() }
    LaunchedEffect(tmdbForZoom) {
        TvHeroZoomHandoff.detailsUrlProvider = { id -> tmdbForZoom.peekBackdropUrl(id) }
        // 触屏的详情页没有首屏大图 (见 TvPadSubjectDetailsPageVariant): 不放大
        TvHeroZoomHandoff.detailsHasHero = false
    }
    // 各页把自己的强制刷新 / 换一批 / 调整本页注册进来, 给动作面板用
    val pageRefresh = remember { TvPageActionHost() }
    val pageShuffle = remember { TvPageActionHost() }
    val pageAdjust = remember { TvPageAdjustHost() }
    // 页面里的调整入口打开的编辑页 (盖在页面上的全屏窗口)
    val adjustWindows = remember { TvAdjustWindows() }
    var showQuickMenu by remember { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalTvBackLongPressHost provides backLongPress,
        LocalTvPageRefreshHost provides pageRefresh,
        LocalTvNavKeyTracker provides navKeys,
        LocalTvTouchInputEnabled provides true,
        LocalTvPageShuffleHost provides pageShuffle,
        LocalTvPageAdjustHost provides pageAdjust,
        LocalTvAdjustWindows provides adjustWindows,
        LocalMainScreenShellVariant provides MainScreenShellVariant {
                page, selfInfo, navigator, onNavigateToPage, onNavigateToSettings,
                onNavigateToSearch, _, modifier, pageContent,
            ->
            // 应用不自己退出 (iOS 不让应用自己退): 首页按返回的退出确认里「确定」什么都不做
            TvMainScreenLayout(
                page, selfInfo, navigator, onNavigateToPage, onNavigateToSettings,
                onNavigateToSearch,
                onExitApp = {},
                modifier = modifier, pageContent = pageContent,
            )
        },
        // ProvideTvScrollActivity: 每个带卡片的页一份「有卡片在滚动」的信号; TvPosterWallTheme: 海报墙页面换上 Apple TV 那套灰阶底色
        LocalExplorationPageVariant provides ExplorationPageVariant { state, modifier ->
            ProvideTvScrollActivity {
                TvPosterWallTheme { TvExplorationPage(state, modifier) }
            }
        },
        LocalSchedulePageVariant provides SchedulePageVariant { presentation, onRetry, modifier ->
            TvScheduleSlot(presentation, onRetry, onBack = { aniNavigator.popBackStack() }, modifier)
        },
        LocalSearchPageVariant provides SearchPageVariant { state, onIntent, suggestionsPager, modifier ->
            ProvideTvScrollActivity {
                TvPosterWallTheme { TvSearchPage(state, onIntent, suggestionsPager, modifier) }
            }
        },
        LocalCollectionPageVariant provides CollectionPageVariant { state, modifier ->
            ProvideTvScrollActivity {
                TvPosterWallTheme { TvCollectionPage(state, modifier) }
            }
        },
        LocalSubjectDetailsPageVariant provides TvTouchSubjectDetailsPageVariant,
        // 播放页: 电视播放器, 触摸适配打开时它自己换成平板外框 (分栏 / 全屏、触屏控件、右栏面板)
        LocalEpisodeScreenVariant provides EpisodeScreenVariant {
                vm, page, danmakuHostState, danmakuEditorState,
                setShowEditCommentSheet, pauseOnPlaying, modifier,
            ->
            TvEpisodeScreenContent(
                vm, page, danmakuHostState, danmakuEditorState,
                setShowEditCommentSheet, pauseOnPlaying, modifier,
            )
        },
        // 播放页数据到之前: 先铺分栏的底 (同详情页那张模糊底), 从详情页点进来底不换
        LocalEpisodeScreenPlaceholder provides { vm, modifier -> TvEpisodeScreenPlaceholder(vm, modifier) },
        // 登录页不挂手机控制台码 (没有 Web 控制台), 登录页自己给输入框
        LocalTvLoginSidePanel provides null,
        // 设置页: 触屏版 (分类与设置项同电视版); 电视独有的项 (控制台、首次引导等) 这里没有
        LocalSettingsScreenVariant provides SettingsScreenVariant { vm, initialTab, legacy, licenses, modifier ->
            TouchSettingsPage(vm, initialTab, legacy, licenses, remember { TvSettingsPlatform.None.withDebugConsole() }, modifier)
        },
    ) {
        // 动作面板顶上那张卡在没有会话时显示「接下来播放」, 它的目标由这条链算 (见 TvUpNextStore)
        LaunchedEffect(Unit) { TvUpNextStore.run() }
        // 应用自己改用户设置时告知或询问 (见 SettingAdjustments)
        TvSettingAdjustmentHost()
        // 「标记看过后删除缓存」删了缓存时弹提示
        TvMarkedDoneCacheDeletionToasts()
        // 动作面板只在流程页以外开 (播放页、登录授权中途跳走会丢掉没做完的流程)
        val currentDestinationClaimable = {
            val route = runCatching { aniNavigator.backStack.lastOrNull() }.getOrNull()
            route != null && route !is NavRoutes.EpisodeDetail && route !is NavRoutes.OAuthAuthorize && route !is NavRoutes.TvOnboarding
        }
        // 保留的会话与 AniAppContent 里是同一个 ViewModel (同 owner 同 key 返回同一实例), 拿它只为把把手传给面板
        // (顶上那张卡读会话、关会话、在后台准备), 同电视包的 TvPageVariants
        val retainSession = LocalAniUiBehavior.current.retainPlaybackSession && LocalThemeSettings.current.tvRetainPlaybackSession
        val sessionHolder = viewModel { RetainedPlaybackSessionHolder() }
        DebugConsoleTargets(aniNavigator, sessionHolder)
        val playbackEntry: PlaybackSessionEntry = if (retainSession) sessionHolder else PlaybackSessionEntry.None
        // 一起看的弹窗 (视频下面、全屏 ⋮ 里的「一起看」打开它), 跟随房主时被带到房主那一集; 同电视包的 TvPageVariants
        TvTogetherHost(aniNavigator, playbackEntry)
        // 应用内通知 (反馈回复、新版本): 侧边栏的「通知」打开通知弹窗, 有没看过的就在首页右下角弹通知气泡; 同电视包的 TvPageVariants.
        // iPad 上进应用时没有本版新功能页与控制台二维码要先走, 一进来就可以弹 (首次引导时不在首页, 等它走完)
        TvNoticeHost(aniNavigator, onMainPage = {
            runCatching { aniNavigator.backStack.lastOrNull() }.getOrNull() is NavRoutes.Main
        })
        LaunchedEffect(Unit) { TvNotices.startBubbles() }
        if (showQuickMenu) {
            TvQuickActionMenu(
                navigator = aniNavigator,
                playback = playbackEntry,
                refreshHost = pageRefresh,
                shuffleHost = pageShuffle,
                adjustHost = pageAdjust,
                onGoHome = {
                    // 焦点交接走标志 (探索页消费); 不在 Main 上时先 pop 回去
                    backLongPress.pendingHomeFocus = true
                    val onMain = runCatching { aniNavigator.backStack.lastOrNull() is NavRoutes.Main }.getOrNull() == true
                    if (!onMain) aniNavigator.popBackOrNavigateToMain(MainScreenPage.Exploration)
                },
                onExitApp = {},
                onDismissRequest = { showQuickMenu = false },
            )
        }
        TvAdjustWindowHost(adjustWindows)
        Box(Modifier.tvNavKeyInterceptor(navKeys).tvTouchKeyboardMode()) {
            // 动作面板的入口在侧边栏 (触屏上没有长按返回 / 长按播放键)
            val openActionPanel = remember { { if (currentDestinationClaimable()) showQuickMenu = true } }
            // 应用更新的那一份状态, 同电视包的 TvPageVariants: 设置里「检查更新」查到后的第二下交到这里 (首页那份在设置页上不在组合里).
            // iOS 上不在应用内下载 (见 UpdateSettings.inAppDownload), 更新是打开浏览器
            val appUpdate = viewModel { AppUpdateViewModel() }
            CompositionLocalProvider(
                LocalTvOpenActionPanel provides openActionPanel,
                LocalAppUpdateViewModel provides appUpdate,
            ) { content() }
            TvAppUpdateHost(appUpdate, inPlayer = false)
            TouchFeedbackHost()
        }
    }
}

/** 触屏的详情页, 播放器内嵌的介绍页仍用遥控器版 [TvSubjectDetailsPageVariant]. */
private val TvTouchSubjectDetailsPageVariant = TvPadSubjectDetailsPageVariant(TvSubjectDetailsPageVariant)
