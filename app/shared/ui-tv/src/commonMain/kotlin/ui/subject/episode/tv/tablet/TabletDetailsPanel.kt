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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_airing_total_episodes_completed
import me.him188.ani.app.ui.lang.tablet_player_details
import me.him188.ani.app.ui.lang.tablet_player_open_full_details
import me.him188.ani.app.ui.rating.FiveRatingStars
import me.him188.ani.app.ui.subject.details.SubjectDetailsLoadState
import me.him188.ani.app.ui.subject.details.layout.TvPadCharactersSection
import me.him188.ani.app.ui.subject.details.layout.TvPadStaffSection
import me.him188.ani.app.ui.subject.details.layout.TvPadSummaryDialog
import me.him188.ani.app.ui.subject.details.layout.rememberTvDetailsDisplaySummary
import me.him188.ani.app.ui.subject.details.layout.subjectAliasesText
import me.him188.ani.app.ui.subject.details.layout.subjectMetaLine
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.renderSubjectSeason
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * 这一部的详情 (视频下面「详情 ›」打开, 盖住右栏): 海报 + 名字 + 开播季度 + 评分 → 简介 (点了看全文) → 标签 → 角色 → 制作人员
 * (这两块同平板详情页, 横滑, 「查看全部」开画廊) → 打开完整详情页. 数据同播放器里的条目详情状态, 没到时转圈.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TabletDetailsPanel(
    vm: EpisodeViewModel,
    onClose: () -> Unit,
) {
    val loadState by vm.episodeDetailsState.subjectDetailsStateLoader.state.collectAsStateWithLifecycle()
    val state = (loadState as? SubjectDetailsLoadState.Ok)?.value
    val info = state?.info
    Column(Modifier.fillMaxSize()) {
        TabletPanelHeader(stringResource(Lang.tablet_player_details), onClose)
        if (state == null || info == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val uiState by state.uiState.collectAsStateWithLifecycle()
        val allCharacters = state.charactersPager.collectAsLazyPagingItemsWithLifecycle()
        val allStaff = state.staffPager.collectAsLazyPagingItemsWithLifecycle()
        val summary = rememberTvDetailsDisplaySummary(state, info)
        val mainEpisodeCount = uiState.episodeListUiState.mainEpisodes.size.takeIf { it > 0 }
        var showSummary by remember { mutableStateOf(false) }
        val navigator = LocalNavigator.current

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    info.imageLarge,
                    contentDescription = null,
                    Modifier.size(width = 72.dp, height = 102.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentScale = ContentScale.Crop,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        info.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (info.name.isNotBlank() && info.name != info.displayName) {
                        Text(
                            info.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        listOfNotNull(
                            renderSubjectSeason(info.airDate).takeIf { info.airDate.isValid },
                            mainEpisodeCount?.let { stringResource(Lang.subject_airing_total_episodes_completed, it.toString()) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val score = info.ratingInfo.scoreFloat
                    if (score > 0f) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(info.ratingInfo.score, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            FiveRatingStars(remember(score) { score.roundToInt() }, starSize = 12.dp)
                        }
                    }
                }
            }

            if (summary.isNotBlank()) {
                // 只露几行: 空行去掉, 免得省略号单独落在一行上 (全文弹窗里照原样)
                val preview = remember(summary) { summary.trim().replace(TABLET_BLANK_LINES, "\n") }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp).tabletTextClickable { showSummary = true },
                )
            }

            if (info.tags.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    info.tags.take(TABLET_DETAILS_MAX_TAGS).forEach { tag ->
                        Text(
                            tag.name,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            TvPadCharactersSection(allCharacters, uiState.totalCharactersCount, horizontalPadding = 16.dp)
            TvPadStaffSection(allStaff, uiState.totalStaffCount, horizontalPadding = 16.dp)

            OutlinedButton(
                onClick = { navigator.navigateSubjectDetails(info.subjectId, placeholder = null) },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            ) {
                Text(stringResource(Lang.tablet_player_open_full_details))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, Modifier.padding(start = 6.dp).size(16.dp))
            }
        }

        if (showSummary) {
            TvPadSummaryDialog(
                title = info.displayName,
                summary = summary,
                aliases = subjectAliasesText(info),
                meta = subjectMetaLine(info, mainEpisodeCount),
                tags = info.tags,
                onClickTag = {},
                onDismiss = { showSummary = false },
            )
        }
    }
}

/** 详情面板里最多摆几个标签 (全的在简介全文弹窗里). */
private const val TABLET_DETAILS_MAX_TAGS = 12

/** 简介预览里连着的空行 (含只有空白的行) 并成一个换行. */
private val TABLET_BLANK_LINES = Regex("\\s*\\n\\s*\\n\\s*")
