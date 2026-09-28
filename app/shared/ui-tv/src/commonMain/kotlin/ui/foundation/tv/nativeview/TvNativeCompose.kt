/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_CORNER
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_ICON_GAP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_ICON_SIZE
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_OUTLINE_WIDTH
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_PADDING_HORIZONTAL
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_PADDING_VERTICAL
import me.him188.ani.app.ui.foundation.tv.TV_HERO_BUTTON_SCALE
import me.him188.ani.app.ui.foundation.tv.TV_HERO_META_GAP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_RATING_STAR_GAP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_RATING_STAR_SIZE
import me.him188.ani.app.ui.foundation.tv.TV_REDUCED_MARQUEE_ITERATIONS
import me.him188.ani.app.ui.foundation.tv.TV_SCROLL_HIDDEN_TEXT_SLIDE_DISTANCE
import me.him188.ani.app.ui.foundation.tv.TvPolishFlags
import me.him188.ani.app.ui.foundation.tv.tvHeroButtonContainerColor
import me.him188.ani.app.ui.foundation.tv.tvHeroButtonOutlineColor
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor

/**
 * 装原生页面的 AndroidView: 铺满, 再向左出血 [bleedLeft] (页面本身让开了收起的侧边栏, 横滑行要从侧边栏底下滑过、从屏幕左缘出屏).
 * 单独一层 graphicsLayer: 原生树每次失效只重录这一层 (里面就是一条画原生 RenderNode 的指令).
 * 视图只建一次 ([factory]), 主题 / 尺寸 / 数据的变化都走 [update], 焦点与滚动位置不因重组丢.
 */
@Composable
fun <T : View> TvNativeHost(
    factory: (Context) -> T,
    update: (T) -> Unit,
    bleedLeft: Dp,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = factory,
        modifier = modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val bleed = bleedLeft.roundToPx()
                val width = constraints.maxWidth + bleed
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .graphicsLayer(),
        update = update,
    )
}

/**
 * 装一条原生卡片行的 AndroidView (详情页那种夹在 Compose 内容中间的行): 布局上只占 [height] 高, 视图本身上下各多出 [bleedVertical]
 * (聚焦卡放大、投影伸出行外, 装它的 AndroidView 会按自己的边界裁掉子视图), 宽度铺满.
 */
@Composable
fun <T : View> TvNativeRowHost(
    factory: (Context) -> T,
    update: (T) -> Unit,
    height: Dp,
    bleedVertical: Dp,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            factory(context).also { view ->
                // 装它的那层 (AndroidViewHolder) 默认按边界裁子视图; 出血靠布局给大, 这里只是保险
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        (v.parent as? ViewGroup)?.clipChildren = false
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val bleed = bleedVertical.roundToPx()
                val rowHeight = height.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = rowHeight + bleed * 2, maxHeight = rowHeight + bleed * 2),
                )
                layout(placeable.width, rowHeight) { placeable.place(0, -bleed) }
            }
            .graphicsLayer(),
        update = update,
    )
}

/**
 * 长按菜单的目标, 收起后还留着: 菜单收起时要淡出 (见 AniDropdownMenu 的定位菜单), 这期间照样组合着上一次的目标、只把展开置假.
 * [shown] 是此刻展开的那个 (null = 收起).
 */
@Composable
internal fun <T : Any> rememberTvMenuTarget(shown: T?): T? {
    val last = remember { TvMenuTargetHolder<T>() }
    if (shown != null) last.value = shown
    return shown ?: last.value
}

private class TvMenuTargetHolder<T : Any> {
    var value: T? = null
}

/** 把 Compose 的矢量图标按 [size] 画成白色位图 (原生侧用 colorFilter 着色); [tint] 给了就直接画成那个颜色. */
@Composable
fun rememberTvNativeIcon(icon: ImageVector, size: Dp, tint: Color = Color.White): Bitmap {
    val painter = rememberVectorPainter(icon)
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }.coerceAtLeast(1)
    return remember(icon, px, tint, density) {
        val image = ImageBitmap(px, px)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(px.toFloat(), px.toFloat())) {
            with(painter) { draw(this@draw.size, colorFilter = ColorFilter.tint(tint)) }
        }
        image.asAndroidBitmap()
    }
}

/**
 * hero 文字块的排版参数: 标题 headlineLarge、评分 titleMedium (主色)、信息行与下一集行 labelLarge、
 * 简介 bodyMedium; 颜色取 hero 前景 / 次要色. [statusPrimary] = 下一集行用主色 (追番页), 否则次要色 (探索页).
 */
@Composable
fun rememberTvNativeHeroTextStyle(
    titleMaxLines: Int,
    lineSpacing: Dp,
    statusHeight: Dp = 0.dp,
): TvNativeHeroTextStyle {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val content = tvHeroContentColor()
    val secondary = tvHeroSecondaryContentColor()
    val visualEffects = LocalThemeSettings.current.visualEffects
    val star = rememberTvNativeIcon(Icons.Rounded.Star, TV_HERO_RATING_STAR_SIZE, colors.primary)
    val stagger = visualEffects.transitions && TvPolishFlags.textStagger
    return remember(density, typography, colors, content, secondary, visualEffects, star, stagger, titleMaxLines, lineSpacing, statusHeight) {
        with(density) {
            TvNativeHeroTextStyle(
                title = typography.headlineLarge.toTvNativeTextStyle(density, content),
                titleMaxLines = titleMaxLines,
                rating = typography.titleMedium.toTvNativeTextStyle(density, colors.primary),
                meta = typography.labelLarge.toTvNativeTextStyle(density, secondary),
                status = typography.labelLarge.toTvNativeTextStyle(density, secondary),
                summary = typography.bodyMedium.toTvNativeTextStyle(density, content),
                star = star,
                starSizePx = TV_HERO_RATING_STAR_SIZE.roundToPx(),
                starGapPx = TV_HERO_RATING_STAR_GAP.roundToPx(),
                metaGapPx = TV_HERO_META_GAP.roundToPx(),
                lineSpacingPx = lineSpacing.roundToPx(),
                statusHeightPx = statusHeight.roundToPx(),
                slidePx = TV_SCROLL_HIDDEN_TEXT_SLIDE_DISTANCE.roundToPx(),
                stagger = stagger,
                animated = visualEffects.transitions,
                marqueeRepeat = when {
                    !visualEffects.marquee -> 0
                    visualEffects.ambient -> -1
                    else -> TV_REDUCED_MARQUEE_ITERATIONS
                },
            )
        }
    }
}

/**
 * hero 操作按钮的外观: 取值同 TvHeroButton 在海报墙主题下 (按 [TV_HERO_BUTTON_SCALE] 缩放的内边距 / 图标 / titleSmall 字号, 底板与描边色).
 */
@Composable
fun rememberTvNativeHeroButtonStyle(): TvNativeHeroButtonStyle {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val dark = colors.surface.luminance() < 0.5f
    return remember(density, colors, typography, dark) {
        with(density) {
            val scale = TV_HERO_BUTTON_SCALE
            val text = typography.titleSmall.let { it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) }
            TvNativeHeroButtonStyle(
                text = text.toTvNativeTextStyle(density, colors.onSurface),
                iconSizePx = (TV_HERO_BUTTON_ICON_SIZE * scale).roundToPx(),
                iconGapPx = (TV_HERO_BUTTON_ICON_GAP * scale).roundToPx(),
                paddingHorizontalPx = (TV_HERO_BUTTON_PADDING_HORIZONTAL * scale).roundToPx(),
                paddingVerticalPx = (TV_HERO_BUTTON_PADDING_VERTICAL * scale).roundToPx(),
                cornerPx = TV_HERO_BUTTON_CORNER.toPx(),
                outlineWidthPx = TV_HERO_BUTTON_OUTLINE_WIDTH.toPx(),
                outlineColor = tvHeroButtonOutlineColor(dark).toArgb(),
                filledColor = tvHeroButtonContainerColor(colors, filled = true, posterWall = true).toArgb(),
                unfilledColor = tvHeroButtonContainerColor(colors, filled = false, posterWall = true).toArgb(),
                focusedColor = colors.primary.toArgb(),
                contentColor = colors.onSurface.toArgb(),
                focusedContentColor = colors.onPrimary.toArgb(),
            )
        }
    }
}


