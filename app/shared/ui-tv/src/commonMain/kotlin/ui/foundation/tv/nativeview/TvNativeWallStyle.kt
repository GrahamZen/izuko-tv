/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isSpecified
import me.him188.ani.app.ui.foundation.LocalImageCrossfade
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FOCUS_TRANSITION_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_BAR_BOTTOM_GAP
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_BAR_HEIGHT
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_BAR_LENGTH
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_TRACK_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_CORNER
import me.him188.ani.app.ui.foundation.tv.TV_PORTRAIT_CARD_COVER_RATIO
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_CARD_FOCUS_STYLE
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_COLUMN_SPACING
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_FOCUSED_ELEVATION
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_IDLE_SHADOW
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_IDLE_SHADOW_BLUR
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_IDLE_SHADOW_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_IDLE_SHADOW_OFFSET_Y
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_OUTLINE_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_OUTLINE_ALPHA_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_ROW_SPACING
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_SHADOW_COLOR_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_TITLE_IDLE_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_TITLE_IDLE_ALPHA_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_TITLE_TOP_GAP
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import me.him188.ani.app.ui.foundation.tv.focusScale
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvHeroSecondaryContentColor
import me.him188.ani.app.ui.foundation.tv.tvPosterWallLabelHeight
import me.him188.ani.app.ui.foundation.tv.tvPosterWallTitleStyle
import kotlin.math.ceil

/**
 * TextView 用的字体参数 (px / em / 字重 / 颜色), 由 Compose 的 [TextStyle] 换算 ([toTvNativeTextStyle]): 原生文字与按同一 [TextStyle] 画的
 * Compose 文字同字号、同行高、同字距、同字重. 字体家族跟系统 (应用在 Android 上本来就不换字体).
 */
@Immutable
data class TvNativeTextStyle(
    val sizePx: Float,
    val lineHeightPx: Int,
    val letterSpacingEm: Float,
    val weight: Int,
    val color: Int,
) {
    fun applyTo(view: TextView) {
        view.setTextSizePx(sizePx)
        if (view is TvNativeTextView) {
            view.fixedLineHeightPx = lineHeightPx
        } else if (Build.VERSION.SDK_INT >= 28) {
            if (lineHeightPx > 0) view.lineHeight = lineHeightPx
        } else if (lineHeightPx > 0) {
            val natural = view.paint.getFontMetricsInt(null)
            view.setLineSpacing((lineHeightPx - natural).toFloat(), 1f)
        }
        view.letterSpacing = letterSpacingEm
        view.typeface = tvNativeTypeface(weight)
        view.setTextColor(color)
    }
}

/** 换成 [TvNativeTextStyle]. 字距 sp 按字号折成 em (TextView 的字距按 em 算). */
fun TextStyle.toTvNativeTextStyle(density: Density, color: Color): TvNativeTextStyle = with(density) {
    val size = fontSize.toPx()
    val letter = when {
        !letterSpacing.isSpecified -> 0f
        letterSpacing.type == TextUnitType.Em -> letterSpacing.value
        else -> if (fontSize.value > 0f) letterSpacing.value / fontSize.value else 0f
    }
    TvNativeTextStyle(
        sizePx = size,
        // 同 Compose 的 LineHeightStyleSpan: 行高按像素向上取整
        lineHeightPx = if (lineHeight.isSpecified) ceil(lineHeight.toPx()).toInt() else 0,
        letterSpacingEm = letter,
        weight = fontWeight?.weight ?: 400,
        color = color.toArgb(),
    )
}

private val typefaces = HashMap<Int, Typeface>()

/** 系统字体的 [weight] 字重 (API 28 起能按数值取; 更早的系统只分常规 / 粗体). */
internal fun tvNativeTypeface(weight: Int): Typeface = typefaces.getOrPut(weight) {
    if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(Typeface.DEFAULT, weight, false)
    } else {
        if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}

/**
 * 原生海报墙卡片 ([TvNativeCardView]) 的尺寸 (px) 与配色 (常量见 TvPosterWall.kt), 由
 * [rememberTvNativeWallStyle] 按当前主题、界面缩放 (Compose 的 density) 与视觉效果档位算好: 原生视图只认像素, 不自己读主题.
 */
@Immutable
data class TvNativeWallStyle(
    val cardWidthPx: Int,
    /** 卡高 = 海报 + 四周聚焦框空隙 (同 TvPortraitCard 的外框). */
    val cardHeightPx: Int,
    /** 封面相对卡片外框的内缩 ([TvFocusRing.Gap]). */
    val gapPx: Int,
    val cornerPx: Float,
    /** 番名块高度 (上间距 + 两行, 见 tvPosterWallLabelHeight). */
    val labelHeightPx: Int,
    val titleTopGapPx: Int,
    val title: TvNativeTextStyle,
    val subtitleColor: Int,
    val rowSpacingPx: Int,
    val columnSpacingPx: Int,
    val focusScale: Float,
    /** 静止时海报底下那圈投影 (见 TvNativeCardShadowView): 颜色 (含透明度; 透明 = 不画), 下移, 模糊半径 (CSS 的约定, σ = 半径 / 2). */
    val idleShadowColor: Int,
    val idleShadowOffsetYPx: Float,
    val idleShadowBlurPx: Float,
    /** 聚焦时海报的系统阴影高度 (静止时为 0, 那时的影是上面那圈). */
    val focusedElevationPx: Float,
    /** 聚焦时番名往下让开多少: 放大后海报下缘多伸出 (倍数 − 1) × 卡高 / 2. */
    val titleShiftPx: Float,
    val titleIdleAlpha: Float,
    val focusMillis: Long,
    val shadowColor: Int,
    val placeholderColor: Int,
    val edgeColor: Int,
    val progressBarHeightPx: Float,
    val progressBarLengthPx: Float,
    val progressBarBottomGapPx: Float,
    val progressTrackColor: Int,
    val progressFillColor: Int,
    /** 图片加载完淡入 (视觉效果流畅档关). */
    val crossfade: Boolean,
    /** 带副标题的卡聚焦时番名跑马灯 (流畅档不滚). */
    val marquee: Boolean,
    /** 行 / 网格被外层列表预取时一起预取几张 (屏上一排放得下的张数 + 露一截的那张). */
    val prefetchItems: Int,
    /** 封面右上角的角标 (卡片的 [TvNativeCard.badge]); null = 这一组卡不画. */
    val badge: TvNativeCardBadgeStyle? = null,
) {
    val coverWidthPx: Int get() = cardWidthPx - gapPx * 2
    val coverHeightPx: Int get() = cardHeightPx - gapPx * 2

    /** 一行卡的项高: 海报 + 番名 (不含行距). */
    val cardBlockHeightPx: Int get() = cardHeightPx + labelHeightPx

    fun applyTitleText(view: TextView) = title.applyTo(view)
}

/**
 * 封面右上角的圆形角标: 直径 [sizePx]、离封面上缘与右缘 [insetPx], 底色 [backgroundColor], 中间一枚已着色的图标 [icon] (按它自己的尺寸画).
 */
@Immutable
data class TvNativeCardBadgeStyle(
    val icon: Bitmap,
    val sizePx: Float,
    val insetPx: Float,
    val backgroundColor: Int,
)

/**
 * [TvNativeWallStyle]. [cardWidth] = 卡宽 (含聚焦框空隙): 探索 / 追番 / 搜索页是 tvPosterWallCardWidth 按内容区算的,
 * 详情页的关联行另算. [columns] = 屏上一排完整放得下的张数 (预取用). [badge] 见 [TvNativeWallStyle.badge].
 * [columnSpacing] = 卡格之间的距离 (不含卡格里的聚焦框空隙). [cardHeight] = 卡格高 (含聚焦框空隙); null = 按卡宽与封面比例
 * [TV_PORTRAIT_CARD_COVER_RATIO] 算.
 */
@Composable
fun rememberTvNativeWallStyle(
    cardWidth: Dp,
    columns: Int,
    badge: TvNativeCardBadgeStyle? = null,
    columnSpacing: Dp = TV_POSTER_WALL_COLUMN_SPACING,
    cardHeight: Dp? = null,
): TvNativeWallStyle {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() >= 0.5f
    val visualEffects = LocalThemeSettings.current.visualEffects
    val crossfade = LocalImageCrossfade.current
    val titleColor = tvHeroContentColor()
    val subtitleColor = tvHeroSecondaryContentColor()
    val titleStyle = tvPosterWallTitleStyle()
    val labelHeight = tvPosterWallLabelHeight()
    return remember(density, colors, light, visualEffects, crossfade, titleColor, subtitleColor, titleStyle, labelHeight, cardWidth, columns, badge, columnSpacing, cardHeight) {
        with(density) {
            val cardWidthPx = cardWidth.roundToPx()
            val cardHeightPx = (cardHeight ?: (cardWidth / TV_PORTRAIT_CARD_COVER_RATIO)).roundToPx()
            val focusScale = TV_POSTER_WALL_CARD_FOCUS_STYLE.focusScale
            TvNativeWallStyle(
                cardWidthPx = cardWidthPx,
                cardHeightPx = cardHeightPx,
                gapPx = TvFocusRing.Gap.roundToPx(),
                cornerPx = TV_PORTRAIT_CARD_CORNER.toPx(),
                labelHeightPx = labelHeight.roundToPx(),
                titleTopGapPx = TV_POSTER_WALL_TITLE_TOP_GAP.roundToPx(),
                title = titleStyle.toTvNativeTextStyle(density, titleColor),
                subtitleColor = subtitleColor.toArgb(),
                rowSpacingPx = TV_POSTER_WALL_ROW_SPACING.roundToPx(),
                columnSpacingPx = columnSpacing.roundToPx(),
                focusScale = focusScale,
                // 流畅档静止不画那圈影
                idleShadowColor = when {
                    !visualEffects.transitions -> Color.Transparent
                    light -> TV_POSTER_WALL_IDLE_SHADOW_LIGHT
                    else -> TV_POSTER_WALL_IDLE_SHADOW
                }.toArgb(),
                idleShadowOffsetYPx = TV_POSTER_WALL_IDLE_SHADOW_OFFSET_Y.toPx(),
                idleShadowBlurPx = TV_POSTER_WALL_IDLE_SHADOW_BLUR.toPx(),
                focusedElevationPx = TV_POSTER_WALL_FOCUSED_ELEVATION.toPx(),
                titleShiftPx = (focusScale - 1f) * cardHeightPx / 2f,
                titleIdleAlpha = if (light) TV_POSTER_WALL_TITLE_IDLE_ALPHA_LIGHT else TV_POSTER_WALL_TITLE_IDLE_ALPHA,
                focusMillis = TV_CARD_FOCUS_TRANSITION_MILLIS.toLong(),
                shadowColor = (if (light) TV_POSTER_WALL_SHADOW_COLOR_LIGHT else Color.Black).toArgb(),
                placeholderColor = colors.surfaceContainerHigh.toArgb(),
                edgeColor = colors.onSurface.copy(
                    alpha = if (light) TV_POSTER_WALL_OUTLINE_ALPHA_LIGHT else TV_POSTER_WALL_OUTLINE_ALPHA,
                ).toArgb(),
                progressBarHeightPx = TV_CARD_PROGRESS_BAR_HEIGHT.toPx(),
                progressBarLengthPx = TV_CARD_PROGRESS_BAR_LENGTH.toPx(),
                progressBarBottomGapPx = TV_CARD_PROGRESS_BAR_BOTTOM_GAP.toPx(),
                progressTrackColor = Color.White.copy(alpha = TV_CARD_PROGRESS_TRACK_ALPHA).toArgb(),
                progressFillColor = colors.primary.toArgb(),
                crossfade = crossfade,
                marquee = visualEffects.marquee,
                prefetchItems = columns + 1,
                badge = badge,
            )
        }
    }
}
