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
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import me.him188.ani.app.tools.formatDateTime
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tv.TvImageZoomState
import me.him188.ani.app.ui.foundation.tv.TvZoomedImageOverlay
import me.him188.ani.app.ui.foundation.tv.tvImageZoomKeys
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_WINDOW_DIM
import me.him188.ani.app.ui.foundation.widgets.centeredPanelColor
import me.him188.ani.app.ui.rating.FiveRatingStars

/**
 * "查看全部"类内容的 TV 大弹窗: 标题 (+可选右侧动作) + 自适应卡片网格 (方向键导航,
 * 默认 BringIntoView 滚动, 返回键关闭). 替代移动端的 ModalBottomSheet.
 *
 * 打开时自动聚焦 [initialFocusIndex] 那一格 (Dialog 独立焦点域, 不聚焦则方向键无处可去; 等分页数据与
 * 卡片组合出来再请求): [itemContent] 的 modifier 参数在那一格带焦点请求器, 必须应用
 * 到条目根 (或可聚焦的容器) 上.
 */
@Composable
internal fun <T : Any> ViewAllGridDialog(
    title: String,
    items: LazyPagingItems<T>,
    onDismissRequest: () -> Unit,
    cellMinWidth: Dp = 280.dp,
    /** 非 null 时固定列数 (卡宽 = 网格均分); null 按 [cellMinWidth] 自适应分列. */
    columns: Int? = null,
    /**
     * 打开时聚焦第几格 (默认首格): 从详情页某一张评论卡进来时是那一条的序号, 落点要跟进来的那张对上.
     *
     * **只搬焦点, 不搬滚动位置** (原因见网格那处注释).
     */
    initialFocusIndex: Int = 0,
    /** 非 null 时条目可长按放大看图: 大图画在本窗口内 (见 [TvImageZoomState]), 焦点不动. */
    imageZoom: TvImageZoomState? = null,
    headerAction: @Composable () -> Unit = {},
    itemContent: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // **scope 必须建在 Dialog 内容里**: 它的解析器按 LocalWindowInfo 判"窗口有焦点"来重试,
        // 建在外面读到的是主窗口 (弹窗开着必为 false), 按帧重试永不运行、窗口获焦事件也听错了窗口.
        // 首格随分页数据迟到时, 请求悬挂到锚点附着事件再送达, 不再逐帧轮询.
        val focus = rememberTvFocusScope()
        focus.InitialFocus(ViewAllGridFirstItemFocus)
        // 底色/窗外压暗与 AniCenteredPanelDialog 同一套: 盖在播放器上时画面要透得出来
        DialogWindowDimAmount(CENTERED_PANEL_WINDOW_DIM)
        Surface(
            // 独立窗口收不到播放页的根按键路由, 播放器内嵌详情页开的弹窗要自己接播放/暂停键
            Modifier.tvOverlayWindowKeys(onDismissRequest)
                .tvFocusNavSignal(focus)
                // 大图开着期间吞掉一切按键 (返回键只关大图, 不关本弹窗). 挂在这儿而不是每个
                // 条目上: onPreviewKeyEvent 只在焦点路径上触发, 网格与卡片都在它的子树里
                .ifThen(imageZoom != null) { tvImageZoomKeys(imageZoom!!) }
                .fillMaxWidth(TV_DETAILS_DIALOG_WIDTH_FRACTION)
                .fillMaxHeight(TV_DETAILS_DIALOG_HEIGHT_FRACTION),
            shape = RoundedCornerShape(16.dp),
            color = centeredPanelColor,
            // 半透明底色查不到 "on" 色, 不显式给会退回 LocalContentColor 的默认纯黑
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        headerAction()
                    }
                    // **不要自己动滚动位置**: 试过用 initialFirstVisibleItemIndex 把目标那一条顶到最上面,
                    // 真机上是"第三条先跑到顶上, 立刻又闪回中间" (用户 2026-09-15) —— 分页刚到的那几条
                    // 不够铺满一屏, LazyGrid 测量时会自己回滚把内容填满视口, 把顶上去的那条拉回来.
                    // 详情页预览的那几条 ([TV_REVIEW_PREVIEW_COUNT]) 本来就都在首屏里, 焦点直接落上去,
                    // 默认 BringIntoViewSpec 算出的滚动量就是 0, 一帧都不动. 真在屏外时它也只做最小滚动.
                    LazyVerticalGrid(
                        if (columns != null) GridCells.Fixed(columns) else GridCells.Adaptive(minSize = cellMinWidth),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items.itemCount,
                            key = items.itemKey(),
                            contentType = items.itemContentType(),
                        ) { index ->
                            items[index]?.let { item ->
                                itemContent(
                                    item,
                                    Modifier.ifThen(index == initialFocusIndex) {
                                        tvFocusAnchor(focus, ViewAllGridFirstItemFocus)
                                    },
                                )
                            }
                        }
                    }
                }
                imageZoom?.let { TvZoomedImageOverlay(it) }
            }
        }
    }
}

private data object ViewAllGridFirstItemFocus : TvFocusKey

/**
 * 聚焦卡容器: 包住本身带 clickable 的移动端条目 (如 PersonCard), 使其在 TV 网格中
 * 呈卡片形态 —— 子树获得焦点时主题色描边示焦 (容器底色不变, 不做提亮"背光").
 * 内部条目的 clickable 自带涟漪指示器, 其焦点状态层会在卡里再画一块小尺寸高亮
 * (与描边叠成两层特效); 这里整体禁用内部指示器, 示焦只由卡容器描边承担
 * (与播放器面板条目 TvPanelItem 的单层效果一致).
 *
 * [onClick] 给**固定锚位横滑条**里的卡用 (见 `TvAnchoredStrip`): 传了它, 卡容器自己就是焦点
 * 目标, 内部条目不再挂 clickable. 必须这样 —— 锚位滚动按**焦点目标矩形**算, 焦点若在内部
 * (比外框内缩一圈 [FOCUS_HIGHLIGHT_CARD_PADDING]), 框架会把内部那圈对齐到锚位, 外框连描边一起
 * 被顶出视口左缘 (真机症状: "第一张卡最左边被挡住一点点"); 且挂在外框上的落点请求器会因为
 * 外框不可聚焦而静默失效. 不传 [onClick] 时行为不变 (自身不可聚焦, 焦点在内部条目上).
 */
@Composable
internal fun FocusHighlightCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            // indication = null: 示焦仍只由下面的描边承担 (涟漪的焦点状态层会再叠一块高亮)
            .ifThen(onClick != null) {
                clickable(interactionSource = interactionSource, indication = null, onClick = onClick!!)
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = TV_CARD_CONTAINER_ALPHA),
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(FOCUS_HIGHLIGHT_CARD_PADDING)) {
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                content()
            }
        }
    }
}

/** 无绘制的指示器: 条目仍可聚焦/点击, 只是不画自己的焦点/按压状态层. */
private data object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node() {}
}

/**
 * 评论列表的 TV 大弹窗 (条目评价 / 人物评论共用): 标题 (+可选"写评价"动作) + 评论卡片网格.
 * 卡片为紧凑纯文本形态 (头像 + 昵称 + 日期 + 评分星 + 正文摘要), 点击展开/收起全文;
 * 富文本图片/贴纸在 TV 上略过 (与播放器评论面板一致).
 */
@Composable
internal fun CommentsGridDialog(
    title: String,
    comments: LazyPagingItems<UIComment>,
    onDismissRequest: () -> Unit,
    showRating: Boolean,
    headerAction: @Composable () -> Unit = {},
    /** 打开时落在第几条 (从详情页的某张评论卡进来时是那一条). */
    initialFocusIndex: Int = 0,
) {
    ViewAllGridDialog(
        title = title,
        items = comments,
        onDismissRequest = onDismissRequest,
        cellMinWidth = COMMENT_GRID_CARD_MIN_WIDTH,
        headerAction = headerAction,
        initialFocusIndex = initialFocusIndex,
    ) { comment, modifier ->
        CommentGridCard(comment, showRating = showRating, modifier = modifier)
    }
}

/** 单条评论卡: 聚焦高亮, 确认键展开/收起全文. */
@Composable
private fun CommentGridCard(
    comment: UIComment,
    showRating: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = { expanded = !expanded },
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (focused) {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = TV_CARD_CONTAINER_FOCUSED_ALPHA)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = TV_CARD_CONTAINER_ALPHA)
        },
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        interactionSource = interactionSource,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AvatarImage(
                    comment.author?.avatarUrl,
                    Modifier.size(24.dp).clip(CircleShape),
                )
                Text(
                    comment.author?.nickname ?: comment.author?.id?.toString() ?: "",
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatDateTime(comment.createdAt, showTime = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (showRating) {
                    comment.rating?.takeIf { it > 0 }?.let { rating ->
                        FiveRatingStars(rating, starSize = 12.dp)
                    }
                }
            }
            Text(
                remember(comment) { comment.content.toPlainText() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else TV_COMMENT_COLLAPSED_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** TV 详情弹窗宽/高占屏比例 (与人物"查看全部"弹窗一致). */
private const val TV_DETAILS_DIALOG_WIDTH_FRACTION = 0.85f
private const val TV_DETAILS_DIALOG_HEIGHT_FRACTION = 0.88f

/** [FocusHighlightCard] 卡容器到内部条目的内边距 (＝焦点目标相对外框的内缩量). */
internal val FOCUS_HIGHLIGHT_CARD_PADDING = 10.dp

/** TV 卡片容器不透明度 (未聚焦/聚焦档): 半透明隐约透出下层背景, 不压住 backdrop. */
internal const val TV_CARD_CONTAINER_ALPHA = 0.45f
internal const val TV_CARD_CONTAINER_FOCUSED_ALPHA = 0.75f

/**
 * 评论卡最小宽度 (自适应分列).
 *
 * 取 360dp 是为了在 TV 的弹窗里**分成两列**: 弹窗宽 0.85 屏 = 816dp, 去掉 24dp 内边距剩 768dp,
 * `(768 + 12) / (360 + 12) = 2` 列, 每列 378dp. 420dp 时只有一列 —— 一屏整好放得下 2 张卡,
 * 详情页预览的第 3 张 (见 TV_REVIEW_PREVIEW_COUNT) 只露半截, 从它进来时焦点落上去会触发
 * "最小滚动把它补全", 观感是开窗之后自己滚一下 (用户 2026-09-15: "第三个评价会在下面然后跑到中间").
 * 两列之后三张卡分占两行, 全在首屏里, 滚动量恒为 0 —— 不靠压掉动画去遮掩, 而是不让它需要滚.
 */
private val COMMENT_GRID_CARD_MIN_WIDTH = 360.dp

/** 评论卡折叠态正文最大行数 (确认键展开全文). */
private const val TV_COMMENT_COLLAPSED_MAX_LINES = 5
