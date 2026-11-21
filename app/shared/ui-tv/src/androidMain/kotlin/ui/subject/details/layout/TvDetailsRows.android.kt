/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.him188.ani.app.ui.foundation.LocalImageCrossfade
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FOCUS_TRANSITION_MILLIS
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeEpisodeRow
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogram
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramStrip
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramBadgeStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMonogramStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_view_all
import me.him188.ani.app.ui.subject.details.sections.FocusEpisodeRowSpec
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_FOCUS_RING
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_FOCUS_SCALE
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_LINE_GAP
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_BAR_CORNER
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_BAR_INSET
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_NAME_WIDTH
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_PLACEHOLDER_SUBTITLE_WIDTH
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SIZE
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SPACING
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_SUBTITLE_ALPHA
import me.him188.ani.app.ui.subject.details.sections.TV_MONOGRAM_TEXT_GAP
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_BADGE_HEIGHT
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_BADGE_OVERHANG
import me.him188.ani.app.ui.subject.details.sections.VIEW_ALL_GALLERY_BADGE_PADDING
import me.him188.ani.app.ui.subject.details.sections.tvPeoplePlaceholderColor
import me.him188.ani.app.ui.subject.details.sections.viewAllGalleryBadgeStyle
import org.jetbrains.compose.resources.stringResource

/**
 * 圆头像行的原生样式, 取值同 Compose 版 (SubjectPeopleSections.kt 的 PersonMonogramCell / ViewAllMonogramCell / TvPeopleStripPlaceholder):
 * 姓名 titleSmall 加粗、副标题 bodySmall (颜色都是此处的内容色), 首字 titleLarge; 首字格与「查看全部」的圆底 surfaceContainerHighest,
 * 箭头 24dp; 环用主题色.
 *
 * @param size 圆的直径 (= 格宽), 默认详情页的; 字号不跟着变, 占位字条的宽度按比例.
 * @param spacing 格间距.
 * @param viewAllLabel 「查看全部」那格的字 (行里没有这一格时可以不给).
 * @param textGap 圆下缘到姓名.
 * @param badges 画圆下缘的标签 (「查看全部」的人物画廊, 取值同 Compose 版 ViewAllGalleries.kt).
 * @param smallText 姓名与副标题各小一号 (13 / 11sp, 行高 18 / 15): 圆比详情页小的地方 (播放器叠层里的画廊、人物页的横滑行).
 */
@Composable
internal fun rememberTvMonogramStyle(
    size: Dp = TV_MONOGRAM_SIZE,
    spacing: Dp = TV_MONOGRAM_SPACING,
    viewAllLabel: String = stringResource(Lang.subject_details_view_all),
    textGap: Dp = TV_MONOGRAM_TEXT_GAP,
    badges: Boolean = false,
    smallText: Boolean = false,
): TvNativeMonogramStyle {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val content = LocalContentColor.current
    val placeholder = tvPeoplePlaceholderColor()
    val crossfade = LocalImageCrossfade.current
    val arrow = rememberTvNativeIcon(Icons.AutoMirrored.Outlined.ArrowForward, 24.dp)
    val badgeStyle = if (badges) viewAllGalleryBadgeStyle() else null
    return remember(density, typography, colors, content, placeholder, crossfade, arrow, viewAllLabel, size, spacing, textGap, badgeStyle, smallText) {
        with(density) {
            val scale = size / TV_MONOGRAM_SIZE
            TvNativeMonogramStyle(
                sizePx = size.roundToPx(),
                spacingPx = spacing.roundToPx(),
                textGapPx = textGap.roundToPx(),
                lineGapPx = TV_MONOGRAM_LINE_GAP.roundToPx(),
                name = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    .let { if (smallText) it.copy(fontSize = 13.sp, lineHeight = 18.sp) else it }
                    .toTvNativeTextStyle(density, content),
                subtitle = typography.bodySmall
                    .let { if (smallText) it.copy(fontSize = 11.sp, lineHeight = 15.sp) else it }
                    .toTvNativeTextStyle(density, content),
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
                badge = badgeStyle?.let {
                    TvNativeMonogramBadgeStyle(
                        text = it.text.toTvNativeTextStyle(density, it.content),
                        containerColor = it.container.toArgb(),
                        heightPx = VIEW_ALL_GALLERY_BADGE_HEIGHT.roundToPx(),
                        paddingPx = VIEW_ALL_GALLERY_BADGE_PADDING.roundToPx(),
                        overhangPx = VIEW_ALL_GALLERY_BADGE_OVERHANG.roundToPx(),
                    )
                },
            )
        }
    }
}

internal actual val tvEpisodeRowContent: (@Composable (FocusEpisodeRowSpec) -> Unit)? = { spec -> TvNativeEpisodeRow(spec) }

@Composable
internal actual fun TvDetailsPeopleStrip(
    items: List<TvNativeMonogram?>?,
    onClick: (index: Int) -> Unit,
    onLongPress: (index: Int) -> Unit,
    onBind: (index: Int) -> Unit,
    horizontalPadding: Dp,
    restoreFocus: Boolean,
    modifier: Modifier,
) {
    TvNativeMonogramStrip(
        items = items,
        style = rememberTvMonogramStyle(),
        onClick = onClick,
        onLongPress = onLongPress,
        modifier = modifier,
        startPadding = horizontalPadding,
        endPadding = horizontalPadding,
        onBind = onBind,
        restoreFocus = restoreFocus,
    )
}
