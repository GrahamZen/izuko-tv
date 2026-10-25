/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import me.him188.ani.app.tools.formatDateTime
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.ProvideRingOnlyFocus
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.rating.FiveRatingStars

/**
 * 评论弹窗 ([CommentsGridDialog]) 的内容: 两列瀑布流 (每列各排各的, 左右不按行对齐), 同一列里条与条之间一道细线, 左右比标题多缩进 [COMMENTS_LIST_SIDE_INSET].
 * 一条: 头像 + 昵称 / 日期竖排、评分星靠右 (与头像那一块竖直居中), 正文在下面, 折叠时最多 [COMMENT_COLLAPSED_MAX_LINES] 行, 点一下 (确认键) 展开 / 收起全文.
 * 富文本图片 / 贴纸略过 (与播放器评论面板一致).
 *
 * 遥控器: 整条可聚焦, 聚焦时画玻璃底 + 主题色描边; 打开时落在第 [initialFocusIndex] 条 ([initialFocus] 挂在那一条上).
 * 只搬焦点, 不动滚动位置 (原因见 [ViewAllGridDialog]): 那一条在屏外时由默认的 BringIntoView 做最小滚动.
 */
@Composable
internal fun CommentsList(
    comments: LazyPagingItems<UIComment>,
    showRating: Boolean,
    initialFocusIndex: Int,
    initialFocus: Modifier,
    modifier: Modifier = Modifier,
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = COMMENTS_DIVIDER_ALPHA)
    val gridState = rememberLazyStaggeredGridState()
    ProvideScrollFadeFocusMargin {
        LazyVerticalStaggeredGrid(
            StaggeredGridCells.Fixed(COMMENTS_COLUMNS),
            modifier.aniVerticalScrollFade({ gridState.canScrollBackward }, { gridState.canScrollForward }),
            state = gridState,
            contentPadding = PaddingValues(horizontal = COMMENTS_LIST_SIDE_INSET),
            horizontalArrangement = Arrangement.spacedBy(COMMENTS_COLUMN_GAP),
        ) {
            items(
                comments.itemCount,
                key = comments.itemKey(),
                contentType = comments.itemContentType(),
            ) { index ->
                comments[index]?.let { comment ->
                    Column {
                        // 每列的第一条上面不画线: 头几条依次排在各列顶上
                        if (index >= COMMENTS_COLUMNS) {
                            HorizontalDivider(Modifier.padding(horizontal = COMMENT_ITEM_PADDING), color = dividerColor)
                        }
                        CommentListItem(
                            comment,
                            showRating = showRating,
                            modifier = if (index == initialFocusIndex) initialFocus else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「查看全部」评论列表 ([CommentsGridDialog] 里那块) 的画法: 默认是 Compose 版 [CommentsList] (iPad 等用它); 电视包换成原生列表
 * (ui-tv 的 TvViewAllCommentsList), 遥控器长按连翻不卡, 一楼带着前几条回复, 点一条在原地换成那一整楼 ([Thread]; 条目评价没有回复, 就是那一条的全文).
 */
interface ViewAllCommentsList {
    @Composable
    fun Comments(
        comments: LazyPagingItems<UIComment>,
        showRating: Boolean,
        initialFocusIndex: Int,
        initialFocus: Modifier,
        modifier: Modifier,
    )

    /** 能单独打开一整楼 ([Thread]): 详情页 / 人物页的评论预览里点一条直接开那一楼; false 时预览照旧打开「查看全部」落在那一条. */
    val hasThread: Boolean get() = false

    /** [comments] 里第 [index] 条的一整楼 (主楼全文 + 全部回复), 左右键翻相邻的楼. 只在 [hasThread] 时调. */
    @Composable
    fun Thread(
        comments: LazyPagingItems<UIComment>,
        index: Int,
        showRating: Boolean,
        initialFocus: Modifier,
        modifier: Modifier,
    ) {
    }
}

/** [ViewAllCommentsList] 的 Compose 版. */
object ComposeViewAllCommentsList : ViewAllCommentsList {
    @Composable
    override fun Comments(
        comments: LazyPagingItems<UIComment>,
        showRating: Boolean,
        initialFocusIndex: Int,
        initialFocus: Modifier,
        modifier: Modifier,
    ) = CommentsList(comments, showRating, initialFocusIndex, initialFocus, modifier)
}

val LocalViewAllCommentsList = staticCompositionLocalOf<ViewAllCommentsList> { ComposeViewAllCommentsList }

/** 列表里显示的正文: 纯文本, 富文本里的图片、贴纸略过. */
fun UIComment.plainContent(): String = content.toPlainText()

/** 一条评论, 排法见 [CommentsList]. */
@Composable
private fun CommentListItem(
    comment: UIComment,
    showRating: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // 触屏上没有聚焦效果
    val focusLook = focused && !LocalTvTouchInputEnabled.current
    val text = commentListTextStyles()
    ProvideRingOnlyFocus {
        Surface(
            onClick = { expanded = !expanded },
            modifier = modifier.fillMaxWidth().padding(vertical = COMMENT_ITEM_OUTER_GAP).testTag(COMMENT_LIST_ITEM_TAG),
            shape = RoundedCornerShape(COMMENT_ITEM_CORNER),
            color = if (focusLook) {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = COMMENT_ITEM_FOCUSED_ALPHA)
            } else {
                Color.Transparent
            },
            border = if (focusLook) BorderStroke(COMMENT_ITEM_RING, MaterialTheme.colorScheme.primary) else null,
            interactionSource = interactionSource,
        ) {
            Column(
                Modifier.padding(horizontal = COMMENT_ITEM_PADDING, vertical = COMMENT_ITEM_VERTICAL_PADDING),
                verticalArrangement = Arrangement.spacedBy(COMMENT_HEADER_GAP),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(
                        comment.author?.avatarUrl,
                        Modifier.size(COMMENT_AVATAR_SIZE).clip(CircleShape),
                    )
                    Column(
                        Modifier.weight(1f).padding(start = COMMENT_AVATAR_GAP),
                        verticalArrangement = Arrangement.spacedBy(COMMENT_NAME_DATE_GAP),
                    ) {
                        Text(
                            comment.author?.nickname ?: comment.author?.id?.toString() ?: "",
                            style = text.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatDateTime(comment.createdAt, showTime = false),
                            style = text.date,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (showRating) {
                        comment.rating?.takeIf { it > 0 }?.let { rating ->
                            FiveRatingStars(rating, starSize = COMMENT_STAR_SIZE, modifier = Modifier.padding(start = COMMENT_STARS_GAP))
                        }
                    }
                }
                Text(
                    remember(comment) { comment.content.toPlainText() },
                    style = text.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else COMMENT_COLLAPSED_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 一条评论里昵称、日期、正文的字 (Compose 版与电视原生版共用). */
@Immutable
data class CommentListTextStyles(val name: TextStyle, val date: TextStyle, val body: TextStyle)

@Composable
fun commentListTextStyles(): CommentListTextStyles {
    val typography = MaterialTheme.typography
    return remember(typography) {
        CommentListTextStyles(
            name = typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            date = typography.labelSmall,
            body = typography.bodyMedium.copy(lineHeight = 19.sp),
        )
    }
}

// 下面的尺寸 Compose 版与电视原生版共用

/** 几列, 列与列之间空多宽. */
const val COMMENTS_COLUMNS = 2
val COMMENTS_COLUMN_GAP = 24.dp

/** 列表左右比标题行多缩进多少. */
val COMMENTS_LIST_SIDE_INSET = 12.dp

/** 一条的左右 / 上下内边距 (聚焦底板到字), 条与条之间细线两侧各留的上下距离. 都收得紧: 电视面板矮, 一屏要放得下四五条. */
val COMMENT_ITEM_PADDING = 12.dp
val COMMENT_ITEM_VERTICAL_PADDING = 8.dp
val COMMENT_ITEM_OUTER_GAP = 2.dp

/** 聚焦底板的圆角、描边宽、底色 (surfaceContainerHighest) 的不透明度. */
val COMMENT_ITEM_CORNER = 12.dp
val COMMENT_ITEM_RING = 2.dp
const val COMMENT_ITEM_FOCUSED_ALPHA = TV_CARD_CONTAINER_FOCUSED_ALPHA

/** 头像边长、头像到昵称、昵称到日期、头像那一行到正文、评分星的大小与离昵称那一列的距离. */
val COMMENT_AVATAR_SIZE = 28.dp
val COMMENT_AVATAR_GAP = 10.dp
val COMMENT_NAME_DATE_GAP = 1.dp
val COMMENT_HEADER_GAP = 6.dp
val COMMENT_STAR_SIZE = 14.dp
val COMMENT_STARS_GAP = 12.dp

/** 正文折叠时最多几行 (点一下展开全文). */
const val COMMENT_COLLAPSED_MAX_LINES = 3

const val COMMENTS_DIVIDER_ALPHA = 0.6f

internal const val COMMENT_LIST_ITEM_TAG = "comment-list-item"
