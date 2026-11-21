/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.mediaselect.summary.MediaSelectorSummaryCard
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.all_episodes_tab_specials
import me.him188.ani.app.ui.lang.subject_details_main_episodes
import me.him188.ani.app.ui.lang.subject_episode_close_selector
import me.him188.ani.app.ui.lang.video_player_select_episode
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadState
import me.him188.ani.app.ui.subject.details.layout.TvEpisodeDetailsDialog
import me.him188.ani.app.ui.subject.details.layout.TvPadEpisodeCard
import me.him188.ani.app.ui.subject.details.sections.episodeMetaText
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerOverlayState
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem
import me.him188.ani.app.ui.subject.episode.list.gridSpecialEpisodes
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeRequest
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import org.jetbrains.compose.resources.stringResource

/**
 * 侧边那一块: 分栏是右栏 (没开面板时顶上是当前数据源、下面是推荐, 面板盖住这两样), 全屏是右侧抽屉 (左边空白点了就关), 竖屏是盖住视频下面那一块的面板.
 * 见 [TabletSideMode]. 抽屉与竖屏面板只在开着某一页时才摆出来. 底色取主题的 surfaceContainerLow (抽屉略透).
 */
@Composable
internal fun TabletSideColumn(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    tablet: TabletPlayerState,
    geometry: TabletPlayerGeometry,
    modifier: Modifier = Modifier,
) {
    val panel = tablet.shownPanel
    // 评论列表滚到哪: 记在这一层 (从一整楼退回来时面板重新组合, 还在原处); 换集从顶上开始
    val commentsGrid = remember(page.episodePresentation.episodeId) { LazyGridState() }
    when (geometry.sideMode) {
        TabletSideMode.DRAWER -> Row(modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tablet.closeShown() },
            )
            val width = with(LocalDensity.current) { geometry.panelWidth.toDp() }
            Box(Modifier.width(width).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f))) {
                if (panel != null) TabletPanelContent(panel, vm, page, overlay, tablet, commentsGrid)
            }
        }

        TabletSideMode.COLUMN, TabletSideMode.SHEET -> Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            if (panel != null) {
                TabletPanelContent(panel, vm, page, overlay, tablet, commentsGrid)
            } else if (geometry.sideMode == TabletSideMode.COLUMN) {
                // 右栏平时: 顶上常驻当前数据源 (同上游宽屏右边详情里那张, 点「更换」开数据源面板), 下面是相关推荐
                Column(Modifier.fillMaxSize()) {
                    MediaSelectorSummaryCard(
                        page.mediaSelectorSummary,
                        onClickManualSelect = { tablet.toggle(TabletSidePanel.SOURCES) },
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        TabletRecommendationsColumn(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletPanelContent(
    panel: TabletSidePanel,
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    tablet: TabletPlayerState,
    commentsGrid: LazyGridState,
) {
    when (panel) {
        TabletSidePanel.EPISODES -> TabletEpisodesPanel(
            vm,
            onClose = { tablet.closeShown() },
            // 全屏抽屉里选了一集就收起 (分栏的右栏留着, 接着能看下一集是哪集)
            onPicked = { if (tablet.fullScreen) tablet.closeShown() },
        )

        TabletSidePanel.SOURCES -> TabletSourcePanel(vm, page, tablet, onClose = { tablet.closeShown() })

        TabletSidePanel.DETAILS -> TabletDetailsPanel(vm, onClose = { tablet.closeShown() })

        TabletSidePanel.COMMENTS -> TabletCommentsPanel(
            vm,
            page,
            onClose = { tablet.closeShown() },
            onOpenThread = { tablet.openThread(it) },
            gridState = commentsGrid,
        )

        TabletSidePanel.THREAD -> {
            val thread = tablet.thread
            if (thread == null) {
                // 那一楼没存 (见 TabletPlayerState.thread), 退回评论列表
                SideEffect { tablet.pop() }
            } else {
                TabletThreadPanel(thread, onBack = { tablet.pop() }, onClose = { tablet.closeShown() })
            }
        }

        TabletSidePanel.DANMAKU -> TabletDanmakuPanel(vm, page, overlay, tablet, onClose = { tablet.closeShown() })
    }
}

/**
 * 面板栏顶: (← 退一层, 第二层起才有) + 标题 + 数量 (可空) + 右边的附加内容 + ✕ (收起整个面板).
 * 给了 [subtitle] 时标题大一号、底下一行写它 (数据源面板的查询进度).
 */
@Composable
internal fun TabletPanelHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .padding(start = if (onBack != null) 4.dp else 16.dp, top = if (subtitle != null) 8.dp else 0.dp, bottom = if (subtitle != null) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = if (subtitle != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = if (subtitle != null) FontWeight.Bold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (note != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(Lang.subject_episode_close_selector))
            }
        }
        HorizontalDivider()
    }
}

/**
 * 面板里切页的小标签 (正片 / 特别篇、弹幕列表 / 弹幕设置): 浅底圆头胶囊, 选中的只把字换成强调色 (不上底色、不描边),
 * 没选中的字淡一档.
 */
@Composable
internal fun TabletTabChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) tabletAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            LocalTextStyle provides MaterialTheme.typography.labelLarge,
            content = label,
        )
    }
}

/**
 * 全部选集: 正片 / 特别篇两个标签 (没有特别篇时不显示), 两列剧照卡 (同平板详情页的选集卡, 没剧照的是文字卡).
 * 点卡片换到那一集 (点正在播的那一集只收面板, 不重新起播); 长按开本集详情 (标记看过 / 取消看过).
 * 数据同播放器的选集条: 分集列表走播放器自己的那条, 剧照 / 进度 / 时长 / 集名取条目详情状态.
 */
@Composable
private fun TabletEpisodesPanel(
    vm: EpisodeViewModel,
    onClose: () -> Unit,
    onPicked: () -> Unit,
) {
    val episodeList by vm.episodeListUiStateFlow.collectAsStateWithLifecycle()
    val loadState by vm.episodeDetailsState.subjectDetailsStateLoader.state.collectAsStateWithLifecycle()
    val state = (loadState as? SubjectDetailsLoadState.Ok)?.value
    val stills by remember(state) { state?.tmdbEpisodeStillsFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
    val playProgress by remember(state) { state?.playProgressFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
    val runtimes by remember(state) { state?.tmdbEpisodeRuntimesFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
    val overviews by remember(state) { state?.tmdbEpisodeOverviewsFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
    val names by remember(state) { state?.tmdbEpisodeNamesFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())

    val specialsPlacement = LocalThemeSettings.current.tvEpisodeSpecials
    val main = episodeList?.mainEpisodes.orEmpty()
    val specials = remember(episodeList, specialsPlacement) { episodeList?.gridSpecialEpisodes(specialsPlacement).orEmpty() }
    val currentEpisodeId = vm.episodeSelectorState.current?.episodeId
    // 打开时停在正在播的那一集所在的标签
    var showSpecials by rememberSaveable { mutableStateOf(specials.any { it.episodeId == currentEpisodeId } && main.none { it.episodeId == currentEpisodeId }) }
    val shown = if (showSpecials && specials.isNotEmpty()) specials else main

    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var detailsTarget by remember { mutableStateOf<EpisodeListItem?>(null) }
    val gridState = rememberLazyGridState()
    var aligned by rememberSaveable { mutableStateOf(false) }
    val currentIndex = shown.indexOfFirst { it.episodeId == currentEpisodeId }
    LaunchedEffect(currentIndex) {
        if (aligned || currentIndex < 0) return@LaunchedEffect
        gridState.scrollToItem(currentIndex)
        aligned = true
    }

    Column(Modifier.fillMaxSize()) {
        TabletPanelHeader(stringResource(Lang.video_player_select_episode), onClose, note = shown.size.toString())
        if (specials.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabletTabChip(
                    selected = !showSpecials,
                    onClick = { showSpecials = false },
                    label = { Text(stringResource(Lang.subject_details_main_episodes) + " " + main.size) },
                )
                TabletTabChip(
                    selected = showSpecials,
                    onClick = { showSpecials = true },
                    label = { Text(stringResource(Lang.all_episodes_tab_specials) + " " + specials.size) },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(shown, key = { it.episodeId }) { item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvPadEpisodeCard(
                        item,
                        stillUrl = stills[item.episodeId],
                        isCurrent = item.episodeId == currentEpisodeId,
                        progress = playProgress[item.episodeId],
                        names = names,
                        onClick = {
                            if (item.episodeId != currentEpisodeId &&
                                !vm.episodeSelectorState.selectEpisodeId(item.episodeId)
                            ) {
                                navigator.navigateEpisodeDetails(vm.subjectId, item.episodeId)
                            }
                            onPicked()
                        },
                        onLongClick = { detailsTarget = item },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                    episodeMetaText(runtimes[item.episodeId], item.airDate)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    detailsTarget?.let { target ->
        TvEpisodeDetailsDialog(
            target,
            episodes = shown,
            onNavigate = { detailsTarget = it },
            stillUrl = stills[target.episodeId],
            runtime = runtimes[target.episodeId],
            overview = overviews[target.episodeId],
            names = names,
            onSetCollectionType = { type ->
                scope.launch {
                    vm.setEpisodeCollectionType.invokeSafe(
                        SetEpisodeCollectionTypeRequest(vm.subjectId, target.episodeId, type),
                    )?.let { toaster.showLoadError(it) }
                }
            },
            onDismiss = { detailsTarget = null },
        )
    }
}
