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
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.tools.LocalTimeFormatter
import me.him188.ani.app.tools.TimeFormatter
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.foundation.LocalImageCrossfade
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeComment
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentList
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.widgets.ANI_SCROLL_FADE
import me.him188.ani.app.ui.subject.details.sections.COMMENTS_COLUMNS
import me.him188.ani.app.ui.subject.details.sections.COMMENTS_COLUMN_GAP
import me.him188.ani.app.ui.subject.details.sections.COMMENTS_DIVIDER_ALPHA
import me.him188.ani.app.ui.subject.details.sections.COMMENTS_LIST_SIDE_INSET
import me.him188.ani.app.ui.subject.details.sections.COMMENT_AVATAR_GAP
import me.him188.ani.app.ui.subject.details.sections.COMMENT_AVATAR_SIZE
import me.him188.ani.app.ui.subject.details.sections.COMMENT_COLLAPSED_MAX_LINES
import me.him188.ani.app.ui.subject.details.sections.COMMENT_HEADER_GAP
import me.him188.ani.app.ui.subject.details.sections.COMMENT_ITEM_CORNER
import me.him188.ani.app.ui.subject.details.sections.COMMENT_ITEM_FOCUSED_ALPHA
import me.him188.ani.app.ui.subject.details.sections.COMMENT_ITEM_OUTER_GAP
import me.him188.ani.app.ui.subject.details.sections.COMMENT_ITEM_PADDING
import me.him188.ani.app.ui.subject.details.sections.COMMENT_ITEM_RING
import me.him188.ani.app.ui.subject.details.sections.COMMENT_ITEM_VERTICAL_PADDING
import me.him188.ani.app.ui.subject.details.sections.COMMENT_NAME_DATE_GAP
import me.him188.ani.app.ui.subject.details.sections.COMMENT_STARS_GAP
import me.him188.ani.app.ui.subject.details.sections.COMMENT_STAR_SIZE
import me.him188.ani.app.ui.subject.details.sections.ViewAllCommentsList
import me.him188.ani.app.ui.subject.details.sections.commentListTextStyles
import me.him188.ani.app.ui.subject.details.sections.plainContent
import me.him188.ani.app.ui.subject.details.sections.tvPeoplePlaceholderColor

/**
 * 「查看全部」评论列表 ([ViewAllCommentsList]) 的原生版: 电视包 (电视与安卓平板) 的入口提供. 遥控器长按连翻时条目复用、不重组,
 * 外观与 Compose 版同一套定义 (ui-subject 的 CommentsList.kt).
 */
object TvViewAllCommentsList : ViewAllCommentsList {
    @Composable
    override fun Comments(
        comments: LazyPagingItems<UIComment>,
        showRating: Boolean,
        initialFocusIndex: Int,
        initialFocus: Modifier,
        modifier: Modifier,
    ) {
        val style = rememberTvNativeCommentStyle()
        val formatter = LocalTimeFormatter.current
        // 每条只转一次 (日期格式化、富文本转纯文本), 分页插进来的才是新的
        val converted = remember(formatter, showRating) { HashMap<String, TvNativeComment>() }
        val snapshot = comments.itemSnapshotList
        val items = remember(snapshot, converted) {
            snapshot.map { comment -> comment?.let { converted.getOrPut(it.stableId) { it.toNativeComment(formatter, showRating) } } }
        }
        TvNativeCommentList(
            items = items,
            style = style,
            initialFocusIndex = initialFocusIndex,
            touchTaps = LocalTvTouchInputEnabled.current,
            onBind = { comments[it] },
            modifier = modifier.then(initialFocus),
        )
    }
}

private fun UIComment.toNativeComment(formatter: TimeFormatter, showRating: Boolean) = TvNativeComment(
    key = stableId,
    avatarUrl = author?.avatarUrl,
    name = author?.nickname ?: author?.id?.toString() ?: "",
    date = if (createdAt == 0L) "" else formatter.format(createdAt, showTime = false),
    rating = rating?.takeIf { showRating && it > 0 },
    text = plainContent(),
)

@Composable
private fun rememberTvNativeCommentStyle(): TvNativeCommentStyle {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val text = commentListTextStyles()
    val crossfade = LocalImageCrossfade.current
    val placeholder = tvPeoplePlaceholderColor()
    val starFull = rememberTvNativeIcon(Icons.Rounded.Star, COMMENT_STAR_SIZE, colors.primary)
    val starHalf = rememberTvNativeIcon(Icons.AutoMirrored.Rounded.StarHalf, COMMENT_STAR_SIZE, colors.primary)
    val starEmpty = rememberTvNativeIcon(Icons.Rounded.StarOutline, COMMENT_STAR_SIZE, colors.primary)
    return remember(density, colors, text, crossfade, placeholder, starFull, starHalf, starEmpty) {
        with(density) {
            TvNativeCommentStyle(
                columns = COMMENTS_COLUMNS,
                columnGapPx = COMMENTS_COLUMN_GAP.roundToPx(),
                sideInsetPx = COMMENTS_LIST_SIDE_INSET.roundToPx(),
                fadePx = ANI_SCROLL_FADE.roundToPx(),
                paddingHorizontalPx = COMMENT_ITEM_PADDING.roundToPx(),
                paddingVerticalPx = COMMENT_ITEM_VERTICAL_PADDING.roundToPx(),
                outerGapPx = COMMENT_ITEM_OUTER_GAP.roundToPx(),
                cornerPx = COMMENT_ITEM_CORNER.toPx(),
                ringWidthPx = COMMENT_ITEM_RING.roundToPx(),
                ringColor = colors.primary.toArgb(),
                focusedColor = colors.surfaceContainerHighest.copy(alpha = COMMENT_ITEM_FOCUSED_ALPHA).toArgb(),
                avatarPx = COMMENT_AVATAR_SIZE.roundToPx(),
                avatarGapPx = COMMENT_AVATAR_GAP.roundToPx(),
                avatarPlaceholderColor = placeholder.toArgb(),
                nameDateGapPx = COMMENT_NAME_DATE_GAP.roundToPx(),
                headerGapPx = COMMENT_HEADER_GAP.roundToPx(),
                name = text.name.toTvNativeTextStyle(density, colors.onSurface),
                date = text.date.toTvNativeTextStyle(density, colors.onSurfaceVariant),
                body = text.body.toTvNativeTextStyle(density, colors.onSurface),
                collapsedLines = COMMENT_COLLAPSED_MAX_LINES,
                starPx = COMMENT_STAR_SIZE.roundToPx(),
                starsGapPx = COMMENT_STARS_GAP.roundToPx(),
                starOverlapPx = 1.dp.roundToPx(),
                starFull = starFull,
                starHalf = starHalf,
                starEmpty = starEmpty,
                dividerColor = colors.outlineVariant.copy(alpha = COMMENTS_DIVIDER_ALPHA).toArgb(),
                dividerPx = 1.dp.roundToPx().coerceAtLeast(1),
                crossfade = crossfade,
            )
        }
    }
}
