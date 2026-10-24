/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.sections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.LocalAniUiBehavior
import me.him188.ani.app.ui.foundation.avatar.AvatarImage
import me.him188.ani.app.ui.foundation.focus.TV_SCROLL_STIFFNESS
import me.him188.ani.app.ui.foundation.ifThen
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.tvCardTextInset
import me.him188.ani.app.ui.foundation.tv.tvFocusableCard
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.widgets.ANI_SCROLL_FADE
import me.him188.ani.app.ui.foundation.widgets.ANI_SCROLL_FADE_UNDER_TITLE
import me.him188.ani.app.ui.foundation.widgets.ProvideScrollFadeFocusMargin
import me.him188.ani.app.ui.foundation.widgets.aniVerticalScrollFade
import me.him188.ani.app.ui.subject.details.components.COVER_WIDTH_TO_HEIGHT_RATIO
import me.him188.ani.app.ui.subject.person.PeopleRowItem
import me.him188.ani.app.ui.subject.person.PosterRowItem

/**
 * 「查看全部」弹窗 (电视与平板的居中弹窗形态, 外壳见 [ViewAllPanelDialog]) 里的画廊网格: 人物 ([PeopleGallery], 圆头像) 与作品
 * ([PosterGallery], 海报). 标题、数据与点按后的动作在公共代码 (ViewAllGalleryDialogs.kt), 网格本身由应用入口提供:
 * 电视包 (电视与安卓平板) 用原生 View (ui-tv 的 TvViewAllGalleries), 没提供时用 Compose 版 ([ComposeViewAllGalleries], 如 iPad).
 * 两边的几何与配色取这里同一组定义 (VIEW_ALL_GALLERY_*、[viewAllGalleryCellWidth]、[viewAllGalleryBadgeStyle]), 看起来一样.
 *
 * 网格铺满父布局给的宽, 高度随内容 (父布局给的上限内, 超出就在网格里滚, 滚不完的那边渐隐). 方向键在网格里走, 走到四边不出网格;
 * 打开时焦点落在第一格 (initialFocus 挂在接焦点的那一层上). 触屏上点一下就是确认.
 */
@Stable
interface ViewAllGalleries {
    /**
     * 人物画廊: 一行 [VIEW_ALL_GALLERY_COLUMNS] 个圆头像, 圆下名字 + 一行副标题, [PeopleRowItem.badge] 压在圆的下缘. 聚焦时只放大圆
     * 并描环, 字跟着圆的下缘走 (同详情页的圆头像行).
     *
     * @param items 列表里的 null 是分页还没到的那一格 (画成占位).
     * @param onLongPress 长按 (放大看照片); null = 没有长按.
     * @param onBind 第几格被绑定 (分页的访问提示).
     * @param initialFocus 打开时该拿焦点的那一层挂的 Modifier (见 [ViewAllPanelDialog]).
     */
    @Composable
    fun PeopleGallery(
        items: List<PeopleRowItem?>,
        onClick: (index: Int) -> Unit,
        onLongPress: ((index: Int) -> Unit)?,
        onBind: (index: Int) -> Unit,
        initialFocus: Modifier,
        modifier: Modifier,
    )

    /** 作品画廊: 一行 [VIEW_ALL_GALLERY_COLUMNS] 张海报 (与人物画廊同一列宽), 海报下番名 + 一行小字. 参数同 [PeopleGallery], 没有长按. */
    @Composable
    fun PosterGallery(
        items: List<PosterRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        initialFocus: Modifier,
        modifier: Modifier,
    )
}

/** 见 [ViewAllGalleries]; 默认是 Compose 版. */
val LocalViewAllGalleries = staticCompositionLocalOf<ViewAllGalleries> { ComposeViewAllGalleries }

/** 一行几格: 人物与作品同一列宽, 换着看两种画廊列是对齐的. */
const val VIEW_ALL_GALLERY_COLUMNS = 6

/** 格间距, 同详情页的圆头像行. */
val VIEW_ALL_GALLERY_COLUMN_SPACING = TV_MONOGRAM_SPACING

/** 行距. */
val VIEW_ALL_GALLERY_ROW_SPACING = 24.dp

/** 网格左右各让出多少: 聚焦放大 (圆 1.115 倍、海报 1.12 倍) 伸出格外的那一截与描环画在这里面, 不被弹窗内容区的边裁掉. */
val VIEW_ALL_GALLERY_SIDE_INSET = 12.dp

/**
 * 网格顶上让出多少: 往下滚了以后内容在这一截里渐隐 (标题行下面那条, 见 [ANI_SCROLL_FADE_UNDER_TITLE]), 不滚时首行的放大也画在这里面;
 * 原生版还往标题行下面那段间距里伸, 首行的投影画得开 (见 TvViewAllGalleries).
 */
val VIEW_ALL_GALLERY_TOP_INSET = ANI_SCROLL_FADE_UNDER_TITLE

/** 网格底下让出多少: 滚不完时内容在这一截里渐隐 (同 aniVerticalScrollFade), 不滚时末行的放大与投影也画在这里面. */
val VIEW_ALL_GALLERY_BOTTOM_INSET = ANI_SCROLL_FADE

/** 圆下缘到名字: 比详情页的行宽一点, 给压在圆下缘的标签让出位置. */
val VIEW_ALL_GALLERY_TEXT_GAP = 8.dp

/** 圆下缘的标签 (「主角」): 高、左右内边距、伸出圆下缘多少 (标签大半压在圆里). */
val VIEW_ALL_GALLERY_BADGE_HEIGHT = 18.dp
val VIEW_ALL_GALLERY_BADGE_PADDING = 8.dp
val VIEW_ALL_GALLERY_BADGE_OVERHANG = 3.dp

/** 一格的宽 (圆的直径 / 海报卡宽): 网格宽 [width] 去掉两侧让出与格间距, 均分 [VIEW_ALL_GALLERY_COLUMNS] 份. */
fun viewAllGalleryCellWidth(width: Dp): Dp =
    ((width - VIEW_ALL_GALLERY_SIDE_INSET * 2 - VIEW_ALL_GALLERY_COLUMN_SPACING * (VIEW_ALL_GALLERY_COLUMNS - 1)) / VIEW_ALL_GALLERY_COLUMNS)
        .coerceAtLeast(0.dp)

/** 圆下缘标签的字与配色 (两套实现共用). */
@Immutable
data class ViewAllGalleryBadgeStyle(val text: TextStyle, val container: Color, val content: Color)

@Composable
fun viewAllGalleryBadgeStyle(): ViewAllGalleryBadgeStyle {
    val colors = MaterialTheme.colorScheme
    return ViewAllGalleryBadgeStyle(
        text = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        container = colors.secondaryContainer,
        content = colors.onSecondaryContainer,
    )
}

/** [ViewAllGalleries] 的 Compose 版 (没有原生实现的平台). */
object ComposeViewAllGalleries : ViewAllGalleries {
    @Composable
    override fun PeopleGallery(
        items: List<PeopleRowItem?>,
        onClick: (index: Int) -> Unit,
        onLongPress: ((index: Int) -> Unit)?,
        onBind: (index: Int) -> Unit,
        initialFocus: Modifier,
        modifier: Modifier,
    ) {
        GalleryGrid(items.size, modifier) { index, cellWidth ->
            onBind(index)
            GalleryMonogramCell(
                items[index],
                cellWidth,
                onClick = { onClick(index) },
                onLongPress = onLongPress?.let { { it(index) } },
                modifier = if (index == 0) initialFocus else Modifier,
            )
        }
    }

    @Composable
    override fun PosterGallery(
        items: List<PosterRowItem?>,
        onClick: (index: Int) -> Unit,
        onBind: (index: Int) -> Unit,
        initialFocus: Modifier,
        modifier: Modifier,
    ) {
        GalleryGrid(items.size, modifier) { index, cellWidth ->
            onBind(index)
            GalleryPosterCell(
                items[index],
                cellWidth,
                onClick = { onClick(index) },
                modifier = if (index == 0) initialFocus else Modifier,
            )
        }
    }
}

/** 定列数的网格, 列宽按 [viewAllGalleryCellWidth]; 滚不完的那边渐隐, 遥控器滚焦点时避开渐隐那一截. */
@Composable
private fun GalleryGrid(
    count: Int,
    modifier: Modifier,
    cell: @Composable (index: Int, cellWidth: Dp) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cellWidth = viewAllGalleryCellWidth(maxWidth)
        val state = rememberLazyGridState()
        ProvideScrollFadeFocusMargin {
            LazyVerticalGrid(
                GridCells.Fixed(VIEW_ALL_GALLERY_COLUMNS),
                Modifier.aniVerticalScrollFade(state, topFade = VIEW_ALL_GALLERY_TOP_INSET),
                state = state,
                contentPadding = PaddingValues(
                    start = VIEW_ALL_GALLERY_SIDE_INSET,
                    top = VIEW_ALL_GALLERY_TOP_INSET,
                    end = VIEW_ALL_GALLERY_SIDE_INSET,
                    bottom = VIEW_ALL_GALLERY_BOTTOM_INSET,
                ),
                horizontalArrangement = Arrangement.spacedBy(VIEW_ALL_GALLERY_COLUMN_SPACING),
                verticalArrangement = Arrangement.spacedBy(VIEW_ALL_GALLERY_ROW_SPACING),
            ) {
                items(count) { index -> cell(index, cellWidth) }
            }
        }
    }
}

/**
 * 人物画廊的一格: 圆 + 名字 + 一行副标题, 标签压在圆下缘. 聚焦 (遥控器) 时只放大圆并描一圈主题色细环, 字与标签不放大、跟着圆的下缘下移,
 * 副标题提亮 (取值同详情页圆头像行, 见 [TV_MONOGRAM_FOCUS_SCALE]). [item] 为 null = 分页还没到的占位.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryMonogramCell(
    item: PeopleRowItem?,
    size: Dp,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val showFocus = focused && !LocalTvTouchInputEnabled.current
    val progress by animateFloatAsState(
        targetValue = if (showFocus) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = TV_SCROLL_STIFFNESS),
        label = "galleryMonogramFocus",
    )
    val focusDriven = LocalAniUiBehavior.current.focusDrivenNavigation
    val placeholder = tvPeoplePlaceholderColor()
    Column(
        modifier
            .width(size)
            .onFocusChanged { focused = it.hasFocus }
            .ifThen(focusDriven && onLongPress != null && item != null) {
                tvLongPressKey(onLongPress = { onLongPress?.invoke() }, onShortPress = onClick)
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                // 示焦完全由圆的放大承担
                indication = null,
                enabled = item != null,
                onLongClick = onLongPress,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val scale = 1f + (TV_MONOGRAM_FOCUS_SCALE - 1f) * progress
        Box(Modifier.size(size)) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .ifThen(progress > 0f) {
                        border(TV_MONOGRAM_FOCUS_RING, MaterialTheme.colorScheme.primary.copy(alpha = progress.coerceIn(0f, 1f)), CircleShape)
                    }
                    .clip(CircleShape)
                    .background(if (item != null && item.imageUrl.isNullOrBlank()) MaterialTheme.colorScheme.surfaceContainerHighest else placeholder),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    item == null -> Unit
                    item.imageUrl.isNullOrBlank() -> Text(
                        monogramInitials(item.name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> AvatarImage(item.imageUrl, Modifier.matchParentSize(), alignment = Alignment.TopCenter)
                }
            }
            item?.badge?.let { badge ->
                GalleryBadge(
                    badge,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { translationY = (size / 2 * (scale - 1f) + VIEW_ALL_GALLERY_BADGE_OVERHANG).toPx() },
                )
            }
        }
        Column(
            Modifier
                .padding(top = VIEW_ALL_GALLERY_TEXT_GAP)
                .graphicsLayer { translationY = (size / 2 * (scale - 1f)).toPx() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TV_MONOGRAM_LINE_GAP),
        ) {
            Text(
                item?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            // 副标题空了也占一行: 同一排格子一样高
            Text(
                item?.subtitle.orEmpty(),
                Modifier.graphicsLayer { alpha = if (showFocus) 1f else TV_MONOGRAM_SUBTITLE_ALPHA },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 压在圆下缘的标签 (「主角」). */
@Composable
private fun GalleryBadge(text: String, modifier: Modifier = Modifier) {
    val style = viewAllGalleryBadgeStyle()
    Box(
        modifier
            .height(VIEW_ALL_GALLERY_BADGE_HEIGHT)
            .clip(CircleShape)
            .background(style.container)
            .padding(horizontal = VIEW_ALL_GALLERY_BADGE_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style.text, color = style.content, maxLines = 1)
    }
}

/** 作品画廊的一张: 2:3 海报 + 番名 + 一行小字 (遥控器上聚焦描边, 同人物页的作品卡). [item] 为 null = 分页还没到的占位. */
@Composable
private fun GalleryPosterCell(
    item: PosterRowItem?,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textInset = tvCardTextInset()
    Column(
        modifier.width(width).tvFocusableCard(onClick, enabled = item != null),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO)
                .clip(RoundedCornerShape(8.dp))
                .background(tvPeoplePlaceholderColor()),
        ) {
            item?.imageUrl?.let { AvatarImage(it, Modifier.fillMaxWidth().aspectRatio(COVER_WIDTH_TO_HEIGHT_RATIO), contentScale = ContentScale.Crop) }
        }
        Text(
            item?.title.orEmpty(),
            Modifier.padding(horizontal = textInset),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = if (item?.subtitle == null) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        item?.subtitle?.let {
            Text(
                it,
                Modifier.padding(horizontal = textInset),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
