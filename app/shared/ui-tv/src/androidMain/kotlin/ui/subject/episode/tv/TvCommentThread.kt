/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tv.LocalTvReadableBlocks
import me.him188.ani.app.ui.foundation.tv.TvImageZoomState
import me.him188.ani.app.ui.foundation.tv.tvImageZoomKeys
import me.him188.ani.app.ui.foundation.tv.tvReadableBlock
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.comment_reply_to
import me.him188.ani.app.ui.lang.video_player_tv_comment_deleted
import me.him188.ani.app.ui.lang.video_player_tv_comment_image_zoom_hint
import me.him188.ani.app.ui.rating.FiveRatingStars
import me.him188.ani.app.ui.subject.details.sections.COMMENT_ITEM_FOCUSED_ALPHA
import org.jetbrains.compose.resources.stringResource

/** 一楼里的一条回复 (见 [TvCommentReplyTarget.replies]). */
@Immutable
class TvThreadReply(
    val comment: TvQuotedComment,
    /** 非空 = 回复的是同层某条回复 (要写明回复谁); null = 直接回复主楼. */
    val replyToName: String?,
)

/**
 * 播放器里只读的评论详情: 一整楼 ([TvCommentThreadContent]) 摆在胶囊上方从屏幕顶铺到胶囊行的一栏, 半透明黑底 (字多).
 * 最下面再按下停住 (不回胶囊: 往下翻长楼时一路按着, 不能翻到底就掉回胶囊); 返回关掉 (根路由).
 * 图在那一块上长按确认键全屏放大 (大图由控制层铺满整屏画, 见 TvPlayerControlsOverlay).
 */
@Composable
internal fun TvCommentThreadView(
    target: TvCommentReplyTarget,
    onNavigate: ((delta: Int) -> Unit)?,
    imageZoom: TvImageZoomState?,
    modifier: Modifier = Modifier,
) {
    val root = target.quoted ?: return
    TvPlayerLayerPanel(
        modifier = modifier,
        // 上下不留: 滚动的那一列自己从顶上让出渐隐那一截再排, 滚不完时在上下边缘渐隐
        contentPadding = PaddingValues(horizontal = TV_THREAD_SIDE_PADDING),
        lockDown = true,
    ) {
        TvCommentThreadContent(
            root = root,
            replies = target.replies,
            focusReply = target.focusReply,
            openKey = target,
            imageZoom = imageZoom,
            colors = TvPlayerCommentThreadColors,
            onNavigate = onNavigate,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 一整楼 (参考 YouTube 电视版的单条评论页), 播放器的评论详情与「查看全部」/ 评论预览点开的那一楼都是它, 外壳与配色 ([colors]) 由调用方给:
 * - 最上面是主楼, 不套卡: 头像 + 名字 (签名灰字) + 楼层与时间 (+ 评分星), 下面全文 (表情、图), 再下面是回应; 主楼整块可停焦点,
 *   比看得见的长就在里面翻页 (见 tvReadableBlock), 有图时长按确认放大看;
 * - 下面一条回复一张卡 (比底亮一档, 聚焦加描边), 左边一道竖线串起来 (同列表卡里楼下的回复), 回复同层某条回复时多一行「回复 @某人」.
 *
 * 焦点: 打开时落在主楼 ([focusReply] 是一条回复的下标就落在那条回复上); 上下在主楼与各条回复之间走, 整列跟着滚; 左右翻相邻的楼
 * ([onNavigate], 调用方换内容并换 [openKey]). 主楼与回复里的图: 长按确认键交给 [imageZoom] 放大, 从正文翻到的那张看起, 不止一张时左右换.
 */
@Composable
internal fun TvCommentThreadContent(
    root: TvQuotedComment,
    replies: List<TvThreadReply>,
    focusReply: Int,
    /** 换了这个就是打开了另一楼: 焦点回到落点、滚回顶上. */
    openKey: Any,
    imageZoom: TvImageZoomState?,
    colors: TvCommentThreadColors,
    onNavigate: ((delta: Int) -> Unit)?,
    modifier: Modifier = Modifier,
    topPadding: Dp = TV_THREAD_TOP_PADDING,
) {
    val focus = rememberTvFocusScope()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    // 看得见的范围的上沿 (窗口坐标): 长按放大时从还没翻过去的第一张图看起
    val viewportTop = remember { FloatArray(1) }
    // 关掉 (或整个收起) 时大图跟着收, 下次打开不带着它
    DisposableEffect(imageZoom) {
        onDispose { imageZoom?.close() }
    }
    // 打开 / 左右翻到别的楼: 焦点落在主楼 (回到顶上), 点的是回复就落在那条回复上
    LaunchedEffect(openKey) {
        if (focusReply in replies.indices) {
            focus.request(TvThreadFocus.Reply(focusReply))
        } else {
            scrollState.scrollTo(0)
            focus.request(TvThreadFocus.Root)
        }
    }
    Box(
        modifier
            // 上下到头就停住: 往下翻长楼时一路按着, 翻到底不能掉到楼外面去 (弹窗里外面没有能接焦点的, 焦点就丢了)
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
                }
            }
            .focusGroup()
            // 上下键在楼里自己挪 (块里翻页的那一层先处理过了): 楼里没有下一块时 Compose 不收这一下, 系统就拿它在整个窗口里按原生视图
            // 找下一个焦点 —— 绕过 Compose 这边的到边规则, 落到楼外面的原生视图上 (「查看全部」里藏在楼底下的列表、播放器里的面板), 焦点就丢了
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                true
            }
            .tvFocusNavSignal(focus)
            // 大图开着时左右键换图、其余按键一概吞掉 (返回键由外壳处理)
            .ifThen(imageZoom != null) { tvImageZoomKeys(imageZoom!!) }
            // 左右键翻相邻的楼: 这一列里没有左右的去处
            .onPreviewKeyEvent { event ->
                val delta = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onNavigate?.invoke(delta)
                true
            },
    ) {
        ProvideScrollFadeFocusMargin {
            CompositionLocalProvider(LocalTvReadableBlocks provides true) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { viewportTop[0] = it.boundsInWindow().top }
                        .aniVerticalScrollFade(scrollState)
                        .verticalScroll(scrollState)
                        .padding(top = topPadding, bottom = TV_THREAD_BOTTOM_PADDING),
                ) {
                    val viewportTopNow = { viewportTop[0] }
                    TvThreadRoot(
                        root,
                        imageZoom,
                        viewportTopNow,
                        colors,
                        modifier = Modifier.tvFocusAnchor(focus, TvThreadFocus.Root),
                    )
                    replies.forEachIndexed { index, reply ->
                        TvThreadReplyCard(
                            reply,
                            imageZoom,
                            viewportTopNow,
                            colors,
                            // 竖线往上接到上一块 (主楼 / 上一张卡) 的下边缘
                            gapAbove = if (index == 0) TV_THREAD_FIRST_REPLY_GAP else TV_COMMENT_ROW_SPACING,
                            modifier = Modifier.tvFocusAnchor(focus, TvThreadFocus.Reply(index)),
                        )
                    }
                }
            }
        }
    }
}

/** 一整楼的配色: 播放器里压在画面上 ([TvPlayerCommentThreadColors]), 弹窗里跟主题 ([tvThemeCommentThreadColors]). */
@Immutable
internal class TvCommentThreadColors(
    val name: Color,
    val text: Color,
    /** 名字后的签名、楼层时间、「回复 @某人」. */
    val secondary: Color,
    /** 回复卡的底 (比外壳的底亮一档) / 聚焦时的底与描边. */
    val card: Color,
    val cardFocused: Color,
    val ring: Color,
    val threadLine: Color,
    val star: Color,
)

/** 播放器里 (半透明黑底上): 同评论面板的卡. */
internal val TvPlayerCommentThreadColors = TvCommentThreadColors(
    name = Color.White,
    text = Color.White.copy(alpha = 0.9f),
    secondary = Color.White.copy(alpha = 0.6f),
    // 比叠层的半透明黑底亮一档 (同 YouTube 回复卡比底亮), 聚焦再亮一点 + 白描边
    card = Color.White.copy(alpha = 0.07f),
    cardFocused = Color.White.copy(alpha = 0.14f),
    ring = Color.White,
    threadLine = TV_COMMENT_THREAD_LINE_COLOR,
    star = Color.White,
)

/** 弹窗里 (玻璃面板上): 字跟主题, 聚焦同评论列表卡 (高亮底 + 主题色描边). */
@Composable
internal fun tvThemeCommentThreadColors(): TvCommentThreadColors {
    val colors = MaterialTheme.colorScheme
    return remember(colors) {
        TvCommentThreadColors(
            name = colors.onSurface,
            text = colors.onSurface,
            secondary = colors.onSurfaceVariant,
            card = colors.onSurface.copy(alpha = 0.05f),
            cardFocused = colors.surfaceContainerHighest.copy(alpha = COMMENT_ITEM_FOCUSED_ALPHA),
            ring = colors.primary,
            threadLine = colors.outlineVariant,
            star = colors.primary,
        )
    }
}

private sealed interface TvThreadFocus : TvFocusKey {
    data object Root : TvThreadFocus
    data class Reply(val index: Int) : TvThreadFocus
}

/**
 * 这一块 (主楼 / 一条回复) 里的图: 长按确认键全屏放大, 从还没翻过去的第一张看起 (下沿还在看得见的范围上沿之下的那张).
 * 没有图时什么都不挂.
 */
@Composable
private fun Modifier.tvThreadImageZoom(
    comment: TvQuotedComment,
    imageZoom: TvImageZoomState?,
    viewportTop: () -> Float,
    imageBottoms: Map<Int, Float>,
): Modifier {
    val urls = remember(comment) { comment.blocks.filterIsInstance<TvCommentBlock.Image>().map { it.url } }
    if (imageZoom == null || urls.isEmpty()) return this
    return tvLongPressKey(
        onLongPress = {
            val top = viewportTop()
            val start = urls.indices.firstOrNull { (imageBottoms[it] ?: Float.MAX_VALUE) > top } ?: 0
            imageZoom.open(urls, start)
        },
        // 短按没有动作
        onShortPress = {},
    )
}

/** 主楼: 平时不套卡, 整块可停焦点, 聚焦时同回复卡 (亮一档 + 描边); 比看得见的长就在里面翻页 (见 tvReadableBlock). */
@Composable
private fun TvThreadRoot(
    comment: TvQuotedComment,
    imageZoom: TvImageZoomState?,
    viewportTop: () -> Float,
    colors: TvCommentThreadColors,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TV_THREAD_CARD_CORNER)
    val imageBottoms = remember(comment) { HashMap<Int, Float>() }
    val zoomHint = imageZoom != null && comment.blocks.any { it is TvCommentBlock.Image }
    Column(
        modifier
            .tvThreadImageZoom(comment, imageZoom, viewportTop, imageBottoms)
            .fillMaxWidth()
            .clip(shape)
            .ifThen(focused) { background(colors.cardFocused).border(2.dp, colors.ring, shape) }
            .tvReadableBlock()
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        TvThreadAuthorRow(comment, avatarSize = TV_THREAD_ROOT_AVATAR_SIZE, nameStyleLarge = true, colors)
        Spacer(Modifier.height(10.dp))
        TvThreadBody(comment, imageBottoms, colors)
        if (comment.reactions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            TvCommentReactionRow(comment.reactions)
        }
        if (zoomHint) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Lang.video_player_tv_comment_image_zoom_hint),
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondary,
            )
        }
    }
}

/** 一条回复: 缩进槽里画竖线 (同列表卡里楼下的回复), 卡片比底亮一档, 聚焦加描边. */
@Composable
private fun TvThreadReplyCard(
    reply: TvThreadReply,
    imageZoom: TvImageZoomState?,
    viewportTop: () -> Float,
    colors: TvCommentThreadColors,
    gapAbove: Dp,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TV_THREAD_CARD_CORNER)
    val imageBottoms = remember(reply) { HashMap<Int, Float>() }
    Box(
        Modifier
            .padding(top = gapAbove)
            .fillMaxWidth()
            // 竖线画在缩进槽里 (卡片之外), 往上多画一截接到上一块的下边缘
            .drawBehind {
                val x = TV_COMMENT_THREAD_LINE_INSET.toPx()
                drawLine(
                    color = colors.threadLine,
                    start = Offset(x, -gapAbove.toPx()),
                    end = Offset(x, size.height),
                    strokeWidth = TV_COMMENT_THREAD_LINE_WIDTH.toPx(),
                )
            }
            .padding(start = TV_COMMENT_REPLY_INDENT),
    ) {
        Column(
            modifier
                .tvThreadImageZoom(reply.comment, imageZoom, viewportTop, imageBottoms)
                .fillMaxWidth()
                .clip(shape)
                .background(if (focused) colors.cardFocused else colors.card)
                .ifThen(focused) { border(2.dp, colors.ring, shape) }
                // 比看得见的长 (少见) 也能在卡里翻页
                .tvReadableBlock()
                .focusable(interactionSource = interactionSource)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            reply.replyToName?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Reply,
                        null,
                        Modifier.size(14.dp),
                        tint = colors.secondary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(Lang.comment_reply_to, name),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            TvThreadAuthorRow(reply.comment, avatarSize = TV_THREAD_REPLY_AVATAR_SIZE, nameStyleLarge = false, colors)
            Spacer(Modifier.height(6.dp))
            TvThreadBody(reply.comment, imageBottoms, colors)
            if (reply.comment.reactions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TvCommentReactionRow(reply.comment.reactions)
            }
        }
    }
}

/** 头像 + 名字 (签名灰字) + 楼层与时间, 有评分时星在右边. */
@Composable
private fun TvThreadAuthorRow(comment: TvQuotedComment, avatarSize: Dp, nameStyleLarge: Boolean, colors: TvCommentThreadColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarImage(comment.avatarUrl, Modifier.size(avatarSize).clip(CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append(comment.authorName)
                    comment.authorSign?.let { sign ->
                        withStyle(SpanStyle(color = colors.secondary, fontWeight = FontWeight.Normal)) {
                            append("  ($sign)")
                        }
                    }
                },
                color = colors.name,
                style = if (nameStyleLarge) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                comment.floorText?.let { "$it  ${comment.timeText}" } ?: comment.timeText,
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondary,
                maxLines = 1,
            )
        }
        comment.rating?.let { rating ->
            FiveRatingStars(rating, starSize = TV_THREAD_STAR_SIZE, color = colors.star, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** 全文: 文本段 (带表情) 与图片按原顺序; 删除了的写一句. 每张图的下沿 (窗口坐标) 记进 [imageBottoms] (长按放大从哪张看起). */
@Composable
private fun ColumnScope.TvThreadBody(comment: TvQuotedComment, imageBottoms: MutableMap<Int, Float>, colors: TvCommentThreadColors) {
    if (comment.deleted) {
        Text(
            stringResource(Lang.video_player_tv_comment_deleted),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondary,
        )
        return
    }
    var imageIndex = 0
    comment.blocks.forEachIndexed { index, block ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        when (block) {
            is TvCommentBlock.Text -> Text(
                block.text.text,
                color = colors.text,
                style = MaterialTheme.typography.bodyMedium,
                inlineContent = tvStickerInlineContent(block.text.stickers),
            )

            is TvCommentBlock.Image -> {
                val image = imageIndex++
                TvCommentQuoteImage(
                    block.url,
                    Modifier.onGloballyPositioned { imageBottoms[image] = it.boundsInWindow().bottom },
                )
            }
        }
    }
}

/** 栏的左右内边距. */
private val TV_THREAD_SIDE_PADDING = 20.dp

/** 滚动的那一列从顶上让出多少再排 (叠层顶上那一截是渐隐), 与底下留多少. */
private val TV_THREAD_TOP_PADDING = 32.dp
private val TV_THREAD_BOTTOM_PADDING = 16.dp

/** 主楼与第一条回复之间的空. */
private val TV_THREAD_FIRST_REPLY_GAP = 14.dp

private val TV_THREAD_ROOT_AVATAR_SIZE = 32.dp
private val TV_THREAD_REPLY_AVATAR_SIZE = 24.dp
private val TV_THREAD_STAR_SIZE = 16.dp

private val TV_THREAD_CARD_CORNER = 12.dp
