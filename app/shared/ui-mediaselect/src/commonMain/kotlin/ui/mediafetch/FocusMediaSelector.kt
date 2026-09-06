/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediafetch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HorizontalRule
import me.him188.ani.app.domain.media.selector.UnsafeOriginalMediaAccess
import me.him188.ani.app.domain.media.selector.blocksSelection
import me.him188.ani.app.tools.formatDateTime
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.focus.restoreFocusAfter
import me.him188.ani.app.ui.foundation.focus.tvWindowInitialFocus
import me.him188.ani.app.ui.foundation.widgets.AniFocusChip
import me.him188.ani.app.ui.foundation.widgets.AniFocusRingSurface
import me.him188.ani.app.ui.foundation.widgets.AniFocusSelectableSurface
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_CONTENT_PADDING
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_SHAPE
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_TITLE_GAP
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_WINDOW_DIM
import me.him188.ani.app.ui.foundation.widgets.centeredPanelColor
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_unknown
import me.him188.ani.app.ui.lang.media_selector_filter_clear
import me.him188.ani.app.ui.lang.media_selector_filter_expand
import me.him188.ani.app.ui.lang.media_selector_filter_selected
import me.him188.ani.app.ui.lang.media_selector_view_show_excluded
import me.him188.ani.app.ui.lang.media_source_results_failed
import me.him188.ani.app.ui.lang.media_source_results_rate_limited
import me.him188.ani.app.ui.lang.media_source_results_verify
import me.him188.ani.app.ui.media.rememberMediaDetailsStrings
import me.him188.ani.app.ui.media.renderSubtitleLanguage
import me.him188.ani.app.ui.settings.rendering.MediaSourceIcons
import me.him188.ani.app.ui.settings.rendering.SmallMediaSourceIcon
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.FileSize
import org.jetbrains.compose.resources.stringResource

// ---- TV 聚焦滚动策略 ----

/**
 * TV 聚焦滚动策略: 焦点元素总是吸附到滚动容器顶部 (横向滚动为左缘), 取代默认的
 * "最小滚动露出" —— 默认策略每次滚动距离取决于元素尺寸与当前位置, 遥控器连续导航时
 * 观感为乱跳; 吸附后每按一次方向键滚动一步, 焦点位置恒定可预期.
 *
 * 通过 [LocalBringIntoViewSpec] 对子树内全部滚动容器 (纵向列表与行内横向滚动) 生效.
 * 非焦点驱动的形态原样组合 [content], 零影响.
 *
 * 注意: 本 spec 恒返回非零滚动距离, 列表末尾滚不到容器顶的条目其 bringIntoView
 * 请求永远不"完成" —— 子树内不要 await `BringIntoViewRequester.bringIntoView()`
 * (fire-and-forget / collectLatest 取消兜底的用法安全).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SnapToStartScrollProvider(content: @Composable () -> Unit) {
    if (!LocalAniUiBehavior.current.focusDrivenNavigation) {
        content()
        return
    }
    val marginPx = with(LocalDensity.current) { SNAP_SCROLL_MARGIN.toPx() }
    val spec = remember(marginPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - marginPx
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** 吸附后焦点元素与容器顶部/左缘的留白. */
private val SNAP_SCROLL_MARGIN = 8.dp

// ---- 资源条目卡片 (详细模式) ----

/**
 * TV 版资源条目: 整卡单焦点 (确认键选择), 聚焦主题色描边示焦 (大块内容, 见 [AniFocusRingSurface]).
 *
 * 替代移动端 [MediaSelectorItem] 的 chip 形态 —— 卡内的大小/分辨率/字幕 chips 与
 * 数据源下拉框各自可聚焦, 遥控器导航会在卡内乱跳; TV 上偏好设置由上方筛选行承担,
 * 卡内信息全部降级为纯文本.
 */
@OptIn(UnsafeOriginalMediaAccess::class)
@Composable
internal fun FocusMediaSelectorItem(
    group: MediaGroup,
    groupState: MediaGroupState,
    mediaSourceInfoProvider: MediaSourceInfoProvider,
    selected: Boolean,
    onSelect: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val media: Media = group.first.original
    val currentItem = groupState.selectedItem ?: media
    val sourceInfo by mediaSourceInfoProvider.rememberMediaSourceInfo(currentItem.mediaSourceId)
    val mediaDetailsStrings = rememberMediaDetailsStrings()
    val reasonText = mediaExclusionReasonText(group.exclusionReason)
    // 硬性不可用 (缓存没下完): 点了也放不出来, 吞掉确认键. 其余排除原因仍可手动选中
    val selectionBlocked = group.exclusionReason?.blocksSelection == true
    val unknownText = stringResource(Lang.cache_unknown)
    val infoText = remember(media, mediaDetailsStrings) {
        buildList {
            media.properties.resolution.takeIf { it.isNotBlank() }?.let(::add)
            if (media.properties.size != FileSize.Zero && media.properties.size != FileSize.Unspecified) {
                add(media.properties.size.toString())
            }
            media.properties.subtitleLanguageIds.forEach { add(renderSubtitleLanguage(it, mediaDetailsStrings)) }
            media.properties.alliance.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ")
    }
    AniFocusRingSurface(
        onClick = { if (!selectionBlocked) onSelect(currentItem) },
        selected = selected,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
        unselectedColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = TV_SELECTOR_ITEM_CONTAINER_ALPHA),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                media.originalTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (infoText.isNotEmpty()) {
                Text(
                    infoText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 排除原因 (无字幕/季度不匹配等) 单独一行, 不与资源信息挤在一起
            reasonText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MediaSourceIcons.location(currentItem.location, currentItem.kind),
                    contentDescription = null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    sourceInfo?.displayName ?: unknownText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatDateTime(media.publishedTime, showTime = false),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 未选中资源卡的容器不透明度 (半透出弹窗底色). */
private const val TV_SELECTOR_ITEM_CONTAINER_ALPHA = 0.5f

// ---- 数据源状态胶囊 (BT / 在线 行) ----

/**
 * TV 版数据源状态条目: 胶囊按钮 (图标 + 名称 + 状态尾标), 与其他弹窗里的胶囊同一套 ([AniFocusChip]: 聚焦主题色实底,
 * 选中加深 + ✓). 替代移动端的 [androidx.compose.material3.InputChip] (聚焦指示过弱, 遥控器上看不清落点).
 *
 * 状态尾标自带颜色 (灰 / 红 / 强调色), 聚焦时一律让位给内容色 —— 压在主色实底上的红字、灰字都读不清.
 */
@Composable
internal fun FocusMediaSourceResultChip(
    selected: Boolean,
    onClick: () -> Unit,
    source: MediaSourceResultPresentation,
    modifier: Modifier = Modifier,
) {
    val failedText = stringResource(Lang.media_source_results_failed)
    val rateLimitedText = stringResource(Lang.media_source_results_rate_limited)
    val verifyText = stringResource(Lang.media_source_results_verify)
    AniFocusChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    ) { focused ->
        // 尾标颜色: 聚焦时统一用内容色 (见 KDoc)
        val contentColor = LocalContentColor.current
        fun tint(color: Color) = if (focused) contentColor else color
        SmallMediaSourceIcon(source.info)
        Text(
            source.info.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 状态尾标: 禁用 / 搜索中 / 失败 / 需验证 / 限流 / 结果数
        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
            when {
                source.isDisabled -> Icon(
                    Icons.Outlined.HorizontalRule, null, Modifier.size(16.dp),
                    tint = tint(MaterialTheme.colorScheme.onSurfaceVariant),
                )

                source.isWorking -> CircularProgressIndicator(
                    Modifier.size(14.dp),
                    color = tint(MaterialTheme.colorScheme.primary),
                    strokeWidth = 2.dp,
                )

                source.isFailedOrAbandoned -> Icon(
                    Icons.Outlined.Close, failedText, Modifier.size(16.dp),
                    tint = tint(MaterialTheme.colorScheme.error),
                )

                source.isCaptchaRequired -> Text(verifyText, color = tint(MaterialTheme.colorScheme.error))

                source.isRateLimited -> Text(rateLimitedText, color = tint(MaterialTheme.colorScheme.tertiary))

                else -> Text(
                    remember(source.totalCount) { "${source.totalCount}" },
                    color = tint(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

// ---- 筛选器 (分辨率 / 字幕语言 / 字幕组) ----

/**
 * TV 版筛选器入口: 胶囊按钮 ([AniFocusChip], 设了值即选中态), 点击打开居中网格弹窗选值.
 *
 * 替代移动端 InputChip + DropdownMenu 形态 —— chip 无聚焦视觉, 下拉菜单靠延时
 * 抢焦点不可靠且长列表纵向翻页费劲; 网格弹窗 3 列可一屏看全, 初始焦点落在当前
 * 选中项, 返回键关闭后焦点还给本按钮.
 */
@Composable
internal fun <T : Any> FocusMediaSelectorFilterChip(
    selected: T?,
    allValues: () -> List<T>,
    onSelect: (T) -> Unit,
    onDeselect: (T) -> Unit,
    name: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> Unit,
) {
    val values by remember(allValues) { derivedStateOf(allValues) }
    // 只有一个可选值: 无可筛选, 显示静态胶囊 (不可聚焦, 遥控器直接跳过)
    if (values.size == 1) {
        Surface(
            modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            // 内边距同 AniFocusChip, 与旁边可聚焦的胶囊一样高
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    values.firstOrNull()?.let { label(it) }
                }
            }
        }
        return
    }

    var showDialog by rememberSaveable { mutableStateOf(false) }

    AniFocusChip(
        selected = selected != null,
        onClick = { showDialog = true },
        modifier = modifier.restoreFocusAfter(showDialog),
    ) {
        selected?.let { label(it) } ?: name()
        Icon(
            Icons.Default.ArrowDropDown,
            stringResource(Lang.media_selector_filter_expand),
            Modifier.size(18.dp),
        )
    }

    if (showDialog) {
        FilterOptionsGridDialog(
            values = values,
            selected = selected,
            onSelectOption = {
                onSelect(it)
                showDialog = false
            },
            onClear = if (selected != null) {
                {
                    selected.let(onDeselect)
                    showDialog = false
                }
            } else null,
            onDismissRequest = {
                showDialog = false
            },
            title = name,
            label = label,
        )
    }
}

/**
 * "显示已被排除的资源"开关胶囊: 与筛选胶囊同行同款, 点击切换 —— 启用时选中色 + 对勾, 再点恢复.
 * 替代移动端列表底部的 文字+Switch 行 (在长列表末尾, 遥控器够不到).
 */
@Composable
internal fun FocusShowExcludedChip(
    checked: Boolean,
    count: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AniFocusChip(
        text = stringResource(Lang.media_selector_view_show_excluded, count),
        selected = checked,
        onClick = onToggle,
        modifier = modifier,
    )
}

/**
 * 筛选值网格弹窗: 固定 [TV_FILTER_DIALOG_COLUMNS] 列 (长列表一屏看全),
 * 初始焦点落在当前选中项 (未选中落第一项), 已选中时头部附"清除"项.
 * 外观同其他弹窗 (半透明面板色、压暗、内边距取 AniCenteredPanelDialog 的公共值).
 */
@Composable
private fun <T : Any> FilterOptionsGridDialog(
    values: List<T>,
    selected: T?,
    onSelectOption: (T) -> Unit,
    onClear: (() -> Unit)?,
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    label: @Composable (T) -> Unit,
) {
    val initialIndex = values.indexOf(selected).coerceAtLeast(0)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogWindowDimAmount(CENTERED_PANEL_WINDOW_DIM)
        Surface(
            // 独立窗口: 把遥控器全局键 (播放/暂停、长按返回) 接回主窗口, 见 tvOverlayWindowKeys
            Modifier.tvOverlayWindowKeys(onDismissRequest)
                .fillMaxWidth(TV_FILTER_DIALOG_WIDTH_FRACTION)
                .heightIn(max = FILTER_DIALOG_MAX_HEIGHT),
            shape = CENTERED_PANEL_SHAPE,
            color = centeredPanelColor,
            // 半透明底在配色表里查不到 "on" 色, 必须显式给 (见 centeredPanelColor)
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                Modifier.padding(CENTERED_PANEL_CONTENT_PADDING),
                verticalArrangement = Arrangement.spacedBy(CENTERED_PANEL_TITLE_GAP),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                }
                LazyVerticalGrid(
                    GridCells.Fixed(TV_FILTER_DIALOG_COLUMNS),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (onClear != null) {
                        item(key = "clear") {
                            FilterOptionCell(
                                selected = false,
                                onClick = onClear,
                            ) {
                                Text(stringResource(Lang.media_selector_filter_clear))
                            }
                        }
                    }
                    items(values.size) { i ->
                        val item = values[i]
                        FilterOptionCell(
                            selected = item == selected,
                            onClick = { onSelectOption(item) },
                            modifier = if (i == initialIndex) {
                                Modifier.tvWindowInitialFocus()
                            } else Modifier,
                        ) {
                            label(item)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 筛选值单元格: 聚焦同弹窗里的其他选项 ([AniFocusSelectableSurface]); 当前值只在右端打勾, 不铺选中色 ——
 * 单选列表的当前值与菜单同一种表示 (见 AniDropdownMenuItem).
 */
@Composable
private fun FilterOptionCell(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AniFocusSelectableSurface(
        onClick = onClick,
        selected = false,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(Modifier.weight(1f)) { content() }
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    stringResource(Lang.media_selector_filter_selected),
                    Modifier.size(16.dp),
                )
            }
        }
    }
}

private const val TV_FILTER_DIALOG_COLUMNS = 3
private const val TV_FILTER_DIALOG_WIDTH_FRACTION = 0.6f
private val FILTER_DIALOG_MAX_HEIGHT = 440.dp
