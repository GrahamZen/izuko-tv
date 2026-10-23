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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import me.him188.ani.app.ui.comment.UIComment
import me.him188.ani.app.ui.foundation.dialogs.DialogWindowDimAmount
import me.him188.ani.app.ui.foundation.dialogs.dialogWindowBlurBehind
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TvImageZoomState
import me.him188.ani.app.ui.foundation.tv.TvZoomedImageOverlay
import me.him188.ani.app.ui.foundation.tv.tvImageZoomKeys
import me.him188.ani.app.ui.foundation.tvOverlayWindowKeys
import me.him188.ani.app.ui.foundation.focus.TvFocusKey
import me.him188.ani.app.ui.foundation.focus.rememberTvFocusScope
import me.him188.ani.app.ui.foundation.focus.tvFocusAnchor
import me.him188.ani.app.ui.foundation.focus.tvFocusNavSignal
import me.him188.ani.app.ui.foundation.widgets.ANI_GLASS_BLUR_RADIUS
import me.him188.ani.app.ui.foundation.widgets.ANI_GLASS_PANEL_SHAPE
import me.him188.ani.app.ui.foundation.widgets.ANI_GLASS_WINDOW_DIM
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_CONTENT_PADDING
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_TITLE_GAP
import me.him188.ani.app.ui.foundation.widgets.CENTERED_PANEL_WINDOW_DIM
import me.him188.ani.app.ui.foundation.widgets.ProvideAniGlassPanel
import me.him188.ani.app.ui.foundation.widgets.ProvidePopupControlStyle
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
import me.him188.ani.app.ui.foundation.widgets.aniGlassButtonColor
import me.him188.ani.app.ui.foundation.widgets.aniGlassEdge
import me.him188.ani.app.ui.foundation.widgets.aniGlassPanelColor
import me.him188.ani.app.ui.foundation.widgets.aniPanelItemColor
import me.him188.ani.app.ui.foundation.widgets.aniPanelSubtitleStyle
import me.him188.ani.app.ui.foundation.widgets.aniPanelTitleStyle
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.foundation.widgets.fillMaxHeightAtMost
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.subject_episode_close
import org.jetbrains.compose.resources.stringResource

/**
 * "查看全部"类内容的 TV / 平板大弹窗外壳, 替代移动端的 ModalBottomSheet: 玻璃面板 (同 AniCenteredPanelDialog), 标题行 + 下面的 [content].
 * 面板高度随内容, 最高 [TV_DETAILS_DIALOG_HEIGHT_FRACTION] 屏; [content] 排在 Column 里, 会滚的内容用 `weight(1f, fill = false)`,
 * 内容多时在面板里滚, 不把标题行挤出去. 返回键关闭.
 *
 * 标题行: 标题 + 淡一档的数目 ([count]) + 右侧动作 ([headerAction]), 下面可选一行说明 ([subtitle], 如所属条目名); 触屏上右上角多一个
 * 关闭钮 (遥控器用返回键).
 *
 * 打开时的焦点 (遥控器): [content] 的 initialFocus 挂在打开时该拿焦点的那一层上 (Dialog 是独立焦点域, 不聚焦则方向键无处可去;
 * 请求悬挂到它附着时再送, 分页数据迟到也接得住). 触屏上不送焦, initialFocus 是空的 Modifier.
 *
 * @param imageZoom 非 null 时条目可长按放大看图: 大图画在本窗口内 (见 [TvImageZoomState]), 焦点不动.
 */
@Composable
internal fun ViewAllPanelDialog(
    title: String,
    onDismissRequest: () -> Unit,
    count: Int? = null,
    subtitle: String? = null,
    imageZoom: TvImageZoomState? = null,
    headerAction: @Composable () -> Unit = {},
    content: @Composable ColumnScope.(initialFocus: Modifier) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // **scope 必须建在 Dialog 内容里**: 它的解析器按 LocalWindowInfo 判"窗口有焦点"来重试,
        // 建在外面读到的是主窗口 (弹窗开着必为 false), 按帧重试永不运行、窗口获焦事件也听错了窗口.
        // 首格随分页数据迟到时, 请求悬挂到锚点附着事件再送达, 不再逐帧轮询.
        val focus = rememberTvFocusScope()
        val touch = LocalTvTouchInputEnabled.current
        if (!touch) focus.InitialFocus(ViewAllGridFirstItemFocus)
        // 玻璃面板、背后模糊与窗外压暗与 AniCenteredPanelDialog 同一套: 盖在播放器上时画面要透得出来
        val blurred = dialogWindowBlurBehind(ANI_GLASS_BLUR_RADIUS)
        DialogWindowDimAmount(if (blurred) ANI_GLASS_WINDOW_DIM else CENTERED_PANEL_WINDOW_DIM)
        Surface(
            // 独立窗口收不到播放页的根按键路由, 播放器内嵌详情页开的弹窗要自己接播放/暂停键
            Modifier.tvOverlayWindowKeys(onDismissRequest)
                .tvFocusNavSignal(focus)
                // 大图开着期间吞掉一切按键 (返回键只关大图, 不关本弹窗). 挂在这儿而不是每个
                // 条目上: onPreviewKeyEvent 只在焦点路径上触发, 网格与卡片都在它的子树里
                .ifThen(imageZoom != null) { tvImageZoomKeys(imageZoom!!) }
                .fillMaxWidth(TV_DETAILS_DIALOG_WIDTH_FRACTION)
                .fillMaxHeightAtMost(TV_DETAILS_DIALOG_HEIGHT_FRACTION),
            shape = ANI_GLASS_PANEL_SHAPE,
            color = aniGlassPanelColor(blurred),
            border = aniGlassEdge(),
            // 半透明底色查不到 "on" 色, 不显式给会退回 LocalContentColor 的默认纯黑
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            // 玻璃面板里的项目换成玻璃底 (卡片、胶囊); 按钮约定只给标题行 (见下)
            ProvideAniGlassPanel {
                Box {
                    Column(
                        Modifier.padding(CENTERED_PANEL_CONTENT_PADDING),
                        verticalArrangement = Arrangement.spacedBy(CENTERED_PANEL_TITLE_GAP),
                    ) {
                        ViewAllPanelHeader(title, count, subtitle, onDismissRequest.takeIf { touch }, headerAction)
                        content(if (touch) Modifier else Modifier.tvFocusAnchor(focus, ViewAllGridFirstItemFocus))
                    }
                    imageZoom?.let { TvZoomedImageOverlay(it) }
                }
            }
        }
    }
}

/** [ViewAllPanelDialog] 的标题行; [onClose] 非 null 时右上角画关闭钮 (触屏). */
@Composable
private fun ViewAllPanelHeader(
    title: String,
    count: Int?,
    subtitle: String?,
    onClose: (() -> Unit)?,
    headerAction: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(
                    title,
                    Modifier.alignByBaseline().weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = aniPanelTitleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                count?.let {
                    Text(
                        it.toString(),
                        Modifier.alignByBaseline().padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
            }
            subtitle?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = aniPanelSubtitleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 标题行的按钮 (如评论的「写评价」) 画成弹窗动作按钮, 见 ProvidePopupControlStyle
        ProvidePopupControlStyle {
            headerAction()
        }
        onClose?.let { close ->
            Surface(
                onClick = close,
                Modifier.padding(start = 12.dp).size(VIEW_ALL_CLOSE_BUTTON_SIZE).testTag(VIEW_ALL_CLOSE_BUTTON_TAG),
                shape = CircleShape,
                color = aniGlassButtonColor(),
                border = aniGlassEdge(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, stringResource(Lang.subject_episode_close), Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * "查看全部"的卡片网格弹窗 (评价等): [ViewAllPanelDialog] 里一张自适应 (或定列数) 的 Compose 网格, 方向键导航, 默认 BringIntoView 滚动.
 *
 * 打开时聚焦 [initialFocusIndex] 那一格: [itemContent] 的 modifier 参数在那一格带焦点请求器, 必须应用到条目根 (或可聚焦的容器) 上.
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
    /** 见 [ViewAllPanelDialog]. */
    imageZoom: TvImageZoomState? = null,
    headerAction: @Composable () -> Unit = {},
    itemContent: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    ViewAllPanelDialog(title, onDismissRequest, imageZoom = imageZoom, headerAction = headerAction) { initialFocus ->
        // **不要自己动滚动位置**: 试过用 initialFirstVisibleItemIndex 把目标那一条顶到最上面,
        // 真机上是"第三条先跑到顶上, 立刻又闪回中间" (用户 2026-09-15) —— 分页刚到的那几条
        // 不够铺满一屏, LazyGrid 测量时会自己回滚把内容填满视口, 把顶上去的那条拉回来.
        // 详情页预览的那几条 ([TV_REVIEW_PREVIEW_COUNT]) 本来就都在首屏里, 焦点直接落上去,
        // 默认 BringIntoViewSpec 算出的滚动量就是 0, 一帧都不动. 真在屏外时它也只做最小滚动.
        // 滚不完的卡片在上下边缘渐隐 (不是被面板内边距一刀切掉); 遥控器上焦点行滚进来时避开渐隐那一截
        val gridState = rememberLazyGridState()
        ProvideScrollFadeFocusMargin {
            LazyVerticalGrid(
                if (columns != null) GridCells.Fixed(columns) else GridCells.Adaptive(minSize = cellMinWidth),
                Modifier.weight(1f, fill = false).aniVerticalScrollFade(gridState),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items.itemCount,
                    key = items.itemKey(),
                    contentType = items.itemContentType(),
                ) { index ->
                    items[index]?.let { item ->
                        itemContent(item, if (index == initialFocusIndex) initialFocus else Modifier)
                    }
                }
            }
        }
    }
}

private data object ViewAllGridFirstItemFocus : TvFocusKey

/** 触屏时标题行右上角关闭钮的直径. */
private val VIEW_ALL_CLOSE_BUTTON_SIZE = 40.dp

internal const val VIEW_ALL_CLOSE_BUTTON_TAG = "view-all-close"

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
        // 玻璃面板里 (查看全部等弹窗) 是淡白的玻璃底, 页面上照旧; 触屏上不画聚焦描边
        color = aniPanelItemColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = TV_CARD_CONTAINER_ALPHA)),
        border = if (focused && !LocalTvTouchInputEnabled.current) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
 * 评论列表的 TV / 平板大弹窗 (条目评价 / 人物评论共用): 标题 (+可选"写评价"动作), 下面是两列瀑布流的评论 ([CommentsList]).
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
    ViewAllPanelDialog(title, onDismissRequest, headerAction = headerAction) { initialFocus ->
        LocalViewAllCommentsList.current.Comments(comments, showRating, initialFocusIndex, initialFocus, Modifier.weight(1f, fill = false))
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
