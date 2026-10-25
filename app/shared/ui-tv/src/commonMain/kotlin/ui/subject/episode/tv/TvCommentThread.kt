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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import org.jetbrains.compose.resources.stringResource

/** 一楼里的一条回复 (见 [TvCommentReplyTarget.replies]). */
@Immutable
class TvThreadReply(
    val comment: TvQuotedComment,
    /** 非空 = 回复的是同层某条回复 (要写明回复谁); null = 直接回复主楼. */
    val replyToName: String?,
)

/**
 * 只读的评论详情: 一整楼 (参考 YouTube 电视版的单条评论页). 胶囊上方从屏幕顶铺到胶囊行的一栏, 半透明黑底 (字多):
 * - 最上面是主楼, 不套卡: 头像 + 名字 (签名灰字) + 楼层与时间, 下面全文 (表情、图), 再下面是回应; 主楼整块可停焦点,
 *   比看得见的长就在里面翻页 (见 tvReadableBlock), 有图时长按确认放大看;
 * - 下面一条回复一张卡 (比底亮一档, 聚焦加白描边), 左边用评论面板里那条竖线串起来 (同 TvCommentsPanel 的回复行),
 *   回复同层某条回复时多一行「回复 @某人」.
 *
 * 焦点: 打开时落在主楼 (从面板里点的是一条回复就落在那条回复上); 上下在主楼与各条回复之间走, 整栏跟着滚, 最下面再按下停住
 * (不回胶囊); 左右翻相邻的楼 ([onNavigate], 由评论面板换 target 并跟着滚); 返回关掉 (根路由).
 * 主楼与回复里的图: 在那一块上长按确认键全屏放大 (大图由控制层铺满整屏画, 见 TvPlayerControlsOverlay), 从正文翻到的那张看起, 不止一张时左右换.
 */
@Composable
internal fun TvCommentThreadView(
    target: TvCommentReplyTarget,
    onNavigate: ((delta: Int) -> Unit)?,
    imageZoom: TvImageZoomState?,
    modifier: Modifier = Modifier,
) {
    val root = target.quoted ?: return
    val focus = rememberTvFocusScope()
    val scrollState = rememberScrollState()
    // 看得见的范围的上沿 (窗口坐标): 长按放大时从还没翻过去的第一张图看起
    val viewportTop = remember { FloatArray(1) }
    // 关掉 (或整个收起) 时大图跟着收, 下次打开不带着它
    DisposableEffect(imageZoom) {
        onDispose { imageZoom?.close() }
    }
    // 打开 / 左右翻到别的楼: 焦点落在主楼 (回到顶上), 从面板里点的是回复就落在那条回复上
    LaunchedEffect(target) {
        val reply = target.focusReply
        if (reply in target.replies.indices) {
            focus.request(TvThreadFocus.Reply(reply))
        } else {
            scrollState.scrollTo(0)
            focus.request(TvThreadFocus.Root)
        }
    }
    TvPlayerLayerPanel(
        modifier = modifier
            .tvFocusNavSignal(focus)
            // 大图开着时左右键换图、其余按键一概吞掉 (返回键由根路由处理)
            .ifThen(imageZoom != null) { tvImageZoomKeys(imageZoom!!) }
            // 左右键翻相邻的楼: 栏里没有左右的去处
            .onPreviewKeyEvent { event ->
                val delta = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onNavigate?.invoke(delta)
                true
            },
        // 上下不留: 滚动的那一列自己从顶上让出渐隐那一截再排, 滚不完时在上下边缘渐隐
        contentPadding = PaddingValues(horizontal = TV_THREAD_SIDE_PADDING),
        // 最下面按下也停住: 往下翻长楼时一路按着, 不能翻到底就掉回胶囊
        lockDown = true,
    ) {
        ProvideScrollFadeFocusMargin {
            CompositionLocalProvider(LocalTvReadableBlocks provides true) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { viewportTop[0] = it.boundsInWindow().top }
                        .aniVerticalScrollFade(scrollState)
                        .verticalScroll(scrollState)
                        .padding(top = TV_THREAD_TOP_PADDING, bottom = TV_THREAD_BOTTOM_PADDING),
                ) {
                    val viewportTopNow = { viewportTop[0] }
                    TvThreadRoot(
                        root,
                        imageZoom,
                        viewportTopNow,
                        modifier = Modifier.tvFocusAnchor(focus, TvThreadFocus.Root),
                    )
                    target.replies.forEachIndexed { index, reply ->
                        TvThreadReplyCard(
                            reply,
                            imageZoom,
                            viewportTopNow,
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

/** 主楼: 平时不套卡, 整块可停焦点, 聚焦时同回复卡 (亮一档 + 白描边); 比看得见的长就在里面翻页 (见 tvReadableBlock). */
@Composable
private fun TvThreadRoot(
    comment: TvQuotedComment,
    imageZoom: TvImageZoomState?,
    viewportTop: () -> Float,
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
            .ifThen(focused) { background(TV_THREAD_CARD_FOCUSED_COLOR).border(2.dp, Color.White, shape) }
            .tvReadableBlock()
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        TvThreadAuthorRow(comment, avatarSize = TV_THREAD_ROOT_AVATAR_SIZE, nameStyleLarge = true)
        Spacer(Modifier.height(10.dp))
        TvThreadBody(comment, imageBottoms)
        if (comment.reactions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            TvCommentReactionRow(comment.reactions)
        }
        if (zoomHint) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Lang.video_player_tv_comment_image_zoom_hint),
                style = MaterialTheme.typography.labelSmall,
                color = TV_THREAD_SECONDARY_COLOR,
            )
        }
    }
}

/** 一条回复: 缩进槽里画竖线 (同评论面板的回复行), 卡片比底亮一档, 聚焦加白描边. */
@Composable
private fun TvThreadReplyCard(
    reply: TvThreadReply,
    imageZoom: TvImageZoomState?,
    viewportTop: () -> Float,
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
                    color = TV_COMMENT_THREAD_LINE_COLOR,
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
                .background(if (focused) TV_THREAD_CARD_FOCUSED_COLOR else TV_THREAD_CARD_COLOR)
                .ifThen(focused) { border(2.dp, Color.White, shape) }
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
                        tint = TV_THREAD_SECONDARY_COLOR,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(Lang.comment_reply_to, name),
                        style = MaterialTheme.typography.labelSmall,
                        color = TV_THREAD_SECONDARY_COLOR,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            TvThreadAuthorRow(reply.comment, avatarSize = TV_THREAD_REPLY_AVATAR_SIZE, nameStyleLarge = false)
            Spacer(Modifier.height(6.dp))
            TvThreadBody(reply.comment, imageBottoms)
            if (reply.comment.reactions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TvCommentReactionRow(reply.comment.reactions)
            }
        }
    }
}

/** 头像 + 名字 (签名灰字) + 楼层与时间. */
@Composable
private fun TvThreadAuthorRow(comment: TvQuotedComment, avatarSize: Dp, nameStyleLarge: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarImage(comment.avatarUrl, Modifier.size(avatarSize).clip(CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append(comment.authorName)
                    comment.authorSign?.let { sign ->
                        withStyle(SpanStyle(color = TV_THREAD_SECONDARY_COLOR, fontWeight = FontWeight.Normal)) {
                            append("  ($sign)")
                        }
                    }
                },
                color = Color.White,
                style = if (nameStyleLarge) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                comment.floorText?.let { "$it  ${comment.timeText}" } ?: comment.timeText,
                style = MaterialTheme.typography.labelSmall,
                color = TV_THREAD_SECONDARY_COLOR,
                maxLines = 1,
            )
        }
    }
}

/** 全文: 文本段 (带表情) 与图片按原顺序; 删除了的写一句. 每张图的下沿 (窗口坐标) 记进 [imageBottoms] (长按放大从哪张看起). */
@Composable
private fun ColumnScope.TvThreadBody(comment: TvQuotedComment, imageBottoms: MutableMap<Int, Float>) {
    if (comment.deleted) {
        Text(
            stringResource(Lang.video_player_tv_comment_deleted),
            style = MaterialTheme.typography.bodyMedium,
            color = TV_THREAD_SECONDARY_COLOR,
        )
        return
    }
    var imageIndex = 0
    comment.blocks.forEachIndexed { index, block ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        when (block) {
            is TvCommentBlock.Text -> Text(
                block.text.text,
                color = TV_THREAD_TEXT_COLOR,
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

/** 回复卡: 比叠层的半透明黑底亮一档 (同 YouTube 回复卡比底亮), 聚焦再亮一点 + 白描边. */
private val TV_THREAD_CARD_COLOR = Color.White.copy(alpha = 0.07f)
private val TV_THREAD_CARD_FOCUSED_COLOR = Color.White.copy(alpha = 0.14f)
private val TV_THREAD_CARD_CORNER = 12.dp

/** 正文与次要字 (名字后的签名、楼层时间) 的颜色, 同评论面板的卡. */
private val TV_THREAD_TEXT_COLOR = Color.White.copy(alpha = 0.9f)
private val TV_THREAD_SECONDARY_COLOR = Color.White.copy(alpha = 0.6f)
