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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.consumeHeldConfirmKey
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tv.TV_NAV_SETTLE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvNavigationSettle
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusActionButton
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_TITLE_GAP
import me.him188.ani.app.ui.foundation.widgets.aniPanelTitleStyle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_source_detail_nav_hint
import org.jetbrains.compose.resources.stringResource

/**
 * 详情弹窗的宿主: 跟着面板内容走 (弹窗开着时结果还在陆续到), [target] 那一行没了就关. 上下键在序列里换上一条 / 下一条
 * (列表里开的跨数据源, 见 [buildTvSourceDetailsSequence]), **背后面板的焦点跟着走** (同详情页选集卡长按弹窗): 单按当场跟,
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
        if (target.followFocus) controller.focusRow(target.railKey, target.rowId, target.inRail)
    }
    fun close() {
        state.detailsTarget.value = null
        if (target.followFocus) controller.focusRow(target.railKey, target.rowId, target.inRail)
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
 * 选源面板里一行的详情 (见 [TvDetailsDialog]): 最右是 [TvSourceDetails.primary] (「选择」那一个; 打开时焦点在它上面, 直接按确定就是选这一条),
 * 往左是 [TvSourceDetails.secondary] (下载这一条、排除字幕组; 排在前面的离「选择」近). 按钮照动作认位置: 换一条时焦点留在同一种动作上.
 */
@Composable
internal fun TvSourceDetailsDialog(
    details: TvSourceDetails,
    position: String,
    onNavigate: (Int) -> Unit,
    onAction: (TvSourceAction) -> Unit,
    onDismiss: () -> Unit,
) {
    TvDetailsDialog(
        title = details.title,
        subtitle = details.subtitle,
        badges = details.badges,
        fields = details.fields,
        note = details.note,
        buttons = (details.secondary.asReversed() + listOfNotNull(details.primary)).map { button ->
            TvDetailsButton(button.label, key = button.action::class.simpleName ?: button.label) { onAction(button.action) }
        },
        position = position,
        onNavigate = onNavigate,
        onDismiss = onDismiss,
    )
}

/**
 * 详情弹窗底下的一颗按钮.
 *
 * [key] 是它占的那一格: 上下键换了一条以后焦点留在 [key] 相同的那颗上 (同一格的字可以变, 如「下载」变「取消下载」), 新的一条没有这一格就回到最右那颗.
 * [widthLabels] = 这一格会轮流写的几种字: 照最长的留宽, 换一条时整排按钮不挪 (同字里有数字的按钮照两位数留宽一个道理).
 * [danger] = 删除这类: 字是错误色.
 */
internal class TvDetailsButton(
    val label: String,
    val key: String = label,
    val danger: Boolean = false,
    val widthLabels: List<String> = emptyList(),
    val onClick: () -> Unit,
)

/**
 * 一条东西的详情 (同详情页选集卡长按开的「本集详情」那一类居中弹窗; 选源面板的条目等用它): 标题完整显示 (折行), 右上角写 [position];
 * 下面是各项信息铺满宽度, 最底下一行左边是上下键的提示、右边是按钮 (见 [TvDetailsBody]).
 */
@Composable
internal fun TvDetailsDialog(
    title: String,
    subtitle: String,
    badges: List<TvSourceBadge>,
    fields: List<TvSourceDetailField>,
    note: String,
    buttons: List<TvDetailsButton>,
    position: String,
    onNavigate: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AniCenteredPanelDialog(
        onDismissRequest = onDismiss,
        heightFraction = null,
        maxWidth = TV_SOURCE_DETAILS_MAX_WIDTH,
    ) {
        TvDetailsBody(
            title = title,
            position = position,
            subtitle = subtitle,
            badges = badges,
            fields = fields,
            note = note,
            buttons = buttons,
            onNavigate = onNavigate,
            modifier = Modifier
                // 长按开出来的: 按住的那次确定键剩下的连发与抬起不能落到按钮上
                .consumeHeldConfirmKey()
                // 播放器里: 播放 / 暂停键照常管播放, 长按返回收干净
                .tvOverlayWindowKeys(onDismiss),
        )
    }
}

/**
 * 详情弹窗的正文: 标题 (右上角 [position]) 下面信息分两段 —— 取值只有几个字的 ([TvSourceDetailField.short]) 排成四列的表格 (名称在上、取值在下),
 * 长短不定的一项一行排在下面; 最底下一行左边是提示, 右边是按钮 ([TvDetailsButtonRow]). 上下键换上一条 / 下一条 ([onNavigate]), 左右键在按钮之间走.
 * 标题与信息那一块至少有一条完整信息那么高 ([TV_DETAILS_INFO_MIN_HEIGHT]), 更长的撑高以后只长不缩 (换到信息少的一条时下面空着),
 * 底下一行照按钮的高 (没有按钮的一条也是): 换一条、信息后补上时弹窗都不变形状, 按钮不跟着上下挪.
 */
@Composable
internal fun TvDetailsBody(
    title: String,
    position: String,
    subtitle: String,
    badges: List<TvSourceBadge>,
    fields: List<TvSourceDetailField>,
    note: String,
    buttons: List<TvDetailsButton>,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondary = LocalContentColor.current.copy(alpha = TV_SOURCE_DETAILS_SECONDARY_ALPHA)
    Column(
        modifier.onPreviewKeyEvent { event ->
            when (event.key) {
                // 这一层只有底下一排能聚焦, 上下本来没有去处
                Key.DirectionUp, Key.DirectionDown -> {
                    if (event.type == KeyEventType.KeyDown) onNavigate(if (event.key == Key.DirectionUp) -1 else 1)
                    true
                }

                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.tvGrowOnlyHeight().heightIn(min = TV_DETAILS_INFO_MIN_HEIGHT), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = CENTERED_PANEL_TITLE_GAP - 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, Modifier.weight(1f), style = aniPanelTitleStyle)
                Text(position, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = secondary, maxLines = 1)
            }
            if (badges.isNotEmpty()) TvSourceBadgeChips(badges)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = secondary)
            }
            val (cells, rows) = fields.partition { it.short }
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
            if (note.isNotEmpty()) {
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        Row(
            // 一颗按钮都没有 (没播出的集、缓存未完成) 也照按钮的高: 弹窗不变矮
            Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = TV_DETAILS_BUTTON_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Lang.tv_source_detail_nav_hint),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TvDetailsButtonRow(buttons)
        }
    }
}

/**
 * 详情弹窗底下靠右的一排按钮 (弹窗按钮的规矩: 按字定宽、高 40、间距 12, 主按钮在最右). 打开时焦点在最右那颗;
 * 换了一条 ([buttons] 变了) 焦点留在 [TvDetailsButton.key] 相同的那颗上 —— 每一格按 key 留着同一个节点, 只换字, 不会先按没聚焦画一帧再亮;
 * 那一格没了就回最右那颗. 一颗都没有 (缓存未完成) 时焦点停在一个隐形节点上, 上下键照样能换.
 */
@Composable
private fun TvDetailsButtonRow(buttons: List<TvDetailsButton>) {
    val keys = if (buttons.isEmpty()) listOf(TV_DETAILS_NO_BUTTON_KEY) else buttons.map { it.key }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    // 焦点最后落在哪一格; 焦点落过一次以后不再按「打开时的落点」抢焦点
    var focusedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(keys) {
        val held = focusedKey ?: return@LaunchedEffect
        if (held !in keys) runCatching { requester(keys.last()).requestFocus() }
    }
    fun Modifier.slot(key: String): Modifier = this
        .focusRequester(requester(key))
        .onFocusChanged { if (it.isFocused) focusedKey = key }

    val initial = focusedKey == null
    Row(horizontalArrangement = Arrangement.spacedBy(TV_DETAILS_BUTTON_GAP), verticalAlignment = Alignment.CenterVertically) {
        if (buttons.isEmpty()) {
            Box(Modifier.size(1.dp).slot(TV_DETAILS_NO_BUTTON_KEY).tvWindowInitialFocus(initial).focusable())
        }
        buttons.forEachIndexed { i, button ->
            key(button.key) {
                AniFocusActionButton(
                    onClick = button.onClick,
                    modifier = Modifier
                        .slot(button.key)
                        .tvWindowInitialFocus(initial && i == buttons.lastIndex)
                        .then(tvReservedButtonWidth(button.widthLabels))
                        .widthIn(max = TV_DETAILS_BUTTON_MAX_WIDTH),
                    accentColor = if (button.danger) MaterialTheme.colorScheme.error else Color.Unspecified,
                ) {
                    Text(button.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 一格会轮流写的几种字 ([TvDetailsButton.widthLabels]) 里最长的那种要多宽, 加上按钮左右内边距; 没给的不留 (照字定宽). */
@Composable
private fun tvReservedButtonWidth(labels: List<String>): Modifier {
    if (labels.isEmpty()) return Modifier
    val style: TextStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width: Dp = remember(labels, style, density) {
        with(density) { labels.maxOf { measurer.measure(it, style).size.width }.toDp() }
    }
    return Modifier.widthIn(min = width + TV_DETAILS_BUTTON_HORIZONTAL_PADDING * 2)
}

/** 只长不缩: 量到过的最高的那次就是往后的最小高 (换一条时这一块不变矮, 下面的东西不挪). */
@Composable
private fun Modifier.tvGrowOnlyHeight(): Modifier {
    var tallest by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    return this
        .onSizeChanged { if (it.height > tallest) tallest = it.height }
        .heightIn(min = with(density) { tallest.toDp() })
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
private const val TV_SOURCE_DETAILS_TABLE_COLUMNS = 4
private const val TV_SOURCE_DETAILS_SECONDARY_ALPHA = 0.7f

/**
 * 标题与信息那一块至少多高: 一条完整的信息 (两行标题、副标题、两行四列的表格、两行长短不定的) 放得下 (同系列简介那样定高, 换一条、信息后补上时不变形状)
 */
private val TV_DETAILS_INFO_MIN_HEIGHT = 260.dp

/** 按钮的高 ([AniFocusActionButton]: 上下内边距 10 + 一行按钮字) */
private val TV_DETAILS_BUTTON_HEIGHT = 40.dp

/** 一排按钮之间 (同别的弹窗底下那一排) */
private val TV_DETAILS_BUTTON_GAP = 12.dp

/** 按钮左右内边距 (同 [AniFocusActionButton]) */
private val TV_DETAILS_BUTTON_HORIZONTAL_PADDING = 24.dp

/** 一颗按钮最宽多少 (字幕组名很长的「排除字幕组「…」」截断, 不把左边的提示挤没) */
private val TV_DETAILS_BUTTON_MAX_WIDTH = 320.dp

/** 没有按钮时那个隐形落点的 key */
private const val TV_DETAILS_NO_BUTTON_KEY = "<none>"
