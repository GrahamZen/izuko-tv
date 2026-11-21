/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

// 候选列表本身就是要看原始资源 (同控制台的 RemoteCandidates)
@file:OptIn(UnsafeOriginalMediaAccess::class)

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.MediaExclusionReason
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.blocksSelection
import me.him188.ani.app.domain.media.selector.isPerfectMatch
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveRegistry
import me.him188.ani.app.domain.mediasource.clouddrive.DriveVideoBitrates
import me.him188.ani.app.domain.mediasource.clouddrive.cloudDriveLoginRequired
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.foundation.widgets.menuHeightCap
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_details_open_link
import me.him188.ani.app.ui.lang.media_selector_filter_alliance
import me.him188.ani.app.ui.lang.media_selector_filter_resolution
import me.him188.ani.app.ui.lang.media_selector_filter_subtitle
import me.him188.ani.app.ui.lang.media_selector_full_search
import me.him188.ani.app.ui.lang.media_selector_item_cache_not_ready
import me.him188.ani.app.ui.lang.media_selector_item_excluded_alliance
import me.him188.ani.app.ui.lang.media_selector_item_no_subtitle
import me.him188.ani.app.ui.lang.media_selector_item_published_before_airing
import me.him188.ani.app.ui.lang.media_selector_item_season_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_single_episode_resource
import me.him188.ani.app.ui.lang.media_selector_item_subject_title_mismatch
import me.him188.ani.app.ui.lang.media_selector_item_unsupported_playback
import me.him188.ani.app.ui.lang.media_selector_sources
import me.him188.ani.app.ui.lang.media_selector_view_detailed_mode
import me.him188.ani.app.ui.lang.media_selector_view_simple_mode
import me.him188.ani.app.ui.lang.subject_episode_cached
import me.him188.ani.app.ui.lang.subject_episode_select_media_source
import me.him188.ani.app.ui.lang.tv_source_all
import me.him188.ani.app.ui.lang.tablet_source_drive_login_hint
import me.him188.ani.app.ui.lang.tablet_source_resume_full_search
import me.him188.ani.app.ui.lang.tablet_source_failed_sources
import me.him188.ani.app.ui.lang.tablet_source_login_sources
import me.him188.ani.app.ui.lang.tv_source_captcha_action
import me.him188.ani.app.ui.lang.tv_source_captcha_hint
import me.him188.ani.app.ui.lang.tv_source_captcha_required
import me.him188.ani.app.ui.lang.tv_source_drive_login_action
import me.him188.ani.app.ui.lang.tv_source_empty_sources
import me.him188.ani.app.ui.lang.tv_source_failed
import me.him188.ani.app.ui.lang.tv_source_failed_state
import me.him188.ani.app.ui.lang.tv_source_full_search_paused
import me.him188.ani.app.ui.lang.tv_source_group_drive
import me.him188.ani.app.ui.lang.tv_source_group_local
import me.him188.ani.app.ui.lang.tv_source_group_online
import me.him188.ani.app.ui.lang.tv_source_hide_empty_sources
import me.him188.ani.app.ui.lang.tv_source_hide_excluded
import me.him188.ani.app.ui.lang.tv_source_keywords
import me.him188.ani.app.ui.lang.tv_source_kind_bt
import me.him188.ani.app.ui.lang.tv_source_kind_cache
import me.him188.ani.app.ui.lang.tv_source_loading
import me.him188.ani.app.ui.lang.tv_source_menu_cache
import me.him188.ani.app.ui.lang.tv_source_no_result
import me.him188.ani.app.ui.lang.tv_source_paused_hint
import me.him188.ani.app.ui.lang.tv_source_playing
import me.him188.ani.app.ui.lang.tv_source_rate_limited_expired
import me.him188.ani.app.ui.lang.tv_source_reason_episode_unknown
import me.him188.ani.app.ui.lang.tv_source_reason_fuzzy_title
import me.him188.ani.app.ui.lang.tv_source_refresh
import me.him188.ani.app.ui.lang.tv_source_resume_source
import me.him188.ani.app.ui.lang.tv_source_retry
import me.him188.ani.app.ui.lang.tv_source_search_paused
import me.him188.ani.app.ui.lang.tv_source_searched
import me.him188.ani.app.ui.lang.tv_source_searching
import me.him188.ani.app.ui.lang.tv_source_settings
import me.him188.ani.app.ui.lang.tv_source_show_excluded
import me.him188.ani.app.ui.media.rememberMediaDetailsStrings
import me.him188.ani.app.ui.media.renderSubtitleLanguage
import me.him188.ani.app.ui.mediafetch.MediaSourceResultPresentation
import me.him188.ani.app.ui.remote.RemoteCache
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.tv.source.TvDriveLoginDialog
import me.him188.ani.app.ui.subject.episode.tv.source.TvSourceBadgeChips
import me.him188.ani.app.ui.subject.episode.tv.source.TvSourceKeywordsDialog
import me.him188.ani.app.ui.subject.episode.tv.source.tvSourceBadgesOf
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.isLowEffort
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * 数据源 (平板右栏 / 全屏抽屉 / 竖屏面板), 照 Web 控制台播放页的候选列表: 一整条能滑的列表, 不分页签.
 *
 * 从上往下: 栏顶「选择数据源」, 下一行查询进度, 右边数据源设置 (调节图标) 与关闭 → 正在播放 (淡紫卡片, 点一下滑到那一条)
 * → 一排操作 (完整搜索、重新搜索、改搜索词) → 「数据源」与柔和胶囊 (条数 / 还在查 / 失败等状态; 点一个只看它,
 * 它查不了时给重试、扫码登录、验证、继续查) → 分辨率 / 字幕 / 字幕组三格筛选与「显示已排除的」
 * → 候选: 按类型分段 (本地 / 网盘 / 在线 / BT, 各段能收起), 段里每个数据源一张卡片, 每条写画质、字幕、字幕组、大小、码率与
 * HDR / 杜比标签, 被排除的写原因. 点一条就选 (选完关不关看设置「选择数据源后自动关闭」), 长按: 下载这一条、打开它在数据源上的链接.
 * 在线那段的头上有简单 / 详细模式 ([TabletPlayerState.webSourcesDetailed]): 简单模式一个源一行, 对得上的线路是小胶囊 (同上游选源),
 * 对不上的写全名与原因; 详细模式同别的段, 每个源一张卡片.
 * 配色跟主题 (视频外是用户主题, 全屏抽屉里是深色): 浅底与卡片取 surfaceContainer 一档档, 强调用 primary.
 * 各块之间靠留白与段标题分开, 不画分隔线; 栏顶下面的细线只在列表滚上去以后出现 (见 [TabletPanelHeader]).
 * 筛选只管这里显示哪些, 不改选源偏好 (同控制台).
 */
@Composable
internal fun TabletSourcePanel(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    tablet: TabletPlayerState,
    onClose: () -> Unit,
) {
    DisposableEffect(vm) {
        // 打开期间搜索一直查完, 关上时正在播又没开完整搜索就暂停 (同电视的选源面板)
        vm.onMediaSelectorShown()
        onDispose { vm.onMediaSelectorHidden() }
    }
    val selector = page.mediaSelectorState
    val presentation by selector.presentationFlow.collectAsStateWithLifecycle()
    val results = page.mediaSourceResultListPresentation
    val sources = results.list
    val fullSearch by vm.fullMediaSearch.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }
    var resolution by rememberSaveable { mutableStateOf<String?>(null) }
    var subtitle by rememberSaveable { mutableStateOf<String?>(null) }
    var alliance by rememberSaveable { mutableStateOf<String?>(null) }
    val collapsed = remember { mutableStateMapOf<TabletSourceSection, Boolean>() }
    var editingKeywords by remember { mutableStateOf(false) }
    var driveLogin by remember { mutableStateOf<String?>(null) }

    val detailsStrings = rememberMediaDetailsStrings()
    // 正在播的那条可能不在搜索结果里: 播放线索直接向源要了这一集 (没等搜索), 紧接着开播把还在查的源都暂停了 (连它自己的源),
    // 结果里就没有它. 补进它那个源的分组, 点正在播放的卡片也滑得到; 不为它恢复搜索, 播放时不多查
    val all = remember(presentation.filteredCandidates, presentation.selected) {
        val list = presentation.filteredCandidates
        val playing = presentation.selected
        if (playing == null || list.any { it.original.mediaId == playing.mediaId }) list
        else list + MaybeExcludedMedia.Included(playing, PLAYING_OUTSIDE_RESULTS)
    }
    val excludedCount = all.count { it is MaybeExcludedMedia.Excluded }
    val inScope = if (showExcluded) all else all.filter { it is MaybeExcludedMedia.Included }
    val candidates = inScope
        .filter { m ->
            val p = m.original.properties
            (resolution == null || p.resolution == resolution) &&
                (subtitle == null || subtitle in p.subtitleLanguageIds) &&
                (alliance == null || p.alliance == alliance)
        }
        .sortedBy { if (it is MaybeExcludedMedia.Included) 0 else 1 }
    val selected = presentation.selected
    val groups = remember(candidates, sources, sourceFilter) { tabletSourceGroups(candidates, sources, sourceFilter) }
    val selectedSource = selected?.let { s -> sources.firstOrNull { it.mediaSourceId == s.mediaSourceId } }

    val pausedCount = sources.count { it.isPaused }
    val status = when {
        results.anyLoading -> stringResource(Lang.tv_source_searching, results.finishedSourceCount, results.enabledSourceCount)
        pausedCount > 0 -> stringResource(Lang.tv_source_search_paused, results.finishedSourceCount, results.enabledSourceCount)
        else -> stringResource(Lang.tv_source_searched, results.enabledSourceCount)
    }

    val webSimple = !tablet.webSourcesDetailed
    val pick: (MaybeExcludedMedia) -> Unit = {
        selector.select(it.original)
        if (vm.videoScaffoldConfig.hideSelectorOnSelect) onClose()
    }
    // 列表里的各行 (段头、源头、候选); 只看一个源时不分段
    val rows = tabletSourceRows(groups, sectioned = sourceFilter == null, collapsed = collapsed, webSimple = webSimple)
    val itemMenu: TabletSourceItemMenu = { media, expanded, onDismiss -> TabletSourceItemMenu(vm, media, expanded, onDismiss) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val filtered = sourceFilter?.let { id -> sources.firstOrNull { it.mediaSourceId == id } }
    // 列表最前面的几行 (正在播放、操作、数据源、筛选、说明): 跟着候选一起滑 (同控制台整页滑), 胶囊铺开占几行也不挤候选.
    // 最上面一行一直在, 晚到的段插在它下面, 没滑过时照样看得见
    val headerKeys = listOfNotNull(
        "now".takeIf { selected != null },
        "actions",
        "sources",
        "filters",
        when {
            filtered != null -> "state"
            rows.isEmpty() -> "empty"
            else -> null
        },
    )

    Column(Modifier.fillMaxSize()) {
        TabletPanelHeader(
            stringResource(Lang.subject_episode_select_media_source),
            onClose,
            subtitle = status,
            scrolled = listState.canScrollBackward,
            trailing = {
                IconButton(
                    onClick = {
                        onClose()
                        navigator.navigateSettings(SettingsTab.MEDIA_SOURCE)
                    },
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = stringResource(Lang.tv_source_settings))
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        ) {
            if (selected != null) {
                item("now") {
                    TabletSourceNowPlaying(
                        sourceName = selectedSource?.info?.displayName
                            ?: if (selected.kind == MediaSourceKind.LocalCache) stringResource(Lang.tv_source_kind_cache) else null,
                        title = selected.originalTitle,
                        resolution = selected.properties.resolution,
                        onClick = {
                            // 滑到在播的那一条 (它那一段收着就先展开; 只看着别的源就放开)
                            val section = sectionOf(selected)
                            val ready = sourceFilter == null && collapsed[section] != true
                            sourceFilter = null
                            collapsed[section] = false
                            if (ready) {
                                val index = rows.indexOfFirst { it.contains(selected) }
                                if (index >= 0) coroutineScope.launch { listState.animateScrollToItem(headerKeys.size + index) }
                            }
                        },
                    )
                }
            }
            item("actions") {
                TabletSourceActions(
                    fullSearch = fullSearch,
                    pausedCount = pausedCount,
                    onFullSearch = { vm.setFullMediaSearch(fullSearch != true) },
                    onRefresh = { vm.refreshFetch() },
                    onEditKeywords = { if (page.fetchRequest != null) editingKeywords = true },
                    extra = { TabletDriveSourceActions(vm, page) },
                )
            }
            item("sources") {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(Lang.media_selector_sources),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TabletSourceChips(
                        sources = sources.filter { it.kind != MediaSourceKind.LocalCache && !it.isDisabled },
                        selectedId = sourceFilter,
                        playingId = selected?.mediaSourceId,
                        onSelect = { id ->
                            // 没登录网盘的源: 胶囊上写着「扫码登录网盘」, 点它直接弹登录窗 (登录后它重新查), 不切成只看它
                            val driveId = id?.let { i -> sources.firstOrNull { it.mediaSourceId == i } }
                                ?.state?.cloudDriveLoginRequired?.driveId
                            if (driveId != null) driveLogin = driveId else sourceFilter = if (sourceFilter == id) null else id
                        },
                    )
                }
            }
            item("filters") {
                TabletSourceFilters(
                    inScope = inScope.map { it.original },
                    resolution = resolution,
                    subtitle = subtitle,
                    alliance = alliance,
                    onResolution = { resolution = it },
                    onSubtitle = { subtitle = it },
                    onAlliance = { alliance = it },
                    excludedCount = excludedCount,
                    showExcluded = showExcluded,
                    onShowExcluded = { showExcluded = it },
                    subtitleLabel = { renderSubtitleLanguage(it, detailsStrings) },
                )
            }
            if (filtered != null) {
                // 只看一个源、它查不了时: 说一句为什么, 给能做的那一下
                item("state") {
                    TabletSourceStateHint(
                        filtered,
                        hasItems = rows.isNotEmpty(),
                        onRetry = { vm.restartSource(filtered.instanceId) },
                        onCaptcha = { coroutineScope.launch { selector.resolveCaptcha(filtered.instanceId) } },
                        onLogin = { driveLogin = it },
                    )
                }
            } else if (rows.isEmpty()) {
                item("empty") {
                    Text(
                        stringResource(
                            when {
                                results.anyLoading -> Lang.tv_source_loading
                                // 开播后暂停了还没查完的源: 不是没有, 是还没查 (顶上「继续完整搜索」接着查)
                                pausedCount > 0 -> Lang.tv_source_paused_hint
                                else -> Lang.tv_source_no_result
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is TabletSourceListRow.SectionHeader -> {
                        val expanded = collapsed[row.section] != true
                        TabletSourceSectionHeader(
                            row.section,
                            count = row.count,
                            first = row.first,
                            expanded = expanded,
                            onToggle = { collapsed[row.section] = collapsed[row.section] != true },
                            trailing = if (row.section == TabletSourceSection.WEB && expanded) {
                                {
                                    TabletWebModeSwitch(
                                        detailed = tablet.webSourcesDetailed,
                                        onChange = { tablet.webSourcesDetailed = it },
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }

                    is TabletSourceListRow.SourceHeader -> TabletSourceGroupHeader(row.group)
                    is TabletSourceListRow.Item -> TabletSourceItem(
                        row.media,
                        last = row.last,
                        matchNote = if (row.group.section == TabletSourceSection.WEB) tabletMatchNote(row.media) else null,
                        playing = row.media.original == selected,
                        subtitleLabel = { renderSubtitleLanguage(it, detailsStrings) },
                        onPick = { pick(row.media) },
                        itemMenu = itemMenu,
                    )

                    is TabletSourceListRow.WebLines -> TabletWebSourceLines(
                        row,
                        selected = selected,
                        subtitleLabel = { renderSubtitleLanguage(it, detailsStrings) },
                        onPick = { pick(it) },
                        itemMenu = itemMenu,
                    )
                }
            }
        }
    }

    if (editingKeywords) {
        val request = page.fetchRequest
        if (request == null) {
            editingKeywords = false
        } else {
            TvSourceKeywordsDialog(
                request = request,
                default = page.defaultFetchRequest,
                onSubmit = { vm.updateFetchRequest(it) },
                onDismiss = { editingKeywords = false },
            )
        }
    }
    driveLogin?.let { driveId ->
        TvDriveLoginDialog(
            driveId,
            onLoggedIn = {
                // 登录一个网盘, 它的网盘源与分享搜索源都能查了
                sources.filter { it.state.cloudDriveLoginRequired?.driveId == driveId }.forEach { vm.restartSource(it.instanceId) }
            },
            onDismiss = { driveLogin = null },
        )
    }
}

/** 列表里的各行 (段头、源头、候选): [sectioned] = 分段 (只看一个源时不分段); 收起的段只留段头. */
internal fun tabletSourceRows(
    groups: List<TabletSourceGroup>,
    sectioned: Boolean,
    collapsed: Map<TabletSourceSection, Boolean>,
    webSimple: Boolean,
): List<TabletSourceListRow> = buildList {
    for (section in TabletSourceSection.entries) {
        val inSection = groups.filter { it.section == section }
        if (inSection.isEmpty()) continue
        if (sectioned) add(TabletSourceListRow.SectionHeader(section, inSection.sumOf { it.items.size }, first = isEmpty()))
        if (sectioned && collapsed[section] == true) continue
        for (group in inSection) {
            if (webSimple && section == TabletSourceSection.WEB) {
                // 在线源简单模式: 一个源一行, 对得上的线路是小胶囊, 名字或集数对不上的写全名列在下面 (同上游选源的在线源)
                val (lines, others) = group.items.partition { tabletLineName(it, group) != null }
                add(TabletSourceListRow.WebLines(group, lines, others))
                continue
            }
            add(TabletSourceListRow.SourceHeader(group))
            group.items.forEachIndexed { index, media ->
                add(TabletSourceListRow.Item(group, media, last = index == group.items.lastIndex))
            }
        }
    }
}

/** 候选按类型分的段 (同控制台与电视选源面板): 本机 / 局域网上的文件与本地缓存在「本地」, 网盘单独一段, 都在在线源前面, BT 最后. */
internal enum class TabletSourceSection { LOCAL, DRIVE, WEB, BT }

/** 一个数据源的候选 (列表里的一组). */
internal class TabletSourceGroup(
    val sourceId: String,
    val source: MediaSourceResultPresentation?,
    val cache: Boolean,
    val section: TabletSourceSection,
    val items: List<MaybeExcludedMedia>,
)

internal sealed interface TabletSourceListRow {
    val key: String

    /** 这一行里有 [media] (正在播放卡片点一下滑到它). */
    fun contains(media: Media?): Boolean = when (this) {
        is Item -> this.media.original == media
        is WebLines -> (lines + others).any { it.original == media }
        else -> false
    }

    /** [first] = 列表里第一段 (上面已有筛选那一条分隔线, 不再画). */
    class SectionHeader(val section: TabletSourceSection, val count: Int, val first: Boolean) : TabletSourceListRow {
        override val key: String get() = "section:" + section.name
    }

    class SourceHeader(val group: TabletSourceGroup) : TabletSourceListRow {
        override val key: String get() = "source:" + group.sourceId
    }

    /** [last] = 这一组的最后一条 (卡片在它底下收口). */
    class Item(val group: TabletSourceGroup, val media: MaybeExcludedMedia, val last: Boolean) : TabletSourceListRow {
        override val key: String get() = "item:" + group.sourceId + ":" + media.original.mediaId
    }

    /** 在线源简单模式下一个源一行: [lines] 写成线路名小胶囊 (见 [tabletLineName]), [others] 名字或集数对不上, 写全名列在下面. */
    class WebLines(val group: TabletSourceGroup, val lines: List<MaybeExcludedMedia>, val others: List<MaybeExcludedMedia>) : TabletSourceListRow {
        override val key: String get() = "lines:" + group.sourceId
    }
}

/**
 * 按数据源分组: 本地缓存在最前, 其余按数据源设置里的顺序 ([sources]), 不在里面的排最后; [only] 非 null 时只留那一个源.
 */
/** 补进列表的正在播放那条 (见面板里 `all`) 的匹配情况: 它是按这部这一集选定的, 当精确匹配. */
private val PLAYING_OUTSIDE_RESULTS = MatchMetadata(MatchMetadata.SubjectMatchKind.EXACT, MatchMetadata.EpisodeMatchKind.EP, 100)

internal fun tabletSourceGroups(
    candidates: List<MaybeExcludedMedia>,
    sources: List<MediaSourceResultPresentation>,
    only: String?,
): List<TabletSourceGroup> {
    val byId = candidates.groupBy { it.original.mediaSourceId }
    val order = buildList {
        addAll(byId.keys.filter { id -> byId.getValue(id).first().original.kind == MediaSourceKind.LocalCache })
        addAll(sources.map { it.mediaSourceId }.filter { it in byId && it !in this })
        addAll(byId.keys.filter { it !in this })
    }
    return order.filter { only == null || it == only }.map { id ->
        val items = byId.getValue(id)
        TabletSourceGroup(
            sourceId = id,
            source = sources.firstOrNull { it.mediaSourceId == id },
            cache = items.first().original.kind == MediaSourceKind.LocalCache,
            section = sectionOf(items.first().original),
            items = items,
        )
    }
}

internal fun sectionOf(media: Media): TabletSourceSection = when {
    media.kind == MediaSourceKind.LocalCache -> TabletSourceSection.LOCAL
    media.kind == MediaSourceKind.BitTorrent -> TabletSourceSection.BT
    media.location.isLowEffort() -> TabletSourceSection.LOCAL
    isCloudDriveMedia(media) -> TabletSourceSection.DRIVE
    else -> TabletSourceSection.WEB
}

/** 网盘里的文件 (播放地址是网盘的占位地址, 见 CloudDriveRegistry.driveOf). */
private fun isCloudDriveMedia(media: Media): Boolean =
    runCatching { KoinPlatform.getKoin().get<CloudDriveRegistry>() }.getOrNull()?.driveOf(media.download.uri) != null

/** 正在播放: 淡紫卡片, 上一行「正在播放 · 源」, 下一行资源标题, 右边分辨率小标. 点一下滑到列表里那一条. */
@Composable
private fun TabletSourceNowPlaying(sourceName: String?, title: String, resolution: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = tabletAccent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, Modifier.size(20.dp), tint = tabletAccent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOfNotNull(stringResource(Lang.tv_source_playing), sourceName).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = tabletAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (resolution.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    resolution,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tabletAccent,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tabletAccent.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * 操作: 完整搜索 (浅底按钮; 有源暂停着时写「继续完整搜索」, 开着时淡紫) + 重新搜索、修改搜索词 (只有图标与字).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabletSourceActions(
    fullSearch: Boolean?,
    pausedCount: Int,
    onFullSearch: () -> Unit,
    onRefresh: () -> Unit,
    onEditKeywords: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (fullSearch != null) {
            val resume = !fullSearch && pausedCount > 0
            val tint = if (fullSearch) tabletAccent else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(tabletSoftFill)
                    .selectable(selected = fullSearch, onClick = onFullSearch, role = Role.Checkbox)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    when {
                        fullSearch -> Icons.Rounded.Check
                        resume -> Icons.Rounded.PlayArrow
                        else -> Icons.Rounded.TravelExplore
                    },
                    contentDescription = null,
                    Modifier.size(18.dp),
                    tint = tint,
                )
                Text(
                    stringResource(if (resume) Lang.tablet_source_resume_full_search else Lang.media_selector_full_search),
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
        }
        TabletSourceTextAction(Icons.Rounded.Refresh, stringResource(Lang.tv_source_refresh), onRefresh)
        TabletSourceTextAction(Icons.Rounded.Edit, stringResource(Lang.tv_source_keywords), onEditKeywords)
        extra()
    }
}

/** 只有图标与字的操作 (没有底, 按下才有水波). */
@Composable
internal fun TabletSourceTextAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 数据源胶囊, 铺开折行 (同控制台): 「全部」+ 各个源 (条数, 或者还在查 / 暂停了 / 失败 / 要验证 / 要登录). 点一个只看它, 再点放开.
 * 查完没结果的源 (正在播的、正看着的除外) 默认收成最后一颗「没有结果的 N 个」, 点了展开.
 * 柔和胶囊: 平时浅底无边框; 正看着的那颗淡紫底描边, 正在播的淡紫字 + 音波图标; 查不了的状态写红字, 还在查的淡一档.
 *
 * [grouped] (下载页, 源多的时候): 有结果的按条数从多到少排; 要扫码登录的、查不了的 (失败 / 验证 / 限流) 也各收成一颗, 点了展开.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TabletSourceChips(
    sources: List<MediaSourceResultPresentation>,
    selectedId: String?,
    playingId: String?,
    onSelect: (String?) -> Unit,
    grouped: Boolean = false,
) {
    var showEmpty by rememberSaveable { mutableStateOf(false) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var showFailed by rememberSaveable { mutableStateOf(false) }
    val pinned = { source: MediaSourceResultPresentation -> source.mediaSourceId == playingId || source.mediaSourceId == selectedId }
    val login = if (grouped) sources.filter { it.state.cloudDriveLoginRequired != null && !pinned(it) } else emptyList()
    val failed = if (grouped) {
        sources.filter { (it.isFailedOrAbandoned || it.isCaptchaRequired || it.isRateLimited) && it !in login && !pinned(it) }
    } else {
        emptyList()
    }
    val empty = sources.filter { source ->
        !source.isWorking && !source.isFailedOrAbandoned && !source.isCaptchaRequired && !source.isRateLimited &&
            source.totalCount == 0 && !pinned(source) && source !in login
    }
    // 稳定排序: 条数一样的照数据源设置里的顺序
    val ordered = if (grouped) sources.sortedByDescending { it.totalCount } else sources
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabletSoftChip(
            label = stringResource(Lang.tv_source_all),
            selected = selectedId == null,
            onClick = { onSelect(null) },
            leading = { tint -> Icon(Icons.Rounded.Layers, contentDescription = null, Modifier.size(18.dp), tint = tint) },
        )
        for (source in ordered) {
            if (!showEmpty && source in empty) continue
            if (!showLogin && source in login) continue
            if (!showFailed && source in failed) continue
            val playing = source.mediaSourceId == playingId
            TabletSoftChip(
                label = source.info.displayName,
                selected = source.mediaSourceId == selectedId,
                playing = playing,
                onClick = { onSelect(source.mediaSourceId) },
                leading = { tint ->
                    if (playing) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, Modifier.size(18.dp), tint = tint)
                    } else {
                        TabletSourceIcon(source, Modifier.size(18.dp))
                    }
                },
                trailing = { TabletSourceStateLabel(source) },
                modifier = if (source.isWorking) Modifier.alpha(0.6f) else Modifier,
            )
        }
        if (login.isNotEmpty()) TabletGroupChip(stringResource(Lang.tablet_source_login_sources, login.size), showLogin) { showLogin = !showLogin }
        if (failed.isNotEmpty()) TabletGroupChip(stringResource(Lang.tablet_source_failed_sources, failed.size), showFailed) { showFailed = !showFailed }
        if (empty.isNotEmpty()) {
            TabletSoftChip(
                label = stringResource(if (showEmpty) Lang.tv_source_hide_empty_sources else Lang.tv_source_empty_sources, empty.size),
                selected = false,
                dim = true,
                onClick = { showEmpty = !showEmpty },
                trailing = {
                    Icon(
                        if (showEmpty) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/** 收着一类源的那颗 (要登录的、查不了的): 字淡一档, 后面 ⌄ / ⌃. */
@Composable
private fun TabletGroupChip(label: String, expanded: Boolean, onClick: () -> Unit) {
    TabletSoftChip(
        label = label,
        selected = false,
        dim = true,
        onClick = onClick,
        trailing = {
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** 一颗柔和胶囊: 圆头、浅底、无边框; [selected] / [playing] 只把字与图标换成强调色 (不上底色、不描边), [dim] 字淡一档. */
@Composable
internal fun TabletSoftChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    dim: Boolean = false,
    leading: (@Composable (tint: Color) -> Unit)? = null,
    /** 名字后面那一格; 文字要跟名字按基线对齐时给它 `Modifier.alignByBaseline()` (小号字按行框居中会比名字低一截). */
    trailing: (@Composable RowScope.() -> Unit)? = null,
    height: Dp = 40.dp,
) {
    val accent = selected || playing
    val tint = when {
        accent -> tabletAccent
        dim -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            // 底色都一样, 选中与正在播只换字色与图标 (整块上色的只留顶上那张「正在播放」卡片, 重点不散)
            .background(tabletSoftFill)
            .selectable(selected = selected, onClick = onClick, role = Role.Checkbox)
            .padding(start = if (leading != null) 12.dp else 16.dp, end = if (trailing != null) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke(tint)
        // 名字与后面那一格在里面一行: 按基线对齐的那几个只在这一行里对齐, 这一行整体在胶囊里居中
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 1, modifier = Modifier.alignByBaseline())
            trailing?.invoke(this)
        }
    }
}

/**
 * 源后面那一小格: 还在查转圈、「已暂停」、失败 / 验证 / 登录 / 限流的红字, 否则条数 (小方块).
 * 字跟名字按基线对齐 ([RowScope.alignByBaseline]), 转圈与小方块居中.
 */
@Composable
private fun RowScope.TabletSourceStateLabel(source: MediaSourceResultPresentation) {
    val error = MaterialTheme.colorScheme.error
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.labelSmall
    val text = Modifier.alignByBaseline()
    when {
        source.isWorking -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        source.isPaused -> Text(stringResource(Lang.tv_source_full_search_paused), text, style = small, color = dim)
        source.isCaptchaRequired -> Text(stringResource(Lang.tv_source_captcha_required), text, style = small, color = error)
        source.isRateLimited -> Text(stringResource(Lang.tv_source_rate_limited_expired), text, style = small, color = error)
        source.state.cloudDriveLoginRequired != null -> Text(stringResource(Lang.tv_source_drive_login_action), text, style = small, color = error)
        source.isFailedOrAbandoned -> Text(stringResource(Lang.tv_source_failed_state), text, style = small, color = error)
        else -> TabletCountBadge(source.totalCount)
    }
}

/** 条数小方块 (胶囊与段头后面). */
@Composable
internal fun TabletCountBadge(count: Int) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
internal fun TabletSourceIcon(source: MediaSourceResultPresentation?, modifier: Modifier) {
    val url = source?.info?.iconUrl
    if (url != null) {
        AsyncImage(url, contentDescription = null, modifier.clip(RoundedCornerShape(3.dp)))
    } else {
        Box(modifier)
    }
}

/** 只看一个源、它查不了 (失败、要验证、要登录、暂停了) 或没结果时: 一句为什么 + 能做的那一下. */
@Composable
internal fun TabletSourceStateHint(
    source: MediaSourceResultPresentation,
    hasItems: Boolean,
    onRetry: () -> Unit,
    onCaptcha: () -> Unit,
    onLogin: (driveId: String) -> Unit,
) {
    val login = source.state.cloudDriveLoginRequired
    val text: String
    var action: Pair<String, () -> Unit>? = null
    when {
        source.isWorking -> {
            if (hasItems) return
            text = stringResource(Lang.tv_source_loading)
        }

        source.isPaused -> {
            text = stringResource(Lang.tv_source_paused_hint)
            action = stringResource(Lang.tv_source_resume_source) to onRetry
        }

        source.isCaptchaRequired -> {
            text = stringResource(Lang.tv_source_captcha_hint)
            action = stringResource(Lang.tv_source_captcha_action) to onCaptcha
        }

        login != null -> {
            text = stringResource(Lang.tablet_source_drive_login_hint)
            val driveId = login.driveId
            if (driveId != null) action = stringResource(Lang.tv_source_drive_login_action) to { onLogin(driveId) }
        }

        source.isRateLimited || source.isFailedOrAbandoned -> {
            text = stringResource(if (source.isRateLimited) Lang.tv_source_rate_limited_expired else Lang.tv_source_failed)
            action = stringResource(Lang.tv_source_retry) to onRetry
        }

        hasItems -> return
        else -> text = stringResource(Lang.tv_source_no_result)
    }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, onClick) -> FilledTonalButton(onClick = onClick) { Text(label) } }
    }
}

/**
 * 分辨率 / 字幕 / 字幕组三个等宽下拉 (选项与条数按眼下列着的算) + 「显示已排除的 N 条」, 上下各一条分隔线.
 * 右栏窄 (宽不到 [TABLET_FILTERS_ONE_LINE_MIN_WIDTH], 如 4:3 的 iPad) 时「显示已排除」挪到下一行靠右, 三个下拉占满一行, 维度名不被截断.
 */
@Composable
internal fun TabletSourceFilters(
    inScope: List<Media>,
    resolution: String?,
    subtitle: String?,
    alliance: String?,
    onResolution: (String?) -> Unit,
    onSubtitle: (String?) -> Unit,
    onAlliance: (String?) -> Unit,
    excludedCount: Int,
    showExcluded: Boolean,
    onShowExcluded: (Boolean) -> Unit,
    subtitleLabel: (String) -> String,
) {
    val resolutions = filterOptions(inScope.map { it.properties.resolution } + listOfNotNull(resolution), rank = ::resolutionRank)
    val subtitles = filterOptions(inScope.flatMap { it.properties.subtitleLanguageIds } + listOfNotNull(subtitle))
    val alliances = filterOptions(inScope.map { it.properties.alliance } + listOfNotNull(alliance))
    val excludedToggle: @Composable () -> Unit = {
        Text(
            stringResource(if (showExcluded) Lang.tv_source_hide_excluded else Lang.tv_source_show_excluded, excludedCount),
            style = MaterialTheme.typography.labelLarge,
            color = if (showExcluded) tabletAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .selectable(selected = showExcluded, onClick = { onShowExcluded(!showExcluded) }, role = Role.Checkbox)
                .padding(horizontal = 6.dp, vertical = 10.dp),
        )
    }
    Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val oneLine = maxWidth >= TABLET_FILTERS_ONE_LINE_MIN_WIDTH
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val box = Modifier.weight(1f)
                    TabletFilterDropdown(stringResource(Lang.media_selector_filter_resolution), resolution, resolutions, { it }, onResolution, box)
                    TabletFilterDropdown(stringResource(Lang.media_selector_filter_subtitle), subtitle, subtitles, subtitleLabel, onSubtitle, box)
                    TabletFilterDropdown(stringResource(Lang.media_selector_filter_alliance), alliance, alliances, { it }, onAlliance, box)
                    if (oneLine && excludedCount > 0) excludedToggle()
                }
                if (!oneLine && excludedCount > 0) {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) {
                        excludedToggle()
                    }
                }
            }
        }
    }
}

/** 筛选那一排连「显示已排除」放成一行要的宽度: 三个下拉写得下维度名 (约 96dp 一个), 再加「显示已排除的 N 条」. */
private val TABLET_FILTERS_ONE_LINE_MIN_WIDTH = 480.dp

/** 一个筛选下拉: 浅底圆角格 (不描边) 写着当前选的 (没选写维度名, 选了字换强调色), 右边 ⌄; 点开列「全部」与各个取值 (带条数). */
@Composable
private fun TabletFilterDropdown(
    title: String,
    value: String?,
    options: List<Pair<String, Int>>,
    label: (String) -> String,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = options.isNotEmpty() || value != null
    val shape = RoundedCornerShape(10.dp)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).clip(shape)
                .background(glassContainerColor())
                .clickable(enabled = enabled) { expanded = true }
                .padding(start = 12.dp, end = 6.dp)
                .alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value?.let(label) ?: title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (value != null) tabletAccent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.menuHeightCap()) {
            DropdownMenuItem(
                text = { Text(stringResource(Lang.tv_source_all)) },
                onClick = {
                    expanded = false
                    onPick(null)
                },
            )
            for ((option, count) in options) {
                DropdownMenuItem(
                    text = { Text(label(option) + "  " + count) },
                    onClick = {
                        expanded = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/** 取值与条数, 空值不列; [rank] 给了按它从高到低排, 否则按条数. */
private fun filterOptions(values: List<String>, rank: ((String) -> Int)? = null): List<Pair<String, Int>> {
    val counts = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
    val ordered = if (rank != null) {
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { rank(it.key) }.thenByDescending { it.value })
    } else {
        counts.entries.sortedByDescending { it.value }
    }
    return ordered.map { it.key to it.value }
}

/** 分辨率按清晰度排: 4K / 2160 最高, 其余取数字部分 (1080P → 1080). */
private fun resolutionRank(value: String): Int {
    val v = value.uppercase()
    if ("4K" in v || "2160" in v) return 2160
    return v.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
}

/**
 * 一段的头: 类型图标 + 名字 + 条数小方块 + [trailing] (在线那段展开时是简单 / 详细模式) + 收起 / 展开;
 * 段与段之间留一段空白、不画线 (同 iOS 的分组列表), [first] 那段紧接着筛选.
 */
@Composable
internal fun TabletSourceSectionHeader(
    section: TabletSourceSection,
    count: Int,
    first: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        if (!first) Spacer(Modifier.height(TABLET_SECTION_GAP))
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (section) {
                    TabletSourceSection.LOCAL -> Icons.Outlined.Folder
                    TabletSourceSection.DRIVE -> Icons.Outlined.Cloud
                    TabletSourceSection.WEB -> Icons.Outlined.PlayCircle
                    TabletSourceSection.BT -> Icons.Rounded.Download
                },
                contentDescription = null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(
                    when (section) {
                        TabletSourceSection.LOCAL -> Lang.tv_source_group_local
                        TabletSourceSection.DRIVE -> Lang.tv_source_group_drive
                        TabletSourceSection.WEB -> Lang.tv_source_group_online
                        TabletSourceSection.BT -> Lang.tv_source_kind_bt
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            TabletCountBadge(count)
            Spacer(Modifier.weight(1f))
            trailing?.let {
                it()
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 在线源的简单 / 详细模式: 两格的小开关, 选中那格字换强调色 (不上底色、不描边). */
@Composable
internal fun TabletWebModeSwitch(detailed: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(CircleShape).background(tabletSoftFill).padding(2.dp)) {
        for ((value, label) in listOf(false to Lang.media_selector_view_simple_mode, true to Lang.media_selector_view_detailed_mode)) {
            val selected = detailed == value
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) tabletAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape)
                    .selectable(selected = selected, onClick = { onChange(value) }, role = Role.RadioButton)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 在线源简单模式下一个源一行 (同上游选源的在线源): 左边源图标与名字, 右边对得上的线路一条一颗小胶囊 (正在播的那颗字与图标是强调色),
 * 点了选; 名字或集数对不上的 ([TabletSourceListRow.WebLines.others]) 写全名与原因, 不带卡片列在下面. 没有卡片底.
 *
 * @param othersTrailing 下面那几条右边的一格 (下载页的下载键), 见 [TabletSourceItem] 的 `trailing`
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TabletWebSourceLines(
    row: TabletSourceListRow.WebLines,
    selected: Media?,
    subtitleLabel: (String) -> String,
    onPick: (MaybeExcludedMedia) -> Unit,
    itemMenu: TabletSourceItemMenu,
    othersTrailing: (@Composable (MaybeExcludedMedia) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.heightIn(min = 36.dp).widthIn(max = 128.dp), verticalAlignment = Alignment.CenterVertically) {
                TabletSourceIcon(row.group.source, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    row.group.source?.info?.displayName ?: row.group.sourceId,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in row.lines) {
                    val playing = item.original == selected
                    TabletSoftChip(
                        label = tabletLineName(item, row.group).orEmpty(),
                        selected = playing,
                        onClick = { onPick(item) },
                        leading = if (playing) {
                            { tint -> Icon(Icons.Rounded.GraphicEq, contentDescription = null, Modifier.size(16.dp), tint = tint) }
                        } else {
                            null
                        },
                        height = 36.dp,
                    )
                }
            }
        }
        for (other in row.others) {
            TabletSourceItem(
                other,
                last = false,
                matchNote = tabletMatchNote(other),
                playing = other.original == selected,
                subtitleLabel = subtitleLabel,
                onPick = { onPick(other) },
                itemMenu = itemMenu,
                inCard = false,
                trailing = othersTrailing?.let { { it(other) } },
            )
        }
    }
}

/**
 * 在线源简单模式下这一条的线路名 (做成小胶囊): 条目名与集数都对得上 (选择器的 isPerfectMatch) 且线路名是真的线路 (不空、不是数据源名;
 * 网盘一类一个文件一行的源这一栏写的是数据源名) 时给, 否则 null (照写资源全名). 同电视选源面板.
 */
internal fun tabletLineName(item: MaybeExcludedMedia, group: TabletSourceGroup): String? {
    val line = item.original.properties.alliance
    return line.takeIf { item.isPerfectMatch() && it.isNotBlank() && it != group.source?.info?.displayName }
}

/** 在线源里名字或集数没对上的那条写的原因 (被排除的另写排除原因, 这里不管), 同电视选源面板. */
@Composable
internal fun tabletMatchNote(item: MaybeExcludedMedia): String? {
    val metadata = (item as? MaybeExcludedMedia.Included)?.metadata ?: return null
    val fuzzy = stringResource(Lang.tv_source_reason_fuzzy_title)
    val episodeUnknown = stringResource(Lang.tv_source_reason_episode_unknown)
    return buildList {
        if (metadata.subjectMatchKind != MatchMetadata.SubjectMatchKind.EXACT) add(fuzzy)
        if (metadata.episodeMatchKind < MatchMetadata.EpisodeMatchKind.EP) add(episodeUnknown)
    }.joinToString("、").ifEmpty { null }
}

/** 一个源的候选是一张卡片: 这里是卡片顶 (源图标 + 名字 + 条数), 下面每条候选接着画卡片身, 最后一条收口. */
@Composable
internal fun TabletSourceGroupHeader(group: TabletSourceGroup) {
    Row(
        Modifier.fillMaxWidth()
            .background(tabletCardFill, RoundedCornerShape(topStart = TABLET_CARD_RADIUS, topEnd = TABLET_CARD_RADIUS))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabletSourceIcon(group.source, Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (group.cache) stringResource(Lang.tv_source_kind_cache) else group.source?.info?.displayName ?: group.sourceId,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(10.dp))
        Text(group.items.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 一条候选 (源卡片里的一行): 标题 + 一行信息 (已缓存 · 分辨率 · 字幕 · 字幕组 · 大小 · 码率) + 特性标签; 被排除的写原因
 * (缓存没下完的不能选), 名字或集数没对上的写 [matchNote], 正在播的标题与「正在播放」淡紫 (不另上底色). [last] 是这张卡片的最后一条, 底下收口留空.
 * 点了选, 长按开 [itemMenu] (播放页: 下载这一条、打开链接).
 *
 * @param inCard 画在源卡片里 (卡片底与收口); false = 不要卡片 (在线源简单模式下线路胶囊底下那几条).
 * @param trailing 这一条右边的一格 (下载页的下载键), 竖着居中; 不能选的那条不画.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabletSourceItem(
    item: MaybeExcludedMedia,
    last: Boolean,
    matchNote: String?,
    playing: Boolean,
    subtitleLabel: (String) -> String,
    onPick: () -> Unit,
    itemMenu: TabletSourceItemMenu,
    inCard: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val media = item.original
    val reason = item.exclusionReason
    val blocked = reason?.blocksSelection == true
    var menu by remember { mutableStateOf(false) }
    val props = media.properties
    val cachedText = stringResource(Lang.subject_episode_cached)
    val meta = listOfNotNull(
        cachedText.takeIf { media.kind == MediaSourceKind.LocalCache },
        props.resolution,
        props.subtitleLanguageIds.map(subtitleLabel).distinct().joinToString(" / "),
        props.alliance,
        props.size.takeIf { it.inBytes > 0 }?.toString(),
        DriveVideoBitrates.label(media.mediaId),
    ).filter { it.isNotBlank() }.distinct().joinToString("  ·  ")
    val badges = remember(media) { tvSourceBadgesOf(media) }
    val cardShape = if (last) {
        RoundedCornerShape(bottomStart = TABLET_CARD_RADIUS, bottomEnd = TABLET_CARD_RADIUS)
    } else {
        RoundedCornerShape(0.dp)
    }
    val card = if (inCard) {
        Modifier.background(tabletCardFill, cardShape).padding(horizontal = 6.dp).padding(bottom = if (last) 6.dp else 0.dp)
    } else {
        Modifier
    }
    Column(Modifier.fillMaxWidth().padding(bottom = if (last && inCard) 10.dp else 0.dp)) {
        Box(Modifier.fillMaxWidth().then(card)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .combinedClickable(enabled = !blocked, onClick = onPick, onLongClick = { menu = true })
                    .padding(horizontal = if (inCard) 10.dp else 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Column(
                Modifier.weight(1f).alpha(if (reason != null) 0.55f else 1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    media.originalTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (playing) tabletAccent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (badges.isNotEmpty()) TvSourceBadgeChips(badges)
                    if (playing) Text(stringResource(Lang.tv_source_playing), style = MaterialTheme.typography.labelSmall, color = tabletAccent)
                }
                if (reason != null) {
                    Text(exclusionText(reason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else if (matchNote != null) {
                    Text(matchNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!blocked) trailing?.invoke()
            }
            itemMenu(media, menu) { menu = false }
        }
    }
}

/** 浅底 (柔和胶囊、完整搜索按钮): 半透明的玻璃色, 透出整页的模糊底 (同平板详情页的胶囊). */
private val tabletSoftFill: Color
    @Composable
    get() = glassContainerColor()

/** 源卡片的底: 同 [tabletSoftFill]. */
private val tabletCardFill: Color
    @Composable
    get() = glassContainerColor()

private val TABLET_CARD_RADIUS = 16.dp

/** 段与段之间的空白 */
private val TABLET_SECTION_GAP = 16.dp

/** 一条候选长按出的菜单: (这一条, 开着没有, 关掉). */
internal typealias TabletSourceItemMenu = @Composable (media: Media, expanded: Boolean, onDismiss: () -> Unit) -> Unit

/** 长按一条: 下载这一条 (同控制台的「缓存」, 本地缓存那一组不给)、打开它在数据源上的链接 (只认 http(s)). */
@Composable
private fun TabletSourceItemMenu(vm: EpisodeViewModel, media: Media, expanded: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val uriHandler = LocalUriHandler.current
    val link = media.originalUrl.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.menuHeightCap()) {
        if (media.kind != MediaSourceKind.LocalCache) {
            DropdownMenuItem(
                text = { Text(stringResource(Lang.tv_source_menu_cache)) },
                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                onClick = {
                    onDismiss()
                    val episodeId = vm.episodeSelectorState.current?.episodeId ?: return@DropdownMenuItem
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { RemoteCache.cacheMedia(vm.subjectId, episodeId, media) }
                        result["message"]?.jsonPrimitive?.content?.let { toaster.toast(it) }
                    }
                },
            )
        }
        if (link != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Lang.cache_details_open_link)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                onClick = {
                    onDismiss()
                    uriHandler.openUri(link)
                },
            )
        }
    }
}

/** 排除原因的文字, 同电视选源面板与控制台. */
@Composable
private fun exclusionText(reason: MediaExclusionReason): String = when (reason) {
    is MediaExclusionReason.ExcludedAlliance -> stringResource(Lang.media_selector_item_excluded_alliance)
    is MediaExclusionReason.MediaWithoutSubtitle -> stringResource(Lang.media_selector_item_no_subtitle)
    is MediaExclusionReason.SingleEpisodeForCompleteSubject -> stringResource(Lang.media_selector_item_single_episode_resource)
    is MediaExclusionReason.UnsupportedByPlatformPlayer -> stringResource(Lang.media_selector_item_unsupported_playback)
    is MediaExclusionReason.FromSequelSeason, is MediaExclusionReason.FromSeriesSeason ->
        stringResource(Lang.media_selector_item_season_mismatch)

    is MediaExclusionReason.SubjectNameMismatch -> stringResource(Lang.media_selector_item_subject_title_mismatch)
    is MediaExclusionReason.CacheNotReady -> stringResource(Lang.media_selector_item_cache_not_ready)
    is MediaExclusionReason.PublishedBeforeAiring -> stringResource(Lang.media_selector_item_published_before_airing)
    else -> reason.toString()
}
