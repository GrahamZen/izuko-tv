/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.github.panpf.sketch.LocalPlatformContext
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.rememberAsyncImageRetryState
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FOCUS_TRANSITION_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_BAR_BOTTOM_GAP
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_BAR_HEIGHT
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_TRACK_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_OBSCURED_COVER_LONG_EDGE_PX
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_CORNER
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_COVER_RATIO
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_CARD_FOCUS_STYLE
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_FOCUSED_ELEVATION
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_OUTLINE_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_OUTLINE_ALPHA_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_ROW_SPACING
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_SECONDARY_LABEL_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_SECONDARY_LABEL_ALPHA_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_SHADOW_COLOR_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_TITLE_TOP_GAP
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import me.him188.ani.app.ui.foundation.tv.focusScale
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_NATIVE_MORE_GLASS_DARK
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_NATIVE_MORE_GLASS_LIGHT
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCard
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeCardBadgeStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMore
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeMoreGlassStyle
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.tvFetchBlurredBackdrop
import me.him188.ani.app.ui.foundation.tv.tvAmbientMarquee
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvPosterWallLabelHeight
import me.him188.ani.app.ui.foundation.tv.tvPosterWallSubtitleStyle
import me.him188.ani.app.ui.foundation.tv.tvPosterWallTitleStyle
import me.him188.ani.app.ui.foundation.tv.tvTouchFocusOnTap
import me.him188.ani.app.ui.foundation.tvLongPressKey
import me.him188.ani.app.ui.foundation.tv.TvModulateAlphaCompositing

/*
 * Compose 版海报墙 (安卓电视上是原生视图, 见 nativeview 包; 别的端画这一套) 的卡片: 竖版海报 + 下方定高两行番名, 外观与原生卡片
 * (TvNativeCardView) 取同一组常量 (TvPosterWall.kt). 尺寸按 [TvWallCardStyle] 给, 页面按卡宽算好.
 */

/**
 * 卡片的尺寸与配色. 宽高 (含封面四周的聚焦框空隙 [gapPx]) 由页面按内容区算好; 其余 (番名字号与块高、圆角、投影) 按海报墙大小
 * ([LocalTvPosterWallScale]) 缩放, 番名在 [textDensity] 下排.
 */
@Immutable
internal class TvWallCardStyle(
    val cardWidthPx: Int,
    val cardHeightPx: Int,
    val gapPx: Int,
    val cornerPx: Float,
    val labelHeightPx: Int,
    val titleTopGapPx: Int,
    val rowSpacingPx: Int,
    val textDensity: Density,
    val titleStyle: TextStyle,
    val subtitleStyle: TextStyle,
    val titleColor: Color,
    /** 没聚焦时番名的不透明度 (聚焦时全亮). */
    val labelIdleAlpha: Float,
    val focusScale: Float,
    val idleElevationPx: Float,
    val focusedElevationPx: Float,
    val shadowColor: Color,
    val edgeColor: Color,
    val placeholderColor: Color,
    val progressFill: Color,
    val moreGlass: TvNativeMoreGlassStyle,
    val badge: TvNativeCardBadgeStyle?,
    val animated: Boolean,
    val marquee: Boolean,
) {
    val coverWidthPx: Int get() = cardWidthPx - gapPx * 2
    val coverHeightPx: Int get() = cardHeightPx - gapPx * 2

    /** 一张卡的块高: 海报 + 番名 (不含行距). */
    val blockHeightPx: Int get() = cardHeightPx + labelHeightPx
}

/**
 * [TvWallCardStyle]: [cardWidth] = 卡宽 (含聚焦框空隙), [cardHeight] = 卡高 (null = 按卡宽与封面比例算). [badge] 给了就按卡片的
 * [TvNativeCard.badge] 画角标.
 */
@Composable
internal fun rememberTvWallCardStyle(
    cardWidth: Dp,
    cardHeight: Dp? = null,
    badge: TvNativeCardBadgeStyle? = null,
): TvWallCardStyle {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() >= 0.5f
    val visualEffects = LocalThemeSettings.current.visualEffects
    val titleColor = tvHeroContentColor()
    val titleStyle = tvPosterWallTitleStyle()
    val subtitleStyle = tvPosterWallSubtitleStyle()
    val labelHeight = tvPosterWallLabelHeight()
    val cardScale = LocalTvPosterWallScale.current
    return remember(density, cardScale, colors, light, visualEffects, titleColor, titleStyle, subtitleStyle, labelHeight, cardWidth, cardHeight, badge) {
        val cardWidthPx = with(density) { cardWidth.roundToPx() }
        val cardHeightPx = with(density) { (cardHeight ?: (cardWidth / TV_PORTRAIT_CARD_COVER_RATIO)).roundToPx() }
        // 卡片自己的尺寸按海报墙大小换算 (卡宽卡高已经由调用方按它算好)
        val cardDensity = if (cardScale == 1f) density else Density(density.density * cardScale, density.fontScale)
        with(cardDensity) {
            TvWallCardStyle(
                cardWidthPx = cardWidthPx,
                cardHeightPx = cardHeightPx,
                gapPx = TvFocusRing.Gap.roundToPx(),
                cornerPx = TV_PORTRAIT_CARD_CORNER.toPx(),
                labelHeightPx = labelHeight.roundToPx(),
                titleTopGapPx = TV_POSTER_WALL_TITLE_TOP_GAP.roundToPx(),
                rowSpacingPx = TV_POSTER_WALL_ROW_SPACING.roundToPx(),
                textDensity = cardDensity,
                titleStyle = titleStyle,
                subtitleStyle = subtitleStyle,
                titleColor = titleColor,
                labelIdleAlpha = if (light) TV_POSTER_WALL_SECONDARY_LABEL_ALPHA_LIGHT else TV_POSTER_WALL_SECONDARY_LABEL_ALPHA,
                focusScale = TV_POSTER_WALL_CARD_FOCUS_STYLE.focusScale,
                // 流畅档静止不抬高 (不画那层淡影)
                idleElevationPx = if (visualEffects.transitions) TV_WALL_CARD_IDLE_ELEVATION.toPx() else 0f,
                focusedElevationPx = TV_POSTER_WALL_FOCUSED_ELEVATION.toPx(),
                shadowColor = if (light) TV_POSTER_WALL_SHADOW_COLOR_LIGHT else Color.Black,
                edgeColor = colors.onSurface.copy(alpha = if (light) TV_POSTER_WALL_OUTLINE_ALPHA_LIGHT else TV_POSTER_WALL_OUTLINE_ALPHA),
                placeholderColor = colors.surfaceContainerHigh,
                progressFill = colors.primary,
                moreGlass = if (light) TV_NATIVE_MORE_GLASS_LIGHT else TV_NATIVE_MORE_GLASS_DARK,
                badge = badge,
                animated = visualEffects.transitions,
                marquee = visualEffects.marquee,
            )
        }
    }
}

/**
 * 海报墙的一张卡 ([card] 为 null = 分页还没到的占位): 海报承载焦点 / 点击 / 长按, 下方定高两行番名; 给了副标题时番名只占第一行
 * (聚焦时放不下跑马灯), 第二行是副标题. 聚焦时海报放大 ([TvWallCardStyle.focusScale]) 并抬起一大片软影, 番名全亮、往下让开放大多伸出的那截.
 * 触屏上点即聚焦再点击 (见 tvTouchFocusOnTap; [touchFocusOnPress] 为 false 时点一下只是点击). 焦点与点击只在封面上 (四周的聚焦框空隙、下面的番名都点不到, 同原生卡片);
 * 焦点请求器挂在 [modifier] 上 (请求委托给封面那个焦点目标).
 *
 * @param onLongPress 长按 (遥控器确定键长按 / 触屏长按), 参数 = 封面在窗口里的框 (菜单的锚点); null = 不认长按.
 * @param dimAlpha 卡片整体的淡化 (0..1, 绘制阶段读): 越过顶线 / hero 态越线的卡. 乘进放大层与番名层, 不在外面另包图层 (投影不吃外层透明度).
 * @param labelAlpha 番名的显隐 (0..1, 绘制阶段读): hero 态里淡出.
 * @param holdFocusLook 不论有没有焦点都画成聚焦的样子 (返回本页时进页落点那张, 焦点到了不再放大一遍).
 * @param touchTwoStep 触屏上没聚焦的卡第一下只聚焦, 已聚焦再点才是点击 (hero 态里换卡只换焦点).
 * @param showFocusLook 聚焦时画不画放大与抬起的投影: 触屏上卡片墙不画 (没有遥控器, 焦点对用户没意义), hero 态里聚焦那张照画.
 * @param touchFocusOnPress 触屏上按下即把焦点拿过来 (见 tvTouchFocusOnTap). 海报墙按触屏的规矩走时为 false: 按下不动焦点 (拖动不会先把焦点
 *   和画面带走), 点一下交给 [onClick], 要不要聚焦由调用方定.
 */
@Composable
internal fun TvWallCard(
    card: TvNativeCard?,
    style: TvWallCardStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    onLongPress: ((Rect) -> Unit)? = null,
    dimAlpha: () -> Float = { 1f },
    labelAlpha: () -> Float = { 1f },
    holdFocusLook: Boolean = false,
    touchTwoStep: Boolean = false,
    showFocusLook: Boolean = true,
    touchFocusOnPress: Boolean = true,
) {
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    val focusWeight by animateFloatAsState(
        if (showFocusLook && (focused || holdFocusLook)) 1f else 0f,
        if (style.animated) tween(TV_CARD_FOCUS_TRANSITION_MILLIS) else snap(),
    )
    val coverBounds = remember { CoverBounds() }
    val currentLongPress by rememberUpdatedState(onLongPress)
    val longPress: (() -> Unit)? = if (onLongPress != null) ({ currentLongPress?.invoke(coverBounds.rect(style.gapPx.toFloat())) }) else null
    val interactionSource = remember { MutableInteractionSource() }
    val cardWidth = with(density) { style.cardWidthPx.toDp() }
    val cardHeight = with(density) { style.cardHeightPx.toDp() }
    val labelHeight = with(density) { style.labelHeightPx.toDp() }
    Column(
        modifier
            .width(cardWidth)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                }
            },
    ) {
        Box(
            Modifier
                .size(cardWidth, cardHeight)
                .onGloballyPositioned { coverBounds.window = it.boundsInWindow() }
                .graphicsLayer {
                    val w = focusWeight
                    val s = 1f + (style.focusScale - 1f) * w
                    scaleX = s
                    scaleY = s
                    compositingStrategy = TvModulateAlphaCompositing
                    val d = dimAlpha()
                    alpha = d
                    // 系统阴影按封面那一块投 (卡片四周留着聚焦框空隙)
                    shape = TvWallCoverOutline(style.gapPx.toFloat(), style.cornerPx)
                    clip = false
                    shadowElevation = lerp(style.idleElevationPx, style.focusedElevationPx, w)
                    // 半透明时阴影按透明遮挡画 (封面底下整块填满), 会从半透明的封面里透出来: 按淡化的平方收一道
                    val shadow = style.shadowColor.copy(alpha = style.shadowColor.alpha * d * d)
                    ambientShadowColor = shadow
                    spotShadowColor = shadow
                },
        ) {
            val shape = RoundedCornerShape(with(density) { style.cornerPx.toDp() })
            Box(
                Modifier.fillMaxSize()
                    .padding(with(density) { style.gapPx.toDp() })
                    .then(if (longPress != null) Modifier.tvLongPressKey(onLongPress = longPress, onShortPress = onClick) else Modifier)
                    .then(if (touchFocusOnPress) Modifier.tvTouchFocusOnTap(twoStep = touchTwoStep) else Modifier)
                    .combinedClickable(
                        interactionSource = interactionSource,
                        // 聚焦态只由放大与投影表达, 不叠涟漪
                        indication = null,
                        onClick = onClick,
                        onLongClick = longPress,
                    )
                    .clip(shape)
                    .background(style.placeholderColor),
            ) {
                if (card != null) TvWallCardCover(card, style)
            }
            // 玻璃边: 封面边缘一圈细线
            Box(
                Modifier.fillMaxSize()
                    .padding(with(density) { style.gapPx.toDp() })
                    .border(Dp.Hairline, style.edgeColor, shape),
            )
        }
        CompositionLocalProvider(LocalDensity provides style.textDensity) {
            TvWallCardLabel(
                card,
                style,
                focused = focused,
                modifier = Modifier.fillMaxWidth()
                    .height(labelHeight)
                    .graphicsLayer {
                        val w = focusWeight
                        // 放大后海报下缘多伸出 (倍数 − 1) × 卡高 / 2: 番名往下让开, 只挪绘制, 行高不变
                        translationY = (style.focusScale - 1f) * w * style.cardHeightPx / 2f
                        compositingStrategy = TvModulateAlphaCompositing
                        alpha = (style.labelIdleAlpha + (1f - style.labelIdleAlpha) * w) * labelAlpha() * dimAlpha()
                    },
            )
        }
    }
}

/** 封面内容: 海报 (打码的降采样成糊图) / 「更多」卡的玻璃 (底下是模糊小图), 进度条, 角标. */
@Composable
private fun TvWallCardCover(card: TvNativeCard, style: TvWallCardStyle) {
    val density = LocalDensity.current
    val more = card.more
    Box(Modifier.fillMaxSize()) {
        val url = card.imageUrl
        if (url != null && more != null) {
            TvWallMoreBlur(url, style.coverWidthPx, style.coverHeightPx)
        } else if (url != null) {
            val retry = rememberAsyncImageRetryState(url)
            AsyncImage(
                if (retry.suppressed) null else url,
                contentDescription = card.title,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { retry.onError() },
                downsampleLongEdgePx = if (card.obscure) TV_OBSCURED_COVER_LONG_EDGE_PX else null,
                crossfade = style.animated,
            )
        }
        if (more != null) {
            TvWallMoreGlass(more, style.moreGlass)
        }
        val progress = card.progress
        if (progress != null && progress > 0f) {
            // 条长按封面宽去掉两个圆角那一截, 两端落在圆角的切点上 (同原生卡片)
            val length = with(density) { (style.coverWidthPx - style.cornerPx * 2 + 2.dp.toPx()).toDp() }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = TV_CARD_PROGRESS_BAR_BOTTOM_GAP)
                    .width(length)
                    .height(TV_CARD_PROGRESS_BAR_HEIGHT)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = TV_CARD_PROGRESS_TRACK_ALPHA)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(TV_CARD_PROGRESS_BAR_HEIGHT)
                        .background(style.progressFill),
                )
            }
        }
        val badge = style.badge
        if (badge != null && card.badge) {
            val size = with(density) { badge.sizePx.toDp() }
            val inset = with(density) { badge.insetPx.toDp() }
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset(x = -inset, y = inset)
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(badge.backgroundColor)),
                contentAlignment = Alignment.Center,
            ) {
                Image(badge.icon, contentDescription = null, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 「更多」卡的玻璃底: 那张竖版封面解成小图、在解码线程上模糊, 再拉伸铺满封面框 (同原生「更多」卡; 只降采样不模糊的话放大出来是一格一格的).
 */
@Composable
private fun TvWallMoreBlur(url: String, widthPx: Int, heightPx: Int) {
    val sketch = LocalSketch.current
    val context = LocalPlatformContext.current
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, widthPx, heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
        image = tvFetchBlurredBackdrop(sketch, context, url, widthPx, heightPx, TV_WALL_MORE_BLUR)?.bitmap
    }
    val shown = image ?: return
    Image(
        shown,
        contentDescription = null,
        Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        // 模糊后的图没有细节, 双线性放大就看不出是小图
        filterQuality = FilterQuality.Low,
    )
}

/** 「更多」卡玻璃底的小图与模糊半径 (同原生「更多」卡). */
private val TV_WALL_MORE_BLUR = TvBackdropBlurSpec(longEdgePx = 64, radiusPx = 8)

/** 「更多」卡: 糊图上铺一层玻璃, 中间一枚圆钮托着加号, 加载中换成转着的圆弧. */
@Composable
private fun TvWallMoreGlass(more: TvNativeMore, glass: TvNativeMoreGlassStyle) {
    Box(
        Modifier.fillMaxSize()
            .background(Color(glass.tint))
            .border(Dp.Hairline, Color(glass.edge)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(TV_WALL_MORE_BUTTON_SIZE)
                .clip(CircleShape)
                .background(Color(glass.buttonFill))
                .border(Dp.Hairline, Color(glass.buttonEdge), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (more) {
                TvNativeMore.Idle -> Icon(Icons.Rounded.Add, contentDescription = null, tint = Color(glass.icon))
                TvNativeMore.Loading -> CircularProgressIndicator(
                    Modifier.size(TV_WALL_MORE_BUTTON_SIZE / 2),
                    color = Color(glass.icon),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** 番名块: 两行番名, 或一行番名 + 一行副标题 (番名聚焦时放不下跑马灯). */
@Composable
private fun TvWallCardLabel(card: TvNativeCard?, style: TvWallCardStyle, focused: Boolean, modifier: Modifier) {
    val topGap = with(LocalDensity.current) { style.titleTopGapPx.toDp() }
    val title = card?.title.orEmpty()
    val subtitle = card?.subtitle
    if (subtitle == null) {
        Text(
            title,
            modifier.padding(top = topGap),
            color = style.titleColor,
            style = style.titleStyle,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        val marquee = focused && style.marquee
        Column(modifier.padding(top = topGap)) {
            Text(
                title,
                Modifier.tvAmbientMarquee(enabled = marquee, iterations = Int.MAX_VALUE),
                color = style.titleColor,
                style = style.titleStyle,
                maxLines = 1,
                overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = card.subtitleColor?.let { Color(it) } ?: style.titleColor,
                style = style.subtitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 卡片 (含聚焦框空隙) 在窗口里的框; 菜单锚在封面上 (往里缩一个空隙). 只在回调里读, 不进快照. */
private class CoverBounds {
    var window: Rect = Rect.Zero

    fun rect(gapPx: Float): Rect = Rect(window.left + gapPx, window.top + gapPx, window.right - gapPx, window.bottom - gapPx)
}

/** 放大层的轮廓 = 封面那一块 (四周比封面多一个聚焦框空隙): 系统阴影按它投, 阴影的边贴着封面. */
private data class TvWallCoverOutline(val gapPx: Float, val cornerPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(gapPx, gapPx, size.width - gapPx, size.height - gapPx, CornerRadius(cornerPx)))
}

/** 卡片静止时的高度: 贴身一圈淡影. */
private val TV_WALL_CARD_IDLE_ELEVATION = 2.dp

/** 「更多」卡中间圆钮的直径. */
private val TV_WALL_MORE_BUTTON_SIZE = 44.dp
