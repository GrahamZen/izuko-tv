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
import me.him188.ani.app.data.models.preference.TvScheduleLayout
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.ui.exploration.ExplorationPageVariant
import me.him188.ani.app.ui.exploration.LocalExplorationPageVariant
import me.him188.ani.app.ui.exploration.TvExplorationPage
import me.him188.ani.app.ui.exploration.schedule.LocalSchedulePageVariant
import me.him188.ani.app.ui.exploration.schedule.SchedulePageVariant
import me.him188.ani.app.ui.exploration.schedule.TvSchedulePage
import me.him188.ani.app.ui.exploration.schedule.grid.TvScheduleGridPage
import me.him188.ani.app.ui.exploration.search.LocalSearchPageVariant
import me.him188.ani.app.ui.exploration.search.SearchPageVariant
import me.him188.ani.app.ui.exploration.search.TvSearchPage
import me.him188.ani.app.ui.foundation.AniUiBehavior
import me.him188.ani.app.ui.foundation.LocalTvBackLongPressHost
import me.him188.ani.app.ui.foundation.LocalTvPageAdjustHost
import me.him188.ani.app.ui.foundation.LocalTvPageRefreshHost
import me.him188.ani.app.ui.foundation.LocalTvPageShuffleHost
import me.him188.ani.app.ui.foundation.TvBackLongPressHost
import me.him188.ani.app.ui.foundation.TvPageActionHost
import me.him188.ani.app.ui.foundation.TvPageAdjustHost
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
import me.him188.ani.app.ui.settings.LocalSettingsScreenVariant
import me.him188.ani.app.ui.settings.SettingsScreenVariant
import me.him188.ani.app.ui.settings.tv.TouchSettingsPage
import me.him188.ani.app.ui.settings.tv.TvSettingsPlatform
import me.him188.ani.app.ui.subject.collection.CollectionPageVariant
import me.him188.ani.app.ui.subject.collection.LocalCollectionPageVariant
import me.him188.ani.app.ui.subject.collection.TvCollectionPage
import me.him188.ani.app.ui.subject.details.LocalSubjectDetailsPageVariant
import me.him188.ani.app.ui.subject.details.layout.TvSubjectDetailsPageVariant
import me.him188.ani.app.ui.tv.TvAniUiBehavior
import me.him188.ani.app.ui.tv.withTouchNavigation

/**
 * 触屏设备 (iPad) 上跑电视界面时的界面行为: 同电视 ([TvAniUiBehavior]); 播放器用的是共享的那一版, 跟播放器相关的几项取共享的默认值,
 * 不自动装更新 (应用商店管); 二级页面有带返回按钮的顶栏 (见 withTouchNavigation).
 */
val TvTouchAniUiBehavior: AniUiBehavior = TvAniUiBehavior.copy(
    supportsWindowedPlayback = AniUiBehavior.Default.supportsWindowedPlayback,
    forceDarkInPlayer = AniUiBehavior.Default.forceDarkInPlayer,
    retainPlaybackSession = false,
    autoInstallUpdates = false,
).withTouchNavigation()

/**
 * 把电视界面装进各共享页面的插槽, 给触屏设备 (iPad) 用, 样子同电视包装在平板上 (触摸适配打开, 见 LocalTvTouchInputEnabled): 主壳 (侧边栏)、
 * 探索 / 追番 / 搜索 / 新番时间表、详情页、设置页 (触屏版). 播放器照旧用共享的那一版. 电视包自己的装配见安卓的 TvPageVariants.
 */
@Composable
fun InstallTvTouchPageVariants(aniNavigator: AniNavigator, content: @Composable () -> Unit) {
    val backLongPress = remember { TvBackLongPressHost() }
    // 方向键按住的真信号 (接着键盘时 hero 文字 / 背景图按住期间不换), 见 TvNavKeyTracker
    val navKeys = rememberTvNavKeyTracker()
    // 放大转场的导航规则要在详情页组合之前知道目标背景 URL: 注册进程内热表的取法 (见 TvHeroZoomHandoff.willZoom)
    val tmdbForZoom = remember { GlobalKoin.get<TmdbImageService>() }
    LaunchedEffect(tmdbForZoom) { TvHeroZoomHandoff.detailsUrlProvider = { id -> tmdbForZoom.peekBackdropUrl(id) } }
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
                onNavigateToSearch, onLogout, modifier, pageContent,
            ->
            // 应用不自己退出 (iOS 不让应用自己退): 首页按返回的退出确认里「确定」什么都不做
            TvMainScreenLayout(
                page, selfInfo, navigator, onNavigateToPage, onNavigateToSettings,
                onNavigateToSearch, onLogout,
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
            ProvideTvScrollActivity {
                when (LocalThemeSettings.current.tvScheduleLayout) {
                    TvScheduleLayout.Grid -> TvPosterWallTheme { TvScheduleGridPage(presentation, onRetry, modifier) }
                    else -> TvSchedulePage(presentation, onRetry, modifier)
                }
            }
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
        LocalSubjectDetailsPageVariant provides TvSubjectDetailsPageVariant,
        // 登录页不挂手机控制台码 (没有 Web 控制台), 登录页自己给输入框
        LocalTvLoginSidePanel provides null,
        // 设置页: 触屏版 (分类与设置项同电视版); 电视独有的项 (控制台、首次引导等) 这里没有
        LocalSettingsScreenVariant provides SettingsScreenVariant { vm, initialTab, legacy, licenses, modifier ->
            TouchSettingsPage(vm, initialTab, legacy, licenses, TvSettingsPlatform.None, modifier)
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
        if (showQuickMenu) {
            TvQuickActionMenu(
                navigator = aniNavigator,
                playback = PlaybackSessionEntry.None,
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
            CompositionLocalProvider(LocalTvOpenActionPanel provides openActionPanel) { content() }
        }
    }
}
