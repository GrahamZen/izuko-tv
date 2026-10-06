/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tv.TV_NAV_SETTLE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvNavigationSettle
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_source_detail_nav_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 详情弹窗的宿主: 跟着面板内容走 (弹窗开着时结果还在陆续到), [target] 那一行没了就关. 左右键在序列里换上一条 / 下一条
 * (右栏开的跨数据源, 见 [buildTvSourceDetailsSequence]), **背后面板的焦点跟着走** (同详情页选集卡长按弹窗): 单按当场跟,
 * 按住连发时合并成松手后一次跳过去 ([TvNavigationSettle], 与 hero 背景同一条规则), 免得每一发都让面板换屏重排.
 * 关掉 (或按了按钮) 时面板的焦点落到最后看的那一行.
 */
@Composable
internal fun TvSourceDetailsHost(
    state: TvSourcePanelState,
    target: TvSourceAction.ShowDetails,
    controller: TvSourcePanelController,
) {
    // 刚打开 / 换栏时序列还没拼好 (null 或另一栏的): 先不画, 等它到
    val sequence = state.detailsSequence.collectAsState().value?.takeIf { it.inRail == target.inRail } ?: return
    val index = sequence.indexOf(target)
    val entry = sequence.entries.getOrNull(index)
    if (entry == null) {
        LaunchedEffect(target) { state.detailsTarget.value = null }
        return
    }
    val settle = remember { TvNavigationSettle(TV_NAV_SETTLE_MILLIS) }
    LaunchedEffect(target) {
        settle.awaitTurn()
        controller.focusRow(target.railKey, target.rowId, target.inRail)
    }
    fun close() {
        state.detailsTarget.value = null
        controller.focusRow(target.railKey, target.rowId, target.inRail)
    }
    TvSourceDetailsDialog(
        details = entry.details,
        position = "${index + 1} / ${sequence.entries.size}",
        onNavigate = { delta ->
            sequence.entries.getOrNull(index + delta)?.let {
                state.detailsTarget.value = target.copy(rowId = it.rowId, railKey = it.railKey)
            }
        },
        onAction = { action ->
            close()
            state.perform(action)
        },
        onDismiss = { close() },
    )
}

/**
 * 一行的详情 (同详情页选集卡长按开的「本集详情」那一类居中弹窗): 标题完整显示 (折行), 左边是各项信息, 右边一列按钮 ——
 * 打开时焦点在第一颗 (「选择」), 直接按确定就是选这一条. 左右键切到上一条 / 下一条 (按钮列跟着换, 焦点回到第一颗), 上下键在按钮之间走.
 *
 * 信息分两段: 取值只有几个字的 ([TvSourceDetailField.short]) 排成三列的表格 (名称在上、取值在下), 长短不定的一项一行排在下面.
 */
@Composable
internal fun TvSourceDetailsDialog(
    details: TvSourceDetails,
    position: String,
    onNavigate: (Int) -> Unit,
    onAction: (TvSourceAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val navHint = stringResource(Lang.tv_source_detail_nav_hint)
    AniCenteredPanelDialog(
        onDismissRequest = onDismiss,
        title = { Text(details.title, style = MaterialTheme.typography.titleLarge) },
        heightFraction = null,
        maxWidth = TV_SOURCE_DETAILS_MAX_WIDTH,
    ) {
        val secondary = LocalContentColor.current.copy(alpha = TV_SOURCE_DETAILS_SECONDARY_ALPHA)
        Column(
            Modifier
                // 长按开出来的: 按住的那次确定键剩下的连发与抬起不能落到「选择」上
                .consumeHeldConfirmKey()
                // 播放器里: 播放 / 暂停键照常管播放, 长按返回收干净
                .tvOverlayWindowKeys(onDismiss)
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) onNavigate(if (event.key == Key.DirectionLeft) -1 else 1)
                            true
                        }

                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (details.subtitle.isNotEmpty()) {
                        Text(details.subtitle, style = MaterialTheme.typography.bodyLarge, color = secondary)
                    }
                    val (cells, rows) = details.fields.partition { it.short }
                    if (cells.isNotEmpty()) TvSourceDetailsTable(cells, secondary)
                    for (field in rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                field.label,
                                Modifier.width(TV_SOURCE_DETAILS_LABEL_WIDTH),
                                style = MaterialTheme.typography.bodyMedium,
                                color = secondary,
                            )
                            Text(
                                field.value,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = field.maxLines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (details.note.isNotEmpty()) {
                        Text(details.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                val buttons = listOfNotNull(details.primary) + details.secondary
                // 换了一条就整列重建: 新的第一颗当场接焦点
                key(details) {
                    if (buttons.isEmpty()) {
                        // 没有能按的 (缓存未完成): 焦点停在一个隐形节点上, 左右键照样能换
                        Box(Modifier.size(1.dp).tvWindowInitialFocus().focusable())
                    } else {
                        Column(Modifier.width(TV_SOURCE_DETAILS_BUTTON_WIDTH), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            buttons.forEachIndexed { i, button ->
                                AniFocusActionButton(
                                    onClick = { onAction(button.action) },
                                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.tvWindowInitialFocus() else Modifier),
                                ) {
                                    Text(button.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Text("$position · $navHint", style = MaterialTheme.typography.labelMedium, color = secondary)
        }
    }
}

/** 取值只有几个字的那些项: 每行 [TV_SOURCE_DETAILS_TABLE_COLUMNS] 格, 名称在上、取值在下; 最后一行不满的格子空着, 列照样对齐. */
@Composable
private fun TvSourceDetailsTable(cells: List<TvSourceDetailField>, secondary: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in cells.chunked(TV_SOURCE_DETAILS_TABLE_COLUMNS)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (cell in row) {
                    Column(Modifier.weight(1f)) {
                        Text(cell.label, style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // 字幕偶尔是好几种语言: 最多折一行
                        Text(cell.value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(TV_SOURCE_DETAILS_TABLE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val TV_SOURCE_DETAILS_MAX_WIDTH = 760.dp
private val TV_SOURCE_DETAILS_LABEL_WIDTH = 88.dp
private val TV_SOURCE_DETAILS_BUTTON_WIDTH = 220.dp
private const val TV_SOURCE_DETAILS_TABLE_COLUMNS = 3
private const val TV_SOURCE_DETAILS_SECONDARY_ALPHA = 0.7f
