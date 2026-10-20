/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv.source

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.widgets.AniCenteredPanelDialog
import me.him188.ani.app.ui.foundation.widgets.AniFocusChip
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.media_selector_bt_filter
import org.jetbrains.compose.resources.stringResource

/**
 * 筛选弹窗 (表头的「筛选」打开, 见 [TvSourceAction.OpenFilter]): 分辨率、字幕两组胶囊 (同搜索页的筛选弹窗), 按确定换勾 (选中的那颗打勾);
 * 弹窗开着, 两组都改完按返回关, 关的时候改过的几项一起生效 (长按返回收干净、触屏点外面也一样). 每生效一项都要把全部资源按新条件重筛一遍、
 * 面板跟着重排, 来回点就是重筛好几遍 (弱机上每次卡一下), 所以不逐项生效; 弹窗挡着面板, 关之前本来也看不到结果.
 * 内容跟着面板走 (查询途中取值陆续多出来), 打开时焦点在第一组当前选的那颗上.
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
    // 弹窗里换过的勾 (每项选了哪个值, null = 全部); 没换过的照面板当前的
    val picked = remember { mutableStateMapOf<TvSourceFilter, String?>() }
    val close = {
        val changed = changedFilters(rows, picked)
        if (changed.isNotEmpty()) state.applyFilters(changed)
        onDismiss()
    }
    AniCenteredPanelDialog(
        onDismissRequest = close,
        title = { Text(stringResource(Lang.media_selector_bt_filter), style = MaterialTheme.typography.titleLarge) },
        heightFraction = null,
        maxWidth = TV_SOURCE_FILTER_MAX_WIDTH,
    ) {
        // 焦点走出视野就滚过去: 所在页面可能关掉了默认的跟随 (按下标自己滚), 弹窗继承它, 这里装回默认
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember { object : BringIntoViewSpec {} }) {
            Column(
                Modifier
                    // 播放器里: 播放 / 暂停键照常管播放, 长按返回收干净
                    .tvOverlayWindowKeys(close)
                    // 取值多时比屏幕高: 弹窗不顶满上下, 多出来的滚
                    .layout { measurable, constraints ->
                        val maxHeight = if (constraints.hasBoundedHeight) {
                            (constraints.maxHeight * TV_SOURCE_FILTER_MAX_HEIGHT_FRACTION).roundToInt().coerceAtLeast(constraints.minHeight)
                        } else {
                            constraints.maxHeight
                        }
                        val placeable = measurable.measure(constraints.copy(maxHeight = maxHeight))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for ((header, options) in filterGroups(rows)) {
                    key(header.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(header.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (option in options) {
                                    key(option.id) {
                                        val pick = option.action as? TvSourceAction.PickFilter
                                        AniFocusChip(
                                            text = option.title,
                                            selected = if (pick != null && pick.filter in picked) picked[pick.filter] == pick.value else option.selected,
                                            onClick = { if (pick != null) picked[pick.filter] = pick.value else option.action?.let(state::perform) },
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

/** 弹窗内容最高占可用高度的多少 (其余留成上下边距), 见 [TvSourceFilterDialog]. */
private const val TV_SOURCE_FILTER_MAX_HEIGHT_FRACTION = 0.85f
