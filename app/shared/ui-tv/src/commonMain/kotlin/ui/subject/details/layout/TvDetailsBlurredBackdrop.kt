/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.layout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.LocalPlatformContext
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.tv.LocalTvTouchInputEnabled
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.TvModulateAlphaCompositing
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_WALL_BACKDROP_CROSSFADE_MILLIS
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBlurredBackdropEntry
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBlurredBackdropImage
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropMaskAlpha
import me.him188.ani.app.ui.foundation.tv.nativeview.tvDetailsBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.tvFetchBlurredBackdrop
import me.him188.ani.app.ui.foundation.tv.nativeview.tvRelativeLuminance
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 详情页翻离首屏后的整屏底: 这部背景图的模糊版. 一张小图拉伸铺满 (多大、糊多少按 [spec], 默认设置里详情页那一档, 见 tvDetailsBackdropBlurSpec),
 * 不做实时模糊; 透明度不变时不重画. 换了程度先留着旧图, 新图解好再换.
 * 平板模式 (平板 / iPad) 刚解过同一张同一档就第一帧画出来, 不再解一遍; 换页时底怎么接上见 [TvTouchBlurredBackdrop].
 * 按页面底色压暗, 深浅照次要文字 (`onSurfaceVariant`) 最难看清那一处的对比度定 (见 tvBackdropMaskAlpha, 同新番时间表的模糊底).
 *
 * @param alpha 不透明度, 绘制里读 (首屏 0, 翻页时与清晰图交叉)
 * @param load 开始解图 (放大转场进行中先不解, 不与转场抢)
 * 平板模式下窗口尺寸变了 (转屏、分屏) 按新尺寸再解一张, 解好前接着铺手上那张 (糊得很重, 拉伸看不出来), 解好直接换上.
 *
 * @param onLoaded 模糊图解好、能盖满整屏了
 * @param spec 糊到哪一档, 默认按设置里详情页那一档
 */
@Composable
internal fun TvDetailsBlurredBackdrop(
    url: String,
    alpha: () -> Float,
    load: Boolean,
    onLoaded: () -> Unit,
    modifier: Modifier = Modifier,
    spec: TvBackdropBlurSpec = tvDetailsBackdropBlurSpec(),
) {
    val sketch = LocalSketch.current
    val context = LocalPlatformContext.current
    val maskColor = AniThemeDefaults.pageContentBackgroundColor.copy(alpha = TV_FULLSCREEN_BACKDROP_DIM_ALPHA)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val loaded by rememberUpdatedState(onLoaded)
    var size by remember { mutableStateOf(IntSize.Zero) }
    // 平板模式: 刚铺过同一张 (详情页 ⇄ 播放页来回) 就第一帧画出来, 不再解一遍 (见 TvRecentBlurredBackdrops)
    val touch = LocalTvTouchInputEnabled.current
    val windowSize = LocalWindowInfo.current.containerSize
    val recent = remember(url, spec) { if (touch) TvRecentBlurredBackdrops.get(url, spec, windowSize) else null }
    var blurred by remember(url) { mutableStateOf(recent) }
    // 手上这张是按哪个窗口尺寸解的 (平板模式转屏后据此再解一张)
    var blurredWindow by remember(url) { mutableStateOf(if (recent != null) windowSize else IntSize.Zero) }
    LaunchedEffect(url, spec) {
        if (recent != null) {
            blurred = recent
            loaded()
        }
    }
    LaunchedEffect(url, size, load, spec, windowSize) {
        if (!load || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        if (blurred?.spec == spec && (!touch || blurredWindow == windowSize)) return@LaunchedEffect
        val image = loadTvBlurredBackdrop(sketch, context, url, size, spec) ?: return@LaunchedEffect
        blurred = image
        blurredWindow = windowSize
        if (touch) TvRecentBlurredBackdrops.put(url, spec, windowSize, image)
        loaded()
    }
    // 量尺寸挂在外面这一层: 还没有图时也要量出来, 才知道按多大解
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        blurred?.let { TvDetailsBlurredImage(it, maskColor, textColor, alpha = alpha) }
    }
}

/**
 * 平板模式的整页模糊底 (平板详情页、播放页分栏, 见 TvPadDetailsBackdrop), 糊到 [spec] 这一档. 换页时底接得上:
 * - 上一页铺的是同一张图的另一档 (详情页 ⇄ 播放页, 两页糊的程度不同): 先照旧铺那一档, 这一档好了以后在
 *   [TV_WALL_BACKDROP_CROSSFADE_MILLIS] 内渐变过来; 返回时反过来;
 * - 同一张同一档刚铺过: 第一帧就满, 不淡入; 那一档还在渐变中 (播放页数据到了、占位换成正式页面) 就接着那一段走;
 * - 都不是: 有 [companion] 那一档 (详情页给的是海报墙 / 播放页那一档) 就先铺它再渐变过来 —— 从海报墙 hero 点进来时
 *   那一张就在内存里, 底接得上; 否则解好再淡入 (同详情页).
 * [companion] 那一档同时提前放进最近几张里: 详情页点进播放页时两张都已经在手, 第一帧就开始渐变.
 * 用到的都在最近解过的几张里 (见 TvRecentBlurredBackdrops), 渐变期间只是多画一张小贴图.
 * 转屏、分屏 (窗口尺寸变了) 不算换页: 淡入的进度不重来, 接着铺手上那张; 新尺寸那张由 [TvDetailsBlurredBackdrop] 解好后直接换上.
 */
@Composable
internal fun TvTouchBlurredBackdrop(url: String, spec: TvBackdropBlurSpec, companion: TvBackdropBlurSpec? = null) {
    val windowSize = LocalWindowInfo.current.containerSize
    val companionSpec = companion?.takeIf { it != spec }
    val start = remember(url, spec) { TvRecentBlurredBackdrops.begin(url, spec, windowSize, companionSpec) }
    val shown = remember(start) { Animatable(start.progress) }
    var loaded by remember(start) { mutableStateOf(false) }
    var from by remember(start) { mutableStateOf(start.from) }
    LaunchedEffect(shown, loaded) {
        if (!loaded) return@LaunchedEffect
        if (shown.value < 1f) {
            shown.animateTo(1f, tween(((1f - shown.value) * TV_WALL_BACKDROP_CROSSFADE_MILLIS).roundToInt()))
        }
        TvRecentBlurredBackdrops.finish(url, spec, windowSize)
    }
    // [companion] 那一档: 不在最近几张里就取一张 (从 hero 进来时是内存命中) 放进去; 自己这一档还没好、底下也还没东西时先铺它
    val sketch = LocalSketch.current
    val context = LocalPlatformContext.current
    LaunchedEffect(start, companionSpec) {
        if (companionSpec == null || TvRecentBlurredBackdrops.get(url, companionSpec, windowSize) != null) return@LaunchedEffect
        val image = loadTvBlurredBackdrop(sketch, context, url, windowSize, companionSpec) ?: return@LaunchedEffect
        TvRecentBlurredBackdrops.put(url, companionSpec, windowSize, image)
        if (!loaded && from == null && shown.value == 0f) {
            from = image
            TvRecentBlurredBackdrops.startFrom(url, spec, windowSize, companionSpec)
        }
    }
    val fromDone by remember(shown) { derivedStateOf { shown.value >= 1f } }
    val fromImage = from
    if (fromImage != null && !fromDone) {
        TvBlurredBackdropImage(
            fromImage,
            alpha = { 1f },
            maskColor = AniThemeDefaults.pageContentBackgroundColor.copy(alpha = TV_FULLSCREEN_BACKDROP_DIM_ALPHA),
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TvDetailsBlurredBackdrop(url, alpha = { shown.value }, load = true, onLoaded = { loaded = true }, spec = spec)
}

/**
 * 平板模式最近解好的几张整屏模糊底 (地址、程度、窗口尺寸都相同才算同一张), 与此刻铺着的是哪一张 (见 TvTouchBlurredBackdrop):
 * 平板详情页与播放页铺同一张图的两档, 两页来回时新页第一帧就画得出来、从上一页那一档渐变过来. 只在主线程读写.
 */
private object TvRecentBlurredBackdrops {
    private data class Key(val url: String, val spec: TvBackdropBlurSpec, val size: IntSize)

    /** 此刻铺着的那一张; [from] 非 null 时正从那一档渐变过来, 从 [start] 起算. */
    private class Shown(val key: Key, val from: TvBackdropBlurSpec?, val start: TimeMark)

    /** 新挂上的那一层从哪一张 ([from], null = 页面底色) 渐变过来, 起点进度 [progress] (0..1). */
    class Start(val from: TvBlurredBackdropEntry?, val progress: Float)

    private const val CAPACITY = 4
    private val entries = LinkedHashMap<Key, TvBlurredBackdropEntry>()
    private var shown: Shown? = null

    fun get(url: String, spec: TvBackdropBlurSpec, size: IntSize): TvBlurredBackdropEntry? = entries[Key(url, spec, size)]

    fun put(url: String, spec: TvBackdropBlurSpec, size: IntSize, image: TvBlurredBackdropEntry) {
        val key = Key(url, spec, size)
        entries.remove(key)
        entries[key] = image
        while (entries.size > CAPACITY) entries.remove(entries.keys.first())
    }

    /**
     * 要铺 [url] 的 [spec] 这一档: 从哪一张、什么进度开始 (见 TvTouchBlurredBackdrop), 并记下这一次.
     * 接不上此刻铺着的那一张时, [companion] 那一档在最近几张里就从它开始.
     */
    fun begin(url: String, spec: TvBackdropBlurSpec, size: IntSize, companion: TvBackdropBlurSpec? = null): Start {
        val key = Key(url, spec, size)
        val current = shown
        val ready = entries[key] != null
        val fromSpec: TvBackdropBlurSpec?
        val start: Start
        when {
            // 同一张同一档 (占位换成正式页面): 接着那一段渐变走
            current != null && current.key == key -> {
                val from = current.from?.let { entries[Key(url, it, size)] }
                fromSpec = if (from != null) current.from else null
                val progress = when {
                    from != null -> (current.start.elapsedNow().inWholeMilliseconds.toFloat() / TV_WALL_BACKDROP_CROSSFADE_MILLIS)
                        .coerceIn(0f, 1f)

                    ready -> 1f
                    else -> 0f
                }
                start = Start(from, progress)
            }

            // 同一张的另一档 (详情页 ⇄ 播放页): 从那一档渐变过来
            current != null && current.key.url == url && current.key.size == size && entries[current.key] != null -> {
                fromSpec = current.key.spec
                start = Start(entries[current.key], 0f)
            }

            !ready && companion != null && entries[Key(url, companion, size)] != null -> {
                fromSpec = companion
                start = Start(entries[Key(url, companion, size)], 0f)
            }

            else -> {
                fromSpec = null
                start = Start(null, if (ready) 1f else 0f)
            }
        }
        val elapsed = (start.progress * TV_WALL_BACKDROP_CROSSFADE_MILLIS).roundToInt().milliseconds
        shown = Shown(key, fromSpec, TimeSource.Monotonic.markNow() - elapsed)
        return start
    }

    /** 正在铺的 [url] 的 [spec] 这一档改成从 [from] 那一档开始渐变 (开始时它还没取到, 后来取到了). */
    fun startFrom(url: String, spec: TvBackdropBlurSpec, size: IntSize, from: TvBackdropBlurSpec) {
        val key = Key(url, spec, size)
        if (shown?.key == key) shown = Shown(key, from, TimeSource.Monotonic.markNow())
    }

    /** [url] 的 [spec] 这一档已经铺满 (渐变走完). */
    fun finish(url: String, spec: TvBackdropBlurSpec, size: IntSize) {
        val key = Key(url, spec, size)
        if (shown?.key == key) shown = Shown(key, null, TimeSource.Monotonic.markNow())
    }
}

/**
 * 海报墙 (hero 态) 已经解好的一张整屏模糊小图 (量好了亮度) 放进平板模式的最近几张里 (见 TvRecentBlurredBackdrops): 从 hero 点进详情页时
 * 第一帧就铺得上它、再渐变到详情页那一档, 不先露出页面底色. [size] 是铺满的那一块 (窗口) 的大小.
 * iPad 的 Compose 海报墙 (TvWallHero) 直接调这个; 安卓的原生海报墙交来的是位图, 见同名的安卓版.
 */
internal fun tvRememberTouchBlurredBackdrop(url: String, spec: TvBackdropBlurSpec, size: IntSize, image: TvBlurredBackdropImage) {
    if (TvRecentBlurredBackdrops.get(url, spec, size) == null) {
        TvRecentBlurredBackdrops.put(url, spec, size, TvBlurredBackdropEntry(image.bitmap, spec, image.worstForLightText, image.worstForDarkText))
    }
}

/**
 * 解一张整屏模糊背景小图并量好亮度 (详情页与缓存页选源背景共用; 地址、尺寸、程度相同就命中同一个内存缓存键).
 * 竖版封面按原地址解: 清晰层铺的就是它, 下载缓存里已经有了. 解不出来时 null.
 */
internal suspend fun loadTvBlurredBackdrop(
    sketch: Sketch,
    context: PlatformContext,
    url: String,
    size: IntSize,
    spec: TvBackdropBlurSpec,
): TvBlurredBackdropEntry? {
    val image = tvFetchBlurredBackdrop(sketch, context, url, size.width, size.height, spec) ?: return null
    return TvBlurredBackdropEntry(image.bitmap, spec, image.worstForLightText, image.worstForDarkText)
}

/**
 * 铺满的模糊背景: 按 [maskColor] 压暗, 深浅照 [textColor] 在图上最难看清那一处的对比度定 (见 tvBackdropMaskAlpha).
 * [image] 为 null 时不画.
 *
 * @param alpha 不透明度, 绘制里读
 */
@Composable
internal fun TvBlurredBackdropImage(
    image: TvBlurredBackdropEntry?,
    alpha: () -> Float,
    maskColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (image != null) TvDetailsBlurredImage(image, maskColor, textColor, modifier, alpha)
}

/** 一张模糊小图拉伸铺满, 按 [entry] 量好的亮度压暗 (压暗画在同一次绘制里, 只盖有图处); [alpha] 在绘制里读. */
@Composable
private fun TvDetailsBlurredImage(
    entry: TvBlurredBackdropEntry,
    maskColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    alpha: () -> Float,
) {
    val maskAlpha = remember(entry, maskColor, textColor) { entry.maskAlpha(maskColor, textColor) }
    Image(
        entry.bitmap,
        contentDescription = null,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha()
                // 只有一张图, 透明度逐笔乘就够, 不开离屏层
                compositingStrategy = TvModulateAlphaCompositing
            },
        contentScale = ContentScale.Crop,
        colorFilter = if (maskAlpha > 0f) ColorFilter.tint(maskColor.copy(alpha = maskAlpha), BlendMode.SrcAtop) else null,
        // 模糊后的图没有细节, 双线性放大就看不出是小图
        filterQuality = FilterQuality.Low,
    )
}

/** 压暗色 [mask] 压多深上面的 [text] 才看得清 (0..1), 判法同海报墙的模糊层. */
private fun TvBlurredBackdropEntry.maskAlpha(mask: Color, text: Color): Float {
    val maskLuminance = tvRelativeLuminance(mask.toArgb())
    val textLuminance = tvRelativeLuminance(text.toArgb())
    val lightText = textLuminance >= maskLuminance
    return tvBackdropMaskAlpha(if (lightText) worstForLightText else worstForDarkText, maskLuminance, textLuminance, mask.alpha)
}
