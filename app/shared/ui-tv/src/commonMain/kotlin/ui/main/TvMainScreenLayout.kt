/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.matchToEpisodes
import me.him188.ani.app.data.network.newestAiredDateStringOrNull
import me.him188.ani.app.data.network.tmdbStillCardSizeUrl
import me.him188.ani.app.data.network.toTmdbLanguage
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.LocalTvBackLongPressHost
import me.him188.ani.app.ui.foundation.LocalTvPageRefreshHost
import me.him188.ani.app.ui.foundation.TvPageRefreshHost
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusLink
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.playback.LocalPlaybackSessionEntry
import me.him188.ani.app.ui.foundation.playback.PlaybackProgress
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionEntry
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionStatus
import me.him188.ani.app.ui.foundation.session.TvNavigationRailDefaults
import me.him188.ani.app.ui.foundation.session.TvNavigationSideRail
import me.him188.ani.app.ui.foundation.session.TvRailAvatarAction
import me.him188.ani.app.ui.foundation.session.buildTvRailItems
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.data.models.preference.TvExitBehavior
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.foundation.tv.TV_CAPSULE_SIZE_LARGE
import me.him188.ani.app.ui.foundation.tv.TV_ICON_GLYPH_SIZE_LARGE
import me.him188.ani.app.ui.foundation.tv.TvCapsuleButton
import me.him188.ani.app.ui.foundation.tv.TvHeroMediaCache
import me.him188.ani.app.ui.foundation.watchtogether.LocalWatchTogetherEntry
import me.him188.ani.app.ui.foundation.watchtogether.WatchTogetherEntryState
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.centeredPanelColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.exit_app_back_to_playback
import me.him188.ani.app.ui.lang.exit_app_close_playback
import me.him188.ani.app.ui.lang.exit_app_title
import me.him188.ani.app.ui.lang.playback_history_episode_label
import me.him188.ani.app.ui.lang.playback_history_title
import me.him188.ani.app.ui.lang.playback_nothing_to_play
import me.him188.ani.app.ui.lang.playback_prepare_in_background
import me.him188.ani.app.ui.lang.playback_up_next_continue
import me.him188.ani.app.ui.lang.playback_up_next_start
import me.him188.ani.app.ui.lang.settings_account_popup_edit_profile
import me.him188.ani.app.ui.lang.settings_account_popup_login_register
import me.him188.ani.app.ui.lang.settings_account_popup_logout
import me.him188.ani.app.ui.lang.tv_exit_press_again
import me.him188.ani.app.ui.lang.tv_force_refresh_toast
import me.him188.ani.app.ui.lang.tv_quick_menu_home
import me.him188.ani.app.ui.lang.tv_quick_menu_refresh
import me.him188.ani.app.ui.lang.tv_service_check_hint
import me.him188.ani.app.ui.lang.watch_together_title
import me.him188.ani.app.ui.subject.episode.PlaybackSessionStatusSeverity
import me.him188.ani.app.ui.subject.episode.PlaybackSessionStatusText
import me.him188.ani.app.ui.subject.episode.playbackSessionStatusText
import me.him188.ani.app.ui.subject.episode.tv.TvRetainedFrameStore
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.datasources.api.toLocalDateOrNull
import org.jetbrains.compose.resources.stringResource

/**
 * TV 主页外壳: 可展开左侧边栏 (头像置顶 → 用户信息页, 搜索/探索/收藏/缓存/设置),
 * 取代旧的 NavigationRail; 各页面在 TV 上隐藏自身顶栏 (纯 chrome), 由本侧边栏统一承载.
 * 侧边栏收起态为一列图标, 内容整体右移让开; 聚焦展开时图标右侧浮出文字并压一层渐变遮罩, 内容不重排.
 * 与详情页侧边栏共用同一实现 (TvNavigationSideRail).
 */
@Composable
fun TvMainScreenLayout(
    page: MainScreenPage,
    selfInfo: SelfInfoUiState,
    navigator: AniNavigator,
    onNavigateToPage: (MainScreenPage) -> Unit,
    onNavigateToSettings: (tab: SettingsTab?) -> Unit,
    onNavigateToSearch: () -> Unit,
    onLogout: () -> Unit,
    /** 真正退出应用 (Android 侧 = AppTerminator: 收掉 torrent 服务再退进程), 由装配处注入. */
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable () -> Unit,
) {
    // 内容区是一个独立 focusGroup, 由 contentFocus 精确定位: 进入它就落到其中第一个可聚焦项.
    // 关键: 外层 Box 用 onEnter 把"从 NavHost 外部进来的任何 enter"(尤其是全局兜底那种无方向
    // 的 requestFocus) 直接重定向到 contentFocus —— 绕开侧边栏 (侧边栏的 onEnter 对非 Left
    // 一律 cancelFocus, 会把无方向 enter 整个取消掉, 焦点就哪都落不下去). 侧边栏只能靠内容区
    // 里主动按左键进入. 切页/丢焦点不在此单独补丁: 侧边栏点击后 clearFocus, 由 AniAppContent
    // 的全局兜底反复 requestFocus, 经此处 onEnter 稳定落进内容区.
    val contentFocus = remember { FocusRequester() }
    // TV 返回键: 收藏/缓存等其它页按返回统一回到探索页; 探索页上的"最后一次返回"三选一
    // (设置-界面, 见 [TvExitBehavior]). 只有焦点在 hero 按钮行时才走得到这里 —— 卡片区的
    // 返回被探索页自己的分层 BackHandler 拦下 (注册更深, 优先), 侧边栏把返回消费成回内容区.
    var showExitDialog by remember { mutableStateOf(false) }
    val exitBehavior = LocalThemeSettings.current.exitBehavior
    BackHandler(enabled = page != MainScreenPage.Exploration) {
        onNavigateToPage(MainScreenPage.Exploration)
    }
    // 「连按两次」的中间态. 判据**就是提示条的动画状态**, 没有第二个计时器:
    //
    //   武装 = currentState || targetState = "提示条还没完全消失"
    //
    // 于是"看得见 = 按下去会退出"是严格成立的, 连淡出那几十毫秒也算数 —— 只有它彻底不见了,
    // 返回键才恢复成"什么都不做". 用 MutableTransitionState 而不是自己 delay 一个动画时长:
    // 后者要把动画时长抄一份在计时器里, 改动画就得记得同步改, 迟早对不上.
    //
    // (走 LocalToaster 是不行的: Android 上那是系统 Toast, 时长写死约 3.5 秒且改不了, 与这里
    // 的窗口对不上就会变成谎话 —— 用户看着提示按返回, 什么也没发生. 见 TvExitHintToast.)
    val exitHintState = remember { MutableTransitionState(false) }
    val exitArmed = exitHintState.currentState || exitHintState.targetState
    val pressAgainText = stringResource(Lang.tv_exit_press_again)
    // Direct 档不注册本处理器 —— 返回穿到系统, 就是老的"直接退出"
    BackHandler(enabled = page == MainScreenPage.Exploration && exitBehavior != TvExitBehavior.Direct) {
        when (exitBehavior) {
            TvExitBehavior.Panel -> showExitDialog = true
            TvExitBehavior.DoubleBack -> {
                if (exitArmed) onExitApp() else exitHintState.targetState = true
            }

            TvExitBehavior.Direct -> Unit // 上面的 enabled 已经排除
        }
    }
    // 到点收起提示条 (武装随之在淡出结束那一刻解除). 复位是必要的: 不然十分钟后的一次返回
    // 会被凑成十分钟前那一次的第二下
    if (exitHintState.targetState) {
        LaunchedEffect(Unit) {
            delay(TV_EXIT_DOUBLE_BACK_WINDOW_MILLIS)
            exitHintState.targetState = false
        }
    }
    if (showExitDialog) {
        TvExitAppDialog(
            navigator = navigator,
            onDismissRequest = { showExitDialog = false },
            onExitApp = onExitApp,
        )
    }
    // 「回到主界面」(快捷菜单发起, 见 TvQuickActionMenu) 的主壳篇: 看到回主页标志而当前
    // 不在探索 tab, 就补一步切过去 —— 从独立目的地回来时 pop 落回的 Main 停在离开时的 tab 上
    // (popBackOrNavigateToMain 只管 pop, 不换 tab); 在 Main 的收藏/缓存页里点「回到主界面」
    // 更是没有任何 pop, 全靠这里. 标志本身不在这里清, 它的消费者是探索页 (拿去聚焦轮播主按钮).
    // 必须 snapshotFlow 盯标志而不是 LaunchedEffect(page): 后者只在换 tab 时重跑, 而"在
    // 收藏页打开菜单点回主页"整个过程 page 根本没变过, 标志置位时不会有人看它
    val backLongPressHost = LocalTvBackLongPressHost.current
    val currentPage by rememberUpdatedState(page)
    val currentOnNavigateToPage by rememberUpdatedState(onNavigateToPage)
    if (backLongPressHost != null) {
        LaunchedEffect(backLongPressHost) {
            snapshotFlow { backLongPressHost.pendingHomeFocus }.collect { pending ->
                if (pending && currentPage != MainScreenPage.Exploration) {
                    currentOnNavigateToPage(MainScreenPage.Exploration)
                }
            }
        }
    }
    Box(
        // 全屏背景由本外层 Box 统一绘制, 主壳内各页 (探索/收藏/缓存) 在 TV 上把自身 Scaffold
        // 设透明透出此色 (搜索/设置是独立页面, 不受影响); 颜色与侧边栏展开面板一致.
        modifier.fillMaxSize().background(AniThemeDefaults.shellBackgroundColor)
            // 进入 Main 的焦点一律先送进内容区 (而非侧边栏); 页面对落点还有更精确的意见时
            // 在自己根上再挂一层 focusProperties.onEnter 改道 (如探索页回上次聚焦的卡)
            .focusProperties { onEnter = { contentFocus.requestFocus() } }
            .focusGroup(),
    ) {
        Box(
            Modifier.fillMaxSize()
                .padding(start = TvNavigationRailDefaults.CollapsedWidth)
                .focusRequester(contentFocus)
                .focusGroup(),
        ) {
            pageContent()
        }
        // 头像关联动作 (焦点在头像上时于其上方浮现): 按登录态切换
        val loggedIn = selfInfo.selfInfo != null && selfInfo.isSessionValid != false
        val avatarActions = buildList {
            if (loggedIn) {
                add(
                    TvRailAvatarAction(
                        Icons.Outlined.Edit,
                        stringResource(Lang.settings_account_popup_edit_profile),
                    ) { onNavigateToSettings(SettingsTab.PROFILE) },
                )
                add(
                    TvRailAvatarAction(
                        Icons.Outlined.History,
                        stringResource(Lang.playback_history_title),
                    ) { navigator.navigatePlaybackHistory() },
                )
                add(
                    TvRailAvatarAction(
                        Icons.AutoMirrored.Outlined.Logout,
                        stringResource(Lang.settings_account_popup_logout),
                    ) { onLogout() },
                )
            } else {
                add(
                    TvRailAvatarAction(
                        Icons.AutoMirrored.Outlined.Login,
                        stringResource(Lang.settings_account_popup_login_register),
                    ) { navigator.navigateEmailLoginStart() },
                )
                add(
                    TvRailAvatarAction(
                        Icons.Outlined.History,
                        stringResource(Lang.playback_history_title),
                    ) { navigator.navigatePlaybackHistory() },
                )
            }
        }
        TvNavigationSideRail(
            selfInfo = selfInfo,
            avatarActions = avatarActions,
            onAvatarClick = {
                if (loggedIn) onNavigateToSettings(SettingsTab.PROFILE) else navigator.navigateEmailLoginStart()
            },
            // 返回/右键: 还原回进入侧边栏之前内容区最后聚焦的元素 (经内容区 enter, 页面
            // 自己的 onEnter 改道会把焦点送回原处, 如探索页的 focusRestorer 链)
            onExitFocus = { runCatching { contentFocus.requestFocus() } },
            items = buildTvRailItems(
                onSearch = onNavigateToSearch,
                onNavigateToPage = onNavigateToPage,
                onSettings = { onNavigateToSettings(null) },
            ),
            modifier = Modifier.fillMaxHeight(),
        )
        TvExitHintToast(state = exitHintState, text = pressAgainText)
    }
}

/**
 * 「再按一次退出」的提示条 —— 照着 **Android 系统 Toast** 的观感画的, 但**可见性由外部传进来的
 * 动画状态直接驱动**, 自己不带任何计时器. 调用方拿同一个 [MutableTransitionState] 当武装判据,
 * 所以"看得见"与"按下去会退出"严格是同一件事, 连淡出中也算.
 *
 * ## 为什么不直接用现成的两个
 *
 * - `LocalToaster.toast()`: Android 上它就是系统 `Toast.makeText(..., LENGTH_LONG)` (见
 *   MainActivity), 时长写死约 3.5 秒且**改不了**. 而这条提示的全部意义在于"看得见 = 按下去会
 *   退出", 时长必须等于武装窗口 (1 秒), 对不上就会变成谎话: 用户看着提示按返回, 什么也没发生.
 *   它还是个单通道, 这一秒里任何别的提示都会把它顶掉, 而武装状态还亮着.
 * - `widgets.Toast` 那个 composable: 时长能绑, 但它是应用自己那套样式 (surfaceContainerHigh、
 *   距底 100dp、横向 60dp 留白、最宽 640dp), 与系统 Toast 差别明显 —— 用户要的是原来那条.
 *
 * 所以照系统 Toast 复刻: 底部居中、距底 [TV_EXIT_HINT_BOTTOM_DP] (framework 的 toast y offset
 * 就是 64dp)、pill 圆角、半透明黑底白字、紧凑内边距.
 *
 * **颜色刻意写死而不取主题**: 系统 Toast 在深浅两种主题下都是深色条白字, 取 inverseSurface 那类
 * 反色角色的话深色主题下会翻成白条黑字, 就不是"原来那条"了.
 */
@Composable
private fun BoxScope.TvExitHintToast(state: MutableTransitionState<Boolean>, text: String) {
    AnimatedVisibility(
        visibleState = state,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TV_EXIT_HINT_BOTTOM_DP),
        // 动画短促: 这条提示的角色是"刚才那下没退出, 想退就立刻再按"的闪现信号, 不是让人读的
        // 说明; 淡出还要算进武装时间里 (见调用处), 拖长就等于偷偷延长了窗口
        enter = fadeIn(tween(TV_EXIT_HINT_FADE_IN_MILLIS)),
        exit = fadeOut(tween(TV_EXIT_HINT_FADE_OUT_MILLIS)),
    ) {
        Surface(
            // 不是 pill (percent = 50): 系统 Toast 的角是圆的但仍看得出是个矩形.
            // 取条高的三分之一左右
            shape = RoundedCornerShape(TV_EXIT_HINT_CORNER),
            color = TV_EXIT_HINT_BACKGROUND,
            contentColor = TV_EXIT_HINT_CONTENT,
        ) {
            Text(
                text,
                Modifier.padding(
                    horizontal = TV_EXIT_HINT_PADDING_HORIZONTAL,
                    vertical = TV_EXIT_HINT_PADDING_VERTICAL,
                ),
                // 行高压到 [TV_EXIT_HINT_LINE_HEIGHT] 并关掉 font padding: 这两样决定条子有多高,
                // 而条高又决定 pill 的半径 —— 差 4dp 就会让圆角看起来"不太一样"
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = TV_EXIT_HINT_LINE_HEIGHT,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * 退出确认弹窗 —— 与长按手势弹出的动作面板是**同一个面板** ([TvActionPanelDialog]).
 *
 * 两者只差两点: 本弹窗不出「回到主界面」(它只在探索页 hero 上弹得出来, 那时按它是空操作),
 * 以及**初始焦点落「退出应用」**.
 *
 * 落点不同不是不一致, 而是**跟着意图走**: 本弹窗只有一条路能弹出来 —— 在探索页 hero 上按返回,
 * 也就是"已经退到最外层还在按返回", 那时用户的意图就是走人; 而长按手势是主动打开面板, 意图是
 * "看一眼 / 回去接着看", 落点因此是最上面那个能按的东西. 同一个键在两种意图下给出各自最可能的
 * 那颗, 比强行统一成一条规则更省一次按键.
 *
 * 代价是那条老风险还在: 用惯长按手势的人可能条件反射按确定. 兜着它的是**「退出应用」是全面板
 * 仅有的两处错误色之一** (聚焦时红色实底), 而且面板底下那行固定标签这一刻明明白白写着"退出应用"
 * —— 焦点落在一颗明显不同的按钮上、旁边还有一行字, 本身就是提示.
 */
@Composable
private fun TvExitAppDialog(
    navigator: AniNavigator,
    onDismissRequest: () -> Unit,
    onExitApp: () -> Unit,
) {
    TvActionPanelDialog(
        navigator = navigator,
        playback = LocalPlaybackSessionEntry.current,
        refreshHost = LocalTvPageRefreshHost.current,
        watchTogether = LocalWatchTogetherEntry.current,
        // 退出确认里不出服务连通那一行: 这个弹窗只回答"要不要退出", 多一行状态就是多一个
        // 让人停下来读的东西, 而它与该不该退出没有关系
        connectivity = null,
        onGoHome = null,
        onExitApp = onExitApp,
        onDismissRequest = onDismissRequest,
        defaultFocus = TvActionPanelDefaultFocus.EXIT,
    )
}

/**
 * 长按返回弹出的快捷菜单 (播放器之外全局同一个, 由应用根部组合, 见 TvPageVariants).
 *
 * 与退出确认弹窗共用 [TvActionPanelDialog]; 本入口多一颗「回到主界面」, 初始焦点落它.
 *
 * @param onGoHome 回到主界面: pop 到 Main + 置 pendingHomeFocus, 由调用方 (根部) 实现 ——
 *   切 tab 与聚焦轮播主按钮分别由主壳和探索页看着标志接力完成.
 * @param refreshHost 当前页注册的强制刷新动作 (没人注册就不显示「刷新本页」).
 * @param watchTogether 「一起看」入口把手; 本入口由调用方传进来而不是读
 *   [LocalWatchTogetherEntry] —— 本菜单组合在 AniAppContent **外面**, 那个 CompositionLocal
 *   在这里读到的是默认空实例 (见 [WatchTogetherEntryState]).
 */
@Composable
fun TvQuickActionMenu(
    navigator: AniNavigator,
    playback: PlaybackSessionEntry,
    refreshHost: TvPageRefreshHost,
    watchTogether: WatchTogetherEntryState?,
    onGoHome: () -> Unit,
    onExitApp: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    TvActionPanelDialog(
        navigator = navigator,
        playback = playback,
        refreshHost = refreshHost,
        watchTogether = watchTogether,
        // 在这里 (而不是根部) 建: 本菜单只在打开的那一瞬间被组合, 所以整个探测子系统在用户第一次
        // 长按返回之前根本不存在 —— 挂在根部就等于每次冷启动都多跑五个请求
        connectivity = viewModel { TvServiceConnectivityState() },
        onGoHome = onGoHome,
        onExitApp = onExitApp,
        onDismissRequest = onDismissRequest,
        defaultFocus = TvActionPanelDefaultFocus.CARD,
    )
}

/** [TvActionPanelDialog] 打开时焦点落哪一颗 —— 按**入口的意图**分, 见各入口的文档. */
private enum class TvActionPanelDefaultFocus {
    /**
     * 主动打开面板 (长按播放键 / 长按返回): 落**最上面那个能按的东西** —— 卡片能按 (正在播放 /
     * 接下来播放) 就是卡片, 只剩占位卡时是圆钮那排第一颗.
     *
     * 判据从"有没有会话"变成"卡片能不能按", 因为「接下来播放」那一态也是能按的. 落点随之变化
     * 不违反"落点恒定"那条: 那条防的是同一个位置的按键做两件不同的事, 而卡片是死是活是屏幕上
     * 一眼可见的 (什么都没得播时那块是明显压暗的占位卡). 占位卡虽然吃焦点, 但**不做默认落点**
     * —— 一进来就落在一个按不动的东西上, 与"接住已经在这儿的焦点"是两回事.
     */
    CARD,

    /** 探索页 hero 上按返回 (意图就是走人): 落最后一颗「退出应用」, 见 [TvExitAppDialog]. */
    EXIT,
}

/**
 * 动作面板里的具名焦点位置 (见 [TvFocusScope]).
 *
 * 只给**当得起目标**的四个位置命名: 卡片那一块、圆钮那排的首尾两颗、连通行末尾的刷新钮.
 * 中间那些圆钮不是任何方向重定向或程序化送焦的目标, 不必命名 —— 到它们靠默认的空间搜索.
 */
private enum class TvActionPanelFocus : TvFocusKey {
    /** 卡片那一块 (正在播放 / 接下来播放 / 占位卡三态共用一个 key: 接力的目标是这一块, 不分哪一态). */
    Card,
    FirstCapsule,
    LastCapsule,
    Refresh,
}

/**
 * **全局动作面板**: 上面一张"正在播放"卡, 下面一排圆钮, 再下面一行固定标签.
 * 条目按上下文出现. **凡是"去别处"的都关面板** (进去看 / 回主页 / 退出); 改本面板自己显示的
 * 东西的那两个不关 —— 卡片右端那一条 (后台会话的开关: 关掉它 / 在后台起起来) 与连通行的刷新钮,
 * 它们的结果就写在面板上.
 *
 * ## 为什么是这个形状
 *
 * 按**作用对象**分两层: 卡片管这个会话 (整块按下去 = 回去接着看, 右端 = 关掉它),
 * 圆钮那排管整个应用 (回主页 / 刷新 / 退出). 原先是"小封面 + 五条全宽按钮"的一列, 毛病不在
 * 封面小, 而在**媒体块与操作块视觉上互不属于彼此** —— 把媒体做成操作本身, 这个问题就没有了,
 * 面板也从 380×393 的竖条变成 460×232 的横条, 更像电视上的控制条而不是手机上的菜单.
 *
 * 顶上这块同时让面板变成**状态显示**: 忘了自己有没有开着播放, 长按一次就知道 (卡片上那行小字
 * 直接写着在找源/在缓冲/还是没有会话). 这是刻意不再往各页面散布提示与入口的选择 —— 遥控器上要记
 * 的特殊操作已经不少, 与其到处加一句话, 不如让一个面板既是入口又是状态.
 *
 * **没有会话时这块不是空的**: 它变成「接下来播放」—— 上次没看完的那一集, 那一集看完了就是下一集
 * (见 [TvUpNextStore]). 于是这个面板从"回到正在播放的入口"升成"接着看的入口": 最常见的情形
 * (没有后台会话) 从一块死板变成长按播放键 + 确定就能接着看. 三态的渲染见本函数下半段.
 *
 * ## 焦点
 *
 * 只有上下 + 卡片内一次左右, 没有网格. 落点见 [TvActionPanelDefaultFocus].
 *
 * **落点在打开那一刻定死**: 「接下来播放」是异步算出来的, 而它在不在决定了落点 —— 数据晚到几帧
 * 再改落点, 用户看到的就是"焦点自己跳走了". 晚到的那次只补卡片内容.
 *
 * 卡片上那些按钮会随状态从布局里消失 (✕ 只在有会话时在场), 而**焦点所在节点一消失, Compose 不会
 * 自动改派**, 面板就会一个焦点都没有、方向键全失效. 凡是会让卡片换态的动作都必须显式把焦点接住,
 * 见 ✕ 那里的 `reclaimCardFocus`.
 */
@Composable
private fun TvActionPanelDialog(
    navigator: AniNavigator,
    playback: PlaybackSessionEntry,
    refreshHost: TvPageRefreshHost?,
    watchTogether: WatchTogetherEntryState?,
    connectivity: TvServiceConnectivityState?,
    onGoHome: (() -> Unit)?,
    onExitApp: () -> Unit,
    onDismissRequest: () -> Unit,
    defaultFocus: TvActionPanelDefaultFocus,
) {
    // 焦点走事件驱动的 TvFocusScope: 锚点用具名 key 声明, 送焦请求在锚点附着那一刻送达.
    // 换掉的是原先"延时 + 每帧重试 requestFocus + 到位确认"那一套 —— 见 TvFocusScope 的文档.
    val focus = rememberTvFocusScope()
    val session = playback.session
    val upNext = if (session == null) TvUpNextStore.target else null

    // 圆钮先列出来再渲染: 默认焦点按"第一颗 / 最后一颗"定位, 而哪些颗在场是上下文决定的
    val actions = buildList {
        onGoHome?.let { goHome ->
            add(
                TvActionPanelAction(Icons.Rounded.Home, stringResource(Lang.tv_quick_menu_home)) {
                    onDismissRequest()
                    goHome()
                },
            )
        }
        // 「一起看」: 只在设置里打开了功能时出现. 原先是侧边栏最底那颗常驻图标, 2026-08-17 挪到
        // 这里 —— 侧边栏那颗要"按左 + 一路往下", 而这个面板是一个手势就到; 播放器内够不到面板,
        // 但那里本来就有胶囊行末尾那颗常驻入口, 覆盖不缺.
        //
        // 不放第一颗: 默认焦点恒定落第一颗 (见本函数文档), 位置就是肌肉记忆, 新增条目不该把它挪走.
        watchTogether?.takeIf { it.enabled }?.let { entry ->
            add(
                TvActionPanelAction(Icons.Rounded.SyncAlt, stringResource(Lang.watch_together_title)) {
                    onDismissRequest()
                    // 面板压在普通页面上 (播放器里长按返回是收叠层, 弹不出本面板), 不是深色背景
                    entry.open()
                },
            )
        }
        refreshHost?.current?.let { refresh ->
            val toast = LocalToaster.current
            val refreshingText = stringResource(Lang.tv_force_refresh_toast)
            add(
                TvActionPanelAction(Icons.Rounded.Refresh, stringResource(Lang.tv_quick_menu_refresh)) {
                    onDismissRequest()
                    // 刷新本身可能没有可见变化 (数据没变时界面一模一样), 必须给一句反馈
                    toast.toast(refreshingText)
                    refresh()
                },
            )
        }
        // 退出恒在最后一颗: 下面按 lastIndex 定位默认焦点, 且危险动作不该夹在中间
        add(
            TvActionPanelAction(
                Icons.Rounded.PowerSettingsNew,
                stringResource(Lang.exit_app_title),
                danger = true,
                onClick = onExitApp,
            ),
        )
    }
    // 落点按入口的意图分 (见 TvActionPanelDefaultFocus): 主动打开面板时落最上面那个能按的东西,
    // 探索页 hero 上按返回时落「退出应用」——那一刻用户的意图就是走人.
    //
    // 卡片是落点时圆钮行里没有"默认那一颗", 用 -1 让下面所有 `index == focusIndex` 一致地不成立
    // **落点在面板打开那一刻定死**: 卡片能不能按决定了默认落点, 而「接下来播放」是异步算出来的
    // (见 TvUpNextStore) —— 数据晚到几帧再改落点, 用户看到的就是"焦点自己跳走了". 晚到的那次
    // 只补卡片内容, 落点不动 (卡片仍能按"上"聚上去).
    val cardActionableAtOpen = remember { session != null || TvUpNextStore.target != null }
    val cardIsDefault = defaultFocus == TvActionPanelDefaultFocus.CARD && cardActionableAtOpen
    val focusIndex = when {
        cardIsDefault -> -1
        defaultFocus == TvActionPanelDefaultFocus.EXIT -> actions.lastIndex
        else -> 0
    }
    // 服务连通那一条上只有末尾那颗刷新钮吃焦点, 而它在卡片正下方、圆钮右上方 —— 默认的方向搜索
    // 会把它当成"卡片↕圆钮"路上的中间站. 下面四条改道把它挪出这条主路: 只能从最后一颗圆钮
    // **向右**进去, 圆钮与它自己按"上"都回卡片, 卡片按"下"直落圆钮那排.
    //
    // 具名锚点取代原先那套 requester 别名: 从前"默认落点"是一个 FocusRequester, 按 focusIndex
    // 别名到卡片/首颗/末颗圆钮上, 另外三个 alt* 顶替没被别名到的那些位置 —— 绕的就是"一个节点上
    // 不能挂两个 requester"。改成按 key 声明之后每个节点恒有且只有一个 key, 这套别名不再需要。
    val initialKey = when {
        cardIsDefault -> TvActionPanelFocus.Card
        focusIndex == 0 -> TvActionPanelFocus.FirstCapsule
        else -> TvActionPanelFocus.LastCapsule
    }

    // 卡片那一块在三态下各说各的话 (见下面渲染处)
    val resumeLabel = stringResource(Lang.exit_app_back_to_playback)
    val nothingToPlayLabel = stringResource(Lang.playback_nothing_to_play)
    val closeLabel = stringResource(Lang.exit_app_close_playback)
    val prepareLabel = stringResource(Lang.playback_prepare_in_background)
    // 后台起会话要建 ViewModel, 那需要平台 Context (见 startInBackground)
    val context = LocalContext.current
    val upNextLabel = stringResource(
        if (upNext?.continuing == true) Lang.playback_up_next_continue else Lang.playback_up_next_start,
    )

    // 标签行显示当前聚焦项的名字. 失焦时**保持上一次的值**而不是清空: 焦点在两个目标之间移动
    // 时会有一帧谁都没有焦点, 清空就会闪一下.
    // 初值要与默认落点对上: 卡片是落点时按它此刻是哪一态取字, 写死"回到正在播放"的话,
    // 「接下来播放」态一打开就会先亮一句不对的
    var focusedLabel by remember {
        mutableStateOf(
            when {
                !cardIsDefault -> actions.getOrNull(focusIndex)?.label.orEmpty()
                session != null -> resumeLabel
                else -> upNextLabel
            },
        )
    }
    var focusedDanger by remember { mutableStateOf(!cardIsDefault && actions.getOrNull(focusIndex)?.danger == true) }
    // ✕ 关掉会话之后要把焦点接到就地换上来的那张卡上 (「接下来播放」或占位卡, 看有没有得播),
    // 这两个状态是那次接力的两端. 不接的话焦点会随 ✕ 一起消失 —— 节点没了 Compose **不会**自动
    // 改派, 面板方向键全失效. 到位标志由三态共用: 接力的目标是"卡片那一块", 不关心它是哪一态.
    var reclaimCardFocus by remember { mutableStateOf(false) }

    // 弹窗是独立窗口, 焦点驱动的界面必须显式请求.
    //
    // 一次到位的那条路仍是下面 Column 上的 onEnter (任何"进入本面板"的焦点请求直接改道到目标).
    // 这里是兜底: onEnter 那一下若因节点还没附着而失败 (requestFocus 对未附着节点静默失败),
    // 由解析器在锚点附着那一刻补上.
    //
    // 与旧实现的差别: 从前是 `delay(300ms)` 之后每帧重发 requestFocus 直到到位或试满 40 次,
    // 那 300ms 正是"弹窗一开焦点先落在布局顺序第一个可聚焦项、之后才跳到该去的地方"那一跳的
    // 由来; 而重发窗口里用户按遥控器就会被抢回去. 现在两样都没有 —— 锚点附着即送, 单发不抢.
    focus.InitialFocus(initialKey)

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            Modifier.width(TV_ACTION_PANEL_WIDTH),
            shape = RoundedCornerShape(16.dp),
            // 与其他 TV 弹窗同一底色 (半透明玻璃), 内容色显式给 —— 半透明底查不到 "on" 色,
            // 不给会退回 LocalContentColor 的默认纯黑 (见 AniCenteredPanelDialog 的注释)
            color = centeredPanelColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                Modifier
                    // 进入本面板的焦点一律改道到默认落点, 不让它先落在布局顺序第一个可聚焦项上
                    // (见上面那条 effect 的说明). 失败就放行默认进组, 由那条 effect 纠正 ——
                    // 这里刻意不 cancelFocusChange(): 那会让面板一个焦点都没有, 方向键全失效.
                    .focusProperties {
                        onEnter = { runCatching { focus.requesterOf(initialKey).requestFocus() } }
                    }
                    // onEnter 只在**焦点组**节点上生效
                    .focusGroup()
                    // 方向/确认键即取消在途送焦请求 —— 框架不与用户抢焦点 (TvFocusScope 的约定:
                    // 持有 scope 的地方必须同时装 Resolver 与这个信号)
                    .tvFocusNavSignal(focus)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                // 顶上这一块**三态**, 三者同宽同高同圆角 —— 面板尺寸恒定, 圆钮那排的位置不随
                // 上面是什么上下跳 (遥控器上位置就是肌肉记忆):
                //
                //  1. **正在播放**: 有后台会话;
                //  2. **接下来播放**: 没有会话但有得播 (上次没看完的那一集, 或那一集看完了就是
                //     下一集; 见 TvUpNextStore) —— 这一态让"没有会话"这个最常见的情形从一块
                //     死板变成"长按播放键 + 确定"就接着看, 右端那一条还能只让它在后台先热起来;
                //  3. **空**: 两样都没有. 才是那张压暗的占位卡.
                val cardSubjectId = session?.subjectId ?: upNext?.subjectId
                val cardEpisodeId = session?.episodeId ?: upNext?.episodeId
                if (cardSubjectId != null && cardEpisodeId != null) {
                    // 前两态**共用一个调用点**: 换态时 Compose 才认得出是同一批节点, 焦点因此
                    // 留在原位 —— 尤其是右端那一条 (后台会话的开关), 它在两态里是同一颗按钮的
                    // 两个方向, 按完不该把焦点甩到别处去
                    TvPlaybackCard(
                        subjectId = cardSubjectId,
                        episodeId = cardEpisodeId,
                        subjectTitle = session?.subjectTitle ?: upNext?.subjectTitle.orEmpty(),
                        episodeSort = session?.episodeSort ?: upNext?.episodeSort.orEmpty(),
                        // 有会话读实时进度; 没有则是播放进度表里那条记录 (上次看到哪),
                        // 时长不知道就整条线不画 —— 与"正在播放但时长未知"同一条规矩
                        progress = {
                            if (session != null) {
                                playback.progress
                            } else {
                                upNext?.takeIf { it.durationMillis > 0 }?.let {
                                    PlaybackProgress(it.positionMillis, it.durationMillis)
                                }
                            }
                        },
                        // 有会话是它此刻的状态 (在找源/在缓冲/…), 没有则是固定一句
                        status = {
                            if (session != null) {
                                playbackSessionStatusText(playback.status)
                            } else {
                                PlaybackSessionStatusText(upNextLabel, PlaybackSessionStatusSeverity.Normal)
                            }
                        },
                        mainLabel = if (session != null) resumeLabel else upNextLabel,
                        // 历史进度压暗: 那条线不是正在走的进度
                        progressBarAlpha = if (session != null) 1f else TV_UP_NEXT_BAR_ALPHA,
                        // 按"下"的落点按**正下方是什么**给, 不按"同属一行"给:
                        //  - 左半 (进去看) → 圆钮那排第一颗, 跳过中间那条服务连通;
                        //  - 右端那一条 → **服务连通行末尾那颗刷新钮** —— 它就在正下方, 而刷新钮
                        //    平时只能从最后一颗圆钮向右进去, 这条正好给它第二个入口.
                        //    先前这里给的是圆钮那排最后一颗 (「退出应用」), 空间上毫无道理:
                        //    从卡片右上角按一下"下"就跳到面板左下方那颗红的, 用户实测反直觉.
                        //    没有服务连通行时 (退出确认变体) 仍然落最后一颗圆钮.
                        // 左半同时是圆钮与刷新钮按"上"的落点
                        // 原先这里手写两个 onFocusChanged 记账: cardFocused 给 ✕ 之后那次接力当
                        // 到位判据, focusArrived 给面板兜底 effect 当到位判据. 两者现在都不需要了
                        // —— 事件驱动的送焦不用"到位确认"这个概念 (请求在锚点附着那一刻送达就完事).
                        // 锚点自身仍在上报焦点得失, 哪天要读"卡片此刻有没有焦点"用 focus.isFocused(Card).
                        mainModifier = Modifier
                            .tvFocusAnchor(focus, TvActionPanelFocus.Card)
                            .tvFocusLink(focus, down = TvActionPanelFocus.FirstCapsule),
                        closeModifier = Modifier
                            .tvFocusLink(
                                focus,
                                down = if (connectivity != null) {
                                    TvActionPanelFocus.Refresh
                                } else {
                                    TvActionPanelFocus.LastCapsule
                                },
                            ),
                        // 主体两态一样: 进播放页看这一集.
                        // force: 跳过一起看跟随模式的导航守卫 —— 那道守卫只在目标与房主已发布的
                        // 播放位置一字不差时放行, 跟随者退出播放页后连回自己的会话都会被挡住.
                        // 跟随该拦的是"播别的", 不是"回去接着看"
                        onClick = {
                            onDismissRequest()
                            navigator.navigateEpisodeDetails(cardSubjectId, cardEpisodeId, force = true)
                        },
                        // 没会话那一档用**空心的圆圈套三角**, 不用下载图标: 本应用真有缓存/下载
                        // 功能, 下载的字形会被直接读成"把这一集缓存下来", 指的是另一件事.
                        // 空心而不是实心 —— 实心读作"正在播", 而这一颗按下去才开始热
                        trailingIcon = if (session != null) Icons.Rounded.Close else Icons.Outlined.PlayCircle,
                        trailingLabel = if (session != null) closeLabel else prepareLabel,
                        // 只有"关掉会话"是危险动作
                        trailingDanger = session != null,
                        // **右端这一条是后台会话的开关**, 两个方向:
                        //
                        // - 有会话 → ✕ 关掉它. **关掉不关面板** (面板里唯一一个按下去不关的动作):
                        //   其余每一颗都是"离开这里去别处", 关面板是那个动作的一部分; 而这颗改的是
                        //   **面板自己正在显示的东西**. 会话本来就在后台、屏幕上看不出来, 按完就关
                        //   面板的话用户只看见面板消失, 没有任何反馈说明会话真结束了. 关掉之后卡片
                        //   就地退成「接下来播放」, 结果直接写在原位.
                        // - 没会话 → **在后台把这一集起起来**, 不进播放页 (见
                        //   PlaybackSessionEntry.startInBackground). 慢的源要十几秒, 原先只能进去
                        //   干等或者进去了再退出来等; 现在按一下让它先热着, 人接着浏览, 就绪时照常
                        //   有提示. 起来之后卡片当场变成「正在播放」, 小字开始报进度.
                        //
                        // 两个方向合用同一个位置, 于是"按一下开、再按一下关"本身就是可逆的;
                        // 而"我现在就要看"始终是主体那一下, 没有因此变成两次按键.
                        onTrailingClick = {
                            if (session != null) {
                                playback.close()
                                // 焦点只在**换组件**那一路会掉: 关掉之后要是连「接下来播放」都没有,
                                // 整块变成占位卡 (另一个 composable, 节点重建, 而 Compose 不会
                                // 自动改派焦点). 退成「接下来播放」是同一个调用点, 右端这一条
                                // 本身都不重建, 焦点自然留在原处 —— 那一路**不能**去 reclaim,
                                // 否则会把焦点从这颗按钮抢到卡片主体上.
                                if (TvUpNextStore.target == null) reclaimCardFocus = true
                            } else {
                                playback.startInBackground(cardSubjectId, cardEpisodeId, context)
                            }
                        },
                        onFocusLabel = { label, danger ->
                            focusedLabel = label
                            focusedDanger = danger
                        },
                    )
                } else {
                    // 连"接下来播放"都没有 (从没看过东西 / 整部看完了且没有下一集): 一张压暗的
                    // 占位卡. 它**可聚焦但没有动作** —— 单看这一屏是笔亏账 (聚焦一个按下去没反应
                    // 的东西), 换来的是卡片换态时焦点**留在原处**而不是整个消失 (焦点所在节点一没,
                    // Compose 不会自动改派). 这一态现在很少见到: 关掉会话通常会退成「接下来播放」.
                    TvNowPlayingPlaceholderCard(
                        Modifier
                            .tvFocusAnchor(focus, TvActionPanelFocus.Card)
                            .tvFocusLink(focus, down = TvActionPanelFocus.FirstCapsule),
                        onFocusChanged = { focused ->
                            if (focused) {
                                focusedLabel = nothingToPlayLabel
                                focusedDanger = false
                            }
                        },
                    )
                }
                if (reclaimCardFocus) {
                    // 换上来的是**新建的节点**, 焦点不会自己过去. 从前这里要走重试, 因为它这一帧
                    // 多半还没附着、requestFocus 会静默失败; 现在请求会悬挂到 Card 锚点附着那一刻
                    // 再送, 一次就够. 放在三态**外面**: 接力的目标是卡片那一块, 换上来的是哪一态无所谓
                    LaunchedEffect(Unit) {
                        focus.request(TvActionPanelFocus.Card)
                        reclaimCardFocus = false
                    }
                }
                Spacer(Modifier.height(if (connectivity != null) 12.dp else 18.dp))
                // 服务连通那一行在圆钮那排**上面**: 它是状态, 归上半部分 (卡片那一块也是状态);
                // 圆钮那排是动作, 下面紧跟着的标签行是它的说明 —— 中间插一行会把说明和它说明的
                // 东西分开. 焦点顺序也因此是 卡片 → 这一行 → 圆钮.
                connectivity?.let { state ->
                    val refreshLabel = stringResource(Lang.tv_service_check_hint)
                    TvServiceConnectivityRow(
                        state = state,
                        // 焦点在这里时标签行照样写: 那颗光看图标只知道"刷新", 不知道刷新什么,
                        // 更不知道还能长按 —— 与圆钮那排是同一条理由 (见标签行的注释)
                        onFocused = {
                            focusedLabel = refreshLabel
                            focusedDanger = false
                        },
                        onOpenProxySettings = {
                            onDismissRequest()
                            navigator.navigateSettings(SettingsTab.PROXY)
                        },
                        refreshFocusRequester = focus.requesterOf(TvActionPanelFocus.Refresh),
                        refreshFocusProperties = {
                            // 左/下回圆钮那排 (它就在最后一颗的右上方), 上回卡片主体.
                            // 进得来的路有两条: 最后一颗圆钮按右, 以及卡片右端那颗 ✕ 按下
                            // (它在正上方). 按"上"仍统一回卡片**主体**而不是回 ✕ ——
                            // 从圆钮行走上来的人要的是那张卡, 不是那颗关闭
                            left = focus.requesterOf(TvActionPanelFocus.LastCapsule)
                            down = focus.requesterOf(TvActionPanelFocus.LastCapsule)
                            // 没有会话时上面换成占位卡, 那张同样吃焦点, 所以这里不按会话分叉
                            up = focus.requesterOf(TvActionPanelFocus.Card)
                            // 向右到头就停: 它是"圆钮那排最右边一颗", 右边没有下一站.
                            // 不拦的话默认的方向搜索会往右上方爬到卡片右端那颗 ✕ 上 (那颗有一
                            // 部分确实在它右边), 长按右键就表现成"按到头忽然跳上去了"
                            right = FocusRequester.Cancel
                        },
                        // 宽度由内容决定 (见那边的注释), 所以要在这里居中
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                ) {
                    actions.forEachIndexed { index, action ->
                        TvCapsuleButton(
                            onClick = action.onClick,
                            icon = { Icon(action.icon, contentDescription = null) },
                            size = TV_CAPSULE_SIZE_LARGE,
                            glyphSize = TV_ICON_GLYPH_SIZE_LARGE,
                            danger = action.danger,
                            onFocusChanged = { focused ->
                                if (focused) {
                                    focusedLabel = action.label
                                    focusedDanger = action.danger
                                }
                            },
                            modifier = Modifier
                                .focusProperties {
                                    // 上恒定回卡片: 卡片是常用目标 (回去接着看), 不能因为中间
                                    // 多了一条状态就变成两步. 没有会话时上面那张占位卡同样吃
                                    // 焦点, 所以这里不按会话分叉 —— 它早先不可聚焦, 那时若不
                                    // 写死, 默认方向搜索会越过它去够更远的东西 (连通行的刷新钮),
                                    // 表现成"按上跳到一个奇怪的地方"
                                    up = focus.requesterOf(TvActionPanelFocus.Card)
                                    // 刷新钮的唯一入口
                                    if (connectivity != null && index == actions.lastIndex) {
                                        right = focus.requesterOf(TvActionPanelFocus.Refresh)
                                    }
                                }
                                // 首尾两颗是具名锚点 (方向重定向与程序化送焦都指它们);
                                // 中间那些不是任何人的目标, 不挂
                                .then(
                                    when {
                                        index == 0 ->
                                            Modifier.tvFocusAnchor(focus, TvActionPanelFocus.FirstCapsule)

                                        index == actions.lastIndex ->
                                            Modifier.tvFocusAnchor(focus, TvActionPanelFocus.LastCapsule)

                                        else -> Modifier
                                    },
                                ),
                        )
                    }
                }
                // 固定一行标签: 圆钮是纯图标, 而「关闭正在播放」「退出应用」两颗光看图标猜不出来
                // (前者"关掉什么?", 后者容易读成"关电视"), 换成图标就等于把信息从界面上拿走, 这一行
                // 是把它放回去. 用固定行而不是详情页那种聚焦浮出: 永远只有一个标签, 没有多个标签
                // 同时淡入淡出的时序问题, 且用户不必先把焦点挪过去才知道这排是干什么的.
                Text(
                    focusedLabel,
                    Modifier.fillMaxWidth().padding(top = 8.dp).height(TV_ACTION_PANEL_LABEL_HEIGHT),
                    color = if (focusedDanger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private class TvActionPanelAction(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * **面板顶上那张卡**: 左边一块 16:9 缩略图 + 剧名/集号/时间, 右端一颗关闭; 底缘压一条播放进度线.
 *
 * 两种用法共用本组件, 只差参数 (见 [TvActionPanelDialog] 里的三态):
 *
 * - **正在播放**: 有后台会话. 小字是会话此刻的状态, 进度线是实时位置, 右端是 ✕ (关掉会话).
 * - **接下来播放**: 没有会话但有得播 (见 [TvUpNextStore]). 小字是「继续播放/接下来播放」,
 *   进度线是上次看到哪、且压暗 (那是历史进度不是正在走的进度), 右端是「在后台准备」.
 *
 * 整块 (除右端那一条) 是**进去看**, 两态都一样 —— 媒体信息不是按钮上方的附属块, 它**就是**那个
 * 按钮. 右端那一条则是**后台会话的开关**: 有会话时关掉它, 没有时开起来. 同一个位置在两态里做的是
 * 同一件事的两个方向, 所以换态时焦点留在原处也不会读错 (两态共用一个调用点, 节点因此不重建).
 * 它长在卡片右端而不是排进下面那排圆钮, 是因为单独一颗图标没人知道它作用于什么 (最容易读成
 * "关掉这个面板"); 挪到卡片上之后, 指代对象由位置本身给出.
 *
 * **两块的聚焦表现刻意不同**: 主体是**玻璃高亮** (半透明主题色渐亮, 见下), 关闭是错误色实底.
 * 两种不同处理, 一眼看得出焦点在哪一半. 关闭未聚焦时压到次要色 —— 它是逃生口不是主角, 但
 * **不能藏起来只在聚焦时出现**: 侧边栏那颗浮出式的关闭按钮刚被删掉, 原因之一就是没有视觉提示
 * 的东西在遥控器上没人猜得到.
 *
 * 主体不用描边: 卡片里有一张图, 描边贴着图边缘显得脏, 而且这块的圆角与内容之间没有卡片那种
 * 留白 (见 `TvFocusRing.Gap` 的约定 —— 竖版卡是内容常驻内缩一圈才好看). 半透明填充压在图**下面**,
 * 既不盖住缩略图, 又与面板本身的玻璃底色是同一种材质.
 *
 * 顶上那行小字**不是固定的"正在播放"**, 而是会话此刻的状态 (在找源 / 在缓冲 / 需要手选 /
 * 加载失败……), 出问题时还会变色. 慢的源要十几秒, 中途可能自动换源、最后卡在等手选 —— 用户退出
 * 播放页正是为了不干等, 那就该让他打开面板的这一刻直接看到进行到哪了, 而不是只能等一声就绪提示,
 * 或者等一个永远不会来的就绪 (见 [PlaybackSessionStatus]).
 *
 * @param progress lambda 而非值: 进度每秒变一次, 收值会把那次读记在调用方 (整个面板) 身上.
 * @param status 同上 —— 它是 `@Composable` 的 (文案要查字符串资源), 但同样在卡片内部才调用,
 *   会话状态那一跳因此记不到整个面板身上.
 * @param mainLabel 主体聚焦时写在面板底部固定标签行上的那句话.
 * @param onTrailingClick `null` = 整块都是主体, 不画右端那一条.
 * @param trailingIcon / @param trailingLabel / @param trailingDanger 右端那一条的图标、标签行文案,
 *   以及聚焦时是不是用错误色实底 (只有"关掉会话"是危险动作).
 * @param progressBarAlpha 进度线的浓度: 实时进度给 1f, 历史进度压暗 (见上).
 * @param onFocusLabel 上报给面板的固定标签行.
 * @param mainModifier 加在左半 (整块那个动作) 上: 面板用它挂焦点落点与方向改道.
 * @param closeModifier 同上, 加在右端 ✕ 那块上.
 */
@Composable
private fun TvPlaybackCard(
    subjectId: Int,
    episodeId: Int,
    subjectTitle: String,
    episodeSort: String,
    progress: () -> PlaybackProgress?,
    status: @Composable () -> PlaybackSessionStatusText,
    mainLabel: String,
    onClick: () -> Unit,
    onTrailingClick: (() -> Unit)?,
    trailingIcon: ImageVector,
    trailingLabel: String,
    trailingDanger: Boolean,
    onFocusLabel: (label: String, danger: Boolean) -> Unit,
    mainModifier: Modifier = Modifier,
    closeModifier: Modifier = Modifier,
    progressBarAlpha: Float = 1f,
) {
    var mainFocused by remember { mutableStateOf(false) }
    var trailingFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    // 玻璃高亮: 平时是面板同款的玻璃底 (glassContainerColor), 聚焦时换成半透明主题色 ——
    // 材质不变只是"亮起来", 而不是加一圈线. 动画让它是渐亮而不是硬切
    val mainContainer by animateColorAsState(
        if (mainFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = TV_NOW_PLAYING_FOCUSED_ALPHA)
        } else {
            glassContainerColor()
        },
    )
    // Box 套 Row: 进度线要横跨整张卡, 所以它是两半的兄弟而不是左半里的孩子.
    // clip 提到最外层, 两半与进度线一起被同一个圆角裁掉
    Box(Modifier.fillMaxWidth().clip(shape).height(TV_NOW_PLAYING_CARD_HEIGHT)) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(mainModifier)
                    .background(mainContainer)
                    .onFocusChanged {
                        mainFocused = it.isFocused
                        if (it.isFocused) onFocusLabel(mainLabel, false)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    ),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvNowPlayingThumbnail(subjectId, episodeId, episodeSort)
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        val statusText = status()
                        Text(
                            statusText.text,
                            color = when (statusText.severity) {
                                // 要用户处理才会继续的那一档单独一个颜色: 它不是错误 (再等也不会好,
                                // 但也没坏), 用 error 会吓人, 用次要色又会被当成"正在进行中"
                                PlaybackSessionStatusSeverity.Attention -> MaterialTheme.colorScheme.tertiary
                                PlaybackSessionStatusSeverity.Error -> MaterialTheme.colorScheme.error
                                PlaybackSessionStatusSeverity.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subjectTitle.isNotBlank()) {
                            Text(
                                subjectTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 进度读在本 lambda 里: 每秒变一次, 只让这一行失效
                        Text(
                            buildString {
                                if (episodeSort.isNotBlank()) {
                                    append(renderEpisodeLabel(episodeSort))
                                }
                                progress()?.let { p ->
                                    if (isNotEmpty()) append(" · ")
                                    append(renderPlaybackTime(p.positionMillis))
                                    if (p.durationMillis > 0) {
                                        append(" / ")
                                        append(renderPlaybackTime(p.durationMillis))
                                    }
                                }
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 右端那一条 (后台会话的开关): 未聚焦与主体同一块玻璃 (卡片看起来是一整块), 聚焦时
            // **实底** —— 与主体的半透明高亮是两种材质, 焦点在哪一半一眼可辨. 实底的颜色按危险与否
            // 分: 关掉会话是错误色, 在后台准备是主题色 (它是个正向动作, 用红的会吓人)
            if (onTrailingClick != null) {
                val trailingContainer by animateColorAsState(
                    when {
                        !trailingFocused -> glassContainerColor()
                        trailingDanger -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Box(
                    Modifier
                        .width(TV_NOW_PLAYING_CLOSE_WIDTH)
                        .fillMaxHeight()
                        .then(closeModifier)
                        .background(trailingContainer)
                        .onFocusChanged {
                            trailingFocused = it.isFocused
                            if (it.isFocused) onFocusLabel(trailingLabel, trailingDanger)
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTrailingClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        trailingIcon,
                        contentDescription = trailingLabel,
                        tint = when {
                            !trailingFocused -> MaterialTheme.colorScheme.onSurfaceVariant
                            trailingDanger -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onPrimary
                        },
                    )
                }
            }
        }
        // 进度线**横跨整张卡**, 不是只压左半那块的底缘: 右端关闭区是同一张卡的一部分
        // (同一块玻璃、同一个圆角), 线到左半就断的话, 关闭区那 56dp 露出来的玻璃底比轨道色
        // 暗, 正好被读成"还没播的部分" —— 一张 74% 的卡看起来像 87%.
        val bar = progress()
        if (bar != null && bar.durationMillis > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(TV_NOW_PLAYING_BAR_HEIGHT)
                    // 轨道用半透明而不是 surfaceVariant: 它现在要跨过两种材质 —— 左半的玻璃
                    // (聚焦时半透明主题色) 与右端关闭区 (聚焦时错误色实底). 不透明的灰在红底上
                    // 是一道突兀的暗痕, 半透明则始终读作"同一条线压在底下的东西上"
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = TV_NOW_PLAYING_BAR_TRACK_ALPHA)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(
                            (bar.positionMillis.toFloat() / bar.durationMillis).coerceIn(0f, 1f),
                        )
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = progressBarAlpha)),
                )
            }
        }
    }
}

/**
 * **什么都没得播时的占位卡**: 与 [TvPlaybackCard] 同宽同高同圆角, 但压暗、居中一句"暂无可播放的内容".
 *
 * 为什么留一张卡而不是整块不画:
 *
 * - **面板尺寸恒定**. 圆钮那排的位置不随有没有会话上下跳 —— 遥控器上位置就是肌肉记忆, 而这个
 *   面板最常用的两颗 (回主界面 / 退出) 都在那排上.
 * - **把话说出来**. "没有正在播放, 也没有可以接着看的"是这个面板要回答的问题之一 (面板的另一半
 *   职责就是状态显示), 写一句比让人从"少了一块"里反推更直接, 也顺带告诉第一次见到它的人上面那块
 *   是干什么的.
 * - 会话结束时只是卡片内容换掉, 不再有一次布局跳动.
 *
 * **保留的是外框几何, 不是每个子元素** —— 两样东西在占位态是负资产, 刻意不画:
 *
 * - **右端的关闭区**: 一个永远灰着的 ✕ 不像空状态, 像一张坏掉的卡.
 * - **底缘进度线**: 空轨道更像"加载不出来"(与 [TvPlaybackCard] 里"时长未知就整条不画"同一条理由).
 *
 * 底色用中性的 onSurface 薄覆盖而不是主题色渐变: 缩略图那档兜底 (有会话但图还没到) 用的正是
 * 主题色渐变, 两者都带颜色的话一眼分不开 —— **有颜色 = 有东西, 灰 = 没有**.
 *
 * ## 它可聚焦, 但按下去什么也不做
 *
 * 这看着是反的 (一个按不动的东西吃焦点), 但它不是为了被按, 是为了**接住焦点**: 卡片右端那颗 ✕
 * 按下去只结束会话、不关面板, 同一位置这一帧就换成别的卡 —— 有得播是「接下来播放」, 什么都没有
 * 才是本卡. 焦点所在的节点消失时 Compose 不会自动改派, 本卡若不可聚焦, 那一路结果是整个面板一个
 * 焦点都没有、方向键全失效.
 *
 * 落焦时只把底色抬到 [TV_NOW_PLAYING_PLACEHOLDER_FOCUSED_ALPHA] —— **刻意比正在播放卡的高亮弱**:
 * 焦点在哪要看得见, 但不能让它看起来像颗能按的按钮.
 *
 * 它也**不会**成为面板打开时的默认落点 (那由 `cardIsDefault` 挡着): 一进来就落在按不动的东西上,
 * 与"接住已经在这儿的焦点"是两回事.
 *
 * @param onFocusChanged 上报焦点进出, 面板据此写固定标签行 (以及确认焦点接力到位了没有).
 */
@Composable
private fun TvNowPlayingPlaceholderCard(
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val container by animateColorAsState(
        MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (focused) {
                TV_NOW_PLAYING_PLACEHOLDER_FOCUSED_ALPHA
            } else {
                TV_NOW_PLAYING_PLACEHOLDER_ALPHA
            },
        ),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(TV_NOW_PLAYING_CARD_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .then(modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusable()
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.PlayCircle,
                contentDescription = null,
                Modifier.size(TV_NOW_PLAYING_PLACEHOLDER_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TV_NOW_PLAYING_PLACEHOLDER_CONTENT_ALPHA),
            )
            Text(
                stringResource(Lang.playback_nothing_to_play),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = TV_NOW_PLAYING_PLACEHOLDER_CONTENT_ALPHA,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * 卡片左边那块 16:9, 按可得性逐级回退 —— 三档都是 16:9, 所以版式不会因为回退而跳动:
 *
 * 1. **最后停在的那一帧** ([TvRetainedFrameStore]): 永远是当前这一集, 而且是"我刚才看的画面缩到
 *    了这里"的空间连续性. 帧在**暂停那一刻**由播放页截好存着, 不是这里现取 —— 现取要另起一路
 *    解码器, 后台会话正握着硬解实例, 那是 issue #10 的引信.
 * 2. **TMDB 单集图**: 那一集自己的剧照.
 * 3. **整部 backdrop**: 进程内热表同步可读, 且它是进详情页的门控条件, 几乎必然有.
 *
 * 兜底的**主题色渐变 + 大号半透明集号不是第四档, 而是一直铺在最底下**的底色, 上面三档谁到位谁盖
 * 上去. 这样"URL 还没解析出来""图正在下载""图下载失败"三种情况自动都是渐变, 一个状态判断都不用
 * 写 —— [AsyncImage] 在这里没有 placeholder 也没有 error painter, 加载中和失败都是**什么都不画**,
 * 底下没东西垫着就是一块空白 (慢网上肉眼可见地空好几秒). 也**不出占位图**: 一张灰底破图片图标会
 * 让整个面板看起来是坏的, 而渐变加集号看起来像是刻意设计的.
 *
 * 用横版而不是竖版封面: 卡片是横的, 竖封面塞进来要么被裁成一条要么把卡片撑高.
 */
@Composable
private fun TvNowPlayingThumbnail(subjectId: Int, episodeId: Int, episodeSort: String) {
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    // 第一档 (最后停在的那一帧) 只有"正在播放"态才可能命中: 「接下来播放」那一集根本还没播过,
    // 帧表里自然没有它, 于是自动落到剧照/backdrop, 不必在这里分叉
    val frame = TvRetainedFrameStore.frameFor(subjectId, episodeId)
    val stillUrl = rememberTvPlayingEpisodeStill(subjectId, episodeId)
    val imageUrl = stillUrl ?: tmdb.peekBackdropUrl(subjectId)
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .width(TV_NOW_PLAYING_THUMB_WIDTH)
            .height(TV_NOW_PLAYING_THUMB_WIDTH * 9 / 16)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (episodeSort.isNotBlank()) {
            Text(
                episodeSort,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        if (frame != null) {
            Image(
                frame,
                contentDescription = null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (imageUrl != null) {
            AsyncImage(
                imageUrl,
                contentDescription = null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * **正在播放那一集**自己的 TMDB 剧照; null = 没有/还没解析出来 (调用方退回整部 backdrop).
 *
 * 走的是各页早就在用的那条链 (`getEpisodeStills` + `matchToEpisodes`), 服务层有持久缓存, 而详情页
 * 进去时通常已经按整季拉过一次, 所以多数情况下这里是缓存命中、不产生请求.
 *
 * **不写进 `TvHeroMediaCache.nextEpisodeMedia`**: 那张表按 subjectId 存"下一集"的剧照, 给"继续
 * 观看"那一行的 hero 用. 往里塞"正在播的这一集"会和它互相覆盖 —— 两边的 episodeId 判据不同,
 * 结果是 hero 时不时显示错集的剧照. 面板自己拿着这一个值就够了.
 *
 * 拿不到条目信息 (进程缓存里没有这部) 就直接放弃: 那说明用户不是从卡片走进来的, 为一张缩略图
 * 现拉一整条链不值得.
 */
@Composable
private fun rememberTvPlayingEpisodeStill(subjectId: Int, episodeId: Int): String? {
    val tmdb = remember { GlobalKoin.get<TmdbImageService>() }
    val settings = remember { GlobalKoin.get<SettingsRepository>() }
    var url by remember(subjectId, episodeId) { mutableStateOf<String?>(null) }
    LaunchedEffect(subjectId, episodeId) {
        val info = TvHeroMediaCache.peekSubjectInfo(subjectId) ?: return@LaunchedEffect
        url = runCatching {
            val language = (settings.uiSettings.flow.first().appLanguage ?: Locale.current).toTmdbLanguage()
            tmdb.getEpisodeStills(
                subjectId,
                info.subjectInfo.name,
                language,
                newestWantedAirDate = info.episodes.newestAiredDateStringOrNull(),
                // 见 SubjectDetailsStateFactory 同名参数
                subjectAirDate = info.subjectInfo.airDate.toLocalDateOrNull()?.toString(),
                subjectEpisodeCount = info.episodes.size,
            )
                ?.matchToEpisodes(info.episodes, info.subjectInfo.airDate.toLocalDateOrNull()?.toString())
                ?.get(episodeId)
                ?.stillUrl
                // 缩略图只有 128dp, 原图档偶有 4K 级. 用**选集卡片那一档** (w780, ~40KB) 而不是
                // hero 档 (w1280): 尺寸上仍是三倍富余, 且进播放器最常见的路子是"详情页选集卡 ->
                // 播放", 那张卡刚刚就在屏幕上按这个档下载过 —— 同一个 URL 即同一个 Coil 缓存键,
                // 命中就没有下载这一步了
                ?.let { tmdbStillCardSizeUrl(it) }
        }.getOrNull()
    }
    return url
}

/** `12` -> `第 12 集`; 复用播放记录那条文案, 不再单开一条. */
@Composable
private fun renderEpisodeLabel(sort: String): String =
    stringResource(Lang.playback_history_episode_label, sort)

/** 毫秒 -> `mm:ss` / `h:mm:ss`. 面板上只显示到秒. */
private fun renderPlaybackTime(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }
}

/**
 * 动作面板宽度. 原为 320dp 的一列全宽按钮, 改成"正在播放卡 + 圆钮行"之后放宽一档:
 * 卡片右侧文字要留得下剧名, 而缩略图 + 关闭已经占掉 190dp.
 */
private val TV_ACTION_PANEL_WIDTH = 460.dp

/** 正在播放卡的高度: 缩略图 72dp + 上下各 12dp. */
private val TV_NOW_PLAYING_CARD_HEIGHT = 96.dp

/** 卡片左边那块 16:9 缩略图的宽度. */
private val TV_NOW_PLAYING_THUMB_WIDTH = 128.dp

/** 卡片右端关闭区的宽度. */
private val TV_NOW_PLAYING_CLOSE_WIDTH = 56.dp

/** 卡片底缘那条进度线的粗细. */
private val TV_NOW_PLAYING_BAR_HEIGHT = 3.dp

/** 进度线轨道的不透明度, 见用处的注释 (要同时压在玻璃与错误色实底上). */
private const val TV_NOW_PLAYING_BAR_TRACK_ALPHA = 0.22f

/**
 * 卡片主体聚焦时那层半透明主题色的不透明度.
 *
 * 0.3 是"明显亮起来但仍看得出是玻璃"的一档: 再低在 10 英尺距离上分不清有没有焦点, 再高就变成
 * 实底、把缩略图周围压成一块色板, 与面板其余部分的材质也对不上.
 */
private const val TV_NOW_PLAYING_FOCUSED_ALPHA = 0.30f

/** 占位卡底色 (onSurface 上的薄覆盖): 只要比面板底稍微分得出是一块区域就够, 见 [TvNowPlayingPlaceholderCard]. */
private const val TV_NOW_PLAYING_PLACEHOLDER_ALPHA = 0.06f

/**
 * 占位卡**聚焦时**的底色浓度. 只是从 0.06 抬到这里 —— 比正在播放卡那档 (0.30 的主题色) 弱得多,
 * 因为它按下去没有动作: 要让人看得见焦点在这儿, 又不能让它看起来像颗能按的按钮.
 */
private const val TV_NOW_PLAYING_PLACEHOLDER_FOCUSED_ALPHA = 0.16f

/**
 * 「接下来播放」态那条进度线的浓度. 压暗是**语义**不是装饰: 它画的是上次看到哪 (静止的历史),
 * 与"正在播放"那条实时走着的线必须一眼分得开.
 */
private const val TV_UP_NEXT_BAR_ALPHA = 0.55f

/** 占位卡里图标与文字的不透明度: 明显压得住 (它不是可操作的东西), 但仍要在 10 英尺外读得出. */
private const val TV_NOW_PLAYING_PLACEHOLDER_CONTENT_ALPHA = 0.55f

private val TV_NOW_PLAYING_PLACEHOLDER_ICON_SIZE = 26.dp

/** 固定标签行的高度: 写死免得空文案时整个面板抖一下. */
private val TV_ACTION_PANEL_LABEL_HEIGHT = 20.dp

/** 提示条距屏幕下缘的距离. */
private val TV_EXIT_HINT_BOTTOM_DP = 24.dp

/**
 * 圆角 = 条高 × 0.442. **不是 pill** —— 这是照真机系统 Toast 的截图拟合出来的 (圆弧方程最小
 * 二乘, rms 0.43px, 拟合得很干净), 而 pill 的比值是 0.5. 差这 0.06 正是之前几版"看着还是不太
 * 一样"的地方.
 *
 * 条高 = 行高 16 + 上下各 [TV_EXIT_HINT_PADDING_VERTICAL] = 45dp, 故 45 × 0.442 ≈ 20dp.
 * 改动上面任何一个都要把这里跟着重算.
 */
private val TV_EXIT_HINT_CORNER = 20.dp

/**
 * 提示条底色与字色: **不跟应用主题走**, 深浅主题下都是同一条 (见 [TvExitHintToast]).
 *
 * 按真机截图解出来的: **源色 RGB(235, 234, 237) + alpha 0.92** —— 系统 Toast 是**半透明**的.
 *
 * 解法: 条子上缘那一带没有文字, 拿它与正上方的背景做逐通道线性回归 —— 半透明合成是
 * `观察值 = α·源色 + (1-α)·背景`, 所以斜率就是 `1-α`. 三个通道各自回归都落在 α ≈ 0.92,
 * 源色 ≈ (235, 234, 237), 彼此独立却一致, 可信. 验算: 黑底上 0.92 × 235 ≈ 216, 正是肉眼在
 * 黑色区域取到的那个 216 —— 之前把 216 直接当成不透明底色, 是把"混合结果"当成了"源色".
 *
 * 定死字面值而不是从主题取: 系统 Toast 不跟应用主题走, 这条要冒充它就也不能跟.
 */
private val TV_EXIT_HINT_BACKGROUND = Color(0xEBEBEAED)

/** 字色: 同一张截图上取的 (笔画最深处均值 RGB(28,27,29)), 与底色同一路冷调. 文字不透明. */
private val TV_EXIT_HINT_CONTENT = Color(0xFF1C1B1D)

/**
 * 内边距. 与圆角一样是从截图**按比例**反推的, 而不是量出绝对 dp —— 截图的 density 说不准
 * (screencap 截的是 override 的 1080p 还是物理 4K, 差一倍), 但比例与 density 无关:
 *
 * ```
 * 左右留白 / 汉字墨迹高 = 2.54      上下留白 / 汉字墨迹高 = 1.343
 * ```
 *
 * 本条用 14sp, 汉字墨迹高约 12.3dp, 于是左右 ≈ 31dp、上下 ≈ 16.5dp. 竖直方向要扣掉行高比
 * 墨迹高多出来的那 1.85dp (行高 16 vs 墨迹 12.3, 上下各摊一半), 所以写 14.5dp.
 */
private val TV_EXIT_HINT_PADDING_HORIZONTAL = 31.dp

private val TV_EXIT_HINT_PADDING_VERTICAL = 14.5.dp

/**
 * 文字行高. Material 的 `bodyMedium` 是 20sp, 系统 Toast 用的是紧凑行高 —— 多出来的部分全加在
 * 条高上, 而条高又决定圆角 (见 [TV_EXIT_HINT_CORNER]), 所以这个值必须钉住.
 *
 * 取 16sp: 比 14sp 的字号略高一点点 (给汉字的上下留一线), 但不像 20sp 那样把条子撑胖.
 * 配合 `includeFontPadding = false`, 文字块高度就是这 16dp.
 */
private val TV_EXIT_HINT_LINE_HEIGHT = 16.sp

/**
 * 提示条**亮着**(不动) 的时长. 实际的武装窗口还要加上淡出那一段 (见调用处: 只有提示条彻底
 * 不见了才解除), 所以总容错是 [TV_EXIT_DOUBLE_BACK_WINDOW_MILLIS] + [TV_EXIT_HINT_FADE_OUT_MILLIS]
 * = 1 秒, 与调整前一致 —— 这次只是把其中一段从"亮着"挪给了"淡出".
 */
private const val TV_EXIT_DOUBLE_BACK_WINDOW_MILLIS = 700L

private const val TV_EXIT_HINT_FADE_IN_MILLIS = 80

/**
 * 淡出时长. **这一段仍然算在武装窗口里**, 所以它既是视觉上的收尾, 也是最后的容错余量.
 *
 * 之前 100ms 看起来是"啪一下没了"而不是淡出 —— 300ms 才读得出是在消退, 而消退本身就是这个
 * 手势的进度条: 用户看着它变淡就知道还剩多少时间.
 */
private const val TV_EXIT_HINT_FADE_OUT_MILLIS = 300
