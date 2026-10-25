/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import me.him188.ani.app.tools.LocalTimeFormatter
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.foundation.LocalImageCrossfade
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_NATIVE_TEXT_ARG
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeComment
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentList
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentListController
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCommentStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTextStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.rememberTvNativeIcon
import me.him188.ani.app.ui.foundation.tv.nativeview.toTvNativeTextStyle
import me.him188.ani.app.ui.foundation.widgets.ANI_SCROLL_FADE
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.comment_reply_to
import me.him188.ani.app.ui.lang.tv_comment_replies_total
import me.him188.ani.app.ui.lang.video_player_tv_comment_deleted
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
import me.him188.ani.app.ui.subject.details.sections.LocalViewAllImageZoom
import me.him188.ani.app.ui.subject.details.sections.ViewAllBackHandler
import me.him188.ani.app.ui.subject.details.sections.ViewAllCommentsList
import me.him188.ani.app.ui.subject.details.sections.commentListTextStyles
import me.him188.ani.app.ui.subject.details.sections.tvPeoplePlaceholderColor
import me.him188.ani.app.ui.subject.episode.tv.TV_COMMENT_REPLY_INDENT
import me.him188.ani.app.ui.subject.episode.tv.TV_COMMENT_THREAD_LINE_COLOR
import me.him188.ani.app.ui.subject.episode.tv.TV_COMMENT_THREAD_LINE_INSET
import me.him188.ani.app.ui.subject.episode.tv.TV_COMMENT_THREAD_LINE_WIDTH
import me.him188.ani.app.ui.subject.episode.tv.TV_PANEL_ITEM_COLOR
import me.him188.ani.app.ui.subject.episode.tv.TV_PANEL_ITEM_FOCUSED_COLOR
import me.him188.ani.app.ui.subject.episode.tv.TvCommentThreadContent
import me.him188.ani.app.ui.subject.episode.tv.toTvNativeComment
import me.him188.ani.app.ui.subject.episode.tv.toTvQuotedComment
import me.him188.ani.app.ui.subject.episode.tv.toTvThreadReplies
import me.him188.ani.app.ui.subject.episode.tv.tvThemeCommentThreadColors
import org.jetbrains.compose.resources.stringResource

/**
 * 「查看全部」评论 ([ViewAllCommentsList]) 的电视版: 电视包 (电视与安卓平板) 的入口提供. 列表是原生的 ([TvNativeCommentList]), 遥控器长按连翻时条目复用、不重组;
 * 一楼带着前几条回复, 点一条在原地换成那一整楼 ([TvCommentThreadContent]; 条目评价没有回复, 就是那一条的全文), 返回回到列表里那一条;
 * 楼里左右键翻相邻的楼.
 * 列数按给的宽度定: 宽弹窗两列瀑布流, 窄栏 (播放器叠层里的人物页) 一列.
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
        // 点开的那一楼; -1 = 列表
        var threadAt by remember { mutableIntStateOf(-1) }
        // 楼已经拿到了焦点: 这之后底下的列表才不可聚焦 (持焦的节点当场变不可聚焦, 整棵树的焦点会被清掉)
        var threadFocused by remember { mutableStateOf(false) }
        // 正在退回列表: 先把焦点交回列表那一条, 下一帧再收起楼 (楼持着焦点直接拿掉, 整棵树就没有焦点了)
        var returning by remember { mutableStateOf(false) }
        val list = remember { TvNativeCommentListController().also { it.entryIndex = initialFocusIndex } }
        val listFocus = remember { FocusRequester() }
        val threadOpen = threadAt >= 0
        ViewAllBackHandler(enabled = threadOpen && !returning) {
            list.entryIndex = threadAt
            returning = true
        }
        LaunchedEffect(returning) {
            if (!returning) return@LaunchedEffect
            // 列表这一帧才重新可聚焦
            withFrameNanos { }
            runCatching { listFocus.requestFocus() }
            threadAt = -1
            threadFocused = false
            returning = false
        }
        // 楼开着时列表不拆, 藏在楼底下: 退回来还停在原来滚到的地方, 不用重排一遍再滚过去 (那样靠下的评论退回来会跳一下)
        Box(modifier.fillMaxSize()) {
            TvCommentsListPane(
                comments,
                showRating,
                list = list,
                focusRequester = listFocus,
                hidden = threadOpen,
                focusBlocked = threadFocused && !returning,
                initialFocus = initialFocus,
                onClick = {
                    list.entryIndex = it
                    threadAt = it
                },
            )
            if (threadOpen) {
                TvCommentThreadPane(
                    comments,
                    threadAt,
                    showRating,
                    onIndexChange = {
                        threadAt = it
                        // 底下藏着的列表跟着走到这一楼, 退回来时它已经在那儿
                        list.reveal(it)
                    },
                    Modifier
                        .matchParentSize()
                        // 盖住底下藏着的列表: 触屏的点按不落到看不见的那些条目上
                        .pointerInput(Unit) {}
                        .onFocusChanged { if (it.hasFocus) threadFocused = true },
                )
            }
        }
    }

    override val hasThread: Boolean get() = true

    @Composable
    override fun Thread(
        comments: LazyPagingItems<UIComment>,
        index: Int,
        showRating: Boolean,
        initialFocus: Modifier,
        modifier: Modifier,
    ) {
        var at by remember(index) { mutableIntStateOf(index) }
        TvCommentThreadPane(comments, at, showRating, onIndexChange = { at = it }, modifier.then(initialFocus))
    }
}

/**
 * 列表: 宽到能放两列时两列瀑布流, 否则一列. 焦点进来落在 [list] 的 entryIndex (打开时那一条, 之后是上次停的那一条 / 退回来的那一楼).
 * [hidden] = 楼盖在上面 (不画), [focusBlocked] = 不让焦点进来 (楼拿到焦点之后).
 */
@Composable
private fun BoxScope.TvCommentsListPane(
    comments: LazyPagingItems<UIComment>,
    showRating: Boolean,
    list: TvNativeCommentListController,
    focusRequester: FocusRequester,
    hidden: Boolean,
    focusBlocked: Boolean,
    initialFocus: Modifier,
    onClick: (Int) -> Unit,
) {
    // 安卓视图那层也挡住 (见 TvNativeCommentListController.focusBlocked)
    SideEffect { list.focusBlocked = focusBlocked }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val columns = if (maxWidth >= TV_COMMENTS_TWO_COLUMN_MIN_WIDTH) COMMENTS_COLUMNS else 1
        TvNativeCommentList(
            items = rememberTvNativeComments(comments, showRating),
            style = rememberTvThemeCommentStyle(columns),
            initialFocusIndex = list.entryIndex,
            touchTaps = LocalTvTouchInputEnabled.current,
            onClick = onClick,
            onBind = { comments[it] },
            modifier = Modifier
                .graphicsLayer { alpha = if (hidden) 0f else 1f }
                .focusProperties { canFocus = !focusBlocked }
                .focusRequester(focusRequester)
                .then(initialFocus),
            controller = list,
            onFocusedPosition = { list.entryIndex = it },
        )
    }
}

/**
 * 一整楼 (同播放器里的评论详情, 配色跟主题): 主楼在顶上, 回复接在下面. 撑满弹窗给的高 —— 弹窗与列表时一样大, 打开、翻楼、退回列表都不变大小.
 * 那一条还没加载到 (分页) 时空着.
 */
@Composable
private fun TvCommentThreadPane(
    comments: LazyPagingItems<UIComment>,
    index: Int,
    showRating: Boolean,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier,
) {
    val comment = if (index < comments.itemCount) comments[index] else null
    val formatter = LocalTimeFormatter.current
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.TopStart) {
        comment ?: return@Box
        val root = remember(comment, formatter, showRating) { comment.toTvQuotedComment(formatter, showRating) }
        val replies = remember(comment, formatter) { comment.toTvThreadReplies(formatter) }
        TvCommentThreadContent(
            root = root,
            replies = replies,
            focusReply = -1,
            openKey = comment.stableId,
            imageZoom = LocalViewAllImageZoom.current,
            colors = tvThemeCommentThreadColors(),
            onNavigate = { delta ->
                val next = index + delta
                if (next in 0 until comments.itemCount && comments.peek(next) != null) onIndexChange(next)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = COMMENTS_LIST_SIDE_INSET),
            topPadding = TV_COMMENT_THREAD_TOP_PADDING,
        )
    }
}

/** 每条只转一次 (时间格式化、富文本压平); 按对象认, 分页插进来的、刷新换了新的才重转. */
@Composable
private fun rememberTvNativeComments(comments: LazyPagingItems<UIComment>, showRating: Boolean): List<TvNativeComment?> {
    val formatter = LocalTimeFormatter.current
    val converted = remember(formatter, showRating) { HashMap<UIComment, TvNativeComment>() }
    val snapshot = comments.itemSnapshotList
    return remember(snapshot, converted) {
        snapshot.map { comment -> comment?.let { converted.getOrPut(it) { it.toTvNativeComment(formatter, showRating) } } }
    }
}

/** 弹窗里的评论列表卡: 配色跟主题, 卡没有底 (聚焦才有), 条与条之间细线. */
@Composable
private fun rememberTvThemeCommentStyle(columns: Int): TvNativeCommentStyle {
    val colors = MaterialTheme.colorScheme
    val text = commentListTextStyles()
    val typography = MaterialTheme.typography
    return rememberTvNativeCommentStyle(
        columns = columns,
        reverseLayout = false,
        name = text.name,
        date = text.date,
        body = text.body,
        replyName = typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        replyMeta = typography.labelSmall,
        replyBody = typography.bodySmall,
        primaryText = colors.onSurface,
        secondaryText = colors.onSurfaceVariant,
        itemColor = Color.Transparent,
        focusedColor = colors.surfaceContainerHighest.copy(alpha = COMMENT_ITEM_FOCUSED_ALPHA),
        ringColor = colors.primary,
        starColor = colors.primary,
        threadLineColor = colors.outlineVariant,
        dividers = true,
        dividerColor = colors.outlineVariant.copy(alpha = COMMENTS_DIVIDER_ALPHA),
    )
}

/**
 * 播放器评论面板的卡 (压在画面上): 一列, 第一楼在最上面 (同胶囊上方的画廊, 胶囊上按确定进的就是它), 每张卡半透明黑底、聚焦更黑 + 白描边,
 * 白字 (同面板里别的条目); 卡与卡之间空 [TV_PLAYER_COMMENT_GAP], 不画线.
 */
@Composable
internal fun rememberTvPlayerCommentStyle(): TvNativeCommentStyle {
    val typography = MaterialTheme.typography
    return rememberTvNativeCommentStyle(
        columns = 1,
        reverseLayout = false,
        name = typography.labelLarge,
        date = typography.labelSmall,
        body = typography.bodyMedium,
        replyName = typography.labelMedium,
        replyMeta = typography.labelSmall,
        replyBody = typography.bodyMedium,
        primaryText = Color.White.copy(alpha = 0.9f),
        secondaryText = Color.White.copy(alpha = 0.6f),
        itemColor = TV_PANEL_ITEM_COLOR,
        focusedColor = TV_PANEL_ITEM_FOCUSED_COLOR,
        ringColor = Color.White,
        starColor = Color.White,
        threadLineColor = TV_COMMENT_THREAD_LINE_COLOR,
        dividers = false,
        dividerColor = Color.Transparent,
        collapsedLines = TV_PLAYER_COMMENT_COLLAPSED_LINES,
        outerGap = TV_PLAYER_COMMENT_GAP / 2,
        sideInset = 0.dp,
        paddingVertical = TV_PLAYER_COMMENT_PADDING_VERTICAL,
    )
}

@Composable
private fun rememberTvNativeCommentStyle(
    columns: Int,
    reverseLayout: Boolean,
    name: TextStyle,
    date: TextStyle,
    body: TextStyle,
    replyName: TextStyle,
    replyMeta: TextStyle,
    replyBody: TextStyle,
    primaryText: Color,
    secondaryText: Color,
    itemColor: Color,
    focusedColor: Color,
    ringColor: Color,
    starColor: Color,
    threadLineColor: Color,
    dividers: Boolean,
    dividerColor: Color,
    collapsedLines: Int = COMMENT_COLLAPSED_MAX_LINES,
    outerGap: Dp = COMMENT_ITEM_OUTER_GAP,
    sideInset: Dp = COMMENTS_LIST_SIDE_INSET,
    paddingVertical: Dp = COMMENT_ITEM_VERTICAL_PADDING,
): TvNativeCommentStyle {
    val density = LocalDensity.current
    val crossfade = LocalImageCrossfade.current
    val placeholder = tvPeoplePlaceholderColor()
    val starFull = rememberTvNativeIcon(Icons.Rounded.Star, COMMENT_STAR_SIZE, starColor)
    val starHalf = rememberTvNativeIcon(Icons.AutoMirrored.Rounded.StarHalf, COMMENT_STAR_SIZE, starColor)
    val starEmpty = rememberTvNativeIcon(Icons.Rounded.StarOutline, COMMENT_STAR_SIZE, starColor)
    val deletedText = stringResource(Lang.video_player_tv_comment_deleted)
    val replyToText = stringResource(Lang.comment_reply_to, TV_NATIVE_TEXT_ARG)
    val moreRepliesText = stringResource(Lang.tv_comment_replies_total, TV_NATIVE_TEXT_ARG)
    return remember(
        density, columns, reverseLayout, name, date, body, replyName, replyMeta, replyBody, primaryText, secondaryText, itemColor,
        focusedColor, ringColor, threadLineColor, dividers, dividerColor, collapsedLines, outerGap, sideInset, paddingVertical,
        crossfade, placeholder, starFull, starHalf, starEmpty, deletedText, replyToText, moreRepliesText,
    ) {
        with(density) {
            val bodyStyle = body.toTvNativeTextStyle(density, primaryText)
            val replyBodyStyle = replyBody.toTvNativeTextStyle(density, primaryText)
            val replyMetaStyle = replyMeta.toTvNativeTextStyle(density, secondaryText)
            TvNativeCommentStyle(
                columns = columns,
                columnGapPx = COMMENTS_COLUMN_GAP.roundToPx(),
                sideInsetPx = sideInset.roundToPx(),
                fadePx = ANI_SCROLL_FADE.roundToPx(),
                paddingHorizontalPx = COMMENT_ITEM_PADDING.roundToPx(),
                paddingVerticalPx = paddingVertical.roundToPx(),
                outerGapPx = outerGap.roundToPx(),
                cornerPx = COMMENT_ITEM_CORNER.toPx(),
                ringWidthPx = COMMENT_ITEM_RING.roundToPx(),
                ringColor = ringColor.toArgb(),
                itemColor = itemColor.toArgb(),
                focusedColor = focusedColor.toArgb(),
                avatarPx = COMMENT_AVATAR_SIZE.roundToPx(),
                avatarGapPx = COMMENT_AVATAR_GAP.roundToPx(),
                avatarPlaceholderColor = placeholder.toArgb(),
                nameDateGapPx = COMMENT_NAME_DATE_GAP.roundToPx(),
                headerGapPx = COMMENT_HEADER_GAP.roundToPx(),
                name = name.copy(fontWeight = FontWeight.SemiBold).toTvNativeTextStyle(density, primaryText),
                date = date.toTvNativeTextStyle(density, secondaryText),
                body = bodyStyle,
                secondaryColor = secondaryText.toArgb(),
                collapsedLines = collapsedLines,
                starPx = COMMENT_STAR_SIZE.roundToPx(),
                starsGapPx = COMMENT_STARS_GAP.roundToPx(),
                starOverlapPx = 1.dp.roundToPx(),
                starFull = starFull,
                starHalf = starHalf,
                starEmpty = starEmpty,
                dividers = dividers,
                dividerColor = dividerColor.toArgb(),
                dividerPx = 1.dp.roundToPx().coerceAtLeast(1),
                reverseLayout = reverseLayout,
                replyName = replyName.toTvNativeTextStyle(density, primaryText),
                replyMeta = replyMetaStyle,
                replyBody = replyBodyStyle,
                replyBodyLines = TV_COMMENT_PREVIEW_REPLY_LINES,
                replyGapPx = TV_COMMENT_REPLY_GAP.roundToPx(),
                replyIndentPx = TV_COMMENT_REPLY_INDENT.roundToPx(),
                threadLineInsetPx = TV_COMMENT_THREAD_LINE_INSET.roundToPx(),
                threadLineWidthPx = TV_COMMENT_THREAD_LINE_WIDTH.roundToPx().coerceAtLeast(1),
                threadLineColor = threadLineColor.toArgb(),
                stickerPx = stickerPx(bodyStyle),
                replyStickerPx = stickerPx(replyBodyStyle),
                // 回应那一行用小字, 表情照正文那么大 (一眼认得出是哪枚), 不超过行高
                reactionStickerPx = minOf(stickerPx(bodyStyle), replyMetaStyle.lineHeightPx.takeIf { it > 0 } ?: Int.MAX_VALUE),
                deletedText = deletedText,
                replyToText = replyToText,
                moreRepliesText = moreRepliesText,
                crossfade = crossfade,
            )
        }
    }
}

/** 行内表情的边长: 同 Compose 那侧 (字号的 1.3 倍), 但不超过行高 (原生文字的行高是定死的, 高出的那截会被裁掉). */
private fun stickerPx(style: TvNativeTextStyle): Int {
    val size = (style.sizePx * TV_COMMENT_STICKER_SCALE).toInt()
    return if (style.lineHeightPx > 0) minOf(size, style.lineHeightPx) else size
}

/** 给的宽度够这么宽才排两列. */
private val TV_COMMENTS_TWO_COLUMN_MIN_WIDTH = 600.dp

/** 弹窗里一整楼从顶上让出多少 (上面是标题行). */
private val TV_COMMENT_THREAD_TOP_PADDING = 8.dp

/** 列表卡里正文与第一条回复、回复与回复之间的空. */
private val TV_COMMENT_REPLY_GAP = 8.dp

/** 列表卡里一条回复的正文最多几行 (全文在整楼里). */
private const val TV_COMMENT_PREVIEW_REPLY_LINES = 2

/** 行内表情相对字号的倍率 (同 Compose 那侧的 TV_INLINE_STICKER_SCALE). */
private const val TV_COMMENT_STICKER_SCALE = 1.3f

/** 播放器评论面板: 卡与卡之间的空 (同面板别的条目), 卡的上下内边距, 正文最多几行. */
private val TV_PLAYER_COMMENT_GAP = 8.dp
private val TV_PLAYER_COMMENT_PADDING_VERTICAL = 10.dp
private const val TV_PLAYER_COMMENT_COLLAPSED_LINES = 4
