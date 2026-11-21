/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

// 候选列表本身就是要看原始资源 (同播放页的选择数据源)
@file:OptIn(UnsafeOriginalMediaAccess::class)

package me.him188.ani.app.ui.subject.episode.tv.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.media.download.DownloadEpisodeOption
import me.him188.ani.app.domain.media.fetch.restart
import me.him188.ani.app.domain.media.fetch.restartSources
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.mediasource.clouddrive.cloudDriveLoginRequired
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.download.subject.DownloadEpisodePickerState
import me.him188.ani.app.ui.download.subject.TvDownloadMediaPickerArgs
import me.him188.ani.app.ui.foundation.dialogs.PlatformDialogProperties
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.theme.glassContainerColor
import me.him188.ani.app.ui.foundation.widgets.menuHeightCap
import me.him188.ani.app.ui.foundation.widgets.windowSizeCap
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_details_open_link
import me.him188.ani.app.ui.lang.cache_management_episode_label
import me.him188.ani.app.ui.lang.cache_subject_cache
import me.him188.ani.app.ui.lang.downloads_episode_picker_all
import me.him188.ani.app.ui.lang.downloads_episode_picker_back
import me.him188.ani.app.ui.lang.downloads_episode_picker_current
import me.him188.ani.app.ui.lang.downloads_episode_picker_downloaded
import me.him188.ani.app.ui.lang.downloads_episode_picker_from_current
import me.him188.ani.app.ui.lang.downloads_episode_picker_line
import me.him188.ani.app.ui.lang.downloads_episode_picker_only_current
import me.him188.ani.app.ui.lang.downloads_episode_picker_selected_count
import me.him188.ani.app.ui.lang.downloads_episode_picker_title
import me.him188.ani.app.ui.lang.downloads_episode_picker_unmatched
import me.him188.ani.app.ui.lang.media_selector_sources
import me.him188.ani.app.ui.lang.tablet_download_title
import me.him188.ani.app.ui.lang.tv_source_keywords
import me.him188.ani.app.ui.lang.tv_source_loading
import me.him188.ani.app.ui.lang.tv_source_manual
import me.him188.ani.app.ui.lang.tv_source_no_result
import me.him188.ani.app.ui.lang.tv_source_refresh
import me.him188.ani.app.ui.lang.tv_source_search_paused
import me.him188.ani.app.ui.lang.tv_source_searched
import me.him188.ani.app.ui.lang.tv_source_searching
import me.him188.ani.app.ui.lang.tv_source_settings
import me.him188.ani.app.ui.media.rememberMediaDetailsStrings
import me.him188.ani.app.ui.media.renderSubtitleLanguage
import me.him188.ani.app.ui.mediafetch.MediaSourceResultListPresentation
import me.him188.ani.app.ui.mediaselect.manual.ManualBrowsePage
import me.him188.ani.app.ui.subject.details.layout.TvDetailsTheme
import me.him188.ani.app.ui.subject.episode.tv.source.TvDriveLoginDialog
import me.him188.ani.app.ui.subject.episode.tv.source.TvSourceKeywordsDialog
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import org.jetbrains.compose.resources.stringResource

/**
 * 缓存页点某一集「下载」之后的选源 (平板模式与 iPad, 见 TvDownloadMediaPickerVariant): 铺满屏幕的一页, 内容同播放页的「选择数据源」
 * ([TabletSourcePanel] 那一套胶囊、筛选、分段与候选行), 标题写「下载第 N 话」.
 *
 * 从上往下: 顶栏 (返回、标题与查询进度; 宽屏时重新搜索 / 改搜索词 / 手动查找也在顶栏, 窄了挪到列表第一行) → 数据源胶囊 (按条数排, 要登录的、
 * 查不了的、没结果的各收成一颗, 见 [TabletSourceChips] 的 grouped) → 筛选 → 按类型分段的候选, 每条右边一颗下载键.
 * 胶囊、筛选与候选在同一个列表里一起滚, 源多了胶囊占几行也不挤候选.
 * 点一条 (或它的下载键) = 选它 ([TvDownloadMediaPickerArgs.onSelect]): 只有这一集的资源直接开始下载, 覆盖别的集的 (合集、在线线路)
 * 由会话进入选集 ([TabletDownloadEpisodePage], 画在这个窗口里盖在上面, 见 [TvDownloadMediaPickerArgs.episodeStep]). 长按: 打开它在数据源上的链接.
 * 没有完整搜索 (下载的查询本来就查完)、没有「正在播放」.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabletDownloadSourcePage(args: TvDownloadMediaPickerArgs) {
    val selector = args.selectorState
    val presentation by selector.presentationFlow.collectAsStateWithLifecycle()
    val results by args.sourceResults.collectAsStateWithLifecycle(MediaSourceResultListPresentation.Empty)
    val request by args.fetchSession.latestRequest.collectAsStateWithLifecycle(null)
    val sources = results.list
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }
    var resolution by rememberSaveable { mutableStateOf<String?>(null) }
    var subtitle by rememberSaveable { mutableStateOf<String?>(null) }
    var alliance by rememberSaveable { mutableStateOf<String?>(null) }
    var webDetailed by rememberSaveable { mutableStateOf(false) }
    val collapsed = remember { mutableStateMapOf<TabletSourceSection, Boolean>() }
    var editingKeywords by remember { mutableStateOf<MediaFetchRequest?>(null) }
    var driveLogin by remember { mutableStateOf<String?>(null) }
    var manualOpen by rememberSaveable { mutableStateOf(false) }

    val detailsStrings = rememberMediaDetailsStrings()
    val all = presentation.filteredCandidates
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
    val groups = remember(candidates, sources, sourceFilter) { tabletSourceGroups(candidates, sources, sourceFilter) }
    val rows = tabletSourceRows(groups, sectioned = sourceFilter == null, collapsed = collapsed, webSimple = !webDetailed)
    val filtered = sourceFilter?.let { id -> sources.firstOrNull { it.mediaSourceId == id } }
    val pausedCount = sources.count { it.isPaused }
    val status = when {
        results.anyLoading -> stringResource(Lang.tv_source_searching, results.finishedSourceCount, results.enabledSourceCount)
        pausedCount > 0 -> stringResource(Lang.tv_source_search_paused, results.finishedSourceCount, results.enabledSourceCount)
        else -> stringResource(Lang.tv_source_searched, results.enabledSourceCount)
    }
    val title = request?.let { stringResource(Lang.tablet_download_title, it.episodeSort.toString()) }
        ?: stringResource(Lang.cache_subject_cache)
    val subtitleLine = listOfNotNull(
        request?.let { it.subjectNameCN ?: it.subjectNames.firstOrNull() },
        request?.episodeName?.takeIf { it.isNotBlank() },
        status,
    ).joinToString(" · ")

    val pick: (MaybeExcludedMedia) -> Unit = { args.onSelect(it.original) }
    val itemMenu: TabletSourceItemMenu = { media, expanded, onDismiss -> TabletDownloadItemMenu(media, expanded, onDismiss) }
    val downloadButton: @Composable (MaybeExcludedMedia) -> Unit = { item ->
        IconButton(onClick = { pick(item) }, Modifier.size(40.dp).clip(CircleShape).background(glassContainerColor())) {
            Icon(Icons.Rounded.Download, contentDescription = stringResource(Lang.cache_subject_cache), Modifier.size(20.dp))
        }
    }
    val actions: @Composable () -> Unit = {
        TabletSourceTextAction(Icons.Rounded.Refresh, stringResource(Lang.tv_source_refresh)) {
            args.fetchSession.restartSources(includeDisabled = false)
        }
        TabletSourceTextAction(Icons.Rounded.Edit, stringResource(Lang.tv_source_keywords)) {
            scope.launch { editingKeywords = args.fetchSession.latestRequest.first() }
        }
        if (args.manualBrowse != null) {
            TabletSourceTextAction(Icons.Rounded.Search, stringResource(Lang.tv_source_manual)) { manualOpen = true }
        }
    }

    // 选集那一步画在同一个窗口里、盖在选源上面 (见 TvDownloadEpisodeStep): 两步之间不换窗口, 不会露出底下的缓存页;
    // 选源留在底下, 回来时滚动位置与筛选都还在
    val episodeStep = args.episodeStep
    TabletDownloadWindow(
        onDismiss = {
            when {
                episodeStep != null -> episodeStep.onBack()
                manualOpen -> manualOpen = false
                else -> args.onDismiss()
            }
        },
    ) {
        val manual = args.manualBrowse
        Box(Modifier.fillMaxSize().then(if (episodeStep != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            if (manualOpen && manual != null) {
                // 手动查找: 点的一集经会话自己进入选集, 不经 onSelect
                ManualBrowsePage(
                    manual,
                    watching = null,
                    onPlayed = {},
                    topBar = { TabletDownloadHeader(stringResource(Lang.tv_source_manual), subtitle = null, onBack = { manualOpen = false }) },
                    modifier = Modifier.fillMaxSize(),
                    showRememberSelection = false,
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= TABLET_DOWNLOAD_WIDE_HEADER_MIN_WIDTH
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxSize()) {
                        TabletDownloadHeader(
                            title,
                            subtitleLine,
                            onBack = args.onDismiss,
                            trailing = {
                                if (wide) actions()
                                IconButton(
                                    onClick = {
                                        args.onDismiss()
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
                            contentPadding = PaddingValues(
                                start = if (wide) TABLET_DOWNLOAD_CONTENT_START_WIDE else 16.dp,
                                end = if (wide) 28.dp else 16.dp,
                                bottom = 24.dp,
                            ),
                        ) {
                            if (!wide) {
                                item("actions") {
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
                                }
                            }
                            item("sources") {
                                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        stringResource(Lang.media_selector_sources),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TabletSourceChips(
                                        sources = sources.filter { it.kind != MediaSourceKind.LocalCache && !it.isDisabled },
                                        selectedId = sourceFilter,
                                        playingId = null,
                                        onSelect = { id ->
                                            // 没登录网盘的源: 点它直接弹登录窗 (登录后它重新查), 不切成只看它
                                            val driveId = id?.let { i -> sources.firstOrNull { it.mediaSourceId == i } }
                                                ?.state?.cloudDriveLoginRequired?.driveId
                                            if (driveId != null) driveLogin = driveId else sourceFilter = if (sourceFilter == id) null else id
                                        },
                                        grouped = true,
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
                                item("state") {
                                    TabletSourceStateHint(
                                        filtered,
                                        hasItems = rows.isNotEmpty(),
                                        onRetry = { args.fetchSession.restart(filtered.instanceId) },
                                        onCaptcha = { scope.launch { selector.resolveCaptcha(filtered.instanceId) } },
                                        onLogin = { driveLogin = it },
                                    )
                                }
                            } else if (rows.isEmpty()) {
                                item("empty") {
                                    Text(
                                        stringResource(if (results.anyLoading) Lang.tv_source_loading else Lang.tv_source_no_result),
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
                                                { TabletWebModeSwitch(detailed = webDetailed, onChange = { webDetailed = it }) }
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
                                        playing = false,
                                        subtitleLabel = { renderSubtitleLanguage(it, detailsStrings) },
                                        onPick = { pick(row.media) },
                                        itemMenu = itemMenu,
                                        trailing = { downloadButton(row.media) },
                                    )

                                    is TabletSourceListRow.WebLines -> TabletWebSourceLines(
                                        row,
                                        selected = null,
                                        subtitleLabel = { renderSubtitleLanguage(it, detailsStrings) },
                                        onPick = pick,
                                        itemMenu = itemMenu,
                                        othersTrailing = { downloadButton(it) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        episodeStep?.content?.invoke()
    }

    editingKeywords?.let { current ->
        TvSourceKeywordsDialog(
            request = current,
            default = null,
            onSubmit = { args.fetchSession.setFetchRequest(it) },
            onDismiss = { editingKeywords = null },
        )
    }
    driveLogin?.let { driveId ->
        TvDriveLoginDialog(
            driveId,
            onLoggedIn = {
                // 登录一个网盘, 它的网盘源与分享搜索源都能查了
                sources.filter { it.state.cloudDriveLoginRequired?.driveId == driveId }
                    .forEach { args.fetchSession.restart(it.instanceId) }
            },
            onDismiss = { driveLogin = null },
        )
    }
}

/**
 * 下载的选集步骤 (平板模式与 iPad, 见 DownloadEpisodePickerVariant): 画在选源的同一个窗口里铺满 (见 TvDownloadEpisodeStep), 逻辑同上游的选集 (DownloadEpisodePicker) ——
 * 顶上「仅本话 / 本话及之后 / 全部」三个快捷选择 (默认本话及之后), 下面每集一行带勾选, 已下载的、这个资源里没有的灰掉不能勾,
 * 发起下载的那一集标「当前」; 底部「已选 N 集」与「下载」. 宽屏排成几列.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabletDownloadEpisodePage(
    state: DownloadEpisodePickerState,
    onBack: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
) {
    val options = state.options
    val selectable = remember(options) { options.filter { it.availability == DownloadEpisodeOption.Availability.AVAILABLE } }
    val onlyCurrent = remember(options) { selectable.filter { it.isCurrent }.mapTo(hashSetOf()) { it.episodeId } }
    val fromCurrent = remember(options) {
        val currentIndex = options.indexOfFirst { it.isCurrent }
        selectable.filter { options.indexOf(it) >= currentIndex }.mapTo(hashSetOf()) { it.episodeId }
    }
    val all = remember(options) { selectable.mapTo(hashSetOf()) { it.episodeId } }
    var selected by remember(state) { mutableStateOf<Set<Int>>(fromCurrent) }

    // 画在选源的窗口里、盖住选源 (见 TabletDownloadSourcePage): 不透明的底, 也挡住点到下面
    Surface(
        Modifier.fillMaxSize(),
        color = AniThemeDefaults.pageContentBackgroundColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= TABLET_DOWNLOAD_WIDE_HEADER_MIN_WIDTH
            val start = if (wide) TABLET_DOWNLOAD_CONTENT_START_WIDE else 16.dp
            val end = if (wide) 28.dp else 16.dp
            Column(Modifier.fillMaxSize()) {
                TabletDownloadHeader(
                    stringResource(Lang.downloads_episode_picker_title),
                    stringResource(
                        Lang.downloads_episode_picker_line,
                        state.chosen.properties.alliance.ifBlank { state.chosen.originalTitle },
                    ),
                    onBack = onBack,
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = start, end = end, top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TabletSoftChip(stringResource(Lang.downloads_episode_picker_only_current), selected == onlyCurrent, { selected = onlyCurrent })
                    TabletSoftChip(stringResource(Lang.downloads_episode_picker_from_current), selected == fromCurrent, { selected = fromCurrent })
                    TabletSoftChip(stringResource(Lang.downloads_episode_picker_all), selected == all, { selected = all })
                }
                LazyVerticalGrid(
                    GridCells.Adaptive(TABLET_DOWNLOAD_EPISODE_MIN_WIDTH),
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = start, end = end, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(options, key = { it.episodeId }) { option ->
                        TabletDownloadEpisodeRow(
                            option,
                            checked = option.episodeId in selected,
                            onToggle = {
                                selected = if (option.episodeId in selected) selected - option.episodeId else selected + option.episodeId
                            },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(start = start, end = end))
                Row(
                    Modifier.fillMaxWidth().padding(start = start, end = end, top = 12.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Lang.downloads_episode_picker_selected_count, selected.size),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) {
                        Text(stringResource(Lang.cache_subject_cache))
                    }
                }
            }
        }
    }
}

/** 一集一行: 勾选框 + 「第 N 话 · 名字」+ 下面一行 (要用的资源名, 或已下载 / 未匹配到资源) + 发起下载的那一集标「当前」. */
@Composable
private fun TabletDownloadEpisodeRow(option: DownloadEpisodeOption, checked: Boolean, onToggle: () -> Unit) {
    val enabled = option.availability == DownloadEpisodeOption.Availability.AVAILABLE
    val secondary = when (option.availability) {
        DownloadEpisodeOption.Availability.AVAILABLE -> option.resourceTitle.orEmpty()
        DownloadEpisodeOption.Availability.ALREADY_DOWNLOADED -> stringResource(Lang.downloads_episode_picker_downloaded)
        DownloadEpisodeOption.Availability.UNMATCHED -> stringResource(Lang.downloads_episode_picker_unmatched)
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp))
            .background(glassContainerColor())
            .clickable(enabled = enabled, onClick = onToggle)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Lang.cache_management_episode_label, option.sort, option.name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary.isNotEmpty()) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (option.isCurrent) {
            Text(
                stringResource(Lang.downloads_episode_picker_current),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tabletAccent,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/**
 * 下载这两页的窗口: 铺满屏幕 (含系统栏与 iOS 安全区), 底色与底板同详情页 (见 TvDetailsTheme), 内容自己让开系统栏. [onDismiss] 接系统返回.
 * 不超过窗口 ([windowSizeCap]): iOS 转屏时弹窗拿到不限的约束, 里面的列表量成无限大会让整个应用退出.
 */
@Composable
private fun TabletDownloadWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = PlatformDialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, usePlatformInsets = false),
    ) {
        TvDetailsTheme {
            Surface(
                Modifier.windowSizeCap().fillMaxSize(),
                color = AniThemeDefaults.pageContentBackgroundColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
            }
        }
    }
}

/** 顶栏: 返回 + 标题 (下面一行小字) + [trailing]. */
@Composable
private fun TabletDownloadHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Lang.downloads_episode_picker_back))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let { Row(verticalAlignment = Alignment.CenterVertically) { it() } }
    }
}

/** 长按一条: 打开它在数据源上的链接 (只认 http(s)); 没有链接就不弹. */
@Composable
private fun TabletDownloadItemMenu(media: Media, expanded: Boolean, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val link = media.originalUrl.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
        ?: return
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.menuHeightCap()) {
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

/** 宽到这里顶栏放得下几个操作, 内容往右让出返回键那一列; 不到时操作挪到列表第一行. */
private val TABLET_DOWNLOAD_WIDE_HEADER_MIN_WIDTH = 720.dp

/** 宽屏时内容的左边: 对齐顶栏标题 (返回键那一列让出来). */
private val TABLET_DOWNLOAD_CONTENT_START_WIDE = 64.dp

/** 选集一格的最小宽: 窄屏一列, 宽屏两三列. */
private val TABLET_DOWNLOAD_EPISODE_MIN_WIDTH = 340.dp
