/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.auto

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_selector_bt_source_count
import me.him188.ani.app.ui.lang.media_selector_filter_alliance
import me.him188.ani.app.ui.lang.media_selector_filter_resolution
import me.him188.ani.app.ui.lang.media_selector_filter_subtitle
import me.him188.ani.app.ui.lang.media_selector_manual_back
import me.him188.ani.app.ui.media.rememberMediaDetailsStrings
import me.him188.ani.app.ui.media.renderSubtitleLanguage
import me.him188.ani.app.ui.mediafetch.MediaSelectorFilterChip
import me.him188.ani.app.ui.mediaselect.bt.BtCompactList
import me.him188.ani.app.ui.mediaselect.bt.BtRow
import me.him188.ani.app.ui.mediaselect.bt.BtRowExclusion
import me.him188.ani.app.ui.mediaselect.common.SourceIcon
import me.him188.ani.app.ui.mediaselect.selector.WebSource
import me.him188.ani.datasources.api.Media
import org.jetbrains.compose.resources.stringResource

/**
 * 自动匹配页里点一个数据源的名字进来: 这个源查到的每一条 (自动匹配页每条线路只有一个按钮, 网盘整个盘就是一条线路, 同一盘里的几个版本都看不到).
 * 行同 BT 资源页的紧凑列表 (线路 / 完整标题 / 标签 / 大小 / 发布日期), 顶上分辨率、字幕、线路三个下拉 —— 只在这一页筛, 不写偏好.
 * 被排除的 (这台设备放不了、条目名不符等) 收在列表末尾「显示已被排除的 N 条」里. 点一条就选它 ([onClickItem]).
 *
 * @param candidates 选择器的候选 (已去掉别的集, 见 MediaSelectorState.Presentation.filteredCandidates)
 */
@Composable
internal fun SourceResourcesPage(
    source: WebSource,
    candidates: List<MaybeExcludedMedia>,
    selected: Media?,
    onBack: () -> Unit,
    onClickItem: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    var resolution by rememberSaveable(source.mediaSourceId) { mutableStateOf<String?>(null) }
    var subtitleLanguageId by rememberSaveable(source.mediaSourceId) { mutableStateOf<String?>(null) }
    var alliance by rememberSaveable(source.mediaSourceId) { mutableStateOf<String?>(null) }
    var revealExcluded by rememberSaveable(source.mediaSourceId) { mutableStateOf(false) }
    val presentation = remember(candidates, source.mediaSourceId, selected, resolution, subtitleLanguageId, alliance) {
        projectSourceList(candidates, source.mediaSourceId, selected, resolution, subtitleLanguageId, alliance)
    }
    val mediaDetailsStrings = rememberMediaDetailsStrings()

    Column(modifier.fillMaxSize().testTag(AutoMatchPageTestTags.SOURCE_PAGE)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, Modifier.testTag(AutoMatchPageTestTags.SOURCE_BACK)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Lang.media_selector_manual_back))
            }
            SourceIcon(source.iconUrl, source.name, Modifier.size(24.dp))
            Text(
                source.name,
                Modifier.weight(1f, fill = false).padding(start = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(Lang.media_selector_bt_source_count, presentation.total),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaSelectorFilterChip(
                selected = resolution,
                allValues = presentation.availableResolutions,
                onSelect = { resolution = it },
                onDeselect = { resolution = null },
                name = stringResource(Lang.media_selector_filter_resolution),
            )
            MediaSelectorFilterChip(
                selected = subtitleLanguageId,
                allValues = presentation.availableSubtitleLanguageIds,
                onSelect = { subtitleLanguageId = it },
                onDeselect = { subtitleLanguageId = null },
                name = stringResource(Lang.media_selector_filter_subtitle),
                label = { renderSubtitleLanguage(it, mediaDetailsStrings) },
            )
            MediaSelectorFilterChip(
                selected = alliance,
                allValues = presentation.availableAlliances,
                onSelect = { alliance = it },
                onDeselect = { alliance = null },
                name = stringResource(Lang.media_selector_filter_alliance),
            )
        }
        HorizontalDivider()
        BtCompactList(
            rows = presentation.included,
            excludedRows = presentation.excluded,
            revealExcluded = revealExcluded,
            onReveal = { revealExcluded = true },
            onClick = { onClickItem(it.media) },
            onLongClick = {},
            timeZone = TimeZone.currentSystemDefault(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalPadding = 4.dp,
            isLoading = source.isLoading,
        )
    }
}

/** [SourceResourcesPage] 的列表: 主列表 (没被排除的) 与排除的两段, 都按选择器的顺序; 筛选候选值取自这个源的全部条目. */
@Immutable
internal data class SourceListPresentation(
    val included: List<BtRow>,
    val excluded: List<BtRow>,
    /** 这个源的全部条数 (筛选前). */
    val total: Int,
    val availableResolutions: List<String>,
    val availableSubtitleLanguageIds: List<String>,
    val availableAlliances: List<String>,
)

/**
 * 纯函数: [candidates] 里属于 [mediaSourceId] 的条目, 按三项筛选 (null = 不限) 留下符合的, 被排除的放 excluded 并带上原因.
 * 候选值取自这个源的全部条目 (筛选前), 去重保序.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
internal fun projectSourceList(
    candidates: List<MaybeExcludedMedia>,
    mediaSourceId: String,
    selected: Media?,
    resolution: String?,
    subtitleLanguageId: String?,
    alliance: String?,
): SourceListPresentation {
    val mine = candidates.filter { it.original.mediaSourceId == mediaSourceId }
    val resolutions = LinkedHashSet<String>()
    val subtitles = LinkedHashSet<String>()
    val alliances = LinkedHashSet<String>()
    for (item in mine) {
        val properties = item.original.properties
        properties.resolution.takeIf { it.isNotBlank() }?.let { resolutions.add(it) }
        subtitles.addAll(properties.subtitleLanguageIds)
        properties.alliance.takeIf { it.isNotBlank() }?.let { alliances.add(it) }
    }
    val included = ArrayList<BtRow>()
    val excluded = ArrayList<BtRow>()
    for (item in mine) {
        val media = item.original
        val properties = media.properties
        if (resolution != null && properties.resolution != resolution) continue
        if (subtitleLanguageId != null && subtitleLanguageId !in properties.subtitleLanguageIds) continue
        if (alliance != null && properties.alliance != alliance) continue
        val exclusion = (item as? MaybeExcludedMedia.Excluded)?.let { BtRowExclusion.Reason(it.exclusionReason) }
        val row = BtRow(media, exclusion, isSelected = media == selected)
        if (exclusion == null) included.add(row) else excluded.add(row)
    }
    return SourceListPresentation(
        included = included,
        excluded = excluded,
        total = mine.size,
        availableResolutions = resolutions.toList(),
        availableSubtitleLanguageIds = subtitles.toList(),
        availableAlliances = alliances.toList(),
    )
}
