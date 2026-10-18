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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.panpf.sketch.LocalPlatformContext
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.foundation.tv.TV_BACKDROP_CROSSFADE_MILLIS
import me.him188.ani.app.ui.foundation.tv.TV_HERO_META_GAP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_RATING_STAR_GAP
import me.him188.ani.app.ui.foundation.tv.TV_HERO_RATING_STAR_SIZE
import me.him188.ani.app.ui.foundation.tv.TvBackdropImage
import me.him188.ani.app.ui.foundation.tv.TvBackdropTreatment
import me.him188.ani.app.ui.foundation.tv.TvHeroZoomHandoff
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_WALL_BACKDROP_CROSSFADE_MILLIS
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeBackdropTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeHeroText
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeWallBackdropTarget
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropMaskAlpha
import me.him188.ani.app.ui.foundation.tv.nativeview.tvFetchBlurredBackdrop
import me.him188.ani.app.ui.foundation.tv.nativeview.tvRelativeLuminance
import me.him188.ani.app.ui.foundation.tv.rememberTvTitleLogoBox
import me.him188.ani.app.ui.foundation.tv.tvAmbientMarquee
import me.him188.ani.app.ui.foundation.tv.tvBackdropTreatmentPainter
import me.him188.ani.app.ui.foundation.tv.tvHeroContentColor

/*
 * Compose 版海报墙的 hero: 进出 hero 态的时间线、hero 背景图、整屏模糊底、标题与简介. 与原生那套 (TvNativeHeroTimeline / TvNativeBackdropView /
 * TvNativeWallBackdropView / TvNativeHeroTextView) 取同一组时长与样式.
 */

/**
 * 海报墙的 **hero 态**: 卡片墙上按确定, 先切成 hero 页的样子 —— 背景与简介回来, 聚焦行移到简介正下方, 上面的行淡出、番名淡出; 再按确定才
 * 进详情页. 按返回变回卡片墙.
 *
 * 进出按一条时间线走, 页面底色与背景图从不同时在变 (先背景后文字, 旧的先走, 淡入淡出一律线性):
 * - 进: 上面几行与番名 ([above]) 0–150ms 淡没, 底色 ([tone]) 0–300ms 压黑; 黑透之后背景图 ([content]) 淡入 400ms, 标题与简介 ([text])
 *   晚 100ms 起淡入 300ms;
 * - 出: 标题与简介 150ms、背景图 250ms 淡出; 图没了才回灰 (350ms), 上面几行与番名同时回来 (250ms).
 * 整屏底色不换时 ([rememberTvWallHeroTimeline] 的 darkens 为 false: 浅色主题, 或 hero 态铺着模糊背景) 底色那段与上面几行的淡出 / 淡入等长.
 *
 * 各段在绘制阶段读, 进出不触发重组; 组合里只读 [visible].
 */
@Stable
internal class TvWallHeroTimeline(initial: Boolean) {
    /** true = hero 态 (目标), 进出都改它. */
    var active: Boolean by mutableStateOf(initial)

    // 页面重建时恢复成 hero 态的话各段直接到位, 不再淡入一遍
    private val start = if (initial) 1f else 0f
    internal val aboveAnim = Animatable(start)
    internal val toneAnim = Animatable(start)
    internal val contentAnim = Animatable(start)
    internal val textAnim = Animatable(start)

    /** 上面几行与番名淡没的程度: 0 = 卡片墙 (都在), 1 = 淡没. */
    val above: Float get() = aboveAnim.value

    /** 整屏底色的黑度: 0 = 卡片墙的底色, 1 = hero 的底色. */
    val tone: Float get() = toneAnim.value

    /** hero 背景图的不透明度. */
    val content: Float get() = contentAnim.value

    /** 标题与简介的不透明度. */
    val text: Float get() = textAnim.value

    private val visibleState = derivedStateOf {
        active || toneAnim.value > 0f || contentAnim.value > 0f || textAnim.value > 0f
    }

    /** 背景层与简介块要不要组合: hero 态, 或正在退回卡片墙. */
    val visible: Boolean get() = visibleState.value

    /** 进出途中 (还没停稳). */
    val settled: Boolean get() = if (active) contentAnim.value >= 1f else aboveAnim.value <= 0f

    internal suspend fun snapAll(target: Float) {
        aboveAnim.snapTo(target)
        toneAnim.snapTo(target)
        contentAnim.snapTo(target)
        textAnim.snapTo(target)
    }
}

/** [TvWallHeroTimeline]: [darkens] = 进 hero 态时整屏底色换成 hero 的底色 (深色主题, 没铺模糊背景). */
@Composable
internal fun rememberTvWallHeroTimeline(initial: Boolean, darkens: Boolean): TvWallHeroTimeline {
    val hero = remember { TvWallHeroTimeline(initial) }
    val animated = LocalThemeSettings.current.visualEffects.transitions
    val toneInMillis = if (darkens) TV_WALL_HERO_TONE_IN_MILLIS else TV_WALL_HERO_ABOVE_OUT_MILLIS
    val toneOutMillis = if (darkens) TV_WALL_HERO_TONE_OUT_MILLIS else TV_WALL_HERO_ABOVE_IN_MILLIS
    LaunchedEffect(hero, animated, darkens) {
        snapshotFlow { hero.active }.collectLatest { active ->
            // 流畅档直接到位, 同其它过渡
            if (!animated) {
                hero.snapAll(if (active) 1f else 0f)
                return@collectLatest
            }
            // 半路反向 (进到一半按返回) 从各段当前值接着走, 时长按剩下的路程折算
            coroutineScope {
                if (active) {
                    launch { hero.aboveAnim.animateTo(1f, tvWallHeroTween(TV_WALL_HERO_ABOVE_OUT_MILLIS, 1f - hero.above)) }
                    hero.toneAnim.animateTo(1f, tvWallHeroTween(toneInMillis, 1f - hero.tone))
                    // 底色换完了才出图; 文字晚一拍 (图已经在了就不等)
                    val textDelay = if (hero.content > 0f) 0L else TV_WALL_HERO_TEXT_DELAY_MILLIS
                    launch { hero.contentAnim.animateTo(1f, tvWallHeroTween(TV_WALL_HERO_CONTENT_IN_MILLIS, 1f - hero.content)) }
                    delay(textDelay)
                    hero.textAnim.animateTo(1f, tvWallHeroTween(TV_WALL_HERO_TEXT_IN_MILLIS, 1f - hero.text))
                } else {
                    launch { hero.textAnim.animateTo(0f, tvWallHeroTween(TV_WALL_HERO_TEXT_OUT_MILLIS, hero.text)) }
                    hero.contentAnim.animateTo(0f, tvWallHeroTween(TV_WALL_HERO_CONTENT_OUT_MILLIS, hero.content))
                    // 图没了才回卡片墙的底色, 上面几行与番名同时回来
                    launch { hero.aboveAnim.animateTo(0f, tvWallHeroTween(TV_WALL_HERO_ABOVE_IN_MILLIS, hero.above)) }
                    hero.toneAnim.animateTo(0f, tvWallHeroTween(toneOutMillis, hero.tone))
                }
            }
        }
    }
    return hero
}

/** 线性, 时长按剩下的路程 ([distance], 0..1) 折算. */
private fun tvWallHeroTween(millis: Int, distance: Float): TweenSpec<Float> =
    TweenSpec((millis * distance.coerceIn(0f, 1f)).roundToInt(), easing = LinearEasing)

private const val TV_WALL_HERO_ABOVE_OUT_MILLIS = 150
private const val TV_WALL_HERO_TONE_IN_MILLIS = 300
private const val TV_WALL_HERO_CONTENT_IN_MILLIS = 400
private const val TV_WALL_HERO_TEXT_DELAY_MILLIS = 100L
private const val TV_WALL_HERO_TEXT_IN_MILLIS = 300
private const val TV_WALL_HERO_TEXT_OUT_MILLIS = 150
private const val TV_WALL_HERO_CONTENT_OUT_MILLIS = 250
private const val TV_WALL_HERO_ABOVE_IN_MILLIS = 250

/** 出 hero 态: 底色回卡片墙那档. 整屏由黑变亮, 比变黑那段长, 否则看着是一闪. */
private const val TV_WALL_HERO_TONE_OUT_MILLIS = 350

/**
 * hero 背景图: [target] 那张贴在本层 (大小由调用方给) 里铺满, 换图交叉淡入; 压着 [treatment] 那套遮罩 (渐隐到页面底色; 绘制里读,
 * 探索页在轮播与聚焦卡两套之间过渡). [dimming] 为 true 时 (焦点已经换到别的条目, 展示目标还没跟上) 压暗一层. 登记放大转场
 * (点进详情页时图原地放大, 见 TvBackdropImage).
 */
@Composable
internal fun TvWallHeroBackdrop(
    target: TvNativeBackdropTarget?,
    dimming: Boolean,
    treatment: () -> TvBackdropTreatment,
    fadeColor: Color,
    modifier: Modifier = Modifier,
) {
    val animated = LocalThemeSettings.current.visualEffects.transitions
    val dim by animateFloatAsState(
        if (dimming) TV_WALL_HERO_DIMMING_ALPHA else 0f,
        if (animated) tween(TV_WALL_HERO_DIMMING_MILLIS) else snap(),
    )
    val currentTreatment by rememberUpdatedState(treatment)
    Box(
        modifier.drawWithCache {
            onDrawWithContent {
                drawContent()
                val painter = tvBackdropTreatmentPainter(size, currentTreatment())
                with(painter) { draw() }
                if (dim > 0f) drawRect(fadeColor, alpha = dim)
            }
        },
    ) {
        Crossfade(
            target,
            Modifier.fillMaxSize(),
            animationSpec = if (animated) tween(TV_BACKDROP_CROSSFADE_MILLIS) else snap(),
        ) { t ->
            if (t != null) {
                Box(Modifier.fillMaxSize()) {
                    // 背景图还没到时先铺竖版封面 (同原生背景层的垫底)
                    t.underlayUrl?.let { underlay ->
                        AsyncImage(underlay, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    TvBackdropImage(t.url, t.subjectId, zoomTreatment = { currentTreatment() })
                }
            }
        }
    }
}

/** 焦点换了、展示目标还没跟上时背景图压暗多少. */
private const val TV_WALL_HERO_DIMMING_ALPHA = 0.5f
private const val TV_WALL_HERO_DIMMING_MILLIS = 150

/**
 * 整屏模糊底 (新番时间表 / hero 态铺的模糊背景): [target] 那部的模糊小图拉伸铺满 (按 [spec] 解, 同原生的模糊层与详情页同一个缓存键),
 * 换图交叉淡入. 按 [maskColor] 压暗, 深浅照 [textColor] 最难看清那一处的对比度定 (见 tvBackdropMaskAlpha); [brightMaskAlpha] 给了 (不是 NaN)
 * 就按主色很亮的图换成这一档 (深色主题的 hero 态, 见 TV_HERO_BLUR_BRIGHT_DIM_ALPHA). [alpha] 在绘制里读.
 */
@Composable
internal fun TvWallBlurBackdrop(
    target: TvNativeWallBackdropTarget?,
    spec: TvBackdropBlurSpec,
    maskColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
) {
    val animated = LocalThemeSettings.current.visualEffects.transitions
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .graphicsLayer { this.alpha = alpha() },
    ) {
        Crossfade(
            target?.url,
            Modifier.fillMaxSize(),
            animationSpec = if (animated) tween(TV_WALL_BACKDROP_CROSSFADE_MILLIS) else snap(),
        ) { url ->
            if (url != null && size.width > 0) TvWallBlurImage(url, size, spec, maskColor, textColor)
        }
    }
}

@Composable
private fun TvWallBlurImage(url: String, size: IntSize, spec: TvBackdropBlurSpec, maskColor: Color, textColor: Color) {
    val sketch = LocalSketch.current
    val context = LocalPlatformContext.current
    var image by remember(url) { mutableStateOf<TvWallBlurred?>(null) }
    LaunchedEffect(url, size, spec) {
        if (image?.spec == spec) return@LaunchedEffect
        val fetched = tvFetchBlurredBackdrop(sketch, context, url, size.width, size.height, spec) ?: return@LaunchedEffect
        image = TvWallBlurred(fetched.bitmap, spec, fetched.worstForLightText, fetched.worstForDarkText)
    }
    val shown = image ?: return
    val maskAlpha = remember(shown, maskColor, textColor) { shown.maskAlpha(maskColor, textColor) }
    Image(
        shown.bitmap,
        contentDescription = null,
        Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        // 压暗画在同一次绘制里 (只盖有图处), 不另开一层
        colorFilter = if (maskAlpha > 0f) ColorFilter.tint(maskColor.copy(alpha = maskAlpha), BlendMode.SrcAtop) else null,
        // 模糊后的图没有细节, 双线性放大就看不出是小图
        filterQuality = FilterQuality.Low,
    )
}

/** 解好的模糊小图与量好的亮度 (同详情页的模糊底). */
private class TvWallBlurred(
    val bitmap: ImageBitmap,
    val spec: TvBackdropBlurSpec,
    private val worstForLightText: Float,
    private val worstForDarkText: Float,
) {
    fun maskAlpha(mask: Color, text: Color): Float {
        val maskLuminance = tvRelativeLuminance(mask.toArgb())
        val textLuminance = tvRelativeLuminance(text.toArgb())
        val lightText = textLuminance >= maskLuminance
        return tvBackdropMaskAlpha(if (lightText) worstForLightText else worstForDarkText, maskLuminance, textLuminance, mask.alpha)
    }
}

/**
 * hero 文字块: 标题 (或标题 logo) → 信息行 (★评分 + 分段着色的一串) → 下一集行 → 简介 (占满剩余高度, 最后一行省略号), 纵向排, 行距
 * [lineSpacing]. 宽度: 标题 [titleWidth], 下一集行与简介 [summaryWidth]. 换条目时旧字淡出、新字从右边滑一小段淡入; [text] 为 null = 藏起来
 * (滚动 / 连发中). 标题的框登记给放大转场 (详情页标题从这里平移过去), 缩回期间按转场层的判定隐藏或平移.
 */
@Composable
internal fun TvWallHeroText(
    text: TvNativeHeroText?,
    titleMaxLines: Int,
    titleWidth: Dp,
    summaryWidth: Dp,
    lineSpacing: Dp,
    modifier: Modifier = Modifier,
) {
    val animated = LocalThemeSettings.current.visualEffects.transitions
    val slidePx = with(LocalDensity.current) { TV_WALL_HERO_TEXT_SLIDE.roundToPx() }
    AnimatedContent(
        text,
        modifier,
        transitionSpec = {
            if (animated) {
                (fadeIn(tween(TV_WALL_HERO_TEXT_SWAP_IN_MILLIS, delayMillis = TV_WALL_HERO_TEXT_SWAP_OUT_MILLIS)) +
                        slideInHorizontally(tween(TV_WALL_HERO_TEXT_SWAP_IN_MILLIS, delayMillis = TV_WALL_HERO_TEXT_SWAP_OUT_MILLIS)) { slidePx })
                    .togetherWith(fadeOut(tween(TV_WALL_HERO_TEXT_SWAP_OUT_MILLIS, easing = LinearEasing)))
            } else {
                fadeIn(snap()) togetherWith fadeOut(snap())
            }
        },
        // 同一条目的内容变了 (条目信息晚到) 原地换, 不过渡
        contentKey = { it?.subjectId },
        contentAlignment = Alignment.TopStart,
    ) { shown ->
        if (shown != null) {
            TvWallHeroTextContent(shown, titleMaxLines, titleWidth, summaryWidth, lineSpacing)
        } else {
            Box(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TvWallHeroTextContent(
    text: TvNativeHeroText,
    titleMaxLines: Int,
    titleWidth: Dp,
    summaryWidth: Dp,
    lineSpacing: Dp,
) {
    val typography = MaterialTheme.typography
    val content = tvHeroContentColor()
    val marquee = LocalThemeSettings.current.visualEffects.marquee
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(lineSpacing)) {
        TvWallHeroTitle(text, titleMaxLines, titleWidth)
        if (text.infoReady && (text.rating != null || text.meta.isNotEmpty())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                text.rating?.let { rating ->
                    Icon(Icons.Rounded.Star, contentDescription = null, Modifier.size(TV_HERO_RATING_STAR_SIZE), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(TV_HERO_RATING_STAR_GAP))
                    // 评分数字用主要文字色, 颜色只在星上
                    Text(rating, color = content, style = typography.titleMedium, maxLines = 1)
                    Spacer(Modifier.width(TV_HERO_META_GAP))
                }
                if (text.meta.isNotEmpty()) {
                    Text(
                        buildAnnotatedString {
                            text.meta.forEach { span -> withStyle(SpanStyle(color = Color(span.color))) { append(span.text) } }
                        },
                        style = typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        text.status?.let { status ->
            val line = buildString {
                append(status.lead)
                status.name?.let { append(status.nameSeparator).append(it) }
                status.tail?.let { append(it) }
            }
            // 下一集 / 开始观看那一行加粗: 与简介同为主要信息, 靠字重分开
            Text(
                line,
                Modifier.width(summaryWidth).then(if (status.wrap) Modifier else Modifier.tvAmbientMarquee(enabled = marquee, iterations = Int.MAX_VALUE)),
                color = Color(status.color),
                style = typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = if (status.wrap) 2 else 1,
                overflow = if (status.wrap) TextOverflow.Ellipsis else TextOverflow.Clip,
            )
        }
        if (text.summary.isNotBlank()) {
            Text(
                text.summary,
                Modifier.width(summaryWidth),
                color = content,
                style = typography.bodyMedium,
                maxLines = if (text.summaryMaxLines > 0) text.summaryMaxLines else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 标题那一行: 文字标题, 或标题 logo (按 logo 框的大小规则, 底对齐左对齐). 框登记给放大转场. */
@Composable
private fun TvWallHeroTitle(text: TvNativeHeroText, maxLines: Int, width: Dp) {
    val density = LocalDensity.current
    val owner = remember { Any() }
    DisposableEffect(owner) { onDispose { TvHeroZoomHandoff.retractTitle(owner) } }
    val subjectId = text.subjectId
    val handoff = Modifier.graphicsLayer {
        // 放大 / 缩回期间: 绘制权在详情页那份或转场层手里时隐藏, 否则按缩回让位平移
        alpha = if (TvHeroZoomHandoff.titleOwnedByOverlay(subjectId) || TvHeroZoomHandoff.titleOwnedByDetails(subjectId)) 0f else 1f
        TvHeroZoomHandoff.shrinkTitleOffset(subjectId)?.let {
            translationX = it.x
            translationY = it.y
        }
    }
    val logoBox = rememberTvTitleLogoBox(with(density) { width.roundToPx() })
    val logo = text.logo
    var logoFailed by remember(logo) { mutableStateOf(false) }
    if (logo != null && logoBox != null && !logoFailed) {
        val size = logoBox.sizeOf(logo.aspectRatio)
        Box(Modifier.height(with(density) { logoBox.maxHeightPx.toDp() }), contentAlignment = Alignment.BottomStart) {
            AsyncImage(
                logo.url(size.widthPx),
                contentDescription = text.title,
                Modifier.size(with(density) { size.widthPx.toDp() }, with(density) { size.heightPx.toDp() })
                    .onGloballyPositioned {
                        TvHeroZoomHandoff.publishTitle(owner, subjectId, it.boundsInRoot(), text.title, maxLines, logo = logo)
                    }
                    .then(handoff),
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onError = { logoFailed = true },
            )
        }
    } else {
        Text(
            text.title,
            Modifier.width(width)
                .onGloballyPositioned {
                    TvHeroZoomHandoff.publishTitle(owner, subjectId, it.boundsInRoot(), text.title, maxLines)
                }
                .then(handoff),
            color = tvHeroContentColor(),
            style = MaterialTheme.typography.headlineLarge,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 新字进场从右边滑多远. */
private val TV_WALL_HERO_TEXT_SLIDE = 14.dp
private const val TV_WALL_HERO_TEXT_SWAP_OUT_MILLIS = 120
private const val TV_WALL_HERO_TEXT_SWAP_IN_MILLIS = 240
