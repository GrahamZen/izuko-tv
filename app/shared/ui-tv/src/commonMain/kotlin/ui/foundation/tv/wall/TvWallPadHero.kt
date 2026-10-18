/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.wall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.tv.TV_OBSCURED_BACKDROP_LONG_EDGE_PX
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroSource
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroText
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.rememberTvTitleLogoBox
import kotlin.math.roundToInt

/*
 * 平板版的 hero 画法, 照平板版探索页 (ui-pad 的 PadExplorationPage) 的轮播 (调试开关 DevSwitches.padCarousel): 探索页的热门轮播与各海报墙的
 * hero 态共用. 整块铺这一部的模糊图, 越往下越接近卡片墙的底色; 右上角清晰的横版图, 左缘与下缘羽化进模糊底; 文字贴在左下、往上长.
 * 换一部的过渡固定一种: 新图在旧图上淡入, 旧字淡出、新字从右边滑一小段淡入.
 */

/**
 * 一块平板版 hero: [source] 是这一部 (null = 空着). 文字左缘在 [textStart], 下缘离这块的底边 [textBottom], 标题宽按 [pageWidth] 的比例,
 * 简介最多 [summaryMaxLines] 行.
 * [ownBlur] = 自己铺模糊底与往下渐变的墙色; 整页底下已经铺着同一部的模糊背景 (hero 态用模糊背景) 时传 false, 只画羽化的横版图.
 * [backdropAlpha] / [textAlpha] 在绘制阶段读. [overlay] 画在最上面 (轮播的圆点).
 */
@Composable
internal fun TvPadHeroLayer(
    source: TvNativeHeroSource?,
    textStart: Dp,
    textBottom: Dp,
    pageWidth: Dp,
    modifier: Modifier = Modifier,
    summaryMaxLines: Int = TV_PAD_HERO_SUMMARY_LINES,
    ownBlur: Boolean = true,
    backdropAlpha: () -> Float = { 1f },
    textAlpha: () -> Float = { 1f },
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val animated = LocalThemeSettings.current.visualEffects.transitions
    val slidePx = with(LocalDensity.current) { TV_PAD_HERO_TEXT_SLIDE.roundToPx() }
    Box(modifier) {
        AnimatedContent(
            source,
            Modifier.fillMaxSize().graphicsLayer { alpha = backdropAlpha() },
            transitionSpec = {
                // 新的一部在旧的上面淡入 (旧的一直不透明, 中间不会变暗)
                fadeIn(tween(TV_PAD_HERO_CROSSFADE_MILLIS, easing = LinearEasing)) togetherWith ExitTransition.KeepUntilTransitionsFinished
            },
            contentKey = { it?.rawSubjectId },
            label = "padHeroBackdrop",
        ) { shown ->
            if (shown != null) TvPadHeroBackdrop(shown, ownBlur)
        }
        AnimatedContent(
            source?.text,
            Modifier.align(Alignment.BottomStart)
                .padding(start = textStart, bottom = textBottom)
                .width(pageWidth * TV_PAD_HERO_TITLE_WIDTH_FRACTION)
                .graphicsLayer { alpha = textAlpha() },
            transitionSpec = {
                // 同电视版 hero 文字块 (TvWallHeroText): 旧字淡出, 新字晚一拍从右边滑一小段 ([TV_PAD_HERO_TEXT_SLIDE]) 淡入.
                // 文字块往上长、高度随内容变: 大小当场换, 不做动画 (做的话条目信息晚到时字会跟着块挪)
                val content = if (animated) {
                    (fadeIn(tween(TV_PAD_HERO_TEXT_IN_MILLIS, delayMillis = TV_PAD_HERO_TEXT_OUT_MILLIS)) +
                            slideInHorizontally(tween(TV_PAD_HERO_TEXT_IN_MILLIS, delayMillis = TV_PAD_HERO_TEXT_OUT_MILLIS)) { slidePx })
                        .togetherWith(fadeOut(tween(TV_PAD_HERO_TEXT_OUT_MILLIS, easing = LinearEasing)))
                } else {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                }
                content using SizeTransform(clip = false) { _, _ -> snap() }
            },
            contentKey = { it?.subjectId },
            contentAlignment = Alignment.BottomStart,
            label = "padHeroText",
        ) { text ->
            // 藏着 (滚动 / 连发中) 时也占满宽度: 块宽变成 0 的话, 外面定宽的约束会把它摆在正中, 正在淡出的旧字整块往右挪半个块宽
            if (text != null) TvPadHeroText(text, pageWidth, summaryMaxLines) else Box(Modifier.fillMaxWidth())
        }
        overlay()
    }
}

/** 一部的画面: ([ownBlur] 时) 模糊底与往下渐变的墙色 + 顶边压暗, 右上角羽化的横版图. */
@Composable
private fun TvPadHeroBackdrop(source: TvNativeHeroSource, ownBlur: Boolean) {
    val backdrop = source.backdrop
    val wallColor = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        if (ownBlur) {
            // 底下铺同一部的模糊版 (整部的横版图, 没有就用背景图), 越往下越接近墙的底色
            val blurUrl = source.wall?.url ?: backdrop?.url
            if (blurUrl != null) {
                AsyncImage(
                    blurUrl,
                    contentDescription = null,
                    Modifier.fillMaxSize().blur(TV_PAD_HERO_BLUR_RADIUS),
                    contentScale = ContentScale.Crop,
                    downsampleLongEdgePx = TV_PAD_HERO_BLUR_DECODE_LONG_EDGE_PX,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to wallColor.copy(alpha = TV_PAD_HERO_BLUR_DIM_ALPHA),
                        TV_PAD_HERO_FLOOR_START to wallColor.copy(alpha = TV_PAD_HERO_BLUR_DIM_ALPHA),
                        1f to wallColor,
                    ),
                ),
            )
        }
        // 右上角清晰的横版图 (继续观看的条目是下一集剧照, 模糊底照旧是整部那张), 左缘与下缘羽化进模糊底
        if (backdrop != null) {
            AsyncImage(
                backdrop.url,
                contentDescription = null,
                Modifier.align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    .tvPadHeroFeather(),
                contentScale = ContentScale.Crop,
                downsampleLongEdgePx = if (backdrop.obscure) TV_OBSCURED_BACKDROP_LONG_EDGE_PX else null,
            )
        }
        if (ownBlur) {
            // 顶边压一点暗, 给状态栏垫底
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(TV_PAD_HERO_TOP_SCRIM_FRACTION).background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = TV_PAD_HERO_TOP_SCRIM_ALPHA), 1f to Color.Transparent),
                ),
            )
        }
    }
}

/** 文字: 标题 (或标题 logo)、评分与信息行、下一集 / 开始观看那一行、简介 (分段换行压成一段, 最多 [summaryMaxLines] 行). */
@Composable
private fun TvPadHeroText(text: TvNativeHeroText, pageWidth: Dp, summaryMaxLines: Int) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val typography = MaterialTheme.typography
    Column(Modifier.fillMaxWidth()) {
        TvPadHeroTitle(text, pageWidth * TV_PAD_HERO_TITLE_WIDTH_FRACTION, contentColor)
        if (text.infoReady && (text.rating != null || text.meta.isNotEmpty())) {
            Text(
                buildAnnotatedString {
                    text.rating?.let {
                        append("★ ")
                        append(it)
                        append("    ")
                    }
                    withStyle(SpanStyle(color = contentColor.copy(alpha = TV_PAD_HERO_SECONDARY_ALPHA))) {
                        text.meta.forEach { append(it.text) }
                    }
                },
                Modifier.padding(top = TV_PAD_HERO_LINE_GAP),
                color = contentColor,
                style = typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        text.status?.let { status ->
            val line = buildString {
                append(status.lead)
                status.name?.let { append(status.nameSeparator).append(it) }
                status.tail?.let { append(it) }
            }
            Text(
                line,
                Modifier.padding(top = TV_PAD_HERO_LINE_GAP),
                color = Color(status.color),
                style = typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = if (status.wrap) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val summary = remember(text.summary) {
            text.summary.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }
        if (summary.isNotEmpty()) {
            Text(
                summary,
                Modifier.padding(top = TV_PAD_HERO_LINE_GAP).fillMaxWidth(TV_PAD_HERO_SUMMARY_WIDTH_FRACTION / TV_PAD_HERO_TITLE_WIDTH_FRACTION),
                color = contentColor.copy(alpha = TV_PAD_HERO_SECONDARY_ALPHA),
                style = typography.bodyMedium,
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 标题: 有标题 logo 就画 logo, 否则加粗的文字标题. logo 照电视版的大小规则 (面积大致相同, 越宽越矮), 高度上限放宽到
 * [TV_PAD_HERO_LOGO_TALL_SCALE] 倍: 竖版 logo 在电视版里先碰到高度上限、面积只有横版的一半不到, 这里文字块往上长、空间够, 让它也按面积放大.
 */
@Composable
private fun TvPadHeroTitle(text: TvNativeHeroText, width: Dp, color: Color) {
    val density = LocalDensity.current
    val logo = text.logo
    val tvBox = rememberTvTitleLogoBox(with(density) { width.roundToPx() })
    if (logo != null && tvBox != null) {
        val box = remember(tvBox) { tvBox.copy(maxHeightPx = (tvBox.maxHeightPx * TV_PAD_HERO_LOGO_TALL_SCALE).roundToInt()) }
        val size = box.sizeOf(logo.aspectRatio)
        with(density) {
            AsyncImage(
                logo.url(size.widthPx),
                contentDescription = text.title,
                Modifier.size(size.widthPx.toDp(), size.heightPx.toDp()),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        Text(
            text.title,
            color = color,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 横版图左缘与下缘羽化成透明, 露出底下的模糊层: 遮罩画在离屏层里 (DstIn), 不离屏会把底下的模糊层一起擦掉. */
private fun Modifier.tvPadHeroFeather(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.horizontalGradient(TV_PAD_HERO_LEFT_FADE_START to Color.Transparent, TV_PAD_HERO_LEFT_FADE_END to Color.Black),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(TV_PAD_HERO_BOTTOM_FADE_START to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/** 海报墙 hero 态的文字块下沿离这块底边 (聚焦行的组标题 / 行顶) 多远. */
internal val TV_PAD_HERO_TEXT_BOTTOM = 20.dp

/** 简介行数: 轮播 (下面有按钮) 三行; 海报墙 hero 态没有按钮, 多放两行, 文字块照样往上长到轮播文字的高度附近. */
private const val TV_PAD_HERO_SUMMARY_LINES = 3
internal const val TV_PAD_HERO_WALL_SUMMARY_LINES = 5

/** 模糊底: 先只解一张长边这么多像素的小图, 再叠一层模糊去掉放大后的块感. */
private const val TV_PAD_HERO_BLUR_DECODE_LONG_EDGE_PX = 160
private val TV_PAD_HERO_BLUR_RADIUS = 24.dp

/** 模糊底上整层压的一层墙色, 让文字在任何画面上都读得清. */
private const val TV_PAD_HERO_BLUR_DIM_ALPHA = 0.35f

/** 从这块高度的这个位置起往下渐变到墙的底色. */
private const val TV_PAD_HERO_FLOOR_START = 0.45f

private const val TV_PAD_HERO_TOP_SCRIM_FRACTION = 0.18f
private const val TV_PAD_HERO_TOP_SCRIM_ALPHA = 0.35f

/** 标题与简介的宽度占页面宽度的比例. */
private const val TV_PAD_HERO_TITLE_WIDTH_FRACTION = 0.46f
private const val TV_PAD_HERO_SUMMARY_WIDTH_FRACTION = 0.40f

private const val TV_PAD_HERO_SECONDARY_ALPHA = 0.75f
private val TV_PAD_HERO_LINE_GAP = 8.dp

/** 标题 logo 的高度上限比电视版放宽几倍 (见 [TvPadHeroTitle]). */
private const val TV_PAD_HERO_LOGO_TALL_SCALE = 2f

/** 换一部的过渡: 背景图在旧图上淡入; 文字同电视版 hero 文字块 (旧字淡出, 新字晚一拍滑一小段淡入). */
private const val TV_PAD_HERO_CROSSFADE_MILLIS = 600
private const val TV_PAD_HERO_TEXT_OUT_MILLIS = 120
private const val TV_PAD_HERO_TEXT_IN_MILLIS = 240
private val TV_PAD_HERO_TEXT_SLIDE = 14.dp

/** 横版图左缘羽化的起止 (占图宽), 下缘从这个高度起淡到透明. */
private const val TV_PAD_HERO_LEFT_FADE_START = 0.02f
private const val TV_PAD_HERO_LEFT_FADE_END = 0.35f
private const val TV_PAD_HERO_BOTTOM_FADE_START = 0.6f
