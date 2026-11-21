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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.him188.ani.app.ui.episode.danmaku.DanmakuSourceChips
import me.him188.ani.app.ui.episode.danmaku.DanmakuTimeShiftDialog
import me.him188.ani.app.ui.episode.danmaku.renderDanmakuServiceId
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.episode_danmaku
import me.him188.ani.app.ui.lang.subject_episode_danmaku_list_empty
import me.him188.ani.app.ui.lang.subject_episode_danmaku_list_title
import me.him188.ani.app.ui.lang.subject_episode_danmaku_settings_title
import me.him188.ani.app.ui.subject.episode.EpisodePageState
import me.him188.ani.app.ui.subject.episode.EpisodeViewModel
import me.him188.ani.app.ui.subject.episode.details.DanmakuListState
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerOverlayState
import me.him188.ani.app.ui.subject.episode.video.settings.EpisodeVideoSettings
import me.him188.ani.app.ui.subject.episode.video.settings.EpisodeVideoSettingsViewModel
import me.him188.ani.app.ui.subject.episode.video.sidesheet.DanmakuRegexFilterSettings
import me.him188.ani.danmaku.api.DanmakuServiceId
import org.jetbrains.compose.resources.stringResource

/**
 * 弹幕面板 (视频底下那一排的「弹幕」打开; 分栏盖住右栏, 全屏是抽屉): 栏顶「弹幕」+ ✕, 下面两页「弹幕列表」「弹幕设置」,
 * 停在哪一页记在 [TabletPlayerState.danmakuSettingsShown] (关了再开回到上次那页).
 *
 * - 弹幕列表: 弹幕源一排 (开关、重新匹配、调整延迟, 同电视版弹幕面板底下那排) → 这一集的全部弹幕按时间排, 自己发的用主题色.
 * - 弹幕设置: 同播放器原来的弹幕设置 (透明度、字号、显示区域、过滤…), 「管理正则过滤」开居中弹窗.
 */
@Composable
internal fun TabletDanmakuPanel(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    tablet: TabletPlayerState,
    onClose: () -> Unit,
) {
    val state by vm.danmakuListState.collectAsStateWithLifecycle()
    val settingsShown = tablet.danmakuSettingsShown
    Column(Modifier.fillMaxSize()) {
        TabletPanelHeader(
            stringResource(Lang.episode_danmaku),
            onClose,
            note = state.danmakuItems.size.takeIf { it > 0 && !settingsShown }?.toString(),
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabletTabChip(
                selected = !settingsShown,
                onClick = { tablet.danmakuSettingsShown = false },
                label = { Text(stringResource(Lang.subject_episode_danmaku_list_title)) },
            )
            TabletTabChip(
                selected = settingsShown,
                onClick = { tablet.danmakuSettingsShown = true },
                label = { Text(stringResource(Lang.subject_episode_danmaku_settings_title)) },
            )
        }
        TabletHairline()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (settingsShown) {
                TabletDanmakuSettings(vm)
            } else {
                TabletDanmakuList(vm, page, overlay, state)
            }
        }
    }
}

@Composable
private fun TabletDanmakuList(
    vm: EpisodeViewModel,
    page: EpisodePageState,
    overlay: TvPlayerOverlayState,
    state: DanmakuListState,
) {
    var editingShiftServiceId by remember { mutableStateOf<DanmakuServiceId?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (state.sourceItems.isNotEmpty()) {
            DanmakuSourceChips(
                sourceItems = state.sourceItems,
                onToggleSource = { serviceId, enabled -> vm.setDanmakuSourceEnabled(serviceId, enabled) },
                onManualMatch = { serviceId ->
                    page.danmakuStatistics.fetchResults.find { it.serviceId == serviceId }?.let {
                        vm.startMatchingDanmaku(it.providerId)
                    }
                },
                onAdjustShift = { serviceId -> editingShiftServiceId = serviceId },
                // 菜单开着时控件不自动收起
                onMenuExpandedChanged = overlay::onPopupExpandedChanged,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            TabletHairline()
        }
        when {
            state.danmakuItems.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Text(
                        stringResource(Lang.subject_episode_danmaku_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(state.danmakuItems.size, key = { i -> "${state.danmakuItems[i].id}-$i" }) { i ->
                    val danmaku = state.danmakuItems[i]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            danmaku.content,
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (danmaku.isSelf) tabletAccent else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            renderTabletDanmakuTime(danmaku.timeMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val editingShiftSource = editingShiftServiceId?.let { serviceId ->
        page.danmakuStatistics.fetchResults.firstOrNull { it.serviceId == serviceId }
    }
    if (editingShiftSource != null) {
        DanmakuTimeShiftDialog(
            serviceName = renderDanmakuServiceId(editingShiftSource.serviceId),
            currentShiftMillis = editingShiftSource.config.shiftMillis,
            onDismissRequest = { editingShiftServiceId = null },
            onConfirm = { newShift ->
                vm.setDanmakuSourceShiftMillis(editingShiftSource.serviceId, newShift)
                editingShiftServiceId = null
            },
        )
    }
}

/** 弹幕设置 (同播放器原来的那页, 自己能滑); 「管理正则过滤」开居中弹窗, 同电视版. */
@Composable
private fun TabletDanmakuSettings(vm: EpisodeViewModel) {
    val viewModel = remember { EpisodeVideoSettingsViewModel() }
    var editingFilters by remember { mutableStateOf(false) }
    EpisodeVideoSettings(
        viewModel,
        onNavigateToFilterSettings = { editingFilters = true },
        modifier = Modifier.fillMaxSize(),
    )
    if (editingFilters) {
        AniCenteredPanelDialog(
            onDismissRequest = { editingFilters = false },
            widthFraction = 0.45f,
        ) {
            DanmakuRegexFilterSettings(
                state = vm.danmakuRegexFilterState,
                onDismissRequest = { editingFilters = false },
                expanded = false,
            )
        }
    }
}

/** 弹幕时间: 分:秒, 两位补零. */
private fun renderTabletDanmakuTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "${(totalSeconds / 60).toString().padStart(2, '0')}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
