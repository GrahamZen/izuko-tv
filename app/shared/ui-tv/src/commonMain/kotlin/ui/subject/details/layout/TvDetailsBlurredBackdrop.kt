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
import androidx.compose.ui.unit.IntSize
import com.github.panpf.sketch.LocalPlatformContext
import me.him188.ani.app.ui.foundation.LocalSketch
import me.him188.ani.app.ui.foundation.theme.AniThemeDefaults
import me.him188.ani.app.ui.foundation.tv.TV_FULLSCREEN_BACKDROP_DIM_ALPHA
import me.him188.ani.app.ui.foundation.tv.nativeview.TV_WALL_BACKDROP_CROSSFADE_MILLIS
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBlurredBackdropEntry
import me.him188.ani.app.ui.foundation.tv.nativeview.TvBlurredBackdropMemo
import me.him188.ani.app.ui.foundation.tv.nativeview.tvDetailsBackdropBlurSpec
import me.him188.ani.app.ui.foundation.tv.nativeview.tvBackdropMaskAlpha
import me.him188.ani.app.ui.foundation.tv.nativeview.tvFetchBlurredBackdrop
import me.him188.ani.app.ui.foundation.tv.nativeview.tvRelativeLuminance
import me.him188.ani.app.ui.foundation.tv.TvModulateAlphaCompositing

/**
 * 详情页翻离首屏后的整屏底: 这部背景图的模糊版. 一张小图拉伸铺满 (多大、糊多少按设置里详情页那一档, 见 tvDetailsBackdropBlurSpec), 不做实时模糊;
 * 透明度不变时不重画. 换了程度先留着旧图, 新图解好再换.
 * 平板 / iPad 的海报墙停在一部上时已经预取好这一档 (见 tvPrefetchBlurredBackdrop), 进页第一帧就是它; 还没解好时先铺同一部别的档解好的那张
 * (见 [TvBlurredBackdropMemo]: 多半是海报墙 hero 态底下那张, 那边默认更糊), 自己这张解好再交叉换上 —— 不先露一下底色.
 * 按页面底色压暗, 深浅照次要文字 (`onSurfaceVariant`) 最难看清那一处的对比度定 (见 tvBackdropMaskAlpha, 同新番时间表的模糊底).
 *
 * @param alpha 不透明度, 绘制里读 (首屏 0, 翻页时与清晰图交叉)
 * @param load 开始解图 (放大转场进行中先不解, 不与转场抢)
 * @param onLoaded 能盖满整屏了 (垫着的那张或自己这档解好)
 */
@Composable
internal fun TvDetailsBlurredBackdrop(
    url: String,
    alpha: () -> Float,
    load: Boolean,
    onLoaded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sketch = LocalSketch.current
    val context = LocalPlatformContext.current
    val maskColor = AniThemeDefaults.pageContentBackgroundColor.copy(alpha = TV_FULLSCREEN_BACKDROP_DIM_ALPHA)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val spec = tvDetailsBackdropBlurSpec()
    val loaded by rememberUpdatedState(onLoaded)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var blurred by remember(url) { mutableStateOf(TvBlurredBackdropMemo.get(url, spec)) }
    // 这一档还没解好: 先垫同一部别的档的那张, 解好后自己这张在它上面淡入
    val placeholder = remember(url) { if (blurred == null) TvBlurredBackdropMemo.latest(url) else null }
    val fadeIn = remember(url) { Animatable(if (blurred == null && placeholder != null) 0f else 1f) }
    LaunchedEffect(url, size, load, spec) {
        if (blurred?.spec == spec) {
            // 记着这一档解好的那张: 已经能盖满整屏
            loaded()
            return@LaunchedEffect
        }
        if (placeholder != null) loaded()
        if (!load || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val image = tvFetchBlurredBackdrop(sketch, context, url, size.width, size.height, spec) ?: return@LaunchedEffect
        val entry = TvBlurredBackdropEntry(image.bitmap, spec, image.worstForLightText, image.worstForDarkText)
        TvBlurredBackdropMemo.put(url, entry)
        blurred = entry
        if (placeholder == null) loaded()
        fadeIn.animateTo(1f, tween(TV_WALL_BACKDROP_CROSSFADE_MILLIS))
    }
    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        val image = blurred
        if (placeholder != null && (image == null || fadeIn.value < 1f)) {
            TvDetailsBlurredImage(placeholder, maskColor, textColor) { alpha() }
        }
        if (image != null) {
            TvDetailsBlurredImage(image, maskColor, textColor) { alpha() * fadeIn.value }
        }
    }
}

/** 一张模糊小图拉伸铺满, 按 [entry] 量好的亮度压暗 (压暗画在同一次绘制里, 只盖有图处); [alpha] 在绘制里读. */
@Composable
private fun TvDetailsBlurredImage(entry: TvBlurredBackdropEntry, maskColor: Color, textColor: Color, alpha: () -> Float) {
    val maskAlpha = remember(entry, maskColor, textColor) { entry.maskAlpha(maskColor, textColor) }
    Image(
        entry.bitmap,
        contentDescription = null,
        modifier = Modifier
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

/**
 * [url] 此刻有能马上铺上的模糊底 (设置那一档解好的, 或先垫着的别的档, 见 [TvBlurredBackdropMemo]): 重新组合的模糊底第一帧就有图, 不必再淡入.
 */
internal fun tvDetailsBlurredBackdropReady(url: String): Boolean = TvBlurredBackdropMemo.latest(url) != null

/** 压暗色 [mask] 压多深上面的 [text] 才看得清 (0..1), 判法同海报墙的模糊层. */
private fun TvBlurredBackdropEntry.maskAlpha(mask: Color, text: Color): Float {
    val maskLuminance = tvRelativeLuminance(mask.toArgb())
    val textLuminance = tvRelativeLuminance(text.toArgb())
    val lightText = textLuminance >= maskLuminance
    return tvBackdropMaskAlpha(if (lightText) worstForLightText else worstForDarkText, maskLuminance, textLuminance, mask.alpha)
}
