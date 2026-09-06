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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.isSpecified
import me.him188.ani.app.ui.foundation.LocalImageCrossfade
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.LocalTvPosterWallScale
import me.him188.ani.app.ui.foundation.tv.TV_CARD_FOCUS_TRANSITION_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_BAR_BOTTOM_GAP
import me.him188.ani.app.ui.foundation.tv.TV_CARD_PROGRESS_BAR_HEIGHT
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
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_SECONDARY_LABEL_ALPHA
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_SECONDARY_LABEL_ALPHA_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_SHADOW_COLOR_LIGHT
import me.him188.ani.app.ui.foundation.tv.TV_POSTER_WALL_TITLE_TOP_GAP
import me.him188.ani.app.ui.foundation.tv.TvFocusRing
import me.him188.ani.app.ui.foundation.tv.focusScale
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor
import me.him188.ani.app.ui.foundation.tv.tvPosterWallLabelHeight
import me.him188.ani.app.ui.foundation.tv.tvPosterWallSubtitleStyle
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
    /** 番名下面那行小字 (卡片的 [TvNativeCard.subtitle]): 字号比番名小一档, 行高相同; 颜色是卡片没另给 ([TvNativeCard.subtitleColor]) 时用的. */
    val subtitle: TvNativeTextStyle,
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
    /**
     * 没聚焦时番名与下面那行小字的不透明度 (聚焦时全亮): 次要那一档 ([TV_POSTER_WALL_SECONDARY_LABEL_ALPHA]). Apple TV 的卡片标题一直全亮,
     * 靠大幅抬起与重投影示焦; 这里的抬起轻, 番名也淡一档, 一排里聚焦的那张一眼看得出.
     */
    val labelIdleAlpha: Float,
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
    /**
     * 番名与副标题照 tvOS 的 vibrancy 画 (见 setTvVibrancy): 字色 (副标题乘上没聚焦时的透明度) 加在底下的背景上, 不是半透明盖上去 —— 压在
     * 模糊背景上时中等亮度的底上也清楚. 新番时间表与 hero 态铺模糊背景的探索 / 追番 / 搜索页开着. 只用于浅色字 (深色主题); 深色字照常画.
     */
    val labelVibrancy: Boolean = false,
    /** 行尾「更多」卡的玻璃外观 (见 [TvNativeMoreGlassStyle]), 按主题深浅取. */
    val moreGlass: TvNativeMoreGlassStyle = TV_NATIVE_MORE_GLASS_DARK,
) {
    val coverWidthPx: Int get() = cardWidthPx - gapPx * 2
    val coverHeightPx: Int get() = cardHeightPx - gapPx * 2

    /** 一行卡的项高: 海报 + 番名 (不含行距). */
    val cardBlockHeightPx: Int get() = cardHeightPx + labelHeightPx
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
 * [TvNativeWallStyle]. [cardWidth] = 卡宽 (含聚焦框空隙, 屏幕上的 dp): 探索 / 追番 / 搜索页是 tvPosterWallGrid 按内容区算的,
 * 详情页的关联行另算. [columns] = 屏上一排完整放得下的张数 (预取用). [badge] 见 [TvNativeWallStyle.badge].
 * [columnSpacing] = 卡格之间的距离 (不含卡格里的聚焦框空隙). [cardHeight] = 卡格高 (含聚焦框空隙); null = 按卡宽与封面比例
 * [TV_PORTRAIT_CARD_COVER_RATIO] 算. [labelVibrancy] 见 [TvNativeWallStyle.labelVibrancy] (浅色主题下不生效).
 *
 * 在海报墙大小 ([LocalTvPosterWallScale]) 里面时, 卡宽卡高之外的卡片尺寸 (番名字号与块高、行距与列距、聚焦框空隙、圆角、投影、进度条) 一起按它缩放.
 */
@Composable
fun rememberTvNativeWallStyle(
    cardWidth: Dp,
    columns: Int,
    badge: TvNativeCardBadgeStyle? = null,
    columnSpacing: Dp = TV_POSTER_WALL_COLUMN_SPACING,
    cardHeight: Dp? = null,
    labelVibrancy: Boolean = false,
): TvNativeWallStyle {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() >= 0.5f
    val visualEffects = LocalThemeSettings.current.visualEffects
    val crossfade = LocalImageCrossfade.current
    val titleColor = tvHeroContentColor()
    val titleStyle = tvPosterWallTitleStyle()
    val subtitleStyle = tvPosterWallSubtitleStyle()
    val labelHeight = tvPosterWallLabelHeight()
    val cardScale = LocalTvPosterWallScale.current
    return remember(density, cardScale, colors, light, visualEffects, crossfade, titleColor, titleStyle, subtitleStyle, labelHeight, cardWidth, columns, badge, columnSpacing, cardHeight, labelVibrancy) {
        val cardWidthPx = with(density) { cardWidth.roundToPx() }
        val cardHeightPx = with(density) { (cardHeight ?: (cardWidth / TV_PORTRAIT_CARD_COVER_RATIO)).roundToPx() }
        // 卡片自己的尺寸按海报墙大小换算 (卡宽卡高已经由调用方按它算好)
        val cardDensity = if (cardScale == 1f) density else Density(density.density * cardScale, density.fontScale)
        with(cardDensity) {
            val focusScale = TV_POSTER_WALL_CARD_FOCUS_STYLE.focusScale
            val gapPx = TvFocusRing.Gap.roundToPx()
            val cornerPx = TV_PORTRAIT_CARD_CORNER.toPx()
            TvNativeWallStyle(
                cardWidthPx = cardWidthPx,
                cardHeightPx = cardHeightPx,
                gapPx = gapPx,
                cornerPx = cornerPx,
                labelHeightPx = labelHeight.roundToPx(),
                titleTopGapPx = TV_POSTER_WALL_TITLE_TOP_GAP.roundToPx(),
                title = titleStyle.toTvNativeTextStyle(cardDensity, titleColor),
                // 与番名同色: 「次要」由没聚焦时的透明度表达 (见 TV_POSTER_WALL_SECONDARY_LABEL_ALPHA)
                subtitle = subtitleStyle.toTvNativeTextStyle(cardDensity, titleColor),
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
                labelIdleAlpha = if (light) TV_POSTER_WALL_SECONDARY_LABEL_ALPHA_LIGHT else TV_POSTER_WALL_SECONDARY_LABEL_ALPHA,
                focusMillis = TV_CARD_FOCUS_TRANSITION_MILLIS.toLong(),
                shadowColor = (if (light) TV_POSTER_WALL_SHADOW_COLOR_LIGHT else Color.Black).toArgb(),
                placeholderColor = colors.surfaceContainerHigh.toArgb(),
                edgeColor = colors.onSurface.copy(
                    alpha = if (light) TV_POSTER_WALL_OUTLINE_ALPHA_LIGHT else TV_POSTER_WALL_OUTLINE_ALPHA,
                ).toArgb(),
                progressBarHeightPx = TV_CARD_PROGRESS_BAR_HEIGHT.toPx(),
                // 条长按**这组卡自己的封面宽**算 (海报墙的卡宽随每排张数与「海报墙大小」变), 去掉两个圆角那一截, 两端落在圆角的切点上:
                // 写死的 TV_CARD_PROGRESS_BAR_LENGTH 是按固定 112dp 的卡算的, 卡一宽条就显得短
                progressBarLengthPx = cardWidthPx - gapPx * 2 - cornerPx * 2 + 2.dp.toPx(),
                progressBarBottomGapPx = TV_CARD_PROGRESS_BAR_BOTTOM_GAP.toPx(),
                progressTrackColor = Color.White.copy(alpha = TV_CARD_PROGRESS_TRACK_ALPHA).toArgb(),
                // 已看那段用主题色 (用户 10-01: 白的在封面上不够显眼): 它压在封面图上, 主题色两档 (深色淡紫 / 浅色深紫) 都比白更跳.
                // 底仍是恒定淡白, 不跟主题走
                progressFillColor = colors.primary.toArgb(),
                crossfade = crossfade,
                marquee = visualEffects.marquee,
                prefetchItems = columns + 1,
                badge = badge,
                labelVibrancy = labelVibrancy && !light,
                moreGlass = if (light) TV_NATIVE_MORE_GLASS_LIGHT else TV_NATIVE_MORE_GLASS_DARK,
            )
        }
    }
}
