/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.data.models.subject.RelatedCharacterInfo
import me.him188.ani.app.data.models.subject.RelatedPersonInfo
import me.him188.ani.app.data.models.subject.nameCn
import me.him188.ani.app.ui.foundation.LocalImageCrossfade
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FOCUS_TRANSITION_MILLIS
import me.him188.ani.app.ui.foundation.tv.TvImageZoomState
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramStrip
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_characters
import me.him188.ani.app.ui.lang.subject_details_staff
import me.him188.ani.app.ui.lang.subject_details_view_all
import me.him188.ani.app.ui.subject.details.sections.CharactersViewAllDialog
import me.him188.ani.app.ui.subject.details.sections.SectionHeader
import me.him188.ani.app.ui.subject.details.sections.StaffViewAllDialog
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_FOCUS_RING
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_FOCUS_SCALE
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_LINE_GAP
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_MOVE_RATE
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_BAR_CORNER
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_BAR_INSET
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_NAME_WIDTH
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_SUBTITLE_WIDTH
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SIZE
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SPACING
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SUBTITLE_ALPHA
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_TEXT_GAP
import me.him188.ani.app.ui.subject.details.sections.monogramInitials
import me.him188.ani.app.ui.subject.details.sections.tvPeoplePlaceholderColor
import me.him188.ani.app.ui.subject.person.PeoplePreviewTarget
import me.him188.ani.app.ui.subject.person.rememberPeopleClickHandler
import org.jetbrains.compose.resources.stringResource

/**
 * 详情页人物页里一排演职人员的数据状态: 有人 / 确认没有 (总数为 0) / 还在路上. 两排 (角色 / 制作人员) 各算各的, 数据到了就原地
 * 填进那一排 (见 [TvDetailsCharactersRow]).
 */
internal enum class TvPeopleRowState { LOADING, CONTENT, EMPTY }

/** [itemCount] = 露出来的那几个已经到了多少, [totalCount] = 总数 (null = 还不知道). */
internal fun tvPeopleRowState(itemCount: Int, totalCount: Int?): TvPeopleRowState = when {
    itemCount > 0 -> TvPeopleRowState.CONTENT
    totalCount == 0 -> TvPeopleRowState.EMPTY
    else -> TvPeopleRowState.LOADING
}

/**
 * 详情页的角色行: 标题 + 原生圆头像横滑行 ([TvNativeMonogramStrip]). 数据没到时是一排占位格, 照样接得住焦点, 到了原地换 ——
 * 焦点所在的节点不拆, 不会被系统改派到页面别处. 还有没露出来的人时行末补一格「查看全部」(开全量弹窗).
 * 短按人物预览, 长按放大看图 ([imageZoom], 大图层与按键拦截挂在页面根上). 上下键交给页面 (行不接).
 *
 * @param horizontalPadding 标题两侧与行两端的留白 (行本身全宽出血, 滑过行首的格从屏幕左缘出屏).
 */
@Composable
internal fun TvDetailsCharactersRow(
    exposed: LazyPagingItems<RelatedCharacterInfo>,
    all: LazyPagingItems<RelatedCharacterInfo>,
    totalCount: Int?,
    imageZoom: TvImageZoomState,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val onClickCharacter = rememberPeopleClickHandler()
    val count = exposed.itemCount
    val snapshot = exposed.itemSnapshotList
    val items = remember(snapshot, totalCount) {
        if (count == 0) {
            null
        } else {
            snapshot.map { info ->
                info?.let {
                    val name = it.character.displayName
                    TvNativeMonogram.Person(
                        imageUrl = it.character.imageMedium,
                        name = name,
                        subtitle = it.character.actors.firstOrNull()?.displayName ?: it.role.nameCn,
                        initials = monogramInitials(name),
                    )
                }
            } + tvPeopleViewAllCell(count, totalCount)
        }
    }
    TvDetailsPeopleRow(
        title = stringResource(Lang.subject_details_characters),
        items = items,
        onClick = { index ->
            if (index >= count) showAll = true
            else exposed.peek(index)?.let { onClickCharacter(PeoplePreviewTarget.Character(it.character.id)) }
        },
        onLongPress = { index -> exposed.peek(index)?.let { imageZoom.open(it.character.imageLarge) } },
        onBind = { index -> if (index < exposed.itemCount) exposed[index] },
        horizontalPadding = horizontalPadding,
        modifier = modifier,
    )
    if (showAll) {
        CharactersViewAllDialog(all, totalCount, onDismissRequest = { showAll = false })
    }
}

/** 详情页的制作人员行: 同 [TvDetailsCharactersRow], 副标题是职位. */
@Composable
internal fun TvDetailsStaffRow(
    exposed: LazyPagingItems<RelatedPersonInfo>,
    all: LazyPagingItems<RelatedPersonInfo>,
    totalCount: Int?,
    imageZoom: TvImageZoomState,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val onClickPerson = rememberPeopleClickHandler()
    val count = exposed.itemCount
    val snapshot = exposed.itemSnapshotList
    val items = remember(snapshot, totalCount) {
        if (count == 0) {
            null
        } else {
            snapshot.map { info ->
                info?.let {
                    val name = it.personInfo.displayName
                    TvNativeMonogram.Person(
                        imageUrl = it.personInfo.imageMedium,
                        name = name,
                        subtitle = it.position.nameCn ?: "",
                        initials = monogramInitials(name),
                    )
                }
            } + tvPeopleViewAllCell(count, totalCount)
        }
    }
    TvDetailsPeopleRow(
        title = stringResource(Lang.subject_details_staff),
        items = items,
        onClick = { index ->
            if (index >= count) showAll = true
            else exposed.peek(index)?.let { onClickPerson(PeoplePreviewTarget.Person(it.personInfo.id)) }
        },
        onLongPress = { index -> exposed.peek(index)?.let { imageZoom.open(it.personInfo.imageLarge) } },
        onBind = { index -> if (index < exposed.itemCount) exposed[index] },
        horizontalPadding = horizontalPadding,
        modifier = modifier,
    )
    if (showAll) {
        StaffViewAllDialog(all, totalCount, onDismissRequest = { showAll = false })
    }
}

/** 还有没露出来的人时行末那格「查看全部」(副标题写剩几个); 都露出来了就没有. */
private fun tvPeopleViewAllCell(count: Int, totalCount: Int?): List<TvNativeMonogram> =
    if ((totalCount ?: 0) > count) listOf(TvNativeMonogram.ViewAll(totalCount?.minus(count))) else emptyList()

/** 一排演职人员: 标题 (两侧留白) + 圆头像横滑行, 间距同 Compose 版的 CharactersSection. */
@Composable
private fun TvDetailsPeopleRow(
    title: String,
    items: List<TvNativeMonogram?>?,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title, modifier = Modifier.padding(horizontal = horizontalPadding))
        TvNativeMonogramStrip(
            items = items,
            style = rememberTvMonogramStyle(),
            onClick = onClick,
            onLongPress = onLongPress,
            repeatMillis = 1000L / TV_MONOGRAM_MOVE_RATE,
            startPadding = horizontalPadding,
            endPadding = horizontalPadding,
            onBind = onBind,
        )
    }
}

/**
 * 圆头像行的原生样式, 取值同 Compose 版 (SubjectPeopleSections.kt 的 PersonMonogramCell / ViewAllMonogramCell / TvPeopleStripPlaceholder):
 * 姓名 titleSmall 加粗、副标题 bodySmall (颜色都是此处的内容色), 首字 titleLarge; 首字格与「查看全部」的圆底 surfaceContainerHighest,
 * 箭头 24dp; 环用主题色.
 *
 * @param size 圆的直径 (= 格宽), 默认详情页的; 字号不跟着变, 占位字条的宽度按比例.
 * @param spacing 格间距.
 * @param viewAllLabel 「查看全部」那格的字 (行里没有这一格时可以不给).
 */
@Composable
internal fun rememberTvMonogramStyle(
    size: Dp = TV_MONOGRAM_SIZE,
    spacing: Dp = TV_MONOGRAM_SPACING,
    viewAllLabel: String = stringResource(Lang.subject_details_view_all),
): TvNativeMonogramStyle {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val content = LocalContentColor.current
    val placeholder = tvPeoplePlaceholderColor()
    val crossfade = LocalImageCrossfade.current
    val arrow = rememberTvNativeIcon(Icons.AutoMirrored.Outlined.ArrowForward, 24.dp)
    return remember(density, typography, colors, content, placeholder, crossfade, arrow, viewAllLabel, size, spacing) {
        with(density) {
            val scale = size / TV_MONOGRAM_SIZE
            TvNativeMonogramStyle(
                sizePx = size.roundToPx(),
                spacingPx = spacing.roundToPx(),
                textGapPx = TV_MONOGRAM_TEXT_GAP.roundToPx(),
                lineGapPx = TV_MONOGRAM_LINE_GAP.roundToPx(),
                name = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold).toTvNativeTextStyle(density, content),
                subtitle = typography.bodySmall.toTvNativeTextStyle(density, content),
                subtitleIdleAlpha = TV_MONOGRAM_SUBTITLE_ALPHA,
                initials = typography.titleLarge.toTvNativeTextStyle(density, colors.onSurfaceVariant),
                focusScale = TV_MONOGRAM_FOCUS_SCALE,
                ringWidthPx = TV_MONOGRAM_FOCUS_RING.toPx(),
                ringColor = colors.primary.toArgb(),
                filledColor = colors.surfaceContainerHighest.toArgb(),
                placeholderColor = placeholder.toArgb(),
                placeholderNameWidthPx = (TV_MONOGRAM_PLACEHOLDER_NAME_WIDTH * scale).roundToPx(),
                placeholderSubtitleWidthPx = (TV_MONOGRAM_PLACEHOLDER_SUBTITLE_WIDTH * scale).roundToPx(),
                placeholderBarInsetPx = TV_MONOGRAM_PLACEHOLDER_BAR_INSET.roundToPx(),
                placeholderBarCornerPx = TV_MONOGRAM_PLACEHOLDER_BAR_CORNER.toPx(),
                arrow = arrow,
                arrowColor = colors.onSurfaceVariant.toArgb(),
                viewAllLabel = viewAllLabel,
                focusMillis = TV_CARD_FOCUS_TRANSITION_MILLIS.toLong(),
                crossfade = crossfade,
            )
        }
    }
}
