/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.cache

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.ui.adaptive.AniListDetailPaneScaffold
import me.him188.ani.app.ui.adaptive.AniTopAppBar
import me.him188.ani.app.ui.adaptive.AniTopAppBarDefaults
import me.him188.ani.app.ui.adaptive.PaneScope
import me.him188.ani.app.ui.cache.components.CacheEpisodeRow
import me.him188.ani.app.ui.cache.components.CacheEpisodeState
import me.him188.ani.app.ui.cache.components.CacheFilterAndSortBar
import me.him188.ani.app.ui.cache.components.CacheGroupState
import me.him188.ani.app.ui.cache.components.CacheManagementOverallStats
import me.him188.ani.app.ui.cache.components.CacheSelectionFloatingToolbar
import me.him188.ani.app.ui.cache.components.CacheSelectionState
import me.him188.ani.app.ui.cache.components.CacheSubjectGroupCard
import me.him188.ani.app.ui.cache.components.TestCacheGroupSates
import me.him188.ani.app.ui.cache.components.createTestMediaStats
import me.him188.ani.app.ui.cache.components.rememberCacheFilterAndSortState
import me.him188.ani.app.ui.cache.components.rememberCacheSelectionState
import me.him188.ani.app.ui.cache.subject.SubjectCacheDetailHeader
import me.him188.ani.app.ui.cache.subject.SubjectCacheDetailPaneContent
import me.him188.ani.app.ui.cache.subject.SubjectCacheSummaryRow
import me.him188.ani.app.ui.cache.subject.rememberSubjectCacheViewModel
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.foundation.animation.AniAnimatedVisibility
import me.him188.ani.app.ui.foundation.layout.AniWindowInsets
import me.him188.ani.app.ui.foundation.layout.currentWindowAdaptiveInfo1
import me.him188.ani.app.ui.foundation.layout.paneHorizontalPadding
import me.him188.ani.app.ui.foundation.layout.plus
import me.him188.ani.app.ui.foundation.navigation.BackHandler
import me.him188.ani.app.ui.foundation.rememberAsyncHandler
import me.him188.ani.app.ui.foundation.rememberCurrentTopAppBarContainerColor
import me.him188.ani.app.ui.foundation.session.SelfAvatar
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.appChromeHazeSource
import me.him188.ani.app.ui.foundation.widgets.BackNavigationIconButton
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_episode_pause_download
import me.him188.ani.app.ui.lang.cache_episode_resume_download
import me.him188.ani.app.ui.lang.cache_management_delete_cache_confirmation
import me.him188.ani.app.ui.lang.cache_management_delete_cache_title
import me.him188.ani.app.ui.lang.cache_management_delete_summary
import me.him188.ani.app.ui.lang.cache_management_delete_summary_item
import me.him188.ani.app.ui.lang.cache_management_enter_selection_mode
import me.him188.ani.app.ui.lang.cache_management_exit_selection
import me.him188.ani.app.ui.lang.cache_management_invalid_cache_info
import me.him188.ani.app.ui.lang.cache_management_more_info
import me.him188.ani.app.ui.lang.cache_management_play
import me.him188.ani.app.ui.lang.cache_management_select_all
import me.him188.ani.app.ui.lang.cache_management_select_item_for_details
import me.him188.ani.app.ui.lang.cache_management_selected_count
import me.him188.ani.app.ui.lang.cache_management_selection_downloading_count
import me.him188.ani.app.ui.lang.cache_management_selection_summary
import me.him188.ani.app.ui.lang.cache_management_streaming_not_supported
import me.him188.ani.app.ui.lang.cache_subject_cancel
import me.him188.ani.app.ui.lang.cache_subject_delete
import me.him188.ani.app.ui.lang.main_screen_page_cache_management
import me.him188.ani.app.ui.user.SelfInfoUiState
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.compose.resources.stringResource

/**
 * 全局缓存管理页面状态
 */
@Immutable
data class CacheManagementState(
    val overallStats: MediaStats,
    val groups: List<CacheGroupState>,
) {
    internal val entries = groups.flatMap { it.entries }

    companion object {
        val Placeholder = CacheManagementState(
            MediaStats.Unspecified,
            emptyList(),
        )
    }
}


/**
 * 全局缓存管理页面.
 *
 * 手机布局: 按条目分组的卡片列表, 点击卡片由 list-detail scaffold 全屏展示详情栏 (顶栏变为返回 + 条目名).
 * 宽屏布局 (≥840dp): 左栏为分组卡片, 右栏为选中条目的完整缓存内容 (含未缓存剧集).
 * 宽屏显示详情后缩小窗口会自然退化为手机的详情栏形态, 返回键可回到列表.
 *
 * 设计稿: [Figma](https://www.figma.com/design/LET1n9mmDa6npDTIlUuJjU/Animeko?node-id=1655-6587)
 */
@Composable
fun CacheManagementScreen(
    vm: CacheManagementViewModel,
    selfInfo: SelfInfoUiState?,
    onPlay: (CacheEpisodeState) -> Unit,
    onClickLogin: () -> Unit,
    onNavigateCacheDetail: (cacheId: String) -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    windowInsets: WindowInsets = AniWindowInsets.forPageContent(),
) {
    val state by vm.stateFlow.collectAsStateWithLifecycle()
    ForcedDarkTheme {
    CacheManagementScreen(
        state,
        selfInfo,
        onPlay,
        onResume = { vm.resumeCache(it) },
        onPause = { vm.pauseCache(it) },
        onDelete = { vm.deleteCache(it) },
        onViewDetail = { onNavigateCacheDetail(it.cacheId) },
        onClickLogin = onClickLogin,
        modifier = modifier,
        navigationIcon = navigationIcon,
        windowInsets = windowInsets,
        detailPaneContent = { group, selectionState ->
            if (group == null) {
                EmptyDetailPanePlaceholder(Modifier.fillMaxSize())
            } else {
                SubjectCacheDetailPaneContent(
                    vm = rememberSubjectCacheViewModel(group.subjectId),
                    selectionState = selectionState,
                    onPlay = onPlay,
                    onViewDetail = { onNavigateCacheDetail(it.cacheId) },
                    modifier = Modifier.fillMaxSize(),
                    singlePane = isSinglePane,
                )
            }
        },
    )
    }
}


@Composable
fun CacheManagementScreen(
    state: CacheManagementState,
    selfInfo: SelfInfoUiState?,
    onPlay: (CacheEpisodeState) -> Unit,
    onResume: (CacheEpisodeState) -> Unit,
    onPause: (CacheEpisodeState) -> Unit,
    onViewDetail: (CacheEpisodeState) -> Unit,
    onDelete: (CacheEpisodeState) -> Unit,
    onClickLogin: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    windowInsets: WindowInsets = AniWindowInsets.forPageContent(),
    detailPaneContent: (@Composable PaneScope.(group: CacheGroupState?, selectionState: CacheSelectionState) -> Unit)? = null,
) {
    // appBarColors 派生出顶栏 / 总体统计块 / 筛选 stickyHeader 三处底色, 默认的
    // surfaceContainerLowest 在浅色下是压在外壳背景上的白色矩形. 沉浸式外壳下统一改成外壳
    // 背景色 (视觉上无边界); 不用透明 —— stickyHeader 需要不透明底盖住滚到其下的列表项.
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation
    val immersiveShell = LocalAniUiBehavior.current.immersiveShell
    val appBarColors = if (immersiveShell) {
        val shellBackground = AniThemeDefaults.shellBackgroundColor
        AniThemeDefaults.topAppBarColors().copy(
            containerColor = shellBackground,
            scrolledContainerColor = shellBackground,
        )
    } else {
        AniThemeDefaults.topAppBarColors()
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val listState = rememberLazyListState()
    val cacheFilterState = rememberCacheFilterAndSortState()
    val selectionState = rememberCacheSelectionState()

    // TV: 删除某条目时, 聚焦的是该条目行, 行被移出列表 -> 焦点丢失. 删除后把焦点落到 top bar
    // 右上角按钮 (它始终存在). 用 pending 标志 + 等 entries 真正更新后再请求, 避免在列表更新前
    // 请求 (那时行还在).
    val topBarActionFocusRequester = remember { FocusRequester() }
    var pendingTopBarFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.entries) {
        if (pendingTopBarFocus) {
            pendingTopBarFocus = false
            if (focusDriven) {
                withFrameNanos { }
                runCatching { topBarActionFocusRequester.requestFocus() }
            }
        }
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<String>()

    // 设计稿: 筛选先过滤剧集再重组分组.
    val filteredGroups = cacheFilterState.applyFilterAndSortGrouped(state.groups)
    val selectionEntries = remember(filteredGroups) { filteredGroups.flatMap { it.entries } }

    // region selection
    var deleteSelectedCacheDialog by rememberSaveable { mutableStateOf(false) }

    // 当前选中的 entries
    val selectedEntries = remember(selectionEntries, selectionState.selectedIds) {
        selectionEntries.filter { it.cacheId in selectionState.selectedIds }
    }
    // 当前选中的 entries 数量
    val selectionCount = selectionState.selectedIds.size

    // 当缓存列表或筛选条件变化并且在编辑模式时, 需要确保 selectedIds 只能是当前可见的 entries,
    // 否则顶栏计数会包含被筛选隐藏 (无法反选) 的项, 而批量操作又不会作用于它们.
    // 列表尚未加载时 (为空) 跳过, 避免清空刚恢复的选择状态.
    LaunchedEffect(selectionEntries, selectionState.inSelection) {
        if (selectionState.inSelection && selectionEntries.isNotEmpty()) {
            val validIds = selectionEntries.mapTo(hashSetOf()) { it.cacheId }
            selectionState.overrideSelected(selectionState.selectedIds.filter { id -> id in validIds }.toSet())
        }
    }


    // 单栏布局且正在显示详情栏 (手机点击卡片进入, 或宽屏显示详情后缩小窗口).
    // 此时顶栏切换为 "返回 + 条目名", 详情内容使用手机样式.
    val isSinglePaneDetailVisible = navigator.scaffoldValue.let { value ->
        value[ListDetailPaneScaffoldRole.List] != PaneAdaptedValue.Expanded &&
                value[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    }

    // 当前正在浏览的 cache group
    var currentViewingGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.groups) {
        if (state.groups.isEmpty()) {
            currentViewingGroupKey = null
        } else if (state.groups.none { it.key == currentViewingGroupKey }) {
            currentViewingGroupKey = state.groups.first().key
        }
    }
    val currentViewingGroup = remember(state.groups, currentViewingGroupKey) {
        state.groups.firstOrNull { it.key == currentViewingGroupKey }
    }

    // 全选的作用范围: 单栏详情下仅为当前条目的缓存, 否则为全部可见缓存.
    val selectAllScopeEntries = if (isSinglePaneDetailVisible) {
        currentViewingGroup?.entries.orEmpty()
    } else {
        selectionEntries
    }
    // 是否已经全选
    val allSelected = remember(selectAllScopeEntries, selectionState.selectedIds) {
        selectAllScopeEntries.isNotEmpty() &&
                selectAllScopeEntries.all { it.cacheId in selectionState.selectedIds }
    }

    // 确认删除的对话框
    if (deleteSelectedCacheDialog) {
        DeleteActionDialog(
            onDismiss = { deleteSelectedCacheDialog = false },
            summary = rememberCacheDeleteSummary(selectedEntries),
            onConfirm = {
                selectionEntries.filter { it.cacheId in selectionState.selectedIds }
                    .forEach { onDelete(it) }
                selectionState.clear()
                deleteSelectedCacheDialog = false
            },
        )
    }
    // endregion

    val tasker = rememberAsyncHandler()

    Scaffold(
        modifier = modifier,
        topBar = {
            CacheManagementTopBar(
                selectionMode = selectionState.inSelection,
                selectionCount = selectionCount,
                allSelected = allSelected,
                hasEntries = selectAllScopeEntries.isNotEmpty(),
                onEnterSelection = { selectionState.enterSelectionWith(emptySet()) },
                onExitSelection = { selectionState.clear() },
                onToggleSelectAll = {
                    val scopeIds = selectAllScopeEntries.map { it.cacheId }
                    selectionState.enterSelectionWith(
                        if (allSelected) {
                            selectionState.selectedIds - scopeIds.toSet()
                        } else {
                            selectionState.selectedIds + scopeIds
                        },
                    )
                },
                selfInfo = selfInfo,
                onClickLogin = onClickLogin,
                navigationIcon = navigationIcon,
                appBarColors = appBarColors,
                windowInsets = AniWindowInsets.forTopAppBarWithoutDesktopTitle(),
                scrollBehavior = scrollBehavior,
                detailPaneTitle = if (isSinglePaneDetailVisible) currentViewingGroup?.subjectName else null,
                onNavigateBackFromDetail = { tasker.launch { navigator.navigateBack() } },
                actionFocusRequester = topBarActionFocusRequester,
            )
        },
        bottomBar = {
            AniAnimatedVisibility(selectionState.inSelection) {
                CacheSelectionFloatingToolbar(
                    resumeEnabled = selectedEntries.any { !it.isFinished && it.isPaused },
                    pauseEnabled = selectedEntries.any { !it.isFinished && !it.isPaused && !it.isFailed },
                    deleteEnabled = selectedEntries.isNotEmpty(),
                    onResumeSelected = {
                        selectedEntries.filter { !it.isFinished && it.isPaused }.forEach(onResume)
                    },
                    onPauseSelected = {
                        selectedEntries.filter { !it.isFinished && !it.isPaused && !it.isFailed }.forEach(onPause)
                    },
                    onDeleteSelected = { deleteSelectedCacheDialog = true },
                    windowInsets = windowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
            }
        },
        // 沉浸式外壳: 透明, 透出外壳统一的全屏背景 (见 MainScreen); 其它平台维持原页面背景色
        containerColor = if (immersiveShell) Color.Transparent else AniThemeDefaults.pageContentBackgroundColor,
        contentWindowInsets = windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        // bottom padding 作为列表的 contentPadding, 让内容可以滚动到毛玻璃导航栏下方.
        val listBottomPadding = PaddingValues(bottom = paddingValues.calculateBottomPadding())
        val windowSizeClass = currentWindowAdaptiveInfo1().windowSizeClass
        val paneExtraPadding = windowSizeClass.paneHorizontalPadding
        AniListDetailPaneScaffold(
            // 毛玻璃 app chrome 的模糊来源.
            modifier = Modifier
                .appChromeHazeSource(backgroundColor = AniThemeDefaults.pageContentBackgroundColor)
                .padding(
                    start = paddingValues.calculateStartPadding(layoutDirection),
                    top = paddingValues.calculateTopPadding(),
                    end = paddingValues.calculateEndPadding(layoutDirection),
                )
                // 设计稿: 超大屏时整体限宽.
                .fillMaxWidth()
                .wrapContentWidth()
                .widthIn(max = 1200.dp),
            navigator = navigator,
            listPaneTopAppBar = null,
            listPaneContent = {
                CacheGroupCardsList(
                    state = state,
                    filteredGroups = filteredGroups,
                    selectionEntries = selectionEntries,
                    selectedEntries = selectedEntries,
                    selectionState = selectionState,
                    cacheFilterState = cacheFilterState,
                    appBarColors = appBarColors,
                    scrollBehavior = scrollBehavior,
                    listState = listState,
                    listBottomPadding = listBottomPadding,
                    paneExtraPadding = paneExtraPadding,
                    highlightSelectedGroupKey = if (isSinglePane) null else currentViewingGroupKey,
                    onClickGroup = { group ->
                        // 单栏 (手机) 下 scaffold 会以前进导航方式全屏展示详情栏, 并支持返回.
                        currentViewingGroupKey = group.key
                        tasker.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                        }
                    },
                    onToggleGroupSelection = { group ->
                        selectionState.toggleSelection(*group.entries.map { it.cacheId }.toTypedArray())
                    },
                    onEnterGroupSelection = { group ->
                        selectionState.enterSelectionWith(selectionState.selectedIds + group.entries.map { it.cacheId })
                    },
                )
            },
            detailPane = {
                Column(
                    Modifier
                        .paneContentPadding(extraStart = (-16).dp, extraEnd = (-16).dp)
                        .paneWindowInsetsPadding()
                        .padding(listBottomPadding)
                        // 单栏时详情栏全屏展示, 滚动需要驱动共享的顶栏.
                        .then(
                            if (isSinglePane) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
                        )
                        .fillMaxSize(),
                ) {
                    if (detailPaneContent != null) {
                        detailPaneContent(currentViewingGroup, selectionState)
                    } else {
                        DefaultCacheGroupDetailPane(
                            group = currentViewingGroup,
                            selectionState = selectionState,
                            onPlay = onPlay,
                            onResume = onResume,
                            onPause = onPause,
                            // TV: 单条删除后该行被移除会丢焦点, 标记一下, 待 entries 更新后把
                            // 焦点落到 top bar.
                            onDelete = { entry ->
                                onDelete(entry)
                                pendingTopBarFocus = true
                            },
                            onViewDetail = onViewDetail,
                            modifier = Modifier.fillMaxSize(),
                            singlePane = isSinglePane,
                        )
                    }
                }
            },
            // Bottom 通过 listBottomPadding 应用, 这里不再包含, 避免重复.
            contentWindowInsets = windowInsets.only(WindowInsetsSides.Horizontal),
            useSharedTransition = false,
            listPanePreferredWidth = preferredListPaneWidth(),
            // 默认的 min 为 412.dp (≥1200dp 时), 会顶掉 400.dp 的 preferred 宽度.
            minListPaneWidth = preferredListPaneWidth(),
        )

        // 选择模式下导航返回应该退出选择模式.
        // 注意: 必须在 AniListDetailPaneScaffold 之后注册, 才能优先于 scaffold 的返回 (详情->列表) 处理.
        BackHandler(selectionState.inSelection) { selectionState.clear() }
    }
}

/**
 * 设计稿: 超大屏 (1600dp+) 时左栏固定 400dp.
 */
@Composable
private fun preferredListPaneWidth(): Dp {
    val windowSizeClass = currentWindowAdaptiveInfo1().windowSizeClass
    return when {
        windowSizeClass.isWidthAtLeastBreakpoint(1600) -> 400.dp
        windowSizeClass.isWidthAtLeastBreakpoint(1200) -> 412.dp // Large, M3 spec
        windowSizeClass.isWidthAtLeastBreakpoint(840) -> 360.dp // Expanded, M3 spec
        else -> (((windowSizeClass.minWidthDp - 24 * 3).toFloat() / 2).dp).coerceAtLeast(360.dp) // M3 spec
    }
}

/**
 * 列表栏: 总体统计 + 筛选栏 + 按条目分组的卡片.
 */
@Composable
private fun PaneScope.CacheGroupCardsList(
    state: CacheManagementState,
    filteredGroups: List<CacheGroupState>,
    selectionEntries: List<CacheEpisodeState>,
    selectedEntries: List<CacheEpisodeState>,
    selectionState: CacheSelectionState,
    cacheFilterState: me.him188.ani.app.ui.cache.components.CacheFilterAndSortState,
    appBarColors: TopAppBarColors,
    scrollBehavior: TopAppBarScrollBehavior,
    listState: LazyListState,
    listBottomPadding: PaddingValues,
    paneExtraPadding: Dp,
    highlightSelectedGroupKey: String?,
    onClickGroup: (CacheGroupState) -> Unit,
    onToggleGroupSelection: (CacheGroupState) -> Unit,
    onEnterGroupSelection: (CacheGroupState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topAppBarContainerColor by rememberCurrentTopAppBarContainerColor(appBarColors, scrollBehavior)
    LazyColumn(
        modifier = modifier
            .paneWindowInsetsPadding()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .fillMaxWidth(),
        state = listState,
        contentPadding = listBottomPadding + PaddingValues(bottom = paneExtraPadding),
    ) {
        item("overall_stats") {
            Surface(
                color = appBarColors.containerColor,
                contentColor = contentColorFor(appBarColors.containerColor),
            ) {
                if (selectionState.inSelection) {
                    CacheSelectionSummary(
                        selectedEntries,
                        Modifier
                            .paneContentPadding()
                            .fillMaxWidth(),
                    )
                } else {
                    CacheManagementOverallStats(
                        { state.overallStats },
                        Modifier
                            .paneContentPadding()
                            .fillMaxWidth(),
                    )
                }
            }
        }
        stickyHeader("filter_row") {
            CacheFilterAndSortBar(
                state = cacheFilterState,
                modifier = Modifier.paneContentPadding().fillMaxWidth().padding(vertical = 8.dp),
                containerColor = topAppBarContainerColor,
                mediaCacheEngineOptions = remember(state.entries) {
                    state.entries.mapNotNull { it.engineKey }.distinct()
                },
            )
        }
        items(filteredGroups, key = { it.key }) { group ->
            CacheSubjectGroupCard(
                group = group,
                selected = group.key == highlightSelectedGroupKey,
                selectionMode = selectionState.inSelection,
                allEntriesSelected = group.entries.all { it.cacheId in selectionState.selectedIds },
                onToggleGroupSelection = { onToggleGroupSelection(group) },
                onLongClick = { onEnterGroupSelection(group) },
                onClick = {
                    if (selectionState.inSelection) {
                        onToggleGroupSelection(group)
                    } else {
                        onClickGroup(group)
                    }
                },
                // 设计稿: 手机上卡片通栏 (内部自带 16dp padding); 宽屏列表栏卡片距 pane 边 8dp.
                modifier = if (isSinglePane) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.padding(horizontal = 8.dp).fillMaxWidth()
                },
                showChevron = isSinglePane,
                shape = if (isSinglePane) RectangleShape else MaterialTheme.shapes.large,
            )
        }
    }
}

/**
 * 无状态版本的详情栏内容, 供测试与预览使用: 仅展示已缓存的剧集, 不包含追加缓存.
 */
@Composable
private fun DefaultCacheGroupDetailPane(
    group: CacheGroupState?,
    selectionState: CacheSelectionState,
    onPlay: (CacheEpisodeState) -> Unit,
    onResume: (CacheEpisodeState) -> Unit,
    onPause: (CacheEpisodeState) -> Unit,
    onDelete: (CacheEpisodeState) -> Unit,
    onViewDetail: (CacheEpisodeState) -> Unit,
    modifier: Modifier = Modifier,
    // 单栏 (手机) 时: 头部为汇总行 (条目名显示在顶栏), 行通栏无圆角无间距.
    singlePane: Boolean = false,
) {
    if (group == null) {
        EmptyDetailPanePlaceholder(modifier)
        return
    }
    val onPauseAll = {
        group.entries.filter { !it.isFinished && !it.isPaused && !it.isFailed }.forEach(onPause)
    }
    val onResumeAll = {
        group.entries.filter { it.isPaused }.forEach(onResume)
    }
    val rowShape = if (singlePane) RectangleShape else MaterialTheme.shapes.medium
    LazyColumn(
        modifier,
        // 设计稿: 宽屏详情栏头部距卡片顶部 16dp, 行间距 8dp; 手机上行连续排列.
        contentPadding = if (singlePane) PaddingValues(0.dp) else PaddingValues(top = 16.dp),
        verticalArrangement = if (singlePane) Arrangement.Top else Arrangement.spacedBy(8.dp),
    ) {
        item("detail_header") {
            if (singlePane) {
                SubjectCacheSummaryRow(
                    cachedEpisodes = group.entries,
                    totalEpisodeCount = group.totalEpisodeCount,
                    inSelection = selectionState.inSelection,
                    selectedEntries = group.entries.filter { it.cacheId in selectionState.selectedIds },
                    onPauseAll = onPauseAll,
                    onResumeAll = onResumeAll,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                SubjectCacheDetailHeader(
                    title = group.subjectName,
                    cachedEpisodes = group.entries,
                    totalEpisodeCount = group.totalEpisodeCount,
                    onPauseAll = onPauseAll,
                    onResumeAll = onResumeAll,
                )
            }
        }
        items(group.entries, key = { it.listItemKey }) { entry ->
            CacheEpisodeRow(
                episode = entry,
                mediaSourceInfoProvider = null,
                selectionMode = selectionState.inSelection,
                selected = entry.cacheId in selectionState.selectedIds,
                onToggleSelected = { selectionState.toggleSelection(entry.cacheId) },
                onEnterSelection = {
                    selectionState.enterSelectionWith(selectionState.selectedIds + entry.cacheId)
                },
                onPlay = { onPlay(entry) },
                onResume = { onResume(entry) },
                onPause = { onPause(entry) },
                onDelete = { onDelete(entry) },
                onViewDetail = { onViewDetail(entry) },
                shape = rowShape,
            )
        }
    }
}

@Composable
private fun EmptyDetailPanePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier.padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(Lang.cache_management_select_item_for_details))
    }
}

@Composable
private fun CacheManagementTopBar(
    selectionMode: Boolean,
    selectionCount: Int,
    allSelected: Boolean,
    hasEntries: Boolean,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    selfInfo: SelfInfoUiState?,
    onClickLogin: () -> Unit,
    navigationIcon: @Composable () -> Unit,
    appBarColors: TopAppBarColors,
    windowInsets: WindowInsets,
    scrollBehavior: TopAppBarScrollBehavior?,
    // 单栏布局显示详情栏时, 顶栏变为 "返回 + 条目名" (与条目缓存页一致); 多选模式优先.
    detailPaneTitle: String? = null,
    onNavigateBackFromDetail: () -> Unit = {},
    /** TV: 绑到右上角 action 按钮的 FocusRequester. 由上层 hoist, 既用于切换选择模式后夺回焦点,
     *  也用于删除条目后把焦点落到这里 (top bar 始终存在). */
    actionFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    // TV: 切换选择模式会把整个 TopAppBar 换成另一套按钮 (if/else 两个不同的 AniTopAppBar), 原来
    // 聚焦的右上角按钮被销毁 -> 焦点丢失. FocusRequester hoist 在上层 (不随 if/else 重建), 绑到
    // 两个分支右上角的 action 按钮上; 模式切换后请求焦点回该按钮, 焦点就不会丢.
    // 跳过首次组合, 避免进页面就抢焦点.
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation
    var selectionModeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(selectionMode) {
        if (!focusDriven) return@LaunchedEffect
        if (!selectionModeSeen) {
            selectionModeSeen = true
            return@LaunchedEffect
        }
        withFrameNanos { } // 等新分支布局完成再请求
        runCatching { actionFocusRequester.requestFocus() }
    }
    val actionFocusModifier = if (focusDriven) Modifier.focusRequester(actionFocusRequester) else Modifier

    if (!selectionMode && detailPaneTitle != null) {
        AniTopAppBar(
            title = { AniTopAppBarDefaults.Title(detailPaneTitle) },
            navigationIcon = { BackNavigationIconButton(onNavigateBackFromDetail) },
            avatar = { },
            colors = appBarColors,
            windowInsets = windowInsets,
            scrollBehavior = scrollBehavior,
        )
        return
    }
    if (selectionMode) {
        val selectedCountText = stringResource(Lang.cache_management_selected_count, selectionCount)
        val exitSelectionText = stringResource(Lang.cache_management_exit_selection)
        val selectAllText = stringResource(Lang.cache_management_select_all)
        AniTopAppBar(
            title = { AniTopAppBarDefaults.Title(selectedCountText) },
            navigationIcon = {
                IconButton(onClick = onExitSelection) { Icon(Icons.Rounded.Close, exitSelectionText) }
            },
            actions = {
                IconButton(
                    onClick = onToggleSelectAll,
                    enabled = hasEntries,
                    modifier = actionFocusModifier,
                ) {
                    Icon(
                        if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                        selectAllText,
                    )
                }
            },
            avatar = { },
            colors = appBarColors,
            windowInsets = windowInsets,
            scrollBehavior = scrollBehavior,
        )
    } else {
        AniTopAppBar(
            title = { AniTopAppBarDefaults.Title(stringResource(Lang.main_screen_page_cache_management)) },
            navigationIcon = navigationIcon,
            actions = {
                val enterSelectionModeText = stringResource(Lang.cache_management_enter_selection_mode)
                IconButton(
                    onClick = onEnterSelection,
                    enabled = hasEntries,
                    modifier = actionFocusModifier,
                ) {
                    Icon(Icons.Default.Checklist, enterSelectionModeText)
                }
            },
            // TV: 头像由主页左侧边栏统一承载, 顶栏不再重复
            avatar = if (focusDriven) {
                { }
            } else {
                selfInfo?.let {
                    { recommendedSize ->
                        SelfAvatar(
                            state = it,
                            onClick = onClickLogin,
                            size = recommendedSize,
                        )
                    }
                } ?: { }
            },
            colors = appBarColors,
            windowInsets = windowInsets,
            scrollBehavior = scrollBehavior,
        )
    }
}


/**
 * 多选模式下代替总体统计的选择摘要: "已选 n 项 · 共 x GB · 含 n 个下载中".
 */
@Composable
private fun CacheSelectionSummary(
    selectedEntries: List<CacheEpisodeState>,
    modifier: Modifier = Modifier,
) {
    val totalSize = remember(selectedEntries) {
        selectedEntries.fold(0L) { acc, entry -> acc + entry.totalSize.inBytes }.bytes
    }
    val downloadingCount = remember(selectedEntries) {
        selectedEntries.count { !it.isFinished && !it.isPaused && !it.isFailed }
    }
    val summaryText = stringResource(Lang.cache_management_selection_summary, selectedEntries.size, "$totalSize")
    val downloadingText = stringResource(Lang.cache_management_selection_downloading_count, downloadingCount)
    Text(
        if (downloadingCount > 0) "$summaryText · $downloadingText" else summaryText,
        modifier.padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

object CacheManagementTestTags {
    const val DELETE_CONFIRM_BUTTON = "cache_management_delete_confirm_button"
}

@Composable
internal fun DeleteActionDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    summary: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(Lang.cache_management_delete_cache_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Lang.cache_management_delete_cache_confirmation))
                if (summary != null) {
                    Text(summary, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(CacheManagementTestTags.DELETE_CONFIRM_BUTTON),
            ) { Text(stringResource(Lang.cache_subject_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(Lang.cache_subject_cancel)) }
        },
    )
}

@Composable
internal fun CacheActionDropdown(
    show: Boolean,
    onDismiss: () -> Unit,
    episode: CacheEpisodeState,
    onPlay: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onViewDetail: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
) {
    val toaster = LocalToaster.current
    val resumeDownloadText = stringResource(Lang.cache_episode_resume_download)
    val pauseDownloadText = stringResource(Lang.cache_episode_pause_download)
    val playText = stringResource(Lang.cache_management_play)
    val invalidCacheInfoText = stringResource(Lang.cache_management_invalid_cache_info)
    val streamingNotSupportedText = stringResource(Lang.cache_management_streaming_not_supported)
    val moreInfoText = stringResource(Lang.cache_management_more_info)
    DropdownMenu(
        expanded = show,
        onDismissRequest = onDismiss,
        modifier = modifier,
        offset = offset,
    ) {
        if (!episode.isFinished) {
            if (episode.isPaused) {
                DropdownMenuItem(
                    text = { Text(resumeDownloadText) },
                    leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                    onClick = {
                        onResume()
                        onDismiss()
                    },
                )
            } else if (!episode.isFailed) {
                DropdownMenuItem(
                    text = { Text(pauseDownloadText) },
                    leadingIcon = { Icon(Icons.Rounded.Pause, null) },
                    onClick = {
                        onPause()
                        onDismiss()
                    },
                )
            }
        }
        if (!episode.isFailed) {
            DropdownMenuItem(
                text = { Text(playText) },
                leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                onClick = {
                    when (episode.playability) {
                        CacheEpisodeState.Playability.PLAYABLE -> {
                            onPlay()
                            onDismiss()
                        }

                        CacheEpisodeState.Playability.INVALID_SUBJECT_EPISODE_ID -> {
                            toaster.toast(invalidCacheInfoText)
                        }

                        CacheEpisodeState.Playability.STREAMING_NOT_SUPPORTED -> {
                            toaster.toast(streamingNotSupportedText)
                        }
                    }
                },
            )
        }
        onViewDetail?.let {
            DropdownMenuItem(
                text = { Text(moreInfoText) },
                leadingIcon = { Icon(Icons.Rounded.Info, null) },
                onClick = {
                    it()
                    onDismiss()
                },
            )
        }

        DropdownMenuItem(
            text = { Text(stringResource(Lang.cache_subject_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = {
                onDelete()
                onDismiss()
            },
        )
    }
}

@OptIn(TestOnly::class)
@Preview
@Composable
private fun PreviewCacheManagementScreen() {
    ProvideCompositionLocalsForPreview {
        CacheManagementScreen(
            state = remember {
                CacheManagementState(
                    createTestMediaStats(),
                    TestCacheGroupSates,
                )
            },
            selfInfo = null,
            onPlay = { },
            onResume = {},
            onPause = {},
            onDelete = {},
            onClickLogin = { },
            onViewDetail = { },
            navigationIcon = { BackNavigationIconButton({ }) },
        )
    }
}

/**
 * 批量删除前那句明细: `将删除 10 项缓存` + 按作品分行的 `作品名（7 项）`.
 *
 * 按作品分行而不是一行列完: 电视上一行放不下几个中文剧名, 而"跨了几部作品"恰恰是这句话要传达的
 * 关键信息 —— 用户以为只在删眼前这一部时, 多出来的那几行就是刹车. 超过 [MAX_TITLES] 部只列前几部
 * 加省略号 (真到那个量级, "跨了很多部"这个事实已经传达到了).
 */
@Composable
internal fun rememberCacheDeleteSummary(entries: List<CacheEpisodeState>): String? {
    if (entries.isEmpty()) return null
    val header = stringResource(Lang.cache_management_delete_summary, entries.size)
    val byTitle = entries.groupBy { it.subjectName }
    // 逐条取资源 (stringResource 只能在组合里调), 所以先在组合里把每一行拼好
    val lines = byTitle.entries.take(MAX_DELETE_SUMMARY_TITLES).map { (title, list) ->
        stringResource(Lang.cache_management_delete_summary_item, title, list.size)
    }
    val more = if (byTitle.size > MAX_DELETE_SUMMARY_TITLES) "\n…" else ""
    return header + "\n" + lines.joinToString("\n") + more
}

/** 删除明细里最多列几部作品, 见 [rememberCacheDeleteSummary]. */
private const val MAX_DELETE_SUMMARY_TITLES = 4
