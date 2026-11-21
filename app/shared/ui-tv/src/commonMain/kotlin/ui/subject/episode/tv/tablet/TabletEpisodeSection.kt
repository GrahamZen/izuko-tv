/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.SubjectAiringInfo
import me.him188.ani.app.data.models.subject.SubjectAiringKind
import me.him188.ani.app.domain.episode.SetEpisodeCollectionTypeRequest
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.showLoadError
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_select_episode
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadState
import me.him188.ani.app.ui.subject.details.layout.TvEpisodeDetailsDialog
import me.him188.ani.app.ui.subject.details.layout.TvPadEpisodeCard
import me.him188.ani.app.ui.subject.details.state.SubjectDetailsState
import me.him188.ani.app.ui.subject.details.state.rememberTmdbAiringInfo
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.list.EpisodeListItem
import me.him188.ani.app.ui.subject.episode.list.gridSpecialEpisodes
import me.him188.ani.app.ui.subject.renderEpAndSort
import me.him188.ani.app.ui.subject.renderTotalEpisodeText
import me.him188.ani.app.ui.subject.rememberSubjectStatusStrings
import org.jetbrains.compose.resources.stringResource

/**
 * 视频下面的「选集」一节 (同哔哩哔哩 iPad 版视频下面的选集):
 * 标题行「选集 … 已完结 · 全 12 话 ›」, 点了在侧边打开完整的选集 (再点收起; 开着时 › 是主题色);
 * 下面一排横滑的集卡片, 同侧边选集面板的卡片但不带剧照 (集号 + 集名; 正在看 / 看完 / 看到一半怎么画见 [TvPadEpisodeCard]).
 * 点卡片换到那一集, 长按出这一集的详情 (同侧边面板).
 *
 * 平时列正片; 正在看特别篇时列特别篇 (同侧边面板打开时停的那个标签). 进来时与换集后把正在看的那张滚进视野.
 * 标题行右边只写连载状态与总集数 (见 [tabletAiringSummary]), 不写看到第几集: 正在看哪一集看高亮的卡片.
 * 一集都没有时整节不画.
 */
@Composable
internal fun TabletEpisodeSection(vm: EpisodeViewModel, tablet: TabletPlayerState, modifier: Modifier = Modifier) {
    val loadState by vm.episodeDetailsState.subjectDetailsStateLoader.state.collectAsStateWithLifecycle()
    val details = (loadState as? SubjectDetailsLoadState.Ok)?.value
    val episodeList by vm.episodeListUiStateFlow.collectAsStateWithLifecycle()
    val specialsPlacement = LocalThemeSettings.current.tvEpisodeSpecials
    val main = episodeList?.mainEpisodes.orEmpty()
    val specials = remember(episodeList, specialsPlacement) { episodeList?.gridSpecialEpisodes(specialsPlacement).orEmpty() }
    val currentEpisodeId = vm.episodeSelectorState.current?.episodeId
    val shown = if (main.none { it.episodeId == currentEpisodeId } && specials.any { it.episodeId == currentEpisodeId }) specials else main
    if (shown.isEmpty()) return

    val playProgress by remember(details) { details?.playProgressFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
    val names by remember(details) { details?.tmdbEpisodeNamesFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
    val navigator = LocalNavigator.current
    var detailsTarget by remember { mutableStateOf<EpisodeListItem?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().tabletTextClickable { tablet.toggle(TabletSidePanel.EPISODES) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Lang.video_player_select_episode),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (details != null) {
                tabletAiringSummary(details)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                Modifier.size(18.dp),
                tint = if (tablet.shownPanel == TabletSidePanel.EPISODES) tabletAccent else LocalContentColor.current,
            )
        }

        val listState = rememberLazyListState()
        val currentIndex = shown.indexOfFirst { it.episodeId == currentEpisodeId }
        var aligned by remember { mutableStateOf(false) }
        LaunchedEffect(currentIndex, shown.size) {
            if (currentIndex < 0) return@LaunchedEffect
            // 正在看的那张前面留一张, 看得出前一集是哪集
            val target = (currentIndex - 1).coerceAtLeast(0)
            if (!aligned) {
                listState.scrollToItem(target)
                aligned = true
                return@LaunchedEffect
            }
            val layout = listState.layoutInfo
            val fullyVisible = layout.visibleItemsInfo.any {
                it.index == currentIndex && it.offset >= 0 && it.offset + it.size <= layout.viewportEndOffset
            }
            if (!fullyVisible) listState.animateScrollToItem(target)
        }
        LazyRow(
            Modifier.tabletRowBleed(),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = TABLET_BELOW_VIDEO_SIDE_PADDING),
        ) {
            items(shown, key = { it.episodeId }) { item ->
                TvPadEpisodeCard(
                    item,
                    stillUrl = null,
                    isCurrent = item.episodeId == currentEpisodeId,
                    progress = playProgress[item.episodeId],
                    names = names,
                    onClick = { pickTabletEpisode(vm, navigator, item.episodeId) },
                    onLongClick = { detailsTarget = item },
                    modifier = Modifier.width(TABLET_EPISODE_CHIP_WIDTH).height(TABLET_EPISODE_CHIP_HEIGHT),
                    centerText = true,
                )
            }
        }
    }

    // 长按的详情: 剧照、时长、简介只在弹窗开着时取 (平时这一排不带剧照, 不为它发请求)
    detailsTarget?.let { target ->
        val stills by remember(details) { details?.tmdbEpisodeStillsFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
        val runtimes by remember(details) { details?.tmdbEpisodeRuntimesFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
        val overviews by remember(details) { details?.tmdbEpisodeOverviewsFlow ?: emptyFlow() }.collectAsStateWithLifecycle(emptyMap())
        TabletEpisodeDetailsDialog(
            vm,
            target,
            episodes = shown,
            stills = stills,
            runtimes = runtimes,
            overviews = overviews,
            names = names,
            onNavigate = { detailsTarget = it },
            onDismiss = { detailsTarget = null },
        )
    }
}

/**
 * 换到 [episodeId] 那一集 (视频下面的选集与侧边选集面板共用): 已经在播它时不动;
 * 这一部的分集列表里没有它 (换不过去) 时打开那一集的播放页.
 */
internal fun pickTabletEpisode(vm: EpisodeViewModel, navigator: AniNavigator, episodeId: Int) {
    if (episodeId != vm.episodeSelectorState.current?.episodeId &&
        !vm.episodeSelectorState.selectEpisodeId(episodeId)
    ) {
        navigator.navigateEpisodeDetails(vm.subjectId, episodeId)
    }
}

/** 一集的详情弹窗 (长按集卡片; 视频下面的选集与侧边选集面板共用), 弹窗里可以改这一集的观看状态. */
@Composable
internal fun TabletEpisodeDetailsDialog(
    vm: EpisodeViewModel,
    target: EpisodeListItem,
    episodes: List<EpisodeListItem>,
    stills: Map<Int, String>,
    runtimes: Map<Int, Int>,
    overviews: Map<Int, String>,
    names: Map<Int, String>,
    onNavigate: (EpisodeListItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    TvEpisodeDetailsDialog(
        target,
        episodes = episodes,
        onNavigate = onNavigate,
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
        onDismiss = onDismiss,
    )
}

/**
 * 选集标题行右边那句: 连载状态 + 总集数, 「已完结 · 全 12 话」「连载至 08 · 预定全 12 话」「未开播」(同哔哩哔哩「已完结，全 26 话」).
 * 连载信息同电视详情页 (按 TMDB 修正过, 见 [rememberTmdbAiringInfo]); 文案同上游的 AiringLabel,
 * 但不写观看进度 (上游那句看过一集后前半变成「看过 03」, 连载中就看不到更新到哪集). 加载中为 null.
 */
@Composable
private fun tabletAiringSummary(details: SubjectDetailsState): String? {
    val uiState by details.uiState.collectAsStateWithLifecycle()
    val info: SubjectAiringInfo = details.rememberTmdbAiringInfo(uiState) ?: return null
    val strings = rememberSubjectStatusStrings()
    val status = when (info.kind) {
        SubjectAiringKind.UPCOMING -> strings.upcoming
        SubjectAiringKind.ON_AIR ->
            if (info.latestSort == null) strings.onAir else strings.onAirTo(renderEpAndSort(info.latestEp, info.latestSort))

        SubjectAiringKind.COMPLETED -> strings.completed
    }
    return listOfNotNull(status, renderTotalEpisodeText(info, strings)).joinToString(" · ")
}

/** 视频下面那一排集卡片的大小: 两行字 (集号 + 集名) 加底下的进度条. */
private val TABLET_EPISODE_CHIP_WIDTH = 156.dp
private val TABLET_EPISODE_CHIP_HEIGHT = 68.dp
