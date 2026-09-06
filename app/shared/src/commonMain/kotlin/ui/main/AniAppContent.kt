/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.models.preference.NoticeSoundKind
import me.him188.ani.app.shared.Res
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.domain.mediasource.rss.RssMediaSource
import me.him188.ani.app.domain.mediasource.web.SelectorMediaSource
import me.him188.ani.app.domain.search.SubjectSearchQuery
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.MainPageRequest
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.navigation.OverrideNavigation
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.navigation.rememberAniBackStack
import me.him188.ani.app.platform.LocalContext
import me.him188.ani.app.platform.navigation.LocalBrowserNavigator
import me.him188.ani.app.ui.adaptive.navigation.AniNavigationSuiteDefaults
import me.him188.ani.app.ui.cache.CacheManagementScreen
import me.him188.ani.app.ui.cache.CacheManagementViewModel
import me.him188.ani.app.ui.cache.details.MediaCacheDetailsPageViewModel
import me.him188.ani.app.ui.cache.details.MediaCacheDetailsScreen
import me.him188.ani.app.ui.cache.details.MediaDetails
import me.him188.ani.app.ui.cache.details.MediaDetailsLazyGrid
import me.him188.ani.app.ui.cache.subject.SubjectCacheScreen
import me.him188.ani.app.ui.cache.subject.rememberSubjectCacheViewModel
import me.him188.ani.app.ui.exploration.schedule.ScheduleScreen
import me.him188.ani.app.ui.exploration.schedule.ScheduleViewModel
import me.him188.ani.app.ui.foundation.animation.NavigationMotionScheme
import me.him188.ani.app.ui.foundation.animation.ProvideAniMotionCompositionLocals
import androidx.compose.ui.graphics.Color
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.effects.OnLifecycleEvent
import me.him188.ani.app.ui.foundation.effects.rememberNoticeSoundPlayer
import me.him188.ani.app.ui.foundation.playback.LocalPlaybackSessionEntry
import me.him188.ani.app.ui.foundation.playback.PlaybackSessionEntry
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.layout.currentWindowAdaptiveInfo1
import me.him188.ani.app.ui.foundation.layout.desktopTitleBar
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.widgets.BackNavigationIconButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.TopAppBarActionButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.main_network_check_failed
import me.him188.ani.app.ui.oauth.BangumiAuthorizeScreen
import me.him188.ani.app.ui.oauth.BangumiAuthorizeViewModel
import me.him188.ani.app.ui.playback.PlaybackHistoryScreen
import me.him188.ani.app.ui.playback.PlaybackHistoryViewModel
import me.him188.ani.app.ui.profile.auth.AniContactList
import me.him188.ani.app.ui.search.SearchScreen
import me.him188.ani.app.ui.settings.SettingsScreen
import me.him188.ani.app.ui.settings.SettingsViewModel
import me.him188.ani.app.ui.settings.mediasource.rss.EditRssMediaSourceScreen
import me.him188.ani.app.ui.settings.mediasource.rss.EditRssMediaSourceViewModel
import me.him188.ani.app.ui.settings.mediasource.selector.EditSelectorMediaSourceScreen
import me.him188.ani.app.ui.settings.mediasource.selector.EditSelectorMediaSourceViewModel
import me.him188.ani.app.ui.settings.tabs.media.torrent.peer.PeerFilterSettingsScreen
import me.him188.ani.app.ui.settings.tabs.media.torrent.peer.PeerFilterSettingsViewModel
import me.him188.ani.app.ui.subject.details.SubjectDetailsScreen
import me.him188.ani.app.ui.subject.details.SubjectDetailsViewModel
import me.him188.ani.app.ui.subject.episode.EpisodeScreen
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.person.CharacterDetailsScreen
import me.him188.ani.app.ui.subject.person.CharacterDetailsViewModel
import me.him188.ani.app.ui.subject.person.PersonDetailsScreen
import me.him188.ani.app.ui.subject.person.PersonDetailsViewModel
import me.him188.ani.app.ui.subject.episode.RetainedPlaybackSessionHolder
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.playback_session_sound_hint
import me.him188.ani.app.ui.subject.episode.rememberRetainedPlaybackNoticeTexts
import me.him188.ani.app.ui.user.SelfInfoStateProducer
import me.him188.ani.datasources.api.source.FactoryId
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/**
 * UI 入口点. 包含所有子页面, 以及组合这些子页面的方式 (navigation).
 */
@Composable
fun AniAppContent(aniNavigator: AniNavigator) {
    val aniAppViewModel = viewModel<AniAppViewModel>()
    val appState = aniAppViewModel.appState.collectAsStateWithLifecycle(null).value ?: return
    // 只有在 APP 首次启动的时候使用 initialNavRoute, 之后 back stack 自己维护并跨进程恢复
    val backStack = rememberAniBackStack(appState.initialNavRoute)
    aniNavigator.setBackStack(backStack)

    // 根底色: 页面切换过渡的淡入淡出间隙会露出它, 见 AniUiBehavior.blackRootBackground
    val rootBackground =
        if (LocalAniUiBehavior.current.blackRootBackground) Color.Black
        else MaterialTheme.colorScheme.background
    // 保留播放会话 (遥控器形态, 可在设置里关): 播放页退出后播放器与整条起播流水线不销毁,
    // 由侧边栏"正在播放"条目回去. holder 挂在这里 (NavHost 之外) 才能不随播放页那个返回栈条目
    // 一起死; 它同时是入口把手 (PlaybackSessionEntry), 经 CompositionLocal 给到 NavHost 内的入口.
    val retainPlaybackSession = LocalAniUiBehavior.current.retainPlaybackSession &&
            LocalThemeSettings.current.tvRetainPlaybackSession
    val sessionHolder = viewModel { RetainedPlaybackSessionHolder() }
    // 设置里关掉时把已经保留着的会话结束掉, 否则它会一直活到应用退出
    LaunchedEffect(sessionHolder, retainPlaybackSession) {
        if (!retainPlaybackSession) sessionHolder.close()
    }
    val playbackSessionHolder = sessionHolder.takeIf { retainPlaybackSession }
    if (playbackSessionHolder != null) {
        // 播放页是否在前台: 由导航状态驱动, 而不是播放页自己的生命周期事件 —— 返回栈条目被 pop 时
        // ON_STOP 与界面销毁的先后不保证, 漏一次就成了"画面没了声音还在".
        // holder 据此把后台的会话按住不出声 (数据源解析完成后流水线会自己 resume).
        val onPlayerPage = backStack.lastOrNull() is NavRoutes.EpisodeDetail
        // 用 SideEffect 而不是 LaunchedEffect: 组合成功后同步落地, 早于回到播放页时那次
        // 自动恢复播放 (ON_START), 否则 holder 会以为还在后台, 刚恢复就又被按下去
        SideEffect {
            playbackSessionHolder.setPlayerPageVisible(onPlayerPage)
        }
        // 应用整个退到后台 (按 HOME 去别的应用) 时也不能提示: Android 上这些提示是系统 Toast
        // 加一声满音量按键音, 会空降在别人的应用/桌面上. Activity 停止只暂停帧时钟, 组合与
        // holder 的协程照常在跑, 所以必须显式告诉它. 攒下的那条回前台再补发, 见 holder 的 notify.
        OnLifecycleEvent { event ->
            when (event) {
                Lifecycle.Event.ON_START -> playbackSessionHolder.setAppForeground(true)
                Lifecycle.Event.ON_STOP -> playbackSessionHolder.setAppForeground(false)
                else -> Unit
            }
        }
        // 后台会话的状态变化提示一声: 慢的源要十几秒, 用户正是为了不干等才退出去的 —— 就绪了要叫他
        // 回来, 而卡住了 (换源也救不回来/没搜到/等他手选) 更要说, 否则他会一直等一个不会来的就绪提示
        val toaster = LocalToaster.current
        val noticeTexts = rememberRetainedPlaybackNoticeTexts()
        // 还要响一声: 这些提示的前提就是用户没在看着屏幕 (退出播放页去翻别的, 或者干脆没看电视),
        // 只给一条会自己消失的 toast 等于没提示. 只有这一组提示配声音, 普通 toast 不配 ——
        // 错误提示全应用到处都有, 每条都响会很吵
        val playNoticeSound = rememberNoticeSoundPlayer()
        // 音色跟着设置走 (可以选到"无声音"). 用 rememberUpdatedState 而不是把它做成 LaunchedEffect
        // 的 key: notices 是无 replay 的 SharedFlow, 重启收集者会在重启的空档里漏掉一次提示
        val noticeSound = rememberUpdatedState(LocalThemeSettings.current.tvNoticeSound)
        // 默认那声是最短最不打扰的轻点音 (见 NoticeSoundKind.Default), 而它恰好也是不少电视
        // 系统 UI 的导航音 —— 容易被当成背景噪音听漏. 所以 toast 里带一句"这声可以换",
        // 否则用户只会觉得"提示音没用", 想不到有更显眼的档位可选.
        // 关掉声音的人不用看这句 (他已经知道这个设置在哪儿), 拼接放在发送那一刻而不是做进
        // noticeTexts: 那样音色一改就换掉 effect 的 key, 重启收集者会漏掉空档里的提示
        val soundHint = stringResource(Lang.playback_session_sound_hint)
        LaunchedEffect(playbackSessionHolder, toaster, noticeTexts, playNoticeSound) {
            playbackSessionHolder.notices.collect {
                val sound = noticeSound.value
                val text = noticeTexts.textOf(it) + if (sound != NoticeSoundKind.None) soundHint else ""
                toaster.toast(text)
                playNoticeSound(sound)
            }
        }
        // 会话在后台也要有个组合挂载点 (WEB 源解析的 WebView 宿主), 详见该函数的注释
        playbackSessionHolder.ComposeRetainedContent()
    }
    Box(Modifier.fillMaxSize().background(rootBackground)) {
        CompositionLocalProvider(
            LocalNavigator provides aniNavigator,
            LocalBrowserNavigator providesDefault aniAppViewModel.browserNavigator,
            LocalPlaybackSessionEntry provides (playbackSessionHolder ?: PlaybackSessionEntry.None),
        ) {
            ProvideAniMotionCompositionLocals {
                AniAppContentImpl(
                    aniNavigator,
                    backStack,
                    appState.mainSceneInitialPage,
                    playbackSessionHolder,
                    Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 全局焦点兜底在开抢之前给页面留的余地, 按**帧**计.
 *
 * 覆盖的是"页面刚组合、自己的焦点作用域还没发出请求"的那几帧 (详情页还要先完成滚动归位).
 * 让位按帧计, 设备一卡帧就跟着变长, 避免固定墙钟在慢设备上过早介入.
 *
 * 原来这里是固定 250ms 墙钟: 流畅时够用, 一卡就抢在锚点前面, 表现为进详情页侧边栏闪一下
 * (兜底的 requestFocus 落在 focusGroup 上会按 Enter 方向进入页面**最左**的可聚焦子树) 再被
 * 页面锚点拉回观看按钮. 墙钟不随设备快慢伸缩, 这类"猜一个时长"的兜底必然在慢设备上漏.
 */
private const val FOCUS_FALLBACK_GRACE_FRAMES = 15

/**
 * 让位的墙钟上限: 页面完全静止 (没有可聚焦内容, 也就没有动画) 时不产帧,
 * [FOCUS_FALLBACK_GRACE_FRAMES] 会一直等不到 —— 而那恰恰是最需要兜底的场景.
 */
private val FOCUS_FALLBACK_GRACE_CEILING = 1.seconds

@Composable
private fun AniAppContentImpl(
    aniNavigator: AniNavigator,
    backStack: List<NavRoutes>,
    mainSceneInitialPage: MainScreenPage,
    /** 非 null 时播放页的 VM 挂到它上面, 退出播放页不销毁会话; null = 本形态不保留会话. */
    playbackSessionHolder: RetainedPlaybackSessionHolder?,
    modifier: Modifier = Modifier,
) {
    // 必须传给所有 Scaffold 和 TopAppBar. 注意, 如果你不传, 你的 UI 很可能会在 macOS 不工作.
    val windowInsetsWithoutTitleBar = ScaffoldDefaults.contentWindowInsets
    val windowInsets = ScaffoldDefaults.contentWindowInsets
        .add(WindowInsets.desktopTitleBar()) // Compose 目前不支持这个所以我们要自己加上
    val navMotionScheme by rememberUpdatedState(NavigationMotionScheme.current)

    // 焦点导航的通用兜底 (无需任何页面单独配合): 没有任何焦点时 Compose 不会自动分配,
    // 方向键会完全失效 (按键只会派发到根部的 onKeyEvent). 这里常驻监视 —— 只要本窗口
    // 持有窗口焦点而 NavDisplay 内没有任何焦点 (刚导航到的页面只有加载动画、聚焦元素被
    // 数据刷新移除、内容迟到等), 就持续把焦点送入当前页面 (requestFocus 挂在 focusGroup
    // 上会进入默认可聚焦子元素), 直到成功为止. 页面自己的焦点锚点 (如详情页播放按钮,
    // 播放器画面) 优先: 已有焦点时这里不动作.
    // 弹窗/对话框 (独立窗口) 打开期间本窗口失去窗口焦点, 兜底自动暂停 ——
    // 不会与弹窗关闭后的焦点恢复逻辑竞争.
    val navDisplayModifier = modifier.ifThen(LocalAniUiBehavior.current.focusDrivenNavigation) {
        val focusRequester = remember { FocusRequester() }
        var hasFocusInside by remember { mutableStateOf(false) }
        val windowInfo = LocalWindowInfo.current
        // Navigation 3 的"当前页面"就是栈顶那个路由对象 (原先是 currentBackStackEntryAsState)
        val currentRoute = backStack.lastOrNull()
        LaunchedEffect(currentRoute) {
            if (currentRoute == null) return@LaunchedEffect
            snapshotFlow { hasFocusInside to windowInfo.isWindowFocused }
                .collectLatest { (focused, windowFocused) ->
                    if (focused || !windowFocused) return@collectLatest
                    // 先让位: 页面自己的焦点锚点要跨几帧才发得出请求 (详情页还要先把滚动归零
                    // 再等一帧). 在这个空窗期抢焦点, requestFocus 会按 Enter 方向进入页面
                    // **最左**的可聚焦子树 —— 有侧边栏的页面上那就是侧边栏, 于是进详情页时
                    // 侧边栏被展开一瞬 (按钮文字闪一下) 再被页面锚点拉走.
                    //
                    // 按帧让位而不是按墙钟, 理由见 [FOCUS_FALLBACK_GRACE_FRAMES]; 上限兜住
                    // "静止页面不产帧"那条路, 见 [FOCUS_FALLBACK_GRACE_CEILING].
                    // 焦点一旦落定 collectLatest 立刻取消本次等待, 这段让位根本不会走完.
                    withTimeoutOrNull(FOCUS_FALLBACK_GRACE_CEILING) {
                        repeat(FOCUS_FALLBACK_GRACE_FRAMES) { withFrameNanos { } }
                    }
                    // 持续重试 (状态一变 collectLatest 即取消): 转场动画期间请求可能落在
                    // 将被移除的旧页面上, 旧页面销毁后焦点再次丢失会自动再触发
                    while (true) {
                        runCatching { focusRequester.requestFocus() }
                        delay(100)
                    }
                }
        }
        onFocusChanged { hasFocusInside = it.hasFocus }
            .focusRequester(focusRequester)
            .focusGroup()
    }


    NavDisplay(
        backStack = backStack,
        modifier = navDisplayModifier,
        onBack = { aniNavigator.popBackStack() },
        entryDecorators = listOf(
            // 让每个页面各自持有 rememberSaveable 状态和 ViewModel, 出栈时一并销毁
            rememberSaveableStateHolderNavEntryDecorator(),
            // **不能用库的 rememberViewModelStoreNavEntryDecorator**: 它在"条目内容离开组合"时就
            // 销毁 per-entry 的 ViewModelStore, 而 Nav3 里被别的页面盖住同样会离开组合 —— 于是从
            // 详情页返回时下层页面的 VM 全是新的: 搜索词丢了 (顶部空词 + "没有找到相关条目")、
            // 追番页 tab 回默认、焦点落点对不上、主屏的启动/更新检查每次返回都重跑 (更新气泡反复弹)。
            // 换成按返回栈判定的实现: 只有 key 真的不在栈里了才 clear。取证与判据见那个文件。
            rememberBackStackAwareViewModelStoreNavEntryDecorator(backStack),
            // 下发"本页是不是栈顶": 页面靠它在返回时补焦点落点. **不能用页面自己的 lifecycle** ——
            // Nav3 里被盖住的条目一直是 RESUMED, 见 LocalPageIsForeground 的文档
            rememberPageForegroundNavEntryDecorator(backStack),
        ),
        transitionSpec = {
            navMotionScheme.enterTransition togetherWith navMotionScheme.exitTransition
        },
        popTransitionSpec = {
            navMotionScheme.popEnterTransition togetherWith navMotionScheme.popExitTransition
        },
        predictivePopTransitionSpec = {
            navMotionScheme.popEnterTransition togetherWith navMotionScheme.popExitTransition
        },
        entryProvider = entryProvider {
            entry<NavRoutes.BangumiAuthorize> {
                val vm = viewModel<BangumiAuthorizeViewModel> { BangumiAuthorizeViewModel() }
                BangumiAuthorizeScreen(
                    vm,
                    onNavigateBack = {
                        aniNavigator.popBackStack(NavRoutes.BangumiAuthorize, true)
                    },
                    onNavigateSettings = {
                        aniNavigator.navigateSettings()
                    },
                    contactActions = {
                        AniContactList()
                    },
                    onAuthorizeSuccess = {
                        aniNavigator.popBackStack(NavRoutes.BangumiAuthorize, true)
                    },
                )
            }
            entry<NavRoutes.Main> { route ->
                val navigationLayoutType =
                    AniNavigationSuiteDefaults.calculateLayoutType(
                        currentWindowAdaptiveInfo1(),
                    )

                val vm = viewModel { MainScreenSharedViewModel() }
                var currentPage by rememberSaveable { mutableStateOf(route.initialPage) }

                // 从其他页面 (如详情页侧边栏、遥控器的「回到主界面」) 弹回主页时切到指定 tab:
                // 弹回不会重建 Main, route.initialPage 不会重新生效, 故经进程级信标传递
                // (Nav3 的栈里没有可挂东西的 entry, 见 MainPageRequest)
                val requestedPage = MainPageRequest.pending
                LaunchedEffect(requestedPage) {
                    val page = requestedPage ?: return@LaunchedEffect
                    currentPage = page
                    MainPageRequest.pending = null
                }

                val toaster = LocalToaster.current
                val networkCheckFailedMessage = stringResource(Lang.main_network_check_failed)
                LaunchedEffect(vm) {
                    vm.networkCheckFailed.collect {
                        toaster.toast(networkCheckFailedMessage)
                    }
                }

                OverrideNavigation(
                    {
                        object : AniNavigator by it {
                            override fun navigateMain(page: MainScreenPage, popUpTargetInclusive: NavRoutes?) {
                                currentPage = page
                            }
                        }
                    },
                ) {
                    val selfInfo by vm.selfInfo.collectAsState() // not -WithLifecycle
                    MainScreen(
                        page = currentPage,
                        selfInfo = selfInfo,
                        onNavigateToPage = { currentPage = it },
                        onNavigateToSettings = { aniNavigator.navigateSettings(it) },
                        onNavigateToSearch = { aniNavigator.navigateSubjectSearch() },
                        navigationLayoutType = navigationLayoutType,
                    )
                }
            }
            entry<NavRoutes.SubjectSearch> { route ->
                val navigator = LocalNavigator.current
                val vm = viewModel(key = route.toString()) { SearchViewModel(route.toQuery()) }

                SearchScreen(
                    vm,
                    onNavigateBack = {
                        aniNavigator.popBackStack()
                    },
                    onNavigateToSubjectDetails = { subjectId, placeholder ->
                        navigator.navigateSubjectDetails(subjectId, placeholder)
                    },
                    onNavigateToEpisodeDetails = { subjectId, episodeId ->
                        navigator.navigateEpisodeDetails(subjectId, episodeId)
                    },
                    windowInsets = windowInsets,
                )
            }
            entry<NavRoutes.SubjectDetail> { route ->
                val vm = viewModel<SubjectDetailsViewModel>(key = route.subjectId.toString()) {
                    val placeholder = route.placeholder?.run {
                        SubjectInfo.createPlaceholder(id, name, coverUrl, nameCN)
                    }
                    SubjectDetailsViewModel(route.subjectId, placeholder)
                }
                SubjectDetailsScreen(
                    vm,
                    onPlay = { aniNavigator.navigateEpisodeDetails(route.subjectId, it) },
                    onLoadErrorRetry = { vm.reload() },
                    onClickTag = {
                        aniNavigator.navigateSubjectSearch(NavRoutes.SubjectSearch(tags = listOf(it.name)))
                    },
                    windowInsets = windowInsets,
                    navigationIcon = {
                        // 有硬件返回键的设备上不显示返回/主页按钮: 连按返回即可回到主页
                        if (LocalAniUiBehavior.current.showBackNavigationButton) {
                            Row {
                                BackNavigationIconButton(
                                    {
                                        aniNavigator.popBackStack(route, inclusive = true)
                                    },
                                )
                                TopAppBarActionButton(
                                    {
                                        aniNavigator.popBackOrNavigateToMain(mainSceneInitialPage)
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.Home,
                                        contentDescription = null,
                                    )
                                }
                            }
                        }
                    },
                )
            }
            entry<NavRoutes.EpisodeDetail> { route ->
                val context = LocalContext.current
                // route 里的 episodeId 是**进这一页时**那一集, 之后不会再变: 播放器内换集 (选集条 /
                // 详情层 / 播完自动连播) 一律是就地 switchEpisode, 根本不导航.
                //
                // 而本页从更深的页面 (播放器里的"缓存"入口 -> 缓存管理) 返回时是整个重新组合的,
                // 按 route 去认会话就与保留着的会话对不上 -> 热会话被销毁重建, 播放器**倒退回进来
                // 那一集** (那一集的缓存要是刚在缓存页删掉, 紧接着还会报一次播放失败).
                //
                // 用 rememberSaveable 记"这一页此刻在播哪一集": 它随返回栈条目存活, 正好是"页面
                // 实例"这个粒度 —— 从更深页面返回时恢复, 而换一集重新导航是新的条目, 不会串.
                var pageEpisodeId by rememberSaveable { mutableIntStateOf(route.episodeId) }
                val initializer: CreationExtras.() -> EpisodeViewModel = {
                    EpisodeViewModel(
                        subjectId = route.subjectId,
                        initialEpisodeId = pageEpisodeId,
                        initialIsFullscreen = false,
                        context,
                    )
                }
                val vm = if (playbackSessionHolder != null) {
                    // 保留会话形态: VM 挂在应用级 holder 的会话上, 退出本页不销毁; 回到同一集
                    // 拿回同一个会话 (状态自然接上), 换集则先销毁旧会话再建新的 —— 先销后建,
                    // 不让两个播放器同时在场. 这些都在 openSession 里, 见该函数.
                    //
                    // 会话必须 remember 住而不是每次重组重新问 holder 要:
                    // viewModel(viewModelStoreOwner = …) 自己没有 remember, 每次重组都会重新读一遍
                    // owner 的 store. 本页退场动画期间 holder 的当前会话可能已经是下一集了, 那时
                    // 重组一次就会在新会话的空 store 里凭空建出第二个 EpisodeViewModel (第二个播放器).
                    // 一个会话的 store 里恒定只有一个 VM, 所以这里也不需要 key.
                    val session = remember(playbackSessionHolder, route) {
                        playbackSessionHolder.openSession(route.subjectId, pageEpisodeId)
                    }
                    // 会话换集后同步回本页: 下次重新组合 (从缓存页返回) 才认得回同一个会话.
                    // session.info 是 snapshot state, snapshotFlow 在这里是成立的
                    LaunchedEffect(session) {
                        snapshotFlow { session.info.episodeId }
                            .collect { if (it > 0) pageEpisodeId = it }
                    }
                    viewModel<EpisodeViewModel>(
                        viewModelStoreOwner = session,
                        initializer = initializer,
                    ).also { vm ->
                        // 上报本页组合的存活: holder 据此决定当前会话是哪一个, 以及被替换掉的
                        // 那个能不能销毁 (它的界面还在退场动画里时不能, 见 RetainedPlaybackSessionHolder)
                        DisposableEffect(session, vm) {
                            playbackSessionHolder.onPageComposed(session, vm)
                            onDispose { playbackSessionHolder.onPageDisposed(session) }
                        }
                    }
                } else {
                    viewModel<EpisodeViewModel>(key = route.toString(), initializer = initializer)
                }
                EpisodeScreen(vm, Modifier.fillMaxSize(), windowInsets)
            }
            entry<NavRoutes.Settings> { route ->
                SettingsScreen(
                    viewModel {
                        SettingsViewModel()
                    },
                    onNavigateToBangumiOAuth = { aniNavigator.navigateBangumiAuthorize() },
                    loadOpenSourceLibrariesJsons = {
                        listOf(
                            Res.readBytes("files/aboutlibraries.json"),
                            Res.readBytes("files/additional_libraries.json"),
                        )
                    },
                    // 本页可以被**长按**开出来 (遥控器动作面板里长按服务连通那一行 → 代理设置),
                    // 那时用户的手还没松: 残余的连发 KeyDown + KeyUp 会落在刚拿到焦点的第一项设置上,
                    // 表现成"页面刚开就自己点了一下". 短按进来挂着它同样安全 (见该 modifier 的文档).
                    Modifier.fillMaxSize().consumeHeldConfirmKey(),
                    route.tab,
                    navigationIcon = {
                        BackNavigationIconButton(
                            {
                                aniNavigator.popBackStack(route, inclusive = true)
                            },
                        )
                    },
                )
            }
            entry<NavRoutes.PlaybackHistory> { route ->
                PlaybackHistoryScreen(
                    vm = viewModel { PlaybackHistoryViewModel() },
                    onNavigateBack = { aniNavigator.popBackStack(route, inclusive = true) },
                    onOpenHistory = { history ->
                        val subjectId = history.subjectId
                        if (subjectId != null) {
                            aniNavigator.navigateEpisodeDetails(subjectId, history.episodeId)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    navigationIcon = {
                        BackNavigationIconButton(
                            {
                                aniNavigator.popBackStack(route, inclusive = true)
                            },
                        )
                    },
                    windowInsets = windowInsetsWithoutTitleBar,
                )
            }
            entry<NavRoutes.Caches> { route ->
                val selfInfo by remember { SelfInfoStateProducer() }.flow.collectAsState(null)
                CacheManagementScreen(
                    vm = viewModel { CacheManagementViewModel() },
                    selfInfo = selfInfo,
                    onPlay = {
                        aniNavigator.navigateEpisodeDetails(it.subjectId, it.episodeId)
                    },
                    onClickLogin = { },
                    onNavigateCacheDetail = { aniNavigator.navigateCacheDetails(it) },
                    modifier = Modifier.fillMaxSize(),
                    navigationIcon = {
                        BackNavigationIconButton(
                            {
                                aniNavigator.popBackStack(route, inclusive = true)
                            },
                        )
                    },
                )
            }
            entry<NavRoutes.CacheDetail> { route ->
                MediaCacheDetailsScreen(
                    viewModel(key = route.toString()) { MediaCacheDetailsPageViewModel(route.cacheId) },
                    navigationIcon = {
                        BackNavigationIconButton(
                            {
                                aniNavigator.popBackStack(route, inclusive = true)
                            },
                        )
                    },
                    Modifier.fillMaxSize(),
                    windowInsets = windowInsets,
                )
            }
            entry<NavRoutes.PersonDetail> { route ->
                val vm = viewModel<PersonDetailsViewModel>(key = "person-${route.personId}") {
                    PersonDetailsViewModel(route.personId)
                }
                PersonDetailsScreen(
                    vm,
                    Modifier.fillMaxSize(),
                    windowInsets = windowInsets,
                    navigationIcon = {
                        BackNavigationIconButton({ aniNavigator.popBackStack(route, inclusive = true) })
                    },
                )
            }
            entry<NavRoutes.CharacterDetail> { route ->
                val vm = viewModel<CharacterDetailsViewModel>(key = "character-${route.characterId}") {
                    CharacterDetailsViewModel(route.characterId)
                }
                CharacterDetailsScreen(
                    vm,
                    Modifier.fillMaxSize(),
                    windowInsets = windowInsets,
                    navigationIcon = {
                        BackNavigationIconButton({ aniNavigator.popBackStack(route, inclusive = true) })
                    },
                )
            }
            entry<NavRoutes.SubjectCaches> { route ->
                // Don't use rememberViewModel to save memory
                // 采纳上游的组合级作用域 (它这次刻意收紧, 避免 detail pane 累积后台 collector).
                val vm = rememberSubjectCacheViewModel(route.subjectId)
                SubjectCacheScreen(
                    vm,
                    onPlay = { aniNavigator.navigateEpisodeDetails(it.subjectId, it.episodeId) },
                    onNavigateCacheDetail = { aniNavigator.navigateCacheDetails(it) },
                    modifier = Modifier.fillMaxSize(),
                    windowInsets = windowInsets,
                    navigationIcon = {
                        BackNavigationIconButton(
                            {
                                aniNavigator.popBackStack(route, inclusive = true)
                            },
                        )
                    },
                )
            }
            entry<NavRoutes.EditMediaSource> { route ->
                val factoryId = FactoryId(route.factoryId)
                val mediaSourceInstanceId = route.mediaSourceInstanceId
                when (factoryId) {
                    RssMediaSource.FactoryId -> EditRssMediaSourceScreen(
                        viewModel<EditRssMediaSourceViewModel>(key = mediaSourceInstanceId) {
                            EditRssMediaSourceViewModel(mediaSourceInstanceId)
                        },
                        mediaDetailsColumn = { media ->
                            MediaDetailsLazyGrid(
                                MediaDetails.from(media, null, null),
                                Modifier.fillMaxSize(),
                                showSourceInfo = false,
                            )
                        },
                        Modifier,
                        windowInsets,
                        navigationIcon = {
                            BackNavigationIconButton(
                                {
                                    aniNavigator.popBackStack(route, inclusive = true)
                                },
                            )
                        },
                    )

                    SelectorMediaSource.FactoryId -> {
                        val context = LocalContext.current
                        EditSelectorMediaSourceScreen(
                            viewModel<EditSelectorMediaSourceViewModel>(key = mediaSourceInstanceId) {
                                EditSelectorMediaSourceViewModel(mediaSourceInstanceId, context)
                            },
                            Modifier,
                            windowInsets = windowInsets,
                            navigationIcon = {
                                BackNavigationIconButton(
                                    {
                                        aniNavigator.popBackStack(route, inclusive = true)
                                    },
                                )
                            },
                        )
                    }

                    else -> error("Unknown factoryId: $factoryId")
                }
            }
            entry<NavRoutes.TorrentPeerSettings> { route ->
                val viewModel = viewModel { PeerFilterSettingsViewModel() }
                PeerFilterSettingsScreen(
                    viewModel.state,
                    navigationIcon = {
                        BackNavigationIconButton(
                            {
                                aniNavigator.popBackStack(route, inclusive = true)
                            },
                        )
                    },
                )
            }
            entry<NavRoutes.Schedule> { route ->
                val vm = viewModel { ScheduleViewModel() }
                val presentation by vm.presentationFlow.collectAsStateWithLifecycle()
                ScheduleScreen(
                    presentation,
                    onRetry = { vm.refresh() },
                    onClickItem = {
                        aniNavigator.navigateSubjectDetails(
                            it.subjectId,
                            placeholder = SubjectDetailPlaceholder(
                                id = it.subjectId,
                                nameCN = it.subjectTitle,
                                coverUrl = it.imageUrl,
                            ),
                        )
                    },
                    Modifier.fillMaxSize(),
                    windowInsets = windowInsets,
                    navigationIcon = {
                        BackNavigationIconButton(
                            {
                                aniNavigator.popBackStack(route, inclusive = true)
                            },
                        )
                    },
                    state = vm.pageState,
                )
            }
        },
    )
}

private fun NavRoutes.SubjectSearch.toQuery(): SubjectSearchQuery {
    return SubjectSearchQuery(
        keywords = keyword ?: "",
        tags = tags,
    )
}
