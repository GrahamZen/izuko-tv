/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import me.him188.ani.app.data.models.subject.RatingInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItemsWithLifecycle
import me.him188.ani.app.tools.formatDateTime
import me.him188.ani.app.ui.comment.CommentReportState
import me.him188.ani.app.ui.comment.CommentState
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.comment.UIRichText
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.focus.restoreFocusAfter
import me.him188.ani.app.ui.foundation.layout.desktopTitleBar
import me.him188.ani.app.ui.foundation.layout.desktopTitleBarPadding
import me.him188.ani.app.ui.foundation.layout.rememberConnectedScrollState
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_details_hot_reviews
import me.him188.ani.app.ui.lang.subject_details_reviews_count
import me.him188.ani.app.ui.lang.subject_details_tab_comments
import me.him188.ani.app.ui.lang.subject_details_view_all
import me.him188.ani.app.ui.lang.subject_details_write_review
import me.him188.ani.app.ui.rating.FiveRatingStars
import me.him188.ani.app.ui.richtext.UIRichElement
import me.him188.ani.app.ui.subject.details.components.SubjectCommentColumn
import me.him188.ani.app.ui.subject.details.components.SubjectDetailsDefaults
import org.jetbrains.compose.resources.stringResource

/** 预览条数 (对齐定稿: 双栏"评价"与三栏"热门评价"均展示 2 条). */
private const val PREVIEW_COMMENT_COUNT = 2

/**
 * 双栏中栏末尾的"评价"预览 (对齐定稿 1505:335 底部): 标题行 + 2 张并排评论卡, "查看全部"打开完整评论流.
 * 中栏过窄 (窄双栏) 时两卡改为上下堆叠.
 */
@Composable
fun ReviewsPreviewSection(
    comments: LazyPagingItems<UIComment>,
    totalCount: Int?,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (comments.itemCount == 0) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            stringResource(Lang.subject_details_tab_comments),
            actionLabel = reviewsCountLabel(totalCount),
            onAction = onShowAll,
        )
        BoxWithConstraints {
            val count = minOf(comments.itemCount, PREVIEW_COMMENT_COUNT)
            if (maxWidth < STACK_REVIEW_CARDS_BELOW_WIDTH) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(count) { i ->
                        comments[i]?.let {
                            ReviewPreviewCard(it, onShowAll, Modifier.fillMaxWidth())
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(count) { i ->
                        comments[i]?.let {
                            ReviewPreviewCard(it, onShowAll, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private val STACK_REVIEW_CARDS_BELOW_WIDTH = 480.dp

/**
 * TV 的评价块: 几条可聚焦的评论摘要 + 行末一个带计数的"查看全部".
 *
 * 上一版是 3×3 九张卡, 九张的确认键**全都是同一个 `onShowAll`** —— 九个落点一个结果, 规范上也站不住
 * (WCAG H2 "相邻的冗余链接应合并", ARIA "复合控件在焦点序列里只占一个停靠点"). 于是改成整块只有行末一个落点.
 *
 * 但真机上一排卡里只有最右一张能聚焦很别扭 (用户 2026-09-15), 而"九个落点一个结果"的病根其实是**结果相同**,
 * 不是落点多: 现在每张卡进的是全量弹窗里**它自己那一条** ([onShowAll] 带序号), 落点与结果一一对应, 冗余链接
 * 的前提不成立. 形态仍照 Plex 的评论行 (卡不可写, 写评价交给手机).
 */
@Composable
fun ReviewsSummarySection(
    comments: LazyPagingItems<UIComment>,
    totalCount: Int?,
    /** 打开全量评论弹窗, 参数是要落在的那一条的序号 (行末"查看全部"给 0). */
    onShowAll: (initialFocusIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 摘要条数上限; 行末另有一个"+N"入口. 实际张数还受可用宽度限制 (见下). */
    previewCount: Int = TV_REVIEW_PREVIEW_COUNT,
    /**
     * 卡高; **null = 由内容撑开**.
     *
     * 独立页第四页整页就这一个区块, 定高 [TV_REVIEW_CARD_HEIGHT] 把版面占满; 播放器内嵌介绍页的
     * 评价是贴在页底的最后一块, 上面还压着标签墙, 定高会把标签挡掉 (用户 2026-09-17) —— 那里让
     * 卡按内容收, 高度还给标签墙.
     */
    cardHeight: Dp? = TV_REVIEW_CARD_HEIGHT,
    /** 正文行数上限. 卡矮了行数要跟着降, 否则文字撑破卡片. */
    cardTextLines: Int = TV_REVIEW_CARD_TEXT_LINES,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TV_REVIEW_HEADER_GAP)) {
        // **不放评分汇总**: hero 的信息带上已经有分数了 (用户 2026-09-15 指出重复)
        SectionHeader(stringResource(Lang.subject_details_tab_comments))
        BoxWithConstraints {
            // **卡的尺寸两边一样, 变的是张数**: 独立页第四页是整屏宽 (放得下 3 张), 播放器内嵌介绍页的
            // 评价在右列 (左边还占着一列海报/评分), 只放得下 2 张. 按窄的那边把卡改小的话两个页面的评价
            // 就长得不一样了 —— 而这个区块本来就是两边共用的 (用户 2026-09-17).
            // 一张卡占「宽 + 其后的间距」, 行末那格另算.
            val fit = (maxWidth - TV_REVIEW_MORE_WIDTH) / (TV_REVIEW_CARD_WIDTH + TV_REVIEW_CARD_SPACING)
            val count = minOf(comments.itemCount, previewCount, fit.toInt().coerceAtLeast(1))
            // 不定高时用 IntrinsicSize.Min 把一排卡拉成等高 (= 最高那张的内容高): 行末"还有 N 条"
            // 只有两行字, 不跟着长就会短一截, 一排卡参差不齐
            val cardModifier = if (cardHeight != null) {
                Modifier.size(TV_REVIEW_CARD_WIDTH, cardHeight)
            } else {
                Modifier.width(TV_REVIEW_CARD_WIDTH).fillMaxHeight()
            }
            val moreModifier = if (cardHeight != null) {
                Modifier.size(TV_REVIEW_MORE_WIDTH, cardHeight)
            } else {
                Modifier.width(TV_REVIEW_MORE_WIDTH).fillMaxHeight()
            }
            Row(
                if (cardHeight == null) Modifier.height(IntrinsicSize.Min) else Modifier,
                horizontalArrangement = Arrangement.spacedBy(TV_REVIEW_CARD_SPACING),
            ) {
                repeat(count) { i ->
                    comments[i]?.let { comment ->
                        // 每张卡进的是全量弹窗里它自己那一条 (见本函数 KDoc)
                        ReviewPreviewCard(
                            comment,
                            onClick = { onShowAll(i) },
                            modifier = cardModifier,
                            maxTextLines = cardTextLines,
                            tvCard = true,
                        )
                    }
                }
                // 行末的"还有 N 条": 与行内卡同高同形, 确认键进弹窗的第一条
                ReviewsMoreCell(
                    remaining = totalCount?.minus(count),
                    onClick = { onShowAll(0) },
                    modifier = moreModifier,
                )
            }
        }
    }
}

/**
 * 评价行末尾的"还有 N 条"格: 与行内评论卡**同高同形**的一张卡, 里面是大号 "+N" 与"查看全部".
 *
 * 行内是矩形卡, 行末就该是矩形卡 —— 先前做成圆形, 混在一排方卡里很突兀 (用户 2026-09-15 指出).
 * 演职人员行用圆是因为那一行本来就是圆 (Monogram), 同一个"行末入口"的形状要跟着各自的行走.
 *
 * 行的排版取自 Plex 的评论行 (2026-09-15 真机实测, dp): 标题 24 高, 卡 218×148、步距 242 (间距 24),
 * 卡内 36dp 头像 + 姓名 18 / 来源 16, 下方 3 行正文.
 */
@Composable
private fun ReviewsMoreCell(
    remaining: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus },
        shape = MaterialTheme.shapes.medium,
        // 与同排的评论卡同一套底色与示焦 (见 ReviewPreviewCard): 一排卡里只有它换个颜色很跳
        color = if (focused) {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = TV_CARD_CONTAINER_FOCUSED_ALPHA)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = TV_CARD_CONTAINER_ALPHA)
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            if (remaining != null && remaining > 0) {
                Text(
                    "+$remaining",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Text(
                stringResource(Lang.subject_details_view_all),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 评价块的排版, 取自 Plex 评论行的实测值 (见 [ReviewsMoreCell]).
 *
 * **卡高比 Plex 的 148dp 矮**: Plex 那一页下面没有别的区块, 我们这一页还要装关联条目 ——
 * 148dp 时整页 24(页顶) + 24(标题) + 28 + 148 + 24 + 296(关联条目) = 544dp, 超出 540dp 的视口,
 * 关联条目底下那行标签被切掉 (用户 2026-09-15: "评价的卡片太高了, 把关联条目挤出一页").
 * 124dp 时总高 520dp, 留 20dp 余量; 正文相应从 5 行减到 4 行.
 */
private const val TV_REVIEW_PREVIEW_COUNT = 3
private const val TV_REVIEW_CARD_TEXT_LINES = 4
private val TV_REVIEW_CARD_LINE_HEIGHT = 14.sp
private val TV_REVIEW_CARD_WIDTH = 218.dp
private val TV_REVIEW_CARD_HEIGHT = 124.dp
private val TV_REVIEW_CARD_SPACING = 24.dp
/**
 * 评价标题到卡片行的间距.
 *
 * "卡片别探出上一页"这件事由末页前置间距 (TV_LAST_PAGE_LEAD_GAP) 负责, 不靠这里撑 —— 早先没有前置
 * 间距时曾把它加到 28dp 去挤, 现在两者重复, 退回常规值把高度还给页面.
 *
 * 末页评价块到关联条目标题之间**也用这个值** (调用方读走), 否则上下两个间距一个 16 一个 24 (区块间距),
 * 卡片看着偏上 (用户 2026-09-15). 顺带把关联条目整体上提 8dp, 这一页的余量也宽裕一点.
 */
val TV_REVIEW_HEADER_GAP = 16.dp
/** 行末"还有 N 条"那一格的宽度: 比评论卡窄一些, 但**同高同形** —— 圆形格混在一排方卡里很突兀 (用户 2026-09-15). */
private val TV_REVIEW_MORE_WIDTH = 140.dp


@Composable
private fun ReviewPreviewCard(
    comment: UIComment,
    /** null = 纯展示, 不可点也不可聚焦. */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** 正文行数上限: 预览行里一行, 评价块的大卡多几行. */
    maxTextLines: Int = 1,
    /** TV 详情页评价块的大卡: 两行头部 + 半透明底 + 聚焦泛白高亮 (与全量弹窗里的评论卡同一套示焦). */
    tvCard: Boolean = false,
) {
    val shape = MaterialTheme.shapes.medium
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val color = when {
        // TV 的评价卡浮在 backdrop 大图上, 不透明底色就是一块黑砖 (用户 2026-09-15: "评论太黑了").
        // 用与详情页其它 TV 卡片 (查看全部弹窗 / 骨架) 同一套两档半透明值, 背景图能透出来
        tvCard -> if (focused) {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = TV_CARD_CONTAINER_FOCUSED_ALPHA)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = TV_CARD_CONTAINER_ALPHA)
        }
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = MaterialTheme.colorScheme.onSurface
    val body: @Composable () -> Unit = {
        ReviewPreviewItem(
            comment,
            Modifier.padding(12.dp),
            showTime = true,
            maxTextLines = maxTextLines,
            // 两行头部只给 TV 的窄卡用, 手机端维持单行
            stackedHeader = tvCard,
        )
    }
    if (onClick == null) {
        Surface(modifier, shape, color, contentColor, content = body)
    } else {
        Surface(
            onClick, modifier, shape = shape, color = color, contentColor = contentColor,
            border = if (tvCard && focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            interactionSource = interactionSource,
            content = body,
        )
    }
}

/**
 * 三栏右栏"热门评价"卡内容 (对齐定稿 1515:336): 标题行 (计数入口) + 2 条紧凑评论, 分隔线间隔.
 */
@Composable
fun HotReviewsCardContent(
    comments: LazyPagingItems<UIComment>,
    totalCount: Int?,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            stringResource(Lang.subject_details_hot_reviews),
            actionLabel = reviewsCountLabel(totalCount),
            onAction = onShowAll,
        )
        repeat(minOf(comments.itemCount, PREVIEW_COMMENT_COUNT)) { i ->
            val comment = comments[i] ?: return@repeat
            if (i > 0) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
            } else {
                Spacer(Modifier.size(8.dp))
            }
            ReviewPreviewItem(
                comment,
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onShowAll),
                maxTextLines = 2,
            )
        }
    }
}

/** 全量评论入口文案: 总数已知时 `1,204 条`, 未知 (加载中) 时回退 `查看全部`, 保证入口始终存在. */
@Composable
private fun reviewsCountLabel(totalCount: Int?): String =
    totalCount?.takeIf { it > 0 }
        ?.let { stringResource(Lang.subject_details_reviews_count, remember(it) { groupThousands(it) }) }
        ?: stringResource(Lang.subject_details_view_all)

/**
 * 单条评论预览: `头像 名字 (时间) ★★★★☆` + 正文摘要 (纯文本, 截断).
 */
@Composable
private fun ReviewPreviewItem(
    comment: UIComment,
    modifier: Modifier = Modifier,
    showTime: Boolean = false,
    maxTextLines: Int = 2,
    /**
     * 头部排成两行 (头像 | 昵称 / 时间·星级) 而不是挤在一行.
     *
     * TV 的评论卡只有 218dp 宽, 一行里塞下「头像 + 昵称 + 时间 + 五颗星」之后昵称只剩两三个字,
     * 实测被截成"用…""伊…" (2026-09-15 真机). Plex 的评论卡正是两行头部: 36dp 图标 + 右侧
     * 姓名 / 来源两行, 姓名因此能占满整个右半宽.
     */
    stackedHeader: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val name = comment.author?.nickname ?: comment.author?.id?.toString() ?: ""
        val time: (@Composable () -> Unit)? = if (showTime) {
            {
                Text(
                    formatDateTime(comment.createdAt, showTime = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        } else null
        val stars: (@Composable () -> Unit)? = comment.rating?.takeIf { it > 0 }?.let { rating ->
            { FiveRatingStars(rating, starSize = 12.dp) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AvatarImage(
                comment.author?.avatarUrl,
                Modifier.size(if (stackedHeader) 36.dp else 24.dp).clip(CircleShape),
            )
            if (stackedHeader) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        time?.invoke()
                        stars?.invoke()
                    }
                }
            } else {
                Text(
                    name,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                time?.invoke()
                stars?.invoke()
            }
        }
        val body = remember(comment) { comment.content.toPlainText() }
        // 正文为空的评论 (只打了分没写字) 不渲染空行, 否则卡里是一整片空白
        if (body.isNotBlank()) {
            Text(
                body,
                // 行距压到 14sp (bodySmall 默认 16): 实测 Plex 的评论卡正文正是 11dp 墨高 / 14dp 行距,
                // 靠这个在同样 148dp 高的卡里塞下 5 行 —— 我们原先 3 行 @16dp, 卡里显得空
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = TV_REVIEW_CARD_LINE_HEIGHT),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxTextLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 提取富文本中的纯文本用于单行/两行预览; 图片/贴纸/引用跳过. */
internal fun UIRichText.toPlainText(): String =
    elements.asSequence()
        .filterIsInstance<UIRichElement.AnnotatedText>()
        .flatMap { it.slice }
        .filterIsInstance<UIRichElement.Annotated.Text>()
        .joinToString("") { it.content }
        .trim()

/**
 * 完整评论流 sheet: 桌面 (双栏/三栏) 没有"评价" tab, 从评价预览/热门评价卡进入.
 * 复用手机"评价" tab 的 [SubjectCommentColumn], 头部提供"写评价"入口.
 *
 * TV 上改为大号居中弹窗 (可导航的纯文本评论卡片网格, 确认键展开全文, 返回键关闭);
 * 保留"写评价"入口.
 */
@Composable
fun SubjectCommentsSheet(
    state: CommentState,
    onClickUrl: (String) -> Unit,
    onClickImage: (String) -> Unit,
    onClickWriteReview: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    reportState: CommentReportState? = null,
    onOpenOriginal: ((UIComment) -> Unit)? = null,
    /** 评分弹窗是否开着: 它盖在本 sheet 上面, 关掉后要把焦点还给"写评价"按钮 (仅遥控器形态). */
    ratingDialogVisible: Boolean = false,
    /** 遥控器形态打开时落在第几条: 从详情页某张评论卡进来时就是那一条 (见 [ReviewsSummarySection]). */
    initialFocusIndex: Int = 0,
) {
    if (LocalAniUiBehavior.current.panelsAsCenteredDialogs) {
        val gridComments = state.list.collectAsLazyPagingItemsWithLifecycle()
        CommentsGridDialog(
            title = stringResource(Lang.subject_details_tab_comments) +
                    (state.count?.takeIf { it > 0 }
                        ?.let { " · " + remember(it) { groupThousands(it) } } ?: ""),
            comments = gridComments,
            onDismissRequest = onDismissRequest,
            showRating = true,
            initialFocusIndex = initialFocusIndex,
            headerAction = {
                // 评分弹窗关掉之后焦点还回本按钮 (它自己是另一个弹窗窗口里的元素,
                // 上面那层关掉时不保证把焦点还回来, 遥控器会当场失去焦点)
                TextButton(onClickWriteReview, Modifier.restoreFocusAfter(ratingDialogVisible)) {
                    Icon(Icons.Rounded.AddComment, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        stringResource(Lang.subject_details_write_review),
                        Modifier.padding(start = 8.dp),
                    )
                }
            },
        )
        return
    }
    ModalBottomSheet(
        onDismissRequest,
        modifier = modifier.desktopTitleBarPadding().statusBarsPadding(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = {
            BottomSheetDefaults.windowInsets
                .add(WindowInsets.desktopTitleBar())
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Lang.subject_details_tab_comments),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClickWriteReview) {
                    Icon(Icons.Rounded.AddComment, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        stringResource(Lang.subject_details_write_review),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
            SubjectDetailsDefaults.SubjectCommentColumn(
                state = state,
                onClickUrl = onClickUrl,
                onClickImage = onClickImage,
                reportState = reportState,
                onOpenOriginal = onOpenOriginal,
                connectedScrollState = rememberConnectedScrollState(),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
