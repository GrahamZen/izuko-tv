/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.pad.foundation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.him188.ani.app.ui.foundation.AsyncImage

/*
 * 平板海报墙: 尺寸与配色取电视海报墙 (ui-tv 的 TvPosterWall.kt) 的同一套定案 —— 照 Apple TV 的海报行与深色灰阶定的;
 * ui-tv 只编 Android, 这里是照抄的一份, 两边改一处要同步另一处. 交互是触屏: 没有焦点放大, 按下微缩一下, 点了就走.
 */

/** 卡片最小宽度: 同电视海报墙, 列数按它与列距从页面宽度算出来 (见 [rememberPadPosterWallLayout]). */
val PAD_POSTER_WALL_CARD_MIN_WIDTH: Dp = 118.dp

/** 卡片之间的列距. */
val PAD_POSTER_WALL_COLUMN_SPACING: Dp = 20.dp

/** 页面左右留白: Apple TV 的屏幕安全区 80 pt (1 pt = 0.5 dp). 行首卡片与组标题都从这里起. */
val PAD_POSTER_WALL_PAGE_MARGIN: Dp = 40.dp

/** 深色主题的页面底色: Apple TV App 的页面底 (44, 44, 46), 不是纯黑. */
val PAD_POSTER_WALL_BACKGROUND_DARK: Color = Color(0xFF2C2C2E)

/** 浅色主题的页面底色: Apple TV App 关掉透明效果时浅色的实色页面底. */
val PAD_POSTER_WALL_BACKGROUND_LIGHT: Color = Color(0xFFBFC4C7)

/** 竖版海报的宽高比. */
const val PAD_POSTER_ASPECT_RATIO: Float = 2f / 3f

/** 海报圆角: Apple 竖版海报类货架一律 20 pt. */
val PAD_POSTER_SHAPE: RoundedCornerShape = RoundedCornerShape(10.dp)

private val PAD_POSTER_WALL_GRAY4 = Color(0xFF3A3A3C)
private val PAD_POSTER_WALL_PLATTER = Color(0xFF414143)
private val PAD_POSTER_WALL_GRAY3 = Color(0xFF48484A)
private val PAD_POSTER_WALL_PLATTER_HIGH = Color(0xFF565658)
private val PAD_POSTER_WALL_PLATTER_LIGHT = Color(0xFFC5CACD)
private val PAD_POSTER_WALL_GRAY6_LIGHT = Color(0xFFCCD0D2)
private val PAD_POSTER_WALL_FOCUS_LIGHT = Color(0xFFC2C7CA)
private val PAD_POSTER_WALL_SUNKEN_LIGHT = Color(0xFFB9BEC1)
private val PAD_POSTER_WALL_SECONDARY_LABEL_LIGHT = Color(0xFF4C4E50)

/** 静止投影: 电视上是黑 40%、下移 2 dp、模糊 6 dp 的一圈淡影, 这里用系统阴影近似. */
private val PAD_POSTER_IDLE_ELEVATION = 4.dp

/** 按下时卡片缩到多大: 触屏没有聚焦放大, 用按下微缩表示「点到了」. */
private const val PAD_POSTER_PRESSED_SCALE = 0.96f

private val PAD_POSTER_TITLE_TOP_GAP = 6.dp
private val PAD_POSTER_TITLE_FONT_SIZE = 13.sp
private val PAD_POSTER_TITLE_LINE_HEIGHT = 18.sp

/** 番名下面那行小字: tvOS 的 Caption 2, 比番名小一档、淡一档. */
private val PAD_POSTER_SUBTITLE_FONT_SIZE = 11.5.sp
private const val PAD_POSTER_SUBTITLE_ALPHA = 0.6f
private val PAD_POSTER_WALL_HEADER_FONT_SIZE = 15.5.sp
private val PAD_POSTER_WALL_HEADER_LINE_HEIGHT = 23.sp

/**
 * 番名的亮度: 电视上未聚焦的番名压到一半、聚焦才全亮; 触屏没有焦点, 一律取这一档, 不然整墙番名都是暗的.
 */
private const val PAD_POSTER_TITLE_ALPHA = 0.85f

/**
 * 海报墙页面的配色: Material 的各档底色换成 Apple TV 深色 / 浅色的那几档灰 (同电视的 tvPosterWallColorScheme),
 * 主题色不动. 菜单、弹窗、卡片底铺在灰底页面上才不是一块块黑的.
 */
fun padPosterWallColorScheme(base: ColorScheme): ColorScheme =
    if (base.surface.luminance() >= 0.5f) {
        base.copy(
            background = PAD_POSTER_WALL_BACKGROUND_LIGHT,
            surface = PAD_POSTER_WALL_BACKGROUND_LIGHT,
            surfaceDim = PAD_POSTER_WALL_BACKGROUND_LIGHT,
            surfaceContainerLowest = PAD_POSTER_WALL_BACKGROUND_LIGHT,
            surfaceContainerLow = PAD_POSTER_WALL_PLATTER_LIGHT,
            surfaceContainer = PAD_POSTER_WALL_GRAY6_LIGHT,
            surfaceContainerHigh = PAD_POSTER_WALL_FOCUS_LIGHT,
            surfaceContainerHighest = PAD_POSTER_WALL_SUNKEN_LIGHT,
            surfaceBright = PAD_POSTER_WALL_GRAY6_LIGHT,
            surfaceVariant = PAD_POSTER_WALL_PLATTER_LIGHT,
            onBackground = Color.Black,
            onSurface = Color.Black,
            onSurfaceVariant = PAD_POSTER_WALL_SECONDARY_LABEL_LIGHT,
        )
    } else {
        base.copy(
            background = PAD_POSTER_WALL_BACKGROUND_DARK,
            surface = PAD_POSTER_WALL_BACKGROUND_DARK,
            surfaceDim = PAD_POSTER_WALL_BACKGROUND_DARK,
            surfaceContainerLowest = PAD_POSTER_WALL_BACKGROUND_DARK,
            surfaceContainerLow = PAD_POSTER_WALL_GRAY4,
            surfaceContainer = PAD_POSTER_WALL_PLATTER,
            surfaceContainerHigh = PAD_POSTER_WALL_GRAY3,
            surfaceContainerHighest = PAD_POSTER_WALL_PLATTER_HIGH,
            surfaceBright = PAD_POSTER_WALL_GRAY3,
            surfaceVariant = PAD_POSTER_WALL_GRAY4,
        )
    }

/** 给页面换上 [padPosterWallColorScheme]. 配色对象按底色方案记住: 换实例会让整页重组. */
@Composable
fun PadPosterWallTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(base) { padPosterWallColorScheme(base) }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 一行卡片的排法: [columns] 张完整的卡铺满页面去掉两侧留白后的宽度, 下一张从右边留白里露出一截. */
@Immutable
class PadPosterWallLayout(
    val columns: Int,
    val cardWidth: Dp,
    val margin: Dp,
)

@Composable
fun rememberPadPosterWallLayout(pageWidth: Dp): PadPosterWallLayout {
    val density = LocalDensity.current
    return remember(pageWidth, density) {
        val available = (pageWidth - PAD_POSTER_WALL_PAGE_MARGIN * 2).coerceAtLeast(PAD_POSTER_WALL_CARD_MIN_WIDTH)
        val columns = with(density) {
            val spacing = PAD_POSTER_WALL_COLUMN_SPACING.roundToPx()
            maxOf(1, (available.roundToPx() + spacing) / (PAD_POSTER_WALL_CARD_MIN_WIDTH.roundToPx() + spacing))
        }
        PadPosterWallLayout(
            columns = columns,
            cardWidth = (available - PAD_POSTER_WALL_COLUMN_SPACING * (columns - 1)) / columns,
            margin = PAD_POSTER_WALL_PAGE_MARGIN,
        )
    }
}

/**
 * 海报卡: 竖版海报 + 下方两行定高的番名; 有 [subtitle] (新番时间表的放送时间与集数) 时番名一行、下面这行小字一行, 总高不变.
 * [progress] 非 null 时海报底部画观看进度 (继续观看行); [overlay] 画在海报那一块里, 长按弹出的菜单放这里就锚在海报上.
 */
@Composable
fun PadPosterCard(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    coverDownsample: Int? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PAD_POSTER_PRESSED_SCALE else 1f, label = "padPosterPress")
    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        PadPosterFrame {
            AsyncImage(
                coverUrl,
                contentDescription = title,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                downsampleLongEdgePx = coverDownsample,
            )
            if (progress != null) {
                PadPosterProgress(progress, Modifier.align(Alignment.BottomCenter))
            }
            overlay()
        }
        PadPosterTitle(title, subtitle)
    }
}

/** 数据还没到的那一格: 海报那块铺底色, 番名留空, 尺寸与 [PadPosterCard] 相同. */
@Composable
fun PadPosterPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier) {
        PadPosterFrame {}
        PadPosterTitle("")
    }
}

/** 一组推荐行尾的「更多」: 按这一组的来源接着推荐. [loading] 时画成加载中, 不响应点击. */
@Composable
fun PadPosterMoreCard(
    label: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PadPosterCard(
        title = "",
        coverUrl = null,
        onClick = { if (!loading) onClick() },
        modifier = modifier,
        overlay = {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)
            } else {
                Text(
                    label,
                    Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = PAD_POSTER_WALL_HEADER_FONT_SIZE,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        },
    )
}

/** 组标题: 同电视海报墙的货架标题 (Apple TV 的 Callout). */
@Composable
fun PadSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier.padding(
            start = PAD_POSTER_WALL_PAGE_MARGIN,
            end = PAD_POSTER_WALL_PAGE_MARGIN,
            top = 24.dp,
            bottom = 12.dp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = PAD_POSTER_WALL_HEADER_FONT_SIZE,
        lineHeight = PAD_POSTER_WALL_HEADER_LINE_HEIGHT,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PadPosterFrame(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(PAD_POSTER_ASPECT_RATIO)
            .shadow(PAD_POSTER_IDLE_ELEVATION, PAD_POSTER_SHAPE)
            .clip(PAD_POSTER_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

@Composable
private fun PadPosterTitle(title: String, subtitle: String? = null) {
    val color = MaterialTheme.colorScheme.onSurface
    Column(Modifier.padding(top = PAD_POSTER_TITLE_TOP_GAP).fillMaxWidth()) {
        Text(
            title,
            Modifier.fillMaxWidth(),
            color = color.copy(alpha = PAD_POSTER_TITLE_ALPHA),
            fontSize = PAD_POSTER_TITLE_FONT_SIZE,
            lineHeight = PAD_POSTER_TITLE_LINE_HEIGHT,
            maxLines = if (subtitle == null) 2 else 1,
            minLines = if (subtitle == null) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                Modifier.fillMaxWidth(),
                color = color.copy(alpha = PAD_POSTER_SUBTITLE_ALPHA),
                fontSize = PAD_POSTER_SUBTITLE_FONT_SIZE,
                lineHeight = PAD_POSTER_TITLE_LINE_HEIGHT,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 海报底部的观看进度: 半透明黑轨道上一段主题色. */
@Composable
private fun PadPosterProgress(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** 与 [PadPosterCard] 等宽的格子, 给行里按页面列宽排卡用. */
fun Modifier.padPosterCardWidth(layout: PadPosterWallLayout): Modifier = width(layout.cardWidth)
