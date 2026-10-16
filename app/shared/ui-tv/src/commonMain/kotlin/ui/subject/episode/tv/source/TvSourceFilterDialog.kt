/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusChip
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_selector_bt_filter
import org.jetbrains.compose.resources.stringResource

/**
 * 筛选弹窗 (表头的「筛选」打开, 见 [TvSourceAction.OpenFilter]): 分辨率、字幕两组胶囊 (同搜索页的筛选弹窗), 按确定当场改这部番的偏好,
 * 选中的那颗打勾; 弹窗开着, 两组都改完按返回关. 内容跟着面板走 (查询途中取值陆续多出来), 打开时焦点在第一组当前选的那颗上.
 */
@Composable
internal fun TvSourceFilterDialog(state: TvSourcePanelState, onDismiss: () -> Unit) {
    val rows = state.content.collectAsState().value?.filter.orEmpty()
    if (rows.isEmpty()) {
        // 没东西可筛了 (结果变了): 关掉
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val initialId = remember { rows.firstOrNull { it.selected && it.focusable }?.id ?: rows.firstOrNull { it.focusable }?.id }
    AniCenteredPanelDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.media_selector_bt_filter), style = MaterialTheme.typography.titleLarge) },
        heightFraction = null,
        maxWidth = TV_SOURCE_FILTER_MAX_WIDTH,
    ) {
        Column(
            // 播放器里: 播放 / 暂停键照常管播放, 长按返回收干净
            Modifier.tvOverlayWindowKeys(onDismiss),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for ((header, options) in filterGroups(rows)) {
                key(header.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(header.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (option in options) {
                                key(option.id) {
                                    AniFocusChip(
                                        text = option.title,
                                        selected = option.selected,
                                        onClick = { option.action?.let(state::perform) },
                                        modifier = if (option.id == initialId) Modifier.tvWindowInitialFocus() else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 按小标题 ([TvSourceRowStyle.Status]) 分组: (小标题, 它下面的取值). */
private fun filterGroups(rows: List<TvSourceRow>): List<Pair<TvSourceRow, List<TvSourceRow>>> = buildList {
    var header: TvSourceRow? = null
    var options = mutableListOf<TvSourceRow>()
    for (row in rows) {
        if (row.style == TvSourceRowStyle.Status) {
            header?.let { add(it to options) }
            header = row
            options = mutableListOf()
        } else {
            options += row
        }
    }
    header?.let { add(it to options) }
}

private val TV_SOURCE_FILTER_MAX_WIDTH = 560.dp
